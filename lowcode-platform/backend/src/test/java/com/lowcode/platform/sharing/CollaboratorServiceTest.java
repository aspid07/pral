package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.repository.ScenarioRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaboratorServiceTest {

    @Mock private CollaboratorRepository collaboratorRepository;
    @Mock private ScenarioRepository scenarioRepository;
    // permissionService — noop-мок (успех по умолчанию): эти тесты про
    // upsert/защиту роли OWNER, авторизация покрыта отдельно ниже
    // (Второе ревью CTO, ASAP-2).
    @Mock private PermissionService permissionService;

    private final UUID callerUserId = UUID.randomUUID();

    private CollaboratorService service() {
        return new CollaboratorService(collaboratorRepository, scenarioRepository, permissionService);
    }

    @Test
    void share_newUser_createsWithGivenRole() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());
        when(collaboratorRepository.save(any(Collaborator.class))).thenAnswer(inv -> inv.getArgument(0));

        CollaboratorDto.Response response = service()
                .share(scenarioId, new CollaboratorDto.ShareRequest(userId, Role.EDITOR), callerUserId);

        assertThat(response.role()).isEqualTo(Role.EDITOR);
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void share_existingUser_updatesRoleInsteadOfDuplicating() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);

        Collaborator existing = mock(Collaborator.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getRole()).thenReturn(Role.READER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(existing));
        when(collaboratorRepository.save(any(Collaborator.class))).thenAnswer(inv -> inv.getArgument(0));

        service().share(scenarioId, new CollaboratorDto.ShareRequest(userId, Role.EDITOR), callerUserId);

        verify(existing).setRole(Role.EDITOR);
        verify(collaboratorRepository, never()).save(argThat(c -> c != existing));
    }

    @Test
    void share_grantingOwnerRole_isRejected() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);

        assertThatThrownBy(() -> service()
                .share(scenarioId, new CollaboratorDto.ShareRequest(UUID.randomUUID(), Role.OWNER), callerUserId))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(collaboratorRepository);
    }

    @Test
    void share_targetingExistingOwner_isRejected() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);

        Collaborator owner = mock(Collaborator.class);
        when(owner.getId()).thenReturn(UUID.randomUUID());
        when(owner.getRole()).thenReturn(Role.OWNER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service()
                .share(scenarioId, new CollaboratorDto.ShareRequest(userId, Role.EDITOR), callerUserId))
                .isInstanceOf(IllegalStateException.class);

        verify(collaboratorRepository, never()).save(any());
    }

    @Test
    void revoke_owner_isRejected() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Collaborator owner = mock(Collaborator.class);
        when(owner.getRole()).thenReturn(Role.OWNER);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service().revoke(scenarioId, userId, callerUserId))
                .isInstanceOf(IllegalStateException.class);
        verify(collaboratorRepository, never()).delete(any());
    }

    @Test
    void revoke_editor_deletes() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Collaborator editor = mock(Collaborator.class);
        when(editor.getRole()).thenReturn(Role.EDITOR);
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.of(editor));

        service().revoke(scenarioId, userId, callerUserId);

        verify(collaboratorRepository).delete(editor);
    }

    @Test
    void revoke_unknownUser_throwsNotFound() {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revoke(scenarioId, userId, callerUserId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void grantOwner_savesOwnerRole() {
        UUID scenarioId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        ArgumentCaptor<Collaborator> captor = ArgumentCaptor.forClass(Collaborator.class);
        when(collaboratorRepository.save(any(Collaborator.class))).thenAnswer(inv -> inv.getArgument(0));

        service().grantOwner(scenarioId, ownerUserId);

        verify(collaboratorRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(captor.getValue().getUserId()).isEqualTo(ownerUserId);
        assertThat(captor.getValue().getScenarioId()).isEqualTo(scenarioId);
    }

    @Test
    void list_unknownScenario_throwsNotFound() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(false);

        assertThatThrownBy(() -> service().list(scenarioId, callerUserId)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void list_returnsAllCollaborators() {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioRepository.existsById(scenarioId)).thenReturn(true);
        Collaborator c1 = mock(Collaborator.class);
        Collaborator c2 = mock(Collaborator.class);
        when(collaboratorRepository.findByScenarioId(scenarioId)).thenReturn(List.of(c1, c2));

        List<CollaboratorDto.Response> result = service().list(scenarioId, callerUserId);

        assertThat(result).hasSize(2);
    }

    // --- Второе ревью CTO, ASAP-2: caller должен иметь право приглашать/
    // отзывать доступ, не только назначаемая роль должна быть валидна ---

    @Test
    void share_callerWithoutAccess_isRejected_beforeAnyRepositoryWrite() {
        // Ключевой сценарий находки: любой аутентифицированный мог раньше
        // вызвать share на ЧУЖОЙ scenarioId и добавить себя сам.
        UUID scenarioId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(scenarioId, callerUserId, Role.EDITOR);

        assertThatThrownBy(() -> service()
                .share(scenarioId, new CollaboratorDto.ShareRequest(callerUserId, Role.EDITOR), callerUserId))
                .isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(collaboratorRepository);
    }

    @Test
    void share_callerIsReaderOnly_isRejected() {
        // READER — недостаточно, чтобы приглашать: тот же уровень, на котором
        // можно ПРОСМАТРИВАТЬ сценарий, не управлять доступом к нему.
        UUID scenarioId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(scenarioId, callerUserId, Role.EDITOR);

        assertThatThrownBy(() -> service()
                .share(scenarioId, new CollaboratorDto.ShareRequest(UUID.randomUUID(), Role.READER), callerUserId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void revoke_callerWithoutAccess_isRejected_beforeAnyRepositoryRead() {
        UUID scenarioId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Scenario", scenarioId))
                .when(permissionService).requireOnScenario(scenarioId, callerUserId, Role.EDITOR);

        assertThatThrownBy(() -> service().revoke(scenarioId, targetUserId, callerUserId))
                .isInstanceOf(EntityNotFoundException.class);

        verify(collaboratorRepository, never()).findByScenarioIdAndUserId(any(), any());
    }
}
