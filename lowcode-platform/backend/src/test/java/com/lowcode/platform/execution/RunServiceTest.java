package com.lowcode.platform.execution;

import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.sharing.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Стоп/Пауза (предыдущая итерация): requestPause/requestResume/requestStop —
 * валидация текущего статуса (см. javadoc RunService) + сигнал в RunControl.
 * Реальный (не мок) RunControl — состояние проверяем через его собственные
 * isPauseRequested()/isStopRequested(), а не verify() на моке: RunControl —
 * небольшой, но с реальной логикой (Lock/Condition) класс, подмена его
 * моком проверяла бы только факт вызова метода, а не то, что он делает.
 *
 * Второе ревью CTO, ASAP-1 (BOLA, эта итерация): permissionService — noop-мок
 * (успех по умолчанию) в тестах статус-валидации ниже — тот же принцип, что и
 * в остальных *ServiceTest после аудита: авторизация проверяется отдельно
 * (см. блок в конце файла), эти тесты — про логику статус-переходов.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock private ExecutionRunRepository executionRunRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private ExecutionEngine executionEngine;
    @Mock private RunControlRegistry runControlRegistry;
    @Mock private PermissionService permissionService;

    private RunService service;
    private UUID runId;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunService(executionRunRepository, scenarioRepository, executionEngine, runControlRegistry,
                permissionService);
        runId = UUID.randomUUID();
    }

    private void stubStatus(ExecutionRun.Status status) {
        ExecutionRun run = mock(ExecutionRun.class);
        when(run.getStatus()).thenReturn(status);
        // getScenarioId() — используется authorization guard'ом внутри getStatus()
        // (permissionService.requireOnScenario) на каждом вызове; noop-мок ниже не
        // читает возвращаемое значение, но сам вызов должен не падать на null.
        lenient().when(run.getScenarioId()).thenReturn(UUID.randomUUID());
        when(executionRunRepository.findById(runId)).thenReturn(Optional.of(run));
    }

    @Test
    void requestPause_runningRun_signalsControl() {
        stubStatus(ExecutionRun.Status.RUNNING);
        RunControl control = new RunControl();
        when(runControlRegistry.find(runId)).thenReturn(Optional.of(control));

        service.requestPause(runId, userId);

        assertThat(control.isPauseRequested()).isTrue();
    }

    @Test
    void requestPause_runAlreadyFinished_throwsConflict_controlNeverTouched() {
        stubStatus(ExecutionRun.Status.COMPLETED);

        assertThatThrownBy(() -> service.requestPause(runId, userId))
                .isInstanceOf(IllegalStateException.class);
        // Ошибка должна произойти на проверке статуса, до похода в реестр —
        // иначе сообщение вводило бы в заблуждение ("control not found" вместо
        // содержательного "run уже завершён").
        verify(runControlRegistry, never()).find(any());
    }

    @Test
    void requestPause_registeredControlMissing_throwsIllegalState() {
        // Гипотетическая гонка: БД ещё говорит RUNNING, но control уже снят из
        // реестра — например, run как раз в этот момент финиширует (см.
        // RunService.start(), finally { runControlRegistry.unregister(...) }).
        stubStatus(ExecutionRun.Status.RUNNING);
        when(runControlRegistry.find(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestPause(runId, userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestResume_pausedRun_signalsControl() {
        stubStatus(ExecutionRun.Status.PAUSED);
        RunControl control = new RunControl();
        control.requestPause(); // симулируем "уже реально на паузе" перед вызовом resume
        when(runControlRegistry.find(runId)).thenReturn(Optional.of(control));

        service.requestResume(runId, userId);

        assertThat(control.isPauseRequested()).isFalse();
    }

    @Test
    void requestResume_runIsRunningNotPaused_throwsConflict() {
        stubStatus(ExecutionRun.Status.RUNNING);

        assertThatThrownBy(() -> service.requestResume(runId, userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestStop_runningRun_signalsControl() {
        stubStatus(ExecutionRun.Status.RUNNING);
        RunControl control = new RunControl();
        when(runControlRegistry.find(runId)).thenReturn(Optional.of(control));

        service.requestStop(runId, userId);

        assertThat(control.isStopRequested()).isTrue();
    }

    @Test
    void requestStop_pausedRun_signalsControl() {
        // Стоп — единственное из трёх действий, разрешённое сразу из ДВУХ
        // статусов (RUNNING и PAUSED), не одного — см. javadoc requestStop.
        stubStatus(ExecutionRun.Status.PAUSED);
        RunControl control = new RunControl();
        when(runControlRegistry.find(runId)).thenReturn(Optional.of(control));

        service.requestStop(runId, userId);

        assertThat(control.isStopRequested()).isTrue();
    }

    @Test
    void requestStop_alreadyCompleted_throwsConflict_controlNeverTouched() {
        stubStatus(ExecutionRun.Status.COMPLETED);

        assertThatThrownBy(() -> service.requestStop(runId, userId))
                .isInstanceOf(IllegalStateException.class);
        verify(runControlRegistry, never()).find(any());
    }

    @Test
    void requestStop_alreadyStopped_throwsConflict() {
        stubStatus(ExecutionRun.Status.STOPPED);

        assertThatThrownBy(() -> service.requestStop(runId, userId))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Второе ревью CTO, ASAP-1 (BOLA): getStatus/start проверяют доступ к
    // сценарию, а не только валидный JWT ---

    @Test
    void getStatus_checksPermissionOnRunsScenario_beforeReturning() {
        UUID scenarioId = UUID.randomUUID();
        ExecutionRun run = mock(ExecutionRun.class);
        when(run.getScenarioId()).thenReturn(scenarioId);
        when(executionRunRepository.findById(runId)).thenReturn(Optional.of(run));

        ExecutionRun result = service.getStatus(runId, userId);

        assertThat(result).isSameAs(run);
        verify(permissionService).requireOnScenario(eq(scenarioId), eq(userId), any());
    }

    @Test
    void getStatus_insufficientPermission_throwsWithoutLeakingRunData() {
        UUID scenarioId = UUID.randomUUID();
        ExecutionRun run = mock(ExecutionRun.class);
        when(run.getScenarioId()).thenReturn(scenarioId);
        when(executionRunRepository.findById(runId)).thenReturn(Optional.of(run));
        doThrow(new com.lowcode.platform.domain.exception.EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(eq(scenarioId), eq(userId), any());

        assertThatThrownBy(() -> service.getStatus(runId, userId))
                .isInstanceOf(com.lowcode.platform.domain.exception.EntityNotFoundException.class);
    }

    @Test
    void start_checksPermissionOnScenario_beforeAcquiringRunSlot() {
        UUID scenarioId = UUID.randomUUID();
        doThrow(new com.lowcode.platform.domain.exception.EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(eq(scenarioId), eq(userId), any());

        assertThatThrownBy(() -> service.start(scenarioId, java.util.Map.of(), userId))
                .isInstanceOf(com.lowcode.platform.domain.exception.EntityNotFoundException.class);

        // Если бы guard не сработал первым, existsById()/save() всё равно
        // отклонили бы неизвестный scenarioId — но с другим сообщением
        // ("не существует" вместо "нет прав"), что для 404-oracle-защиты не
        // одно и то же. Явно проверяем порядок.
        verify(scenarioRepository, never()).existsById(any());
    }
}
