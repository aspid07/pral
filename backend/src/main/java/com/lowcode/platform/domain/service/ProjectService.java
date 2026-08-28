package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.ProjectDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.Project;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.ProjectRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.ProjectMemberService;
import com.lowcode.platform.sharing.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final SchemeRepository schemeRepository;
    private final ProjectMemberService projectMemberService;
    private final PermissionService permissionService;

    public ProjectService(ProjectRepository projectRepository, SchemeRepository schemeRepository,
                           ProjectMemberService projectMemberService, PermissionService permissionService) {
        this.projectRepository = projectRepository;
        this.schemeRepository = schemeRepository;
        this.projectMemberService = projectMemberService;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public Page<ProjectDto.Response> list(UUID userId, Pageable pageable) {
        // Ревью CTO, п.1.3: раньше отдавал ВСЕ проекты в базе, а не
        // только те, к которым у пользователя есть доступ.
        return projectRepository.findAllVisibleTo(userId, pageable).map(this::toResponse);
    }

    /**
     * Второе ревью CTO, ASAP-1 (BOLA): раньше get/update/delete принимали
     * только {projectId} из URL и работали с сущностью без единой проверки,
     * что userId вообще имеет к ней отношение — id проекта тривиально
     * получить (виден в URL/Location-заголовке/логах любого другого запроса),
     * так что "список только моих" (list() выше) не защищал ничего за
     * пределами самого списка. requireOnProject бросает 404 (не 403, см.
     * его javadoc в PermissionService) — тот же самый ответ, что и для
     * реально несуществующего projectId, специально неотличимый.
     */
    @Transactional(readOnly = true)
    public ProjectDto.Response get(UUID projectId, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.READER);
        return toResponse(findProjectOrThrow(projectId));
    }

    /**
     * Создаёт Проект и сразу пустую Общую схему в одной транзакции —
     * см. UC1 в functional-requirements.md: у Проекта всегда есть Scheme.
     * ownerId теперь всегда присутствует (источник — SecurityContext на
     * уровне контроллера, не тело запроса) — становится OWNER в
     * project_member. Раньше опциональность позволяла тихо создать проект
     * без владельца, которому потом некому было стать (см. ревью CTO, п.1.4).
     */
    @Transactional
    public ProjectDto.Response create(ProjectDto.CreateRequest request, UUID ownerId) {
        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project = projectRepository.save(project);

        Scheme scheme = new Scheme();
        scheme.setProjectId(project.getId());
        schemeRepository.save(scheme);

        projectMemberService.grantOwner(project.getId(), ownerId);

        return toResponse(project, scheme.getId());
    }

    @Transactional
    public ProjectDto.Response update(UUID projectId, ProjectDto.UpdateRequest request, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.EDITOR);
        Project project = findProjectOrThrow(projectId);
        project.setName(request.name());
        project.setDescription(request.description());
        return toResponse(project);
    }

    /**
     * OWNER, не EDITOR — удаление всего проекта (и, каскадно, всех блоков/
     * схем/сценариев в нём) выше по разрушительности, чем правки содержимого;
     * та же граница, что api-contract.md уже фиксирует явно для Scenario
     * ("DELETE — только Owner").
     */
    @Transactional
    public void delete(UUID projectId, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.OWNER);
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project", projectId);
        }
        // Scheme и все дочерние сущности удаляются каскадно на уровне БД (см. V1__init.sql, on delete cascade)
        projectRepository.deleteById(projectId);
    }

    private Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project", projectId));
    }

    private ProjectDto.Response toResponse(Project project) {
        UUID schemeId = schemeRepository.findByProjectId(project.getId())
                .map(Scheme::getId)
                .orElse(null);
        return toResponse(project, schemeId);
    }

    private ProjectDto.Response toResponse(Project project, UUID schemeId) {
        return new ProjectDto.Response(
                project.getId(), project.getName(), project.getDescription(),
                schemeId, project.getCreatedAt());
    }
}
