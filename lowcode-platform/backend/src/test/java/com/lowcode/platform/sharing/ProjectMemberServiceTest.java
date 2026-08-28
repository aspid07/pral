package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.repository.ProjectRepository;
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
class ProjectMemberServiceTest {

    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectRepository projectRepository;
    // permissionService — noop-мок (успех по умолчанию): эти тесты про
    // upsert/защиту роли OWNER, авторизация покрыта отдельно ниже
    // (Второе ревью CTO, ASAP-2).
    @Mock private PermissionService permissionService;

    private final UUID callerUserId = UUID.randomUUID();

    private ProjectMemberService service() {
        return new ProjectMemberService(projectMemberRepository, projectRepository, permissionService);
    }

    @Test
    void grant_newUser_createsWithGivenRole() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectMemberDto.Response response = service()
                .grant(projectId, new ProjectMemberDto.GrantRequest(userId, Role.EDITOR), callerUserId);

        assertThat(response.role()).isEqualTo(Role.EDITOR);
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void grant_existingUser_updatesRoleInsteadOfDuplicating() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);

        ProjectMember existing = mock(ProjectMember.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getRole()).thenReturn(Role.READER);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(existing));
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service().grant(projectId, new ProjectMemberDto.GrantRequest(userId, Role.EDITOR), callerUserId);

        verify(existing).setRole(Role.EDITOR);
        verify(projectMemberRepository, never()).save(argThat(m -> m != existing));
    }

    @Test
    void grant_grantingOwnerRole_isRejected() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);

        assertThatThrownBy(() -> service()
                .grant(projectId, new ProjectMemberDto.GrantRequest(UUID.randomUUID(), Role.OWNER), callerUserId))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void grant_targetingExistingOwner_isRejected() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);

        ProjectMember owner = mock(ProjectMember.class);
        when(owner.getId()).thenReturn(UUID.randomUUID());
        when(owner.getRole()).thenReturn(Role.OWNER);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service()
                .grant(projectId, new ProjectMemberDto.GrantRequest(userId, Role.EDITOR), callerUserId))
                .isInstanceOf(IllegalStateException.class);

        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void revoke_owner_isRejected() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProjectMember owner = mock(ProjectMember.class);
        when(owner.getRole()).thenReturn(Role.OWNER);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service().revoke(projectId, userId, callerUserId)).isInstanceOf(IllegalStateException.class);
        verify(projectMemberRepository, never()).delete(any());
    }

    @Test
    void revoke_editor_deletes() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProjectMember editor = mock(ProjectMember.class);
        when(editor.getRole()).thenReturn(Role.EDITOR);
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(editor));

        service().revoke(projectId, userId, callerUserId);

        verify(projectMemberRepository).delete(editor);
    }

    @Test
    void revoke_unknownUser_throwsNotFound() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revoke(projectId, userId, callerUserId)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void grantOwner_savesOwnerRole() {
        UUID projectId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service().grantOwner(projectId, ownerUserId);

        verify(projectMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.OWNER);
        assertThat(captor.getValue().getUserId()).isEqualTo(ownerUserId);
        assertThat(captor.getValue().getProjectId()).isEqualTo(projectId);
    }

    @Test
    void list_unknownProject_throwsNotFound() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(false);

        assertThatThrownBy(() -> service().list(projectId, callerUserId)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void list_returnsAllMembers() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);
        ProjectMember m1 = mock(ProjectMember.class);
        ProjectMember m2 = mock(ProjectMember.class);
        when(projectMemberRepository.findByProjectId(projectId)).thenReturn(List.of(m1, m2));

        List<ProjectMemberDto.Response> result = service().list(projectId, callerUserId);

        assertThat(result).hasSize(2);
    }

    // --- Второе ревью CTO, ASAP-2: caller должен иметь право приглашать/
    // отзывать доступ, не только назначаемая роль должна быть валидна ---

    @Test
    void grant_callerWithoutAccess_isRejected_beforeAnyRepositoryWrite() {
        UUID projectId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Project", projectId))
                .when(permissionService).requireOnProject(projectId, callerUserId, Role.EDITOR);

        assertThatThrownBy(() -> service()
                .grant(projectId, new ProjectMemberDto.GrantRequest(callerUserId, Role.EDITOR), callerUserId))
                .isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void revoke_callerWithoutAccess_isRejected_beforeAnyRepositoryRead() {
        UUID projectId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Project", projectId))
                .when(permissionService).requireOnProject(projectId, callerUserId, Role.EDITOR);

        assertThatThrownBy(() -> service().revoke(projectId, targetUserId, callerUserId))
                .isInstanceOf(EntityNotFoundException.class);

        verify(projectMemberRepository, never()).findByProjectIdAndUserId(any(), any());
    }
}
