package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.ScenarioStepDto;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.versioning.VersioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScenarioStepServiceTest {

    @Mock private ScenarioStepRepository scenarioStepRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private EntryPointRepository entryPointRepository;
    // Не стабится: snapshot() возвращает void, мок ничего не делает по умолчанию —
    // достаточно просто передать его в конструктор.
    @Mock private VersioningService versioningService;
    // lenient: authorization noop-мок (успех по умолчанию, void-метод ничего не
    // делает) — эти тесты про валидацию полей шага, авторизация покрыта отдельно.
    @Mock private PermissionService permissionService;

    private final UUID userId = UUID.randomUUID();

    private ScenarioStepService service() {
        return new ScenarioStepService(scenarioStepRepository, scenarioRepository, entryPointRepository,
                versioningService, permissionService);
    }

    @Test
    void create_call_assignsNextOrderIndexAmongSiblings() {
        UUID scenarioId = UUID.randomUUID();
        UUID entryPointId = UUID.randomUUID();

        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));
        when(entryPointRepository.existsById(entryPointId)).thenReturn(true);

        ScenarioStep existingSibling = new ScenarioStep();
        existingSibling.setOrderIndex(2);
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, null))
                .thenReturn(List.of(existingSibling));
        when(scenarioStepRepository.save(any(ScenarioStep.class))).thenAnswer(inv -> inv.getArgument(0));

        ScenarioStepDto.Response response = service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.CALL, null, entryPointId, null, null, null, null), userId);

        assertThat(response.orderIndex()).isEqualTo(3);
        assertThat(response.stepType()).isEqualTo(ScenarioStep.StepType.CALL);
    }

    @Test
    void create_call_withoutEntryPoint_throws() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));

        assertThatThrownBy(() -> service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.CALL, null, null, null, null, null, null), userId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_altWrapper_withEntryPoint_throws() {
        UUID scenarioId = UUID.randomUUID();
        UUID entryPointId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));

        assertThatThrownBy(() -> service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.ALT, null, entryPointId, "cache miss", null, null, null), userId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_retry_withoutMaxAttempts_throws() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));

        assertThatThrownBy(() -> service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.RETRY, null, null, null, null, null, null), userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void create_retry_withMaxAttempts_succeeds() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, null)).thenReturn(List.of());
        when(scenarioStepRepository.save(any(ScenarioStep.class))).thenAnswer(inv -> inv.getArgument(0));

        ScenarioStepDto.Response response = service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.RETRY, null, null, null, null, 3, null), userId);

        assertThat(response.maxAttempts()).isEqualTo(3);
    }

    @Test
    void create_call_withMaxAttempts_throws() {
        UUID scenarioId = UUID.randomUUID();
        UUID entryPointId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));
        when(entryPointRepository.existsById(entryPointId)).thenReturn(true);

        assertThatThrownBy(() -> service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.CALL, null, entryPointId, null, null, 3, null), userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void create_timeout_withoutTimeoutMs_throws() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));

        assertThatThrownBy(() -> service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.TIMEOUT, null, null, null, null, null, null), userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void create_timeout_withTimeoutMs_succeeds() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(new Scenario()));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, null)).thenReturn(List.of());
        when(scenarioStepRepository.save(any(ScenarioStep.class))).thenAnswer(inv -> inv.getArgument(0));

        ScenarioStepDto.Response response = service().create(scenarioId,
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.TIMEOUT, null, null, null, null, null, 5000), userId);

        assertThat(response.timeoutMs()).isEqualTo(5000);
    }

    @Test
    void delete_removesChildrenRecursivelyBeforeParent() {
        // ScenarioStep.id проставляется Hibernate (@GeneratedValue), сеттера нет —
        // в юнит-тесте id мокаем через Mockito, а не через реальные new ScenarioStep().
        UUID scenarioId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        ScenarioStep parent = mock(ScenarioStep.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getScenarioId()).thenReturn(scenarioId);

        ScenarioStep child = mock(ScenarioStep.class);
        when(child.getId()).thenReturn(childId);

        when(scenarioStepRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(scenarioStepRepository.findByParentStepId(parentId)).thenReturn(List.of(child));
        when(scenarioStepRepository.findByParentStepId(childId)).thenReturn(List.of());

        service().delete(scenarioId, parentId, userId);

        var inOrder = inOrder(scenarioStepRepository);
        inOrder.verify(scenarioStepRepository).delete(child);
        inOrder.verify(scenarioStepRepository).delete(parent);
    }
}
