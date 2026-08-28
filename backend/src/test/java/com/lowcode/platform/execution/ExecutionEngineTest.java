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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEngineTest {

    @Mock private ExecutionEventPublisher eventPublisher;
    @Mock private ScenarioStepRepository scenarioStepRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private EntryPointRepository entryPointRepository;
    @Mock private BlockInstanceRepository blockInstanceRepository;
    @Mock private SchemeRepository schemeRepository;
    // Стоп/Пауза (эта итерация): checkpoint() внутри run() пишет сюда напрямую,
    // но только когда control реально попросили на паузу — тесты, написанные
    // ДО Стоп/Паузы, используют оверлоад run() без RunControl (see below),
    // где control никогда не просят на паузу, так что findById() здесь вообще
    // не вызывается в старых тестах — стаб не нужен, мок используется только
    // в новых тестах ниже (см. блок "Стоп/Пауза").
    @Mock private ExecutionRunRepository executionRunRepository;

    private ExecutionEngine engine;
    private UUID runId;
    private UUID scenarioId;
    private UUID entryPointA; // корневой entry point сценария
    private UUID blockA;
    private UUID schemeA;
    private UUID projectA;

    @BeforeEach
    void setUp() {
        engine = new ExecutionEngine(eventPublisher, scenarioStepRepository, scenarioRepository,
                entryPointRepository, blockInstanceRepository, schemeRepository, executionRunRepository);

        runId = UUID.randomUUID();
        scenarioId = UUID.randomUUID();
        entryPointA = UUID.randomUUID();
        blockA = UUID.randomUUID();
        schemeA = UUID.randomUUID();
        projectA = UUID.randomUUID();

        Scenario scenario = mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenario.getEntryPointId()).thenReturn(entryPointA);
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenario));

        wireEntryPoint(entryPointA, blockA, EntryPoint.Kind.SYNC_METHOD, "Root");
        wireBlockToProject(blockA, schemeA, projectA);
    }

    // lenient() ниже — эти helper-методы общие для разных тестов, и не каждый
    // стаб реально консьюмится в каждом конкретном тесте (например,
    // entryPointA.getName()/getKind() нужны только тесту на self-reference,
    // где entryPointA сам оказывается CALL-целью; getOrderIndex() может не
    // вызваться вовсе, если JDK сортирует список из одного элемента без
    // обращения к компаратору). Strict-stubbing по умолчанию у MockitoExtension
    // иначе валит тест с UnnecessaryStubbingException — см. ревью.
    private EntryPoint wireEntryPoint(UUID id, UUID blockInstanceId, EntryPoint.Kind kind, String name) {
        EntryPoint ep = mock(EntryPoint.class);
        lenient().when(ep.getId()).thenReturn(id);
        lenient().when(ep.getBlockInstanceId()).thenReturn(blockInstanceId);
        lenient().when(ep.getKind()).thenReturn(kind);
        lenient().when(ep.getName()).thenReturn(name);
        when(entryPointRepository.findById(id)).thenReturn(Optional.of(ep));
        return ep;
    }

    private void wireBlockToProject(UUID blockId, UUID schemeId, UUID projectId) {
        BlockInstance block = mock(BlockInstance.class);
        lenient().when(block.getSchemeId()).thenReturn(schemeId);
        when(blockInstanceRepository.findById(blockId)).thenReturn(Optional.of(block));

        Scheme scheme = mock(Scheme.class);
        lenient().when(scheme.getProjectId()).thenReturn(projectId);
        when(schemeRepository.findById(schemeId)).thenReturn(Optional.of(scheme));
    }

    private ScenarioStep mockStep(UUID id, ScenarioStep.StepType type, int orderIndex) {
        ScenarioStep step = mock(ScenarioStep.class);
        lenient().when(step.getId()).thenReturn(id);
        lenient().when(step.getScenarioId()).thenReturn(scenarioId);
        lenient().when(step.getStepType()).thenReturn(type);
        lenient().when(step.getOrderIndex()).thenReturn(orderIndex);
        return step;
    }

    @Test
    void call_withNullCalledEntryPointId_reportsRunErrorInsteadOfCrashing() {
        // Возможное следствие ON DELETE SET NULL при удалении EntryPoint
        // (см. V4__fix_entry_point_delete_cascade.sql) — "битый" CALL-шаг,
        // такое CRUD-слой на create/update не пропустит, но по каскаду возникнуть может.
        ScenarioStep call = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(call.getCalledEntryPointId()).thenReturn(null);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorMessage()).contains("deleted entry point");
        // Ревью CTO, п.3.3: RUN_ERROR теперь несёт stepId вместо всегда null.
        UUID callStepId = call.getId();
        verify(eventPublisher).publishRunError(eq(runId), eq(callStepId), anyString());
    }

    @Test
    void selfReferencingCall_isDetectedAsCycle_andReportedAsRunError() {
        // Сценарий вызывает свой же корневой entry point — должен быть пойман
        // до бесконечной рекурсии.
        ScenarioStep call = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(call.getCalledEntryPointId()).thenReturn(entryPointA);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorMessage()).contains("Cycle detected");
        UUID callStepId = call.getId();
        verify(eventPublisher).publishRunError(eq(runId), eq(callStepId), anyString());
        verify(eventPublisher, never()).publishRunCompleted(any());
    }

    @Test
    void retry_publishesRetryingForAllButLastAttempt_thenExecutesChildOnce() {
        UUID retryStepId = UUID.randomUUID();
        ScenarioStep retryStep = mockStep(retryStepId, ScenarioStep.StepType.RETRY, 0);
        when(retryStep.getMaxAttempts()).thenReturn(3);

        UUID childEntryPoint = UUID.randomUUID();
        UUID childBlock = UUID.randomUUID();
        wireEntryPoint(childEntryPoint, childBlock, EntryPoint.Kind.SYNC_METHOD, "Child");
        // Тот же проект, что и root (projectA) — без CLUSTER_ENTERED. Специально
        // НЕ вызываем wireBlockToProject(childBlock, schemeA, ...): это заново
        // застабило бы schemeRepository.findById(schemeA), затенив стаб из
        // setUp() для того же id — тот стал бы "неиспользуемым" для strict-stubs.
        // Вместо этого просто вешаем childBlock на уже поднятую в setUp() схему.
        BlockInstance childBlockInstance = mock(BlockInstance.class);
        lenient().when(childBlockInstance.getSchemeId()).thenReturn(schemeA);
        when(blockInstanceRepository.findById(childBlock)).thenReturn(Optional.of(childBlockInstance));

        ScenarioStep child = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(child.getCalledEntryPointId()).thenReturn(childEntryPoint);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(retryStep));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, retryStepId))
                .thenReturn(List.of(child));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isTrue();
        verify(eventPublisher).publishStepRetrying(runId, retryStepId, 1, 3);
        verify(eventPublisher).publishStepRetrying(runId, retryStepId, 2, 3);
        verify(eventPublisher, never()).publishStepRetrying(eq(runId), eq(retryStepId), eq(3), anyInt());
        UUID childStepId = child.getId();
        verify(eventPublisher, times(1)).publishStepStarted(
                eq(runId), eq(childStepId), any(), eq(childEntryPoint), any(), eq("sync"), any(), any());
        verify(eventPublisher).publishRunCompleted(runId);
    }

    @Test
    void alt_executesOnlyFirstBranchByOrderIndex() {
        UUID altStepId = UUID.randomUUID();
        ScenarioStep altStep = mockStep(altStepId, ScenarioStep.StepType.ALT, 0);

        UUID branchAEntryPoint = UUID.randomUUID();
        wireEntryPoint(branchAEntryPoint, blockA, EntryPoint.Kind.SYNC_METHOD, "BranchA");
        UUID branchBEntryPoint = UUID.randomUUID();

        ScenarioStep branchA = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(branchA.getCalledEntryPointId()).thenReturn(branchAEntryPoint);
        // branchB намеренно НЕ получает calledEntryPointId — сам смысл теста в
        // том, что она никогда не исполняется, а значит стаб был бы гарантированно
        // неиспользуемым (strict-stubbing это ловит как ошибку).
        ScenarioStep branchB = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 1);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(altStep));
        // Порядок из репозитория намеренно "перепутан" — движок должен сам отсортировать по orderIndex.
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, altStepId))
                .thenReturn(List.of(branchB, branchA));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isTrue();
        UUID branchAStepId = branchA.getId();
        UUID branchBStepId = branchB.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(branchAStepId), any(), eq(branchAEntryPoint), any(), any(), any(), any());
        verify(eventPublisher, never()).publishStepStarted(
                eq(runId), eq(branchBStepId), any(), any(), any(), any(), any(), any());
    }

    @Test
    void parallel_childrenWithoutOwnGroupId_inheritParallelNodeIdAsGroup() {
        UUID parallelStepId = UUID.randomUUID();
        ScenarioStep parallelStep = mockStep(parallelStepId, ScenarioStep.StepType.PARALLEL, 0);

        UUID entryPointB = UUID.randomUUID();
        wireEntryPoint(entryPointB, blockA, EntryPoint.Kind.ASYNC_EVENT, "SideEffect");

        ScenarioStep child = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(child.getCalledEntryPointId()).thenReturn(entryPointB);
        when(child.getParallelGroupId()).thenReturn(null);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(parallelStep));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, parallelStepId))
                .thenReturn(List.of(child));

        engine.run(runId, scenarioId, java.util.Map.of());

        UUID childStepId = child.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(childStepId), any(), eq(entryPointB), any(), eq("async"),
                eq(parallelStepId.toString()), any());
    }

    @Test
    void alt_withExplicitBranchSelection_executesChosenBranchNotFirst() {
        UUID altStepId = UUID.randomUUID();
        ScenarioStep altStep = mockStep(altStepId, ScenarioStep.StepType.ALT, 0);

        UUID branchBEntryPoint = UUID.randomUUID();
        wireEntryPoint(branchBEntryPoint, blockA, EntryPoint.Kind.SYNC_METHOD, "BranchB");

        // branchA намеренно НЕ получает calledEntryPointId — явно выбрана branchB,
        // значит branchA никогда не исполняется (см. комментарий в тесте выше).
        ScenarioStep branchA = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        ScenarioStep branchB = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 1);
        when(branchB.getCalledEntryPointId()).thenReturn(branchBEntryPoint);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(altStep));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, altStepId))
                .thenReturn(List.of(branchA, branchB));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of(altStepId, branchB.getId()));

        assertThat(outcome.success()).isTrue();
        UUID branchAStepId = branchA.getId();
        UUID branchBStepId = branchB.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(branchBStepId), any(), eq(branchBEntryPoint), any(), any(), any(), any());
        verify(eventPublisher, never()).publishStepStarted(
                eq(runId), eq(branchAStepId), any(), any(), any(), any(), any(), any());
    }

    @Test
    void alt_withSelectionPointingToUnknownChild_reportsRunError() {
        UUID altStepId = UUID.randomUUID();
        ScenarioStep altStep = mockStep(altStepId, ScenarioStep.StepType.ALT, 0);
        // Ошибка "не дочерний шаг ALT" бросается до того, как движок вообще
        // посмотрел бы на calledEntryPointId branchA — стабить его незачем.
        ScenarioStep branchA = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(altStep));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, altStepId))
                .thenReturn(List.of(branchA));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of(altStepId, UUID.randomUUID()));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorMessage()).contains("is not a child of ALT step");
    }

    @Test
    void call_crossingProjectBoundary_publishesClusterEnteredAndExternalKind() {
        UUID otherEntryPoint = UUID.randomUUID();
        UUID otherBlock = UUID.randomUUID();
        UUID otherScheme = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        wireEntryPoint(otherEntryPoint, otherBlock, EntryPoint.Kind.SYNC_METHOD, "Other");
        wireBlockToProject(otherBlock, otherScheme, otherProject);

        ScenarioStep call = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(call.getCalledEntryPointId()).thenReturn(otherEntryPoint);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isTrue();
        verify(eventPublisher).publishClusterEntered(runId, otherProject);
        UUID callStepId = call.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(callStepId), any(), eq(otherEntryPoint), any(), eq("external"), any(), any());
    }

    @Test
    void call_returningToOriginalProject_afterCrossProjectCall_isNotExternal() {
        // Ревью CTO, п.2.3: currentProjectId раньше не восстанавливался после
        // возврата из чужого проекта — root-уровневый CALL, идущий ПОСЛЕ
        // кросс-проектного, ошибочно получал бы kind=external, хотя сам по
        // себе он локальный (см. фикс в ExecutionEngine.executeCall — finally
        // { ctx.currentProjectId = previousProjectId; }).
        UUID entryPointB = UUID.randomUUID();
        UUID blockB = UUID.randomUUID();
        UUID schemeB = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        wireEntryPoint(entryPointB, blockB, EntryPoint.Kind.SYNC_METHOD, "Other");
        wireBlockToProject(blockB, schemeB, projectB);

        // entryPointC живёт на том же blockA/projectA, что и корень сценария —
        // после возврата из projectB второй CALL должен остаться локальным.
        UUID entryPointC = UUID.randomUUID();
        wireEntryPoint(entryPointC, blockA, EntryPoint.Kind.SYNC_METHOD, "BackHome");

        ScenarioStep call1 = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(call1.getCalledEntryPointId()).thenReturn(entryPointB);
        ScenarioStep call2 = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 1);
        when(call2.getCalledEntryPointId()).thenReturn(entryPointC);
        // Третий CALL — снова в projectB: kind всё ещё external (это по-прежнему
        // кросс-проектный вызов), но CLUSTER_ENTERED для projectB не должен
        // прилететь второй раз (api-contract.md: "впервые... в рамках run").
        ScenarioStep call3 = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 2);
        when(call3.getCalledEntryPointId()).thenReturn(entryPointB);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call1, call2, call3));

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of());

        assertThat(outcome.success()).isTrue();
        UUID call2Id = call2.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(call2Id), any(), eq(entryPointC), any(), eq("sync"), any(), any());
        UUID call3Id = call3.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(call3Id), any(), eq(entryPointB), any(), eq("external"), any(), any());
        verify(eventPublisher, times(1)).publishClusterEntered(eq(runId), eq(projectB));
    }

    // --- Стоп/Пауза (эта итерация) ---

    @Test
    void run_stopRequestedBeforeAnyStep_stopsImmediately_noStepsExecuted() {
        // Ни entryPoint, ни calledEntryPointId у шага НЕ стабятся намеренно —
        // checkpoint() должен бросить StopRequestedException ДО того, как
        // движок вообще заглянет в поля шага (кроме id/type/orderIndex,
        // застабленных лениво в mockStep()) — иначе strict-stubbing поймал бы
        // неиспользуемый стаб как ошибку, что само по себе доказывает: шаг
        // действительно не начал выполняться.
        ScenarioStep call = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        RunControl control = new RunControl();
        control.requestStop();

        ExecutionEngine.RunOutcome outcome = engine.run(runId, scenarioId, java.util.Map.of(), control);

        assertThat(outcome.stopped()).isTrue();
        // currentStepId ещё null — остановка сработала до первого шага.
        verify(eventPublisher).publishRunStopped(eq(runId), isNull());
        verify(eventPublisher, never())
                .publishStepStarted(any(), any(), any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishRunError(any(), any(), any());
        verify(eventPublisher, never()).publishRunCompleted(any());
    }

    @Test
    void run_pauseRequestedBeforeStart_blocksThenResumeContinuesToCompletion() throws InterruptedException {
        UUID entryPointB = UUID.randomUUID();
        UUID entryPointC = UUID.randomUUID();
        wireEntryPoint(entryPointB, blockA, EntryPoint.Kind.SYNC_METHOD, "Second");
        wireEntryPoint(entryPointC, blockA, EntryPoint.Kind.SYNC_METHOD, "Third");

        ScenarioStep call1 = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(call1.getCalledEntryPointId()).thenReturn(entryPointB);
        ScenarioStep call2 = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 1);
        when(call2.getCalledEntryPointId()).thenReturn(entryPointC);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call1, call2));

        RunControl control = new RunControl();
        control.requestPause(); // выставлена ДО старта — движок встанет ещё до первого шага

        AtomicReference<ExecutionEngine.RunOutcome> outcomeRef = new AtomicReference<>();
        Thread engineThread = new Thread(() ->
                outcomeRef.set(engine.run(runId, scenarioId, java.util.Map.of(), control)));
        engineThread.start();

        // Ждём, пока движок реально дойдёт до checkpoint'а и опубликует
        // RUN_PAUSED — не полагаемся на Thread.sleep, timeout()-верификация
        // Mockito поллит вызов, а не спит фиксированное время вслепую.
        verify(eventPublisher, timeout(2000)).publishRunPaused(eq(runId), isNull());
        // Пока движок стоит на паузе — ни один шаг не должен был начаться.
        verify(eventPublisher, never())
                .publishStepStarted(any(), any(), any(), any(), any(), any(), any(), any());

        control.requestResume();
        engineThread.join(2000);

        assertThat(outcomeRef.get().success()).isTrue();
        verify(eventPublisher).publishRunResumed(runId);
        UUID call1Id = call1.getId();
        UUID call2Id = call2.getId();
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(call1Id), any(), eq(entryPointB), any(), any(), any(), any());
        verify(eventPublisher).publishStepStarted(
                eq(runId), eq(call2Id), any(), eq(entryPointC), any(), any(), any(), any());
        verify(eventPublisher).publishRunCompleted(runId);
    }

    @Test
    void run_stopRequestedWhilePaused_unblocksImmediately_returnsStoppedOutcome() throws InterruptedException {
        // Ни entryPoint, ни calledEntryPointId у шага не стабятся — та же
        // логика, что и в run_stopRequestedBeforeAnyStep_... выше: Стоп во
        // время паузы обязан прервать ДО того, как движок хоть раз заглянет
        // в поля самого CALL-шага.
        ScenarioStep call = mockStep(UUID.randomUUID(), ScenarioStep.StepType.CALL, 0);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        RunControl control = new RunControl();
        control.requestPause();

        AtomicReference<ExecutionEngine.RunOutcome> outcomeRef = new AtomicReference<>();
        Thread engineThread = new Thread(() ->
                outcomeRef.set(engine.run(runId, scenarioId, java.util.Map.of(), control)));
        engineThread.start();

        verify(eventPublisher, timeout(2000)).publishRunPaused(eq(runId), isNull());

        control.requestStop();
        engineThread.join(2000);

        assertThat(outcomeRef.get().stopped()).isTrue();
        verify(eventPublisher).publishRunStopped(eq(runId), isNull());
        // Пользователь видел "на паузе" (RUN_PAUSED уже улетел выше) — это не
        // противоречие, но продолжения (RUN_RESUMED) быть не должно: движок
        // прервался, не возобновился.
        verify(eventPublisher, never()).publishRunResumed(any());
        verify(eventPublisher, never())
                .publishStepStarted(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
