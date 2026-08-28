package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.exception.ReferencedByScenariosException;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntryPointServiceTest {

    @Mock private EntryPointRepository entryPointRepository;
    @Mock private BlockInstanceRepository blockInstanceRepository;
    @Mock private SchemeRepository schemeRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private ScenarioStepRepository scenarioStepRepository;
    @Mock private EntryPointProjectResolver projectResolver;
    @Mock private PermissionService permissionService;

    private final UUID userId = UUID.randomUUID();

    private EntryPointService service() {
        return new EntryPointService(entryPointRepository, blockInstanceRepository, schemeRepository,
                scenarioRepository, scenarioStepRepository, projectResolver, permissionService);
    }

    // permissionService — lenient-noop (не бросает по умолчанию, Mockito mock
    // void-метода и так ничего не делает) — эти тесты про delete-логику,
    // авторизация покрыта отдельно ниже.

    @Test
    void delete_withoutConfirm_referencedByOtherScenarioStep_throwsWithScenarioList() {
        UUID entryPointId = UUID.randomUUID();
        EntryPoint entryPoint = mock(EntryPoint.class);
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.of(entryPoint));

        UUID callingScenarioId = UUID.randomUUID();
        ScenarioStep callingStep = mock(ScenarioStep.class);
        when(callingStep.getScenarioId()).thenReturn(callingScenarioId);
        when(scenarioStepRepository.findByCalledEntryPointIdIn(List.of(entryPointId)))
                .thenReturn(List.of(callingStep));

        Scenario callingScenario = mock(Scenario.class);
        when(callingScenario.getId()).thenReturn(callingScenarioId);
        when(scenarioRepository.findById(callingScenarioId)).thenReturn(Optional.of(callingScenario));
        when(scenarioRepository.findByEntryPointId(entryPointId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(entryPointId, false, userId))
                .isInstanceOf(ReferencedByScenariosException.class)
                .satisfies(ex -> assertThat(((ReferencedByScenariosException) ex).getReferencingScenarios())
                        .containsExactly(callingScenario));

        verify(entryPointRepository, never()).delete(any());
    }

    @Test
    void delete_withConfirm_deletesEvenIfReferenced() {
        UUID entryPointId = UUID.randomUUID();
        EntryPoint entryPoint = mock(EntryPoint.class);
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.of(entryPoint));

        service().delete(entryPointId, true, userId);

        verify(entryPointRepository).delete(entryPoint);
        verifyNoInteractions(scenarioStepRepository);
    }

    @Test
    void delete_withoutConfirm_noReferences_deletesCleanly() {
        UUID entryPointId = UUID.randomUUID();
        EntryPoint entryPoint = mock(EntryPoint.class);
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.of(entryPoint));
        when(scenarioStepRepository.findByCalledEntryPointIdIn(List.of(entryPointId))).thenReturn(List.of());
        when(scenarioRepository.findByEntryPointId(entryPointId)).thenReturn(Optional.empty());

        service().delete(entryPointId, false, userId);

        verify(entryPointRepository).delete(entryPoint);
    }

    // --- Второе ревью CTO, ASAP-1: авторизация проверяется на ДОМАШНЕМ
    // проекте entry point (через EntryPointProjectResolver), не на проекте
    // вызывающей стороны — см. javadoc EntryPointService.get. ---

    @Test
    void delete_insufficientRoleOnHomeProject_throwsNotFound_deletesNothing() {
        UUID entryPointId = UUID.randomUUID();
        UUID homeProjectId = UUID.randomUUID();
        // entryPointRepository.findById НЕ стабится намеренно: guard в
        // EntryPointService.delete() идёт ПЕРВЫМ, до findOrThrow(), так что
        // само тело сущности не понадобится ни разу — сам этот факт и
        // проверяется ниже через verify(never()).
        when(projectResolver.resolveProjectId(entryPointId)).thenReturn(homeProjectId);
        doThrow(new EntityNotFoundException("Project", homeProjectId))
                .when(permissionService).requireOnProject(homeProjectId, userId, Role.EDITOR);

        assertThatThrownBy(() -> service().delete(entryPointId, true, userId))
                .isInstanceOf(EntityNotFoundException.class);

        verify(entryPointRepository, never()).delete(any());
        verify(entryPointRepository, never()).findById(any());
    }

    @Test
    void get_checksPermissionOnEntryPointsOwnProject_notOnAnyOtherProject() {
        UUID entryPointId = UUID.randomUUID();
        UUID homeProjectId = UUID.randomUUID();
        EntryPoint entryPoint = mock(EntryPoint.class);
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.of(entryPoint));
        when(projectResolver.resolveProjectId(entryPointId)).thenReturn(homeProjectId);

        service().get(entryPointId, userId);

        verify(permissionService).requireOnProject(homeProjectId, userId, Role.READER);
    }
}
