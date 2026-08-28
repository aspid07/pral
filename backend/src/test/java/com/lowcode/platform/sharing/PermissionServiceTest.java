package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.service.EntryPointProjectResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock private CollaboratorRepository collaboratorRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private EntryPointProjectResolver projectResolver;

    private UUID scenarioId;
    private UUID userId;
    private UUID entryPointId;
    private UUID homeProjectId;

    private PermissionService service() {
        return new PermissionService(collaboratorRepository, projectMemberRepository, scenarioRepository, projectResolver);
    }

    @BeforeEach
    void setUp() {
        scenarioId = UUID.randomUUID();
        userId = UUID.randomUUID();
        entryPointId = UUID.randomUUID();
        homeProjectId = UUID.randomUUID();

        Scenario scenario = mock(Scenario.class);
        // lenient(): не каждый тест доходит до этих строк (например,
        // effectiveRole_unknownScenario_throwsNotFound бросает раньше,
        // effectiveRoleOnProject_isSimplePassthrough вообще не резолвит
        // сценарий) — см. подробный комментарий в ExecutionEngineTest про тот
        // же паттерн с общими helper-стабами.
        lenient().when(scenario.getEntryPointId()).thenReturn(entryPointId);
        lenient().when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenario));
        lenient().when(projectResolver.resolveProjectId(entryPointId)).thenReturn(homeProjectId);
    }

    // ВАЖНО: результат этих хелперов нужно сохранять в локальную переменную
    // ДО того, как передавать его в when(...).thenReturn(...) снаружи. Хелперы
    // сами вызывают when() — если вызвать их прямо внутри аргумента ещё не
    // завершённого внешнего when(...).thenReturn(...), Mockito словит два
    // "открытых" when() одновременно и бросит UnfinishedStubbingException
    // (тот же класс проблемы, что и вложенный eq(mock.getId()) в eq(...) —
    // см. ExecutionEngineTest).
    private ProjectMember projectMember(Role role) {
        ProjectMember m = mock(ProjectMember.class);
        when(m.getRole()).thenReturn(role);
        return m;
    }

    private Collaborator collaborator(Role role) {
        Collaborator c = mock(Collaborator.class);
        when(c.getRole()).thenReturn(role);
        return c;
    }

    @Test
    void effectiveRole_onlyScenarioCollaborator_returnsThatRole() {
        Collaborator editor = collaborator(Role.EDITOR);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(editor));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThat(service().effectiveRoleOnScenario(scenarioId, userId)).contains(Role.EDITOR);
    }

    @Test
    void effectiveRole_onlyProjectMember_returnsThatRole() {
        ProjectMember owner = projectMember(Role.OWNER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.of(owner));

        assertThat(service().effectiveRoleOnScenario(scenarioId, userId)).contains(Role.OWNER);
    }

    @Test
    void effectiveRole_bothPresent_projectRoleHigher_returnsProjectRole() {
        Collaborator reader = collaborator(Role.READER);
        ProjectMember owner = projectMember(Role.OWNER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(reader));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.of(owner));

        assertThat(service().effectiveRoleOnScenario(scenarioId, userId)).contains(Role.OWNER);
    }

    @Test
    void effectiveRole_bothPresent_scenarioRoleHigher_returnsScenarioRole() {
        Collaborator editor = collaborator(Role.EDITOR);
        ProjectMember reader = projectMember(Role.READER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(editor));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.of(reader));

        assertThat(service().effectiveRoleOnScenario(scenarioId, userId)).contains(Role.EDITOR);
    }

    @Test
    void effectiveRole_neitherPresent_returnsEmpty() {
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThat(service().effectiveRoleOnScenario(scenarioId, userId)).isEmpty();
    }

    @Test
    void effectiveRole_unknownScenario_throwsNotFound() {
        UUID unknownScenarioId = UUID.randomUUID();
        when(scenarioRepository.findById(unknownScenarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().effectiveRoleOnScenario(unknownScenarioId, userId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void hasAtLeastOnScenario_exactMatch_isTrue() {
        Collaborator editor = collaborator(Role.EDITOR);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(editor));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThat(service().hasAtLeastOnScenario(scenarioId, userId, Role.EDITOR)).isTrue();
    }

    @Test
    void hasAtLeastOnScenario_below_isFalse() {
        Collaborator reader = collaborator(Role.READER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(reader));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThat(service().hasAtLeastOnScenario(scenarioId, userId, Role.EDITOR)).isFalse();
    }

    @Test
    void hasAtLeastOnScenario_noRoleAtAll_isFalse() {
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThat(service().hasAtLeastOnScenario(scenarioId, userId, Role.READER)).isFalse();
    }

    @Test
    void effectiveRoleOnProject_isSimplePassthrough() {
        UUID projectId = UUID.randomUUID();
        ProjectMember editor = projectMember(Role.EDITOR);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(editor));

        assertThat(service().effectiveRoleOnProject(projectId, userId)).contains(Role.EDITOR);
    }

    // --- Второе ревью CTO, ASAP-1: guard-методы (единая точка принуждения) ---

    @Test
    void requireOnScenario_sufficientRole_doesNotThrow() {
        Collaborator editor = collaborator(Role.EDITOR);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(editor));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        service().requireOnScenario(scenarioId, userId, Role.EDITOR);
        // Не бросило — этого достаточно, assertThatCode(...).doesNotThrowAnyException() ниже избыточен для void.
    }

    @Test
    void requireOnScenario_insufficientRole_throwsNotFound_not403() {
        Collaborator reader = collaborator(Role.READER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(reader));
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        // Именно EntityNotFoundException (→ 404 у ApiExceptionHandler), не отдельный
        // 403-тип — см. javadoc requireOnScenario: не должно быть отличимо от
        // "такого сценария не существует", иначе получаем оракул существования.
        assertThatThrownBy(() -> service().requireOnScenario(scenarioId, userId, Role.EDITOR))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void requireOnScenario_noRoleAtAll_throwsNotFound() {
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectIdAndUserId(homeProjectId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireOnScenario(scenarioId, userId, Role.READER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void requireOnProject_sufficientRole_doesNotThrow() {
        UUID projectId = UUID.randomUUID();
        ProjectMember owner = projectMember(Role.OWNER);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(owner));

        service().requireOnProject(projectId, userId, Role.EDITOR);
    }

    @Test
    void requireOnProject_insufficientRole_throwsNotFound() {
        UUID projectId = UUID.randomUUID();
        ProjectMember reader = projectMember(Role.READER);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(reader));

        assertThatThrownBy(() -> service().requireOnProject(projectId, userId, Role.OWNER))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
