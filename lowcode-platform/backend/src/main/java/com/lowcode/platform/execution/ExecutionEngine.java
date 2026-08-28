package com.lowcode.platform.execution;

import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Рекурсивный интерпретатор ScenarioStep. Для каждого CALL-шага:
 *  1) публикует STEP_STARTED
 *  2) если у called_entry_point_id есть своя Scenario — разворачивает её
 *     рекурсивно (это и есть "никаких чёрных ящиков" из системного анализа)
 *  3) публикует STEP_COMPLETED
 *
 * ALT/PARALLEL/RETRY/TIMEOUT — управляющие узлы, оборачивающие вложенные шаги
 * (см. дерево через parentStepId). Смысловые допущения, зафиксированные в этой
 * итерации (нет реального клиента для вызовов — движок ничего по сети не
 * дёргает, это визуализация/симуляция потока, а не исполнение бизнес-логики):
 *
 *  - ALT: без модели условий рантайма реальное вычисление branch отсутствует —
 *    ветка выбирается пользователем на старте run (POST .../runs, тело
 *    branchSelections: altStepId → id ветки) и передаётся движку целиком;
 *    для ALT-узлов без явного выбора берётся первая ветка по order_index.
 *  - PARALLEL: дети выполняются последовательно (см. api-contract.md,
 *    "Решение: parallel-вызовы в WebSocket-схеме"), но получают общий
 *    parallelGroupId (id самого PARALLEL-узла), если не проставили свой.
 *  - RETRY: чистая симуляция без реального сигнала успеха/неудачи (подтверждено
 *    заказчиком) — публикует STEP_RETRYING maxAttempts-1 раз, затем один раз
 *    штатно выполняет обёрнутые шаги.
 *  - TIMEOUT: реального замера времени нет; timeoutMs передаётся во
 *    STEP_STARTED как метаданные (например, для индикатора обратного отсчёта
 *    на фронтенде). Если выполнение обёрнутых шагов падает с ошибкой —
 *    публикуется STEP_TIMEOUT вместо/вместе с общим RUN_ERROR.
 *  - Защита от циклов: если Entry Point встречается в текущем стеке вызовов
 *    повторно (Сценарий A → ... → Сценарий, вызывающий тот же Entry Point A),
 *    run завершается RUN_ERROR вместо ухода в бесконечную рекурсию.
 */
@Service
public class ExecutionEngine {

    private final ExecutionEventPublisher eventPublisher;
    private final ScenarioStepRepository scenarioStepRepository;
    private final ScenarioRepository scenarioRepository;
    private final EntryPointRepository entryPointRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final SchemeRepository schemeRepository;
    // Стоп/Пауза (эта итерация): checkpoint() пишет ExecutionRun.status
    // напрямую отсюда, а не через RunService — движок сам знает МОМЕНТ, когда
    // реально встал на паузу/возобновился (RunService знает только момент,
    // когда пользователь НАЖАЛ кнопку, что не то же самое — см. RunControl).
    private final ExecutionRunRepository executionRunRepository;

    public ExecutionEngine(ExecutionEventPublisher eventPublisher,
                            ScenarioStepRepository scenarioStepRepository,
                            ScenarioRepository scenarioRepository,
                            EntryPointRepository entryPointRepository,
                            BlockInstanceRepository blockInstanceRepository,
                            SchemeRepository schemeRepository,
                            ExecutionRunRepository executionRunRepository) {
        this.eventPublisher = eventPublisher;
        this.scenarioStepRepository = scenarioStepRepository;
        this.scenarioRepository = scenarioRepository;
        this.entryPointRepository = entryPointRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.schemeRepository = schemeRepository;
        this.executionRunRepository = executionRunRepository;
    }

    /**
     * Намеренно БЕЗ @Transactional на весь метод: обход дерева ScenarioStep может
     * включать десятки последовательных SELECT'ов, а run() выполняется в фоновом
     * виртуальном потоке (RunService), не привязанном к HTTP-запросу — единая
     * долгая read-only транзакция держала бы соединение из пула всё это время и
     * при нескольких параллельных run могла бы исчерпать пул для обычных CRUD-
     * запросов. Каждый вызов репозитория и так транзакционен сам по себе
     * (SimpleJpaRepository); единый консистентный снэпшот на весь run не нужен —
     * это визуализация потока, а не операция, которой критична атомарность чтения.
     *
     * Оверлоад без RunControl — для существующих вызывающих (тесты, старый код):
     * даёт свежий, никогда не остановленный/не поставленный на паузу control,
     * то есть ведёт себя ровно как раньше, без Стоп/Паузы.
     */
    public RunOutcome run(UUID runId, UUID scenarioId, Map<UUID, UUID> branchSelections) {
        return run(runId, scenarioId, branchSelections, new RunControl());
    }

    public RunOutcome run(UUID runId, UUID scenarioId, Map<UUID, UUID> branchSelections, RunControl control) {
        eventPublisher.publishRunStarted(runId, scenarioId);
        // ctx объявлен снаружи try — нужен в catch, чтобы достать currentStepId
        // (см. ниже, ревью CTO п.3.3: RUN_ERROR раньше всегда уходил с stepId=null).
        RunContext ctx = new RunContext(runId, branchSelections != null ? branchSelections : Map.of(), control);
        try {
            Scenario scenario = scenarioRepository.findById(scenarioId)
                    .orElseThrow(() -> new IllegalStateException("Scenario not found: " + scenarioId));

            ctx.currentProjectId = projectOf(scenario.getEntryPointId(), ctx);
            // Стартовый проект — не "вход" в него, run и так с него начинается;
            // без этого возврат сюда позже из другого проекта заново стрельнул
            // бы CLUSTER_ENTERED для места, откуда всё началось.
            ctx.enteredProjects.add(ctx.currentProjectId);
            ctx.callStack.add(scenario.getEntryPointId());

            executeScenario(scenario.getId(), scenario.getEntryPointId(), ctx);

            eventPublisher.publishRunCompleted(runId);
            return RunOutcome.ok();
        } catch (StopRequestedException stopped) {
            // Отдельно от общего catch ниже: это не бизнес-ошибка (см. класс
            // StopRequestedException) — пользователь сам попросил остановить.
            eventPublisher.publishRunStopped(runId, ctx.currentStepId);
            return RunOutcome.stop();
        } catch (RuntimeException ex) {
            eventPublisher.publishRunError(runId, ctx.currentStepId, ex.getMessage());
            return RunOutcome.failure(ex.getMessage());
        }
    }

    /**
     * Точка проверки Стоп/Пауза — вызывается перед КАЖДЫМ шагом на ЛЮБОМ
     * уровне вложенности (executeSteps используется и для корневых шагов
     * сценария, и рекурсивно внутри executeCall/ALT/PARALLEL/RETRY/TIMEOUT),
     * не только один раз в начале run(). ctx.currentStepId на момент вызова —
     * ещё ПРЕДЫДУЩИЙ (последний завершённый) шаг: сюда мы попадаем ДО того,
     * как currentStepId переставят на следующий (см. executeSteps) — то есть
     * события RUN_PAUSED/RUN_STOPPED всегда указывают на последний реально
     * завершённый шаг, а не на тот, что вот-вот начался бы.
     */
    private void checkpoint(RunContext ctx) {
        RunControl control = ctx.control;
        if (control.isStopRequested()) {
            throw new StopRequestedException();
        }
        if (control.isPauseRequested()) {
            persistStatus(ctx.runId, ExecutionRun.Status.PAUSED);
            eventPublisher.publishRunPaused(ctx.runId, ctx.currentStepId);

            control.awaitResumeOrStop(); // блокирует ЭТОТ (виртуальный) поток — не HTTP-поток запроса на паузу

            if (control.isStopRequested()) {
                // Стоп во время паузы — синтетический RUN_PAUSED уже был опубликован
                // выше и это нормально: пользователь видел "на паузе", потом нажал
                // Стоп — RUN_STOPPED из run()'s catch довершит картину без противоречий.
                throw new StopRequestedException();
            }
            persistStatus(ctx.runId, ExecutionRun.Status.RUNNING);
            eventPublisher.publishRunResumed(ctx.runId);
        }
    }

    private void persistStatus(UUID runId, ExecutionRun.Status status) {
        executionRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            executionRunRepository.save(run);
        });
    }

    private void executeScenario(UUID scenarioId, UUID sourceEntryPointId, RunContext ctx) {
        List<ScenarioStep> rootSteps =
                scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId);
        executeSteps(rootSteps, sourceEntryPointId, ctx, null);
    }

    private void executeSteps(List<ScenarioStep> steps, UUID sourceEntryPointId, RunContext ctx,
                               String inheritedParallelGroupId) {
        for (ScenarioStep step : steps) {
            checkpoint(ctx);
            // Ревью CTO, п.3.3: держим "текущий" шаг в контексте, чтобы
            // RUN_ERROR в catch-блоке run() мог указать stepId, а не всегда null.
            // Самый вложенный/глубокий шаг на момент падения перезапишет это
            // последним — то есть укажет именно на реальное место сбоя, не на
            // внешнюю обёртку (ALT/PARALLEL/RETRY/TIMEOUT).
            ctx.currentStepId = step.getId();
            switch (step.getStepType()) {
                case CALL -> executeCall(step, sourceEntryPointId, ctx, inheritedParallelGroupId);
                case ALT -> executeAlt(step, sourceEntryPointId, ctx);
                case PARALLEL -> executeParallel(step, sourceEntryPointId, ctx);
                case RETRY -> executeRetry(step, sourceEntryPointId, ctx);
                case TIMEOUT -> executeTimeout(step, sourceEntryPointId, ctx);
                // Switch-statement по enum не проверяется компилятором на exhaustiveness —
                // без этой ветки новый StepType молча не выполнился бы вместо явной ошибки
                // (см. ScenarioStepTree — тот же риск закрыт и в ScenarioGraphService).
                default -> throw new IllegalStateException("Unhandled ScenarioStep.StepType: " + step.getStepType());
            }
        }
    }

    private void executeCall(ScenarioStep step, UUID sourceEntryPointId, RunContext ctx,
                              String inheritedParallelGroupId) {
        UUID targetEntryPointId = step.getCalledEntryPointId();
        if (targetEntryPointId == null) {
            // Шаг "битый" — цель когда-то была удалена (ON DELETE SET NULL, см. миграцию
            // V4). ScenarioStepService не даёт создать/обновить CALL с null-целью, но такое
            // состояние может возникнуть позже, каскадом от удаления EntryPoint.
            throw new IllegalStateException(
                    "Step " + step.getId() + " calls a deleted entry point (calledEntryPointId is null)");
        }
        EntryPoint target = entryPointRepository.findById(targetEntryPointId)
                .orElseThrow(() -> new IllegalStateException("Unreachable entry point: " + targetEntryPointId));

        UUID targetProjectId = projectOf(targetEntryPointId, ctx);
        boolean crossedBoundary = !targetProjectId.equals(ctx.currentProjectId);
        String kind = crossedBoundary
                ? "external"
                : (target.getKind() == EntryPoint.Kind.SYNC_METHOD ? "sync" : "async");

        String parallelGroupId = step.getParallelGroupId() != null ? step.getParallelGroupId() : inheritedParallelGroupId;
        eventPublisher.publishStepStarted(ctx.runId, step.getId(), sourceEntryPointId, targetEntryPointId,
                target.getName(), kind, parallelGroupId, null);

        // Ревью CTO, п.2.3: currentProjectId — это состояние СТЕКА вызовов (куда
        // мы сейчас "физически" зашли), а не глобальный флаг — должно
        // восстанавливаться при возврате из вложенного сценария, иначе
        // A(P1) -> B(P2) -> возврат -> C(P1) считает C внешним вызовом.
        UUID previousProjectId = ctx.currentProjectId;
        if (crossedBoundary) {
            ctx.currentProjectId = targetProjectId;
            // api-contract.md: CLUSTER_ENTERED — "токен ВПЕРВЫЕ входит в блок
            // другого проекта в рамках этого run". Дедупликация по всему run,
            // не по текущему стеку — повторный заход в тот же проект позже
            // всё ещё даёт kind=external (это по-прежнему кросс-проектный
            // вызов), но не переиспользует событие подсветки контейнера.
            if (ctx.enteredProjects.add(targetProjectId)) {
                eventPublisher.publishClusterEntered(ctx.runId, targetProjectId);
            }
        }

        if (!ctx.callStack.add(targetEntryPointId)) {
            throw new IllegalStateException(
                    "Cycle detected: entry point " + targetEntryPointId + " is already in the call stack");
        }
        try {
            // Если Scenario у Entry Point нет — это лист дерева вызовов (валидное
            // состояние, см. functional-requirements.md).
            scenarioRepository.findByEntryPointId(targetEntryPointId)
                    .ifPresent(nested -> executeScenario(nested.getId(), targetEntryPointId, ctx));
        } finally {
            ctx.callStack.remove(targetEntryPointId);
            ctx.currentProjectId = previousProjectId;
        }

        eventPublisher.publishStepCompleted(ctx.runId, step.getId());
    }

    private void executeAlt(ScenarioStep step, UUID sourceEntryPointId, RunContext ctx) {
        List<ScenarioStep> branches = orderedChildren(step);
        if (branches.isEmpty()) {
            return;
        }
        UUID selectedBranchId = ctx.branchSelections.get(step.getId());
        ScenarioStep chosen;
        if (selectedBranchId != null) {
            chosen = branches.stream()
                    .filter(b -> b.getId().equals(selectedBranchId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Selected branch " + selectedBranchId + " is not a child of ALT step " + step.getId()));
        } else {
            // Без явного выбора — детерминированно первая ветка по order_index
            // (обратная совместимость с run без branchSelections).
            chosen = branches.get(0);
        }
        executeSteps(List.of(chosen), sourceEntryPointId, ctx, null);
    }

    private void executeParallel(ScenarioStep step, UUID sourceEntryPointId, RunContext ctx) {
        executeSteps(orderedChildren(step), sourceEntryPointId, ctx, step.getId().toString());
    }

    private void executeRetry(ScenarioStep step, UUID sourceEntryPointId, RunContext ctx) {
        int maxAttempts = step.getMaxAttempts() != null ? step.getMaxAttempts() : 1;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            eventPublisher.publishStepRetrying(ctx.runId, step.getId(), attempt, maxAttempts);
        }
        // Симуляция: последняя попытка всегда выполняется штатно (нет реального
        // сигнала успеха/неудачи, см. класс-комментарий).
        executeSteps(orderedChildren(step), sourceEntryPointId, ctx, null);
    }

    private void executeTimeout(ScenarioStep step, UUID sourceEntryPointId, RunContext ctx) {
        try {
            executeSteps(orderedChildren(step), sourceEntryPointId, ctx, null);
        } catch (RuntimeException ex) {
            eventPublisher.publishStepTimeout(ctx.runId, step.getId());
            throw ex;
        }
    }

    private List<ScenarioStep> orderedChildren(ScenarioStep parent) {
        return ScenarioStepTree.orderedChildren(scenarioStepRepository, parent);
    }

    private UUID projectOf(UUID entryPointId, RunContext ctx) {
        UUID cached = ctx.projectByEntryPoint.get(entryPointId);
        if (cached != null) {
            return cached;
        }
        EntryPoint entryPoint = entryPointRepository.findById(entryPointId)
                .orElseThrow(() -> new IllegalStateException("Unreachable entry point: " + entryPointId));
        BlockInstance block = blockInstanceRepository.findById(entryPoint.getBlockInstanceId())
                .orElseThrow(() -> new IllegalStateException("Block not found: " + entryPoint.getBlockInstanceId()));
        Scheme scheme = schemeRepository.findById(block.getSchemeId())
                .orElseThrow(() -> new IllegalStateException("Scheme not found: " + block.getSchemeId()));
        UUID projectId = scheme.getProjectId();
        ctx.projectByEntryPoint.put(entryPointId, projectId);
        return projectId;
    }

    public record RunOutcome(Outcome outcome, String errorMessage) {
        public enum Outcome { SUCCESS, FAILURE, STOPPED }
        // Названо ok(), а не success() — метод с именем success() столкнулся бы
        // с автогенерируемым accessor'ом компонента "success" (java.lang.boolean
        // success()) и не скомпилировался бы: javac требует, чтобы метод с именем
        // компонента был публичным инстанс-accessor'ом с той же сигнатурой, а не
        // статической фабрикой. Реальная ошибка компиляции, поймана не мной.
        //
        // Раньше RunOutcome был boolean success + errorMessage — Стоп по кнопке
        // пользователя не бизнес-ошибка (см. StopRequestedException), но и не
        // "успех" в смысле RUN_COMPLETED, поэтому двух состояний не хватило,
        // добавлен явный третий Outcome.STOPPED вместо перегрузки success=false
        // ещё и под остановку (RunService.finish() маппит это в разные статусы
        // ExecutionRun: FAILED vs STOPPED — разница важна для UI).
        //
        // success()/stopped() — производные удобные предикаты поверх outcome(),
        // не отдельное состояние: держат старые вызывающие места (тесты,
        // написанные до трёхзначного Outcome) рабочими без правки каждого.
        public boolean success() { return outcome == Outcome.SUCCESS; }
        public boolean stopped() { return outcome == Outcome.STOPPED; }

        static RunOutcome ok() { return new RunOutcome(Outcome.SUCCESS, null); }
        static RunOutcome failure(String errorMessage) { return new RunOutcome(Outcome.FAILURE, errorMessage); }
        // Названо stop(), не stopped() — со stopped() тут же столкнулось бы с
        // инстанс-предикатом stopped() выше: те же грабли, что и success()/ok()
        // чуть выше (javac различает методы по имени+параметрам, не по
        // static/instance и не по типу возврата — две сигнатуры "()" с одним
        // именем не компилируются, даже если одна static, а другая нет).
        static RunOutcome stop() { return new RunOutcome(Outcome.STOPPED, null); }
    }

    /** Состояние одного run — держится локально на стеке вызовов, а не в полях бина
     *  (ExecutionEngine — singleton, run() может выполняться параллельно для разных runId). */
    private static final class RunContext {
        final UUID runId;
        final Set<UUID> callStack = new LinkedHashSet<>();
        final Map<UUID, UUID> branchSelections;
        final RunControl control;
        // Мемоизация entryPoint -> project в рамках одного run (см. review):
        // без неё diamond-паттерн вызовов заново резолвил бы одну и ту же
        // цепочку entry_point -> block_instance -> scheme на каждый CALL.
        final Map<UUID, UUID> projectByEntryPoint = new java.util.HashMap<>();
        // Ревью CTO, п.2.3: для дедупликации CLUSTER_ENTERED по всему run
        // (api-contract.md: "впервые входит... в рамках этого run"), не по
        // текущей позиции в стеке — currentProjectId теперь восстанавливается
        // при выходе, так что без отдельного множества повторный заход
        // заново считался бы "первым".
        final Set<UUID> enteredProjects = new java.util.HashSet<>();
        UUID currentProjectId;
        UUID currentStepId;

        RunContext(UUID runId, Map<UUID, UUID> branchSelections, RunControl control) {
            this.runId = runId;
            this.branchSelections = branchSelections;
            this.control = control;
        }
    }
}
