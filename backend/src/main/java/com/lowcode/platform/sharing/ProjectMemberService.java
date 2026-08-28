package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Роли на уровне Project — "грубая" гранулярность гибридной модели прав
 * (см. V9-миграцию). Структурно почти дословно повторяет CollaboratorService
 * (та же upsert-семантика, та же защита OWNER) — сознательно не стал
 * выносить в общую генерик-абстракцию ради двух экземпляров паттерна;
 * если появится третий — стоит пересмотреть.
 *
 * Второе ревью CTO, ASAP-2 (эскалация привилегий) — тот же фикс, что и в
 * CollaboratorService (см. его javadoc): grant()/revoke() раньше не
 * проверяли, что вызывающий вообще имеет право распоряжаться доступом к
 * этому projectId — POST .../members с {"userId": <свой>, "role": "EDITOR"}
 * пускал кого угодно в чужой проект.
 */
@Service
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final PermissionService permissionService;

    public ProjectMemberService(ProjectMemberRepository projectMemberRepository, ProjectRepository projectRepository,
                                 PermissionService permissionService) {
        this.projectMemberRepository = projectMemberRepository;
        this.projectRepository = projectRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberDto.Response> list(UUID projectId, UUID callerUserId) {
        permissionService.requireOnProject(projectId, callerUserId, Role.READER);
        requireProject(projectId);
        return projectMemberRepository.findByProjectId(projectId).stream().map(this::toResponse).toList();
    }

    /** EDITOR — минимум, необходимый, чтобы приглашать кого-либо в проект (см. class-javadoc, ASAP-2). */
    @Transactional
    public ProjectMemberDto.Response grant(UUID projectId, ProjectMemberDto.GrantRequest request, UUID callerUserId) {
        permissionService.requireOnProject(projectId, callerUserId, Role.EDITOR);
        requireProject(projectId);
        if (request.role() == Role.OWNER) {
            throw new IllegalArgumentException("Cannot grant OWNER on a project — it is set once at project creation");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, request.userId())
                .orElseGet(() -> {
                    ProjectMember m = new ProjectMember();
                    m.setProjectId(projectId);
                    m.setUserId(request.userId());
                    return m;
                });

        if (member.getId() != null && member.getRole() == Role.OWNER) {
            throw new IllegalStateException("Cannot change role of the project owner");
        }

        member.setRole(request.role());
        member = projectMemberRepository.save(member);
        return toResponse(member);
    }

    @Transactional
    public void revoke(UUID projectId, UUID userId, UUID callerUserId) {
        permissionService.requireOnProject(projectId, callerUserId, Role.EDITOR);
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("ProjectMember", userId));
        if (member.getRole() == Role.OWNER) {
            throw new IllegalStateException("Cannot revoke the project owner");
        }
        projectMemberRepository.delete(member);
    }

    /** Вызывается из ProjectService.create() — единственное место, выдающее OWNER; без callerUserId/guard намеренно — не пользовательский эндпоинт. */
    @Transactional
    public void grantOwner(UUID projectId, UUID ownerUserId) {
        ProjectMember owner = new ProjectMember();
        owner.setProjectId(projectId);
        owner.setUserId(ownerUserId);
        owner.setRole(Role.OWNER);
        projectMemberRepository.save(owner);
    }

    private void requireProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project", projectId);
        }
    }

    private ProjectMemberDto.Response toResponse(ProjectMember m) {
        return new ProjectMemberDto.Response(m.getId(), m.getProjectId(), m.getUserId(), m.getRole());
    }
}
