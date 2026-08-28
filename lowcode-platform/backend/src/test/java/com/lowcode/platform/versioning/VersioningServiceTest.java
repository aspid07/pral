package com.lowcode.platform.versioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VersioningServiceTest {

    @Mock private ScenarioVersionRepository versionRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private ScenarioStepRepository scenarioStepRepository;
    // Настоящий Jackson ObjectMapper, а не мок — нужна реальная (де)сериализация,
    // это и есть предмет теста (snapshot round-trip).
    private final ObjectMapper objectMapper = new ObjectMapper();
    // permissionService — noop-мок (успех по умолчанию): snapshot() его вообще
    // не трогает (см. javadoc VersioningService.snapshot), list()/get() покрыты
    // отдельно в блоке про авторизацию ниже.
    @Mock private PermissionService permissionService;
    private final UUID userId = UUID.randomUUID();

    private VersioningService service() {
        return new VersioningService(versionRepository, scenarioRepository, scenarioStepRepository, objectMapper,
                permissionService);
    }

    private Scenario mockScenario(UUID id, UUID entryPointId, UUID ownerId, String name) {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getId()).thenReturn(id);
        when(scenario.getName()).thenReturn(name);
        when(scenario.getEntryPointId()).thenReturn(entryPointId);
        when(scenario.getOwnerId()).thenReturn(ownerId);
        return scenario;
    }

    @Test
    void snapshot_computesNextVersionNumber_asExistingCountPlusOne() {
        UUID scenarioId = UUID.randomUUID();
        Scenario scenario = mockScenario(scenarioId, UUID.randomUUID(), UUID.randomUUID(), "Place order");
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenario));
        when(scenarioStepRepository.findByScenarioIdOrderByOrderIndexAsc(scenarioId)).thenReturn(List.of());
        when(versionRepository.countByScenarioId(scenarioId)).thenReturn(2L);

        ArgumentCaptor<ScenarioVersion> captor = ArgumentCaptor.forClass(ScenarioVersion.class);
        when(versionRepository.save(any(ScenarioVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        service().snapshot(scenarioId);

        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(3);
        assertThat(captor.getValue().getScenarioId()).isEqualTo(scenarioId);
    }

    @Test
    void snapshot_serializesScenarioAndSteps_roundTripsThroughGet() {
        UUID scenarioId = UUID.randomUUID();
        UUID entryPointId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Scenario scenario = mockScenario(scenarioId, entryPointId, ownerId, "Place order");
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenario));
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);

        ScenarioStep step = mock(ScenarioStep.class);
        UUID stepId = UUID.randomUUID();
        UUID calledEntryPointId = UUID.randomUUID();
        when(step.getId()).thenReturn(stepId);
        when(step.getScenarioId()).thenReturn(scenarioId);
        when(step.getOrderIndex()).thenReturn(0);
        when(step.getStepType()).thenReturn(ScenarioStep.StepType.CALL);
        when(step.getCalledEntryPointId()).thenReturn(calledEntryPointId);
        when(scenarioStepRepository.findByScenarioIdOrderByOrderIndexAsc(scenarioId)).thenReturn(List.of(step));
        when(versionRepository.countByScenarioId(scenarioId)).thenReturn(0L);

        // Реальный save, который держит объект в памяти теста (мок репозитория,
        // но с настоящим ObjectMapper) — так проверяем именно (де)сериализацию,
        // а не просто факт вызова save().
        ScenarioVersion[] saved = new ScenarioVersion[1];
        when(versionRepository.save(any(ScenarioVersion.class))).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });

        service().snapshot(scenarioId);

        // saved[0] — настоящий ScenarioVersion (создан внутри snapshot() как
        // new ScenarioVersion()), не мок — id у него null (не персистился по-настоящему).
        // Поэтому матчим по scenarioId, а не по конкретному versionId.
        when(versionRepository.findByIdAndScenarioId(any(UUID.class), eq(scenarioId)))
                .thenReturn(Optional.of(saved[0]));

        VersionDto.Detail detail = service().get(scenarioId, UUID.randomUUID(), userId);

        assertThat(detail.snapshot().scenario().name()).isEqualTo("Place order");
        assertThat(detail.snapshot().scenario().entryPointId()).isEqualTo(entryPointId);
        assertThat(detail.snapshot().steps()).hasSize(1);
        assertThat(detail.snapshot().steps().get(0).calledEntryPointId()).isEqualTo(calledEntryPointId);
    }

    @Test
    void snapshot_unknownScenario_throwsNotFound() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().snapshot(scenarioId)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void list_unknownScenario_throwsNotFound() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(false);

        assertThatThrownBy(() -> service().list(scenarioId, userId)).isInstanceOf(EntityNotFoundException.class);
    }

    // --- Второе ревью CTO, ASAP-1 (BOLA): история изменений чужого сценария ---

    @Test
    void list_insufficientPermission_throwsNotFound_beforeTouchingRepository() {
        UUID scenarioId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(scenarioId, userId, Role.READER);

        assertThatThrownBy(() -> service().list(scenarioId, userId)).isInstanceOf(EntityNotFoundException.class);
        verify(versionRepository, never()).findByScenarioIdOrderByVersionNumberAsc(any());
    }

    @Test
    void get_insufficientPermission_throwsNotFound_beforeTouchingRepository() {
        UUID scenarioId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(scenarioId, userId, Role.READER);

        assertThatThrownBy(() -> service().get(scenarioId, versionId, userId))
                .isInstanceOf(EntityNotFoundException.class);
        verify(versionRepository, never()).findByIdAndScenarioId(any(), any());
    }
}
