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

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private SchemeRepository schemeRepository;
    // Не стабится: grantOwner() возвращает void, мок ничего не делает по умолчанию.
    @Mock private ProjectMemberService projectMemberService;
    @Mock private PermissionService permissionService;

    private ProjectService service() {
        return new ProjectService(projectRepository, schemeRepository, projectMemberService, permissionService);
    }

    @Test
    void createProject_alsoCreatesScheme() {
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            return p; // в реальной БД id проставит Hibernate; для теста не важно
        });
        when(schemeRepository.save(any(Scheme.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID ownerId = UUID.randomUUID();
        ProjectDto.Response response = service()
                .create(new ProjectDto.CreateRequest("Order Service", "Оформление заказов"), ownerId);

        assertThat(response.name()).isEqualTo("Order Service");
        verify(schemeRepository, times(1)).save(any(Scheme.class));
        verify(projectMemberService).grantOwner(any(), eq(ownerId));
    }

    @Test
    void delete_unknownProject_throwsNotFound() {
        // Второе ревью CTO, ASAP-1: guard идёт ПЕРВЫМ (до проверки existsById),
        // поэтому даже для "проекта реально нет" ответ определяется тем же
        // кодом, что и "нет прав" — оба реалистичных случая моделируются
        // мокнутым permissionService, бросающим EntityNotFoundException сам
        // (как это по-настоящему делает requireOnProject на несуществующем id).
        UUID missingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Project", missingId))
                .when(permissionService).requireOnProject(missingId, userId, Role.OWNER);

        assertThatThrownBy(() -> service().delete(missingId, userId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_insufficientRole_throwsNotFound_beforeTouchingRepository() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Project", projectId))
                .when(permissionService).requireOnProject(projectId, userId, Role.OWNER);

        assertThatThrownBy(() -> service().delete(projectId, userId))
                .isInstanceOf(EntityNotFoundException.class);
        // Guard должен сработать ДО существующей проверки/удаления — иначе
        // порядок вызовов давал бы наблюдаемую разницу между "нет проекта" и
        // "есть, но не твой" через побочные эффекты (например, тайминг).
        verify(projectRepository, never()).existsById(any());
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void get_insufficientRole_throwsNotFound_beforeTouchingRepository() {
        // Второе ревью CTO, ASAP-1: главный сценарий находки — GET по чужому
        // id раньше отдавал данные без единой проверки видимости.
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new EntityNotFoundException("Project", projectId))
                .when(permissionService).requireOnProject(projectId, userId, Role.READER);

        assertThatThrownBy(() -> service().get(projectId, userId))
                .isInstanceOf(EntityNotFoundException.class);
        verify(projectRepository, never()).findById(any());
    }

    @Test
    void get_returnsSchemeId_whenSchemeExists() {
        // new Project()/new Scheme() не годятся здесь: у Project нет setId() (id
        // проставляет Hibernate при персисте), поэтому project.getId() всегда
        // null, а schemeRepository.findByProjectId(projectId) с настоящим id
        // никогда бы не сматчился — ровно то же самое, что словил Mockito
        // strict-stubbing в других тестах (см. ревью).
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(project.getName()).thenReturn("Catalog Service");

        UUID schemeId = UUID.randomUUID();
        Scheme scheme = mock(Scheme.class);
        when(scheme.getId()).thenReturn(schemeId);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(schemeRepository.findByProjectId(projectId)).thenReturn(Optional.of(scheme));

        ProjectDto.Response response = service().get(projectId, userId);

        assertThat(response.name()).isEqualTo("Catalog Service");
        // Тест называется "returnsSchemeId..." — а раньше не проверял schemeId вообще.
        assertThat(response.schemeId()).isEqualTo(schemeId);
        verify(permissionService).requireOnProject(projectId, userId, Role.READER);
    }
}
