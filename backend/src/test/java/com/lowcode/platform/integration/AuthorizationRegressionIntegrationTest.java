package com.lowcode.platform.integration;

import com.lowcode.platform.auth.AuthDto;
import com.lowcode.platform.domain.api.BlockInstanceDto;
import com.lowcode.platform.domain.api.BlockTypeDto;
import com.lowcode.platform.domain.api.EntryPointDto;
import com.lowcode.platform.domain.api.ProjectDto;
import com.lowcode.platform.domain.api.ScenarioDto;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.execution.RunDto;
import com.lowcode.platform.sharing.CollaboratorDto;
import com.lowcode.platform.sharing.ProjectMemberDto;
import com.lowcode.platform.sharing.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Второе ревью CTO, ASAP-1 (BOLA) + ASAP-2 (эскалация через sharing) — сам
 * отчёт явно требует именно этот сьют как условие считать "Итерацию A"
 * закрытой ("MEDIUM-7: ни один тест не имеет формы «пользователь B получает
 * 403/404 при попытке доступа к ресурсу пользователя A» — то есть весь P0-1
 * не был бы пойман существующим тестовым набором").
 *
 * Два реальных зарегистрированных пользователя (настоящий Postgres, настоящие
 * JWT) — owner создаёт Project/Scheme/Block/EntryPoint/Scenario, intruder
 * пытается достать/изменить/удалить их по id, а также сам себя пригласить
 * через /members и /share. Ожидаемый результат везде — 404 (не 403, см.
 * javadoc PermissionService.requireOnProject — умышленно неотличимо от
 * "такого ресурса не существует").
 *
 * Требует Docker в окружении, где выполняется `./gradlew test`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthorizationRegressionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    private String ownerToken;
    private String intruderToken;
    private UUID intruderUserId;
    private UUID microserviceBlockTypeId;

    @BeforeEach
    void setUp() {
        // TestRestTemplate по умолчанию — на SimpleClientHttpRequestFactory
        // (JDK HttpURLConnection): PATCH не поддерживается штатно
        // (ProtocolException — HttpURLConnection в принципе не умеет в PATCH),
        // а POST, получивший 401/403-подобный отказ, в отдельных случаях
        // кидает HttpRetryException вместо ResponseEntity со статусом.
        // Apache HttpClient 5 обеих проблем не имеет — этот тест-сьют активно
        // использует и PATCH (otherUser_cannotUpdateProject), и ответы с
        // отказом на POST (start run, share, members).
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());

        AuthDto.TokenResponse owner = register("owner");
        AuthDto.TokenResponse intruder = register("intruder");
        ownerToken = owner.accessToken();
        intruderToken = intruder.accessToken();
        intruderUserId = intruder.userId();

        BlockTypeDto.Response[] blockTypes =
                as(ownerToken, HttpMethod.GET, "/api/v1/block-types", null, BlockTypeDto.Response[].class).getBody();
        microserviceBlockTypeId = List.of(blockTypes).stream()
                .filter(bt -> "MICROSERVICE".equals(bt.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    // --- ASAP-1: прямой доступ по id к чужим ресурсам ---

    @Test
    void otherUser_cannotReadProjectById() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/projects/{id}", projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotUpdateProject() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<Map> response = as(intruderToken, HttpMethod.PATCH, "/api/v1/projects/{id}",
                new ProjectDto.UpdateRequest("Hacked", null), Map.class, projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Владелец по-прежнему видит исходное имя — правки не применились.
        ProjectDto.Response afterAttempt = as(ownerToken, HttpMethod.GET, "/api/v1/projects/{id}",
                null, ProjectDto.Response.class, projectId).getBody();
        assertThat(afterAttempt.name()).isEqualTo("Owner's Project");
    }

    @Test
    void otherUser_cannotDeleteProject() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<Void> response = as(intruderToken, HttpMethod.DELETE, "/api/v1/projects/{id}",
                null, Void.class, projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Проект по-прежнему существует для владельца — удаление не прошло.
        ResponseEntity<ProjectDto.Response> stillThere = as(ownerToken, HttpMethod.GET, "/api/v1/projects/{id}",
                null, ProjectDto.Response.class, projectId);
        assertThat(stillThere.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void otherUser_cannotReadSchemeOfProject() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/projects/{id}/scheme", projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotReadBlockById() {
        UUID projectId = createProject("Owner's Project").id();
        BlockInstanceDto.Response block = createBlock(projectId, "OrderApi");

        ResponseEntity<Map> response = getAsIntruder("/api/v1/projects/{projectId}/blocks/{blockId}",
                projectId, block.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotReadScenarioById() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/scenarios/{id}", scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotDeleteScenario() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Void> response = as(intruderToken, HttpMethod.DELETE, "/api/v1/scenarios/{id}",
                null, Void.class, scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<ScenarioDto.Response> stillThere = as(ownerToken, HttpMethod.GET, "/api/v1/scenarios/{id}",
                null, ScenarioDto.Response.class, scenarioId);
        assertThat(stillThere.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void otherUser_cannotReadScenarioSteps() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/scenarios/{id}/steps", scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotReadScenarioGraph() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/scenarios/{id}/graph", scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotReadScenarioVersions() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/scenarios/{id}/versions", scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotStartRunOfScenario() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = as(intruderToken, HttpMethod.POST, "/api/v1/scenarios/{id}/runs",
                null, Map.class, scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotReadRunStatus() {
        UUID scenarioId = createFullScenario("Place order").id();
        RunDto.StartResponse run = as(ownerToken, HttpMethod.POST, "/api/v1/scenarios/{id}/runs",
                null, RunDto.StartResponse.class, scenarioId).getBody();

        ResponseEntity<Map> response = getAsIntruder("/api/v1/runs/{id}", run.runId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- ASAP-2: сам sharing-эндпоинт не проверял право приглашающего ---

    @Test
    void otherUser_cannotInviteSelfIntoProject_viaMembersEndpoint() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<Map> response = as(intruderToken, HttpMethod.POST, "/api/v1/projects/{id}/members",
                new ProjectMemberDto.GrantRequest(intruderUserId, Role.EDITOR), Map.class, projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // И сам список участников чужого проекта тоже не должен быть виден.
        ResponseEntity<Map> listResponse = getAsIntruder("/api/v1/projects/{id}/members", projectId);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherUser_cannotInviteSelfIntoScenario_viaShareEndpoint() {
        UUID scenarioId = createFullScenario("Place order").id();

        ResponseEntity<Map> response = as(intruderToken, HttpMethod.POST, "/api/v1/scenarios/{id}/share",
                new CollaboratorDto.ShareRequest(intruderUserId, Role.EDITOR), Map.class, scenarioId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> listResponse = getAsIntruder("/api/v1/scenarios/{id}/collaborators", scenarioId);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Контрольная группа: владелец по-прежнему видит и делает всё сам ---

    @Test
    void owner_canStillReadAndDeleteTheirOwnProject() {
        UUID projectId = createProject("Owner's Project").id();

        ResponseEntity<ProjectDto.Response> get = as(ownerToken, HttpMethod.GET, "/api/v1/projects/{id}",
                null, ProjectDto.Response.class, projectId);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> delete = as(ownerToken, HttpMethod.DELETE, "/api/v1/projects/{id}",
                null, Void.class, projectId);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- helpers ---

    private AuthDto.TokenResponse register(String label) {
        return rest.postForObject("/api/v1/auth/register",
                new AuthDto.RegisterRequest("it-" + label + "-" + UUID.randomUUID() + "@example.com",
                        "correct horse battery staple", label),
                AuthDto.TokenResponse.class);
    }

    private <T> ResponseEntity<T> as(String token, HttpMethod method, String path, Object body, Class<T> responseType,
                                      Object... uriVars) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), responseType, uriVars);
    }

    private ResponseEntity<Map> getAsIntruder(String path, Object... uriVars) {
        return as(intruderToken, HttpMethod.GET, path, null, Map.class, uriVars);
    }

    private ProjectDto.Response createProject(String name) {
        return as(ownerToken, HttpMethod.POST, "/api/v1/projects",
                new ProjectDto.CreateRequest(name, null), ProjectDto.Response.class).getBody();
    }

    private BlockInstanceDto.Response createBlock(UUID projectId, String label) {
        return as(ownerToken, HttpMethod.POST, "/api/v1/projects/{projectId}/blocks",
                new BlockInstanceDto.CreateRequest(microserviceBlockTypeId, label, 0, 0),
                BlockInstanceDto.Response.class, projectId).getBody();
    }

    private EntryPointDto.Response createEntryPoint(UUID blockId, String name) {
        return as(ownerToken, HttpMethod.POST, "/api/v1/blocks/{blockId}/entry-points",
                new EntryPointDto.CreateRequest(name, EntryPoint.Kind.SYNC_METHOD),
                EntryPointDto.Response.class, blockId).getBody();
    }

    private ScenarioDto.Response createFullScenario(String name) {
        UUID projectId = createProject(name + " project").id();
        BlockInstanceDto.Response block = createBlock(projectId, name + " block");
        EntryPointDto.Response entryPoint = createEntryPoint(block.id(), name + " entry point");
        return as(ownerToken, HttpMethod.POST, "/api/v1/scenarios",
                new ScenarioDto.CreateRequest(name, entryPoint.id()), ScenarioDto.Response.class).getBody();
    }
}
