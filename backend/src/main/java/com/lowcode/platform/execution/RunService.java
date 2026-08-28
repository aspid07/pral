package com.lowcode.platform.execution;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * POST /scenarios/{id}/runs должен вернуть runId сразу, а не ждать, пока
 * сценарий полностью проиграется — фронтенд получает ход исполнения через
 * WebSocket (см. api-contract.md). Поэтому ExecutionEngine.run() запускается
 * в отдельном виртуальном потоке (Java 21), а этот сервис хранит только
 * итоговый статус для GET /runs/{runId}.
 *
 * Стоп/Пауза (Стоп/Пауза, предыдущая итерация): requestPause/requestResume/
 * requestStop — только ВАЛИДАЦИЯ + сигнал (см. RunControl/RunControlRegistry).
 * Саму СМЕНУ статуса в БД и WS-события RUN_PAUSED/RUN_RESUMED делает
 * ExecutionEngine.checkpoint() из потока исполнения, в момент, когда движок
 * РЕАЛЬНО встал/возобновился — не этот сервис в момент, когда пользователь
 * нажал кнопку (см. javadoc RunControl). RUN_STOPPED/Status.STOPPED — тоже от
 * движка, через finish() ниже, куда попадает после того, как run() поймает
 * StopRequestedException.
 *
 * Второе ревью CTO, ASAP-1 (BOLA): start/getStatus/pause/resume/stop раньше
 * не проверяли вообще ничего, кроме валидного JWT — статус и лог чужого run
 * (то, какие сервисы с чем интегрируются в чужом проекте) были читаемы кем
 * угодно. getStatus() — единая внутренняя точка проверки (см. её javadoc);
 * requireStatus()/requestStop() проходят через неё же, так что забыть
 * авторизацию в одном из трёх экшенов физически нельзя — единственный путь
 * достать ExecutionRun из этого сервиса теперь идёт через guard.
 */
@Service
public class RunService {

    // Ревью CTO, п.1.5: раньше — ничем не ограниченный newVirtualThreadPerTaskExecutor.
    // Пока эндпоинт был открыт (см. 1.1, уже закрыто), цикл POST .../runs выедал
    // пул соединений HikariCP: каждый run — десятки последовательных запросов
    // в БД (обход дерева ScenarioStep). Лимит на весь инстанс, не на
    // пользователя — этого достаточно, чтобы не положить процесс целиком;
    // per-user throttling — отдельная, более тонкая задача (нужен реальный
    // rate-limiter вроде Bucket4j, см. п.2.7).
    private static final int MAX_CONCURRENT_RUNS = 20;

    private final ExecutionRunRepository executionRunRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionEngine executionEngine;
    private final RunControlRegistry runControlRegistry;
    private final PermissionService permissionService;
    private final Semaphore runSlots = new Semaphore(MAX_CONCURRENT_RUNS);
    // ExecutorService (не просто Executor) — нужен для graceful shutdown,
    // см. @PreDestroy ниже.
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RunService(ExecutionRunRepository executionRunRepository,
                       ScenarioRepository scenarioRepository,
                       ExecutionEngine executionEngine,
                       RunControlRegistry runControlRegistry,
                       PermissionService permissionService) {
        this.executionRunRepository = executionRunRepository;
        this.scenarioRepository = scenarioRepository;
        this.executionEngine = executionEngine;
        this.runControlRegistry = runControlRegistry;
        this.permissionService = permissionService;
    }

    /**
     * READER, не EDITOR — запуск чужого (но видимого) сценария не мутирует
     * его определение, это скорее "просмотр/исполнение", тот же уровень, что
     * и getStatus/pause/resume/stop ниже (согласованный минимум для всего
     * "запуск и контроль над ним" семейства действий).
     */
    public ExecutionRun start(UUID scenarioId, Map<UUID, UUID> branchSelections, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new EntityNotFoundException("Scenario", scenarioId);
        }
        if (!runSlots.tryAcquire()) {
            throw new RunCapacityExceededException(
                    "Too many scenario runs in progress (max " + MAX_CONCURRENT_RUNS + "), try again shortly");
        }

        ExecutionRun run = new ExecutionRun();
        run.setScenarioId(scenarioId);
        run.setStatus(ExecutionRun.Status.RUNNING);
        // +1 к количеству прошлых запусков ЭТОГО сценария — гонка при параллельном
        // старте возможна (см. комментарий в V7-миграции), для демо-инструмента ок.
        run.setRunNumber((int) executionRunRepository.countByScenarioId(scenarioId) + 1);
        run = executionRunRepository.save(run);
        UUID runId = run.getId();

        RunControl control = runControlRegistry.register(runId);
        runExecutor.execute(() -> {
            try {
                ExecutionEngine.RunOutcome outcome = executionEngine.run(runId, scenarioId, branchSelections, control);
                finish(runId, outcome);
            } finally {
                runControlRegistry.unregister(runId);
                runSlots.release();
            }
        });

        return run;
    }

    /**
     * Единая точка, через которую этот сервис вообще достаёт ExecutionRun —
     * requireStatus()/requestStop() идут через неё же (см. class-javadoc),
     * так что забыть guard в одном из трёх control-действий физически нельзя.
     */
    public ExecutionRun getStatus(UUID runId, UUID userId) {
        ExecutionRun run = findRunOrThrow(runId);
        permissionService.requireOnScenario(run.getScenarioId(), userId, Role.READER);
        return run;
    }

    /**
     * "Не сейчас выполняется" проверяется здесь (409 через IllegalStateException,
     * см. ApiExceptionHandler.handleConflict), а не молча игнорируется — иначе
     * пользователь мог бы решить, что Пауза сработала для уже завершённого run.
     *
     * Небольшая, осознанно не устранённая гонка: между чтением статуса здесь и
     * реальной сменой статуса в БД (её делает ExecutionEngine.checkpoint из
     * другого потока) проходит какое-то время — control.requestPause() мог бы
     * долететь до движка чуть раньше или чуть позже, чем читается run.getStatus()
     * в следующем запросе того же пользователя. Для визуализации потока
     * (не критичной к атомарности бизнес-операции, см. javadoc ExecutionEngine)
     * это приемлемо; полноценная защита потребовала бы блокировки строки в БД
     * ради состояния, которое и так самокорректируется на следующем polling'е.
     */
    public void requestPause(UUID runId, UUID userId) {
        requireStatus(runId, userId, ExecutionRun.Status.RUNNING, "paused").requestPause();
    }

    public void requestResume(UUID runId, UUID userId) {
        requireStatus(runId, userId, ExecutionRun.Status.PAUSED, "resumed").requestResume();
    }

    /** Стоп разрешён и из RUNNING, и из PAUSED — в отличие от pause/resume, у которых точно один legal-предшественник. */
    public void requestStop(UUID runId, UUID userId) {
        ExecutionRun run = getStatus(runId, userId);
        if (run.getStatus() != ExecutionRun.Status.RUNNING && run.getStatus() != ExecutionRun.Status.PAUSED) {
            throw new IllegalStateException(
                    "Run " + runId + " has already finished (status: " + run.getStatus() + "), cannot be stopped");
        }
        controlOf(runId).requestStop();
    }

    private RunControl requireStatus(UUID runId, UUID userId, ExecutionRun.Status required, String actionPastTense) {
        ExecutionRun run = getStatus(runId, userId);
        if (run.getStatus() != required) {
            throw new IllegalStateException("Run " + runId + " cannot be " + actionPastTense
                    + " from status " + run.getStatus() + " (expected " + required + ")");
        }
        return controlOf(runId);
    }

    private RunControl controlOf(UUID runId) {
        return runControlRegistry.find(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "Run " + runId + " has no active control (already finished?)"));
    }

    private ExecutionRun findRunOrThrow(UUID runId) {
        return executionRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run", runId));
    }

    private void finish(UUID runId, ExecutionEngine.RunOutcome outcome) {
        executionRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(switch (outcome.outcome()) {
                case SUCCESS -> ExecutionRun.Status.COMPLETED;
                case FAILURE -> ExecutionRun.Status.FAILED;
                case STOPPED -> ExecutionRun.Status.STOPPED;
            });
            run.setErrorMessage(outcome.errorMessage());
            run.setFinishedAt(Instant.now());
            executionRunRepository.save(run);
        });
    }

    /**
     * Ревью CTO, п.3.5: ExecutorService.close() (Java 19+) в худшем случае
     * ждёт завершения задач сутками циклом — если хоть один run завис, JVM не
     * останавливается, пока её не прибьёт SIGKILL по таймауту Docker (обычно
     * 10с). Явный таймаут + принудительный shutdownNow() вместо этого.
     *
     * Стоп/Пауза: shutdownNow() прерывает виртуальные потоки — если какой-то
     * run в этот момент стоит на паузе (заблокирован в RunControl.awaitResumeOrStop()),
     * InterruptedException там трактуется как запрос остановки (см. javadoc
     * RunControl) — иначе висящая на паузе задача не завершилась бы даже от
     * shutdownNow(), и этот метод всё равно бы прождал полные 10 секунд впустую.
     */
    @PreDestroy
    void shutdownRunExecutor() {
        runExecutor.shutdown();
        try {
            if (!runExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                runExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            runExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
