package com.lowcode.platform.integration;

import com.lowcode.platform.auth.AuthDto;
import com.lowcode.platform.domain.api.BlockInstanceDto;
import com.lowcode.platform.domain.api.BlockTypeDto;
import com.lowcode.platform.domain.api.EntryPointDto;
import com.lowcode.platform.domain.api.ProjectDto;
import com.lowcode.platform.domain.api.ScenarioDto;
import com.lowcode.platform.domain.api.ScenarioStepDto;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.ScenarioStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Единственный тест в проекте, который реально поднимает Postgres (не мок) и
 * прогоняет через него настоящие Flyway-миграции. Существует конкретно потому,
 * что баг с ON DELETE (см. ревью, V4__fix_entry_point_delete_cascade.sql) был
 * физически невидим для Mockito-юнит-тестов — DataIntegrityViolationException
 * бросает настоящий Postgres на constraint'е, а не мок репозитория.
 *
 * Требует Docker в окружении, где запускается `mvn test`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BlockAndEntryPointDeleteCascadeIntegrationTest {

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

    private UUID microserviceBlockTypeId;

    @BeforeEach
    void setUp() {
        // Stage 4: все /api/v1/** кроме /auth/** теперь требуют аутентификации —
        // регистрируем тестового пользователя один раз и вешаем его токен на
        // КАЖДЫЙ последующий запрос через интерцептор, а не переписываем
        // каждый rest.postForObject/getForObject на exchange() с ручными заголовками.
        AuthDto.TokenResponse token = rest.postForObject("/api/v1/auth/register",
                new AuthDto.RegisterRequest("it-" + UUID.randomUUID() + "@example.com",
                        "correct horse battery staple", "IT User"),
                AuthDto.TokenResponse.class);
        rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("Authorization", "Bearer " + token.accessToken());
            return execution.execute(request, body);
        });

        BlockTypeDto.Response[] blockTypes = rest.getForObject("/api/v1/block-types", BlockTypeDto.Response[].class);
        microserviceBlockTypeId = List.of(blockTypes).stream()
                .filter(bt -> "MICROSERVICE".equals(bt.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    /**
     * Раньше эта цепочка падала с DataIntegrityViolationException (500) на шаге
     * confirm=true, потому что scenario.entry_point_id не имел ON DELETE CASCADE
     * и блокировал удаление entry_point, на который каскадом от блока пыталась
     * добраться БД. После V4 — сценарий, реализующий удаляемую точку, тоже
     * удаляется каскадом (что и есть корректная семантика: сценарий не может
     * существовать без entry point, который он реализует).
     */
    @Test
    void deletingBlock_withConfirmTrue_cascadesThroughOwnScenarioWithoutConstraintViolation() {
        UUID projectId = createProject("Order Service").id();
        BlockInstanceDto.Response block = createBlock(projectId, "OrderApi");
        EntryPointDto.Response entryPoint = createEntryPoint(block.id(), "POST /orders");
        ScenarioDto.Response scenario = createScenario("Place order", entryPoint.id());

        // Без confirm — должны получить 409 (сценарий реализует этот entry point)
        ResponseEntity<Map> conflict = rest.exchange(
                "/api/v1/projects/{projectId}/blocks/{blockId}", org.springframework.http.HttpMethod.DELETE,
                null, Map.class, projectId, block.id());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // С confirm=true — реально удаляется, без 500 от БД
        ResponseEntity<Void> deleted = rest.exchange(
                "/api/v1/projects/{projectId}/blocks/{blockId}?confirm=true", org.springframework.http.HttpMethod.DELETE,
                null, Void.class, projectId, block.id());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Сценарий, реализующий удалённый entry point, каскадом удалился тоже
        ResponseEntity<Map> scenarioAfter = rest.getForEntity("/api/v1/scenarios/{id}", Map.class, scenario.id());
        assertThat(scenarioAfter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Симметричный случай: entry point, на который ссылается CALL-шаг ЧУЖОГО
     * сценария (не тот, который сценарий реализует). После V4 — шаг не удаляется
     * вместе с точкой, а становится "битым" (calledEntryPointId = null), сам
     * сценарий и остальная структура шагов сохраняются.
     */
    @Test
    void deletingEntryPoint_withConfirmTrue_referencedByCallStep_nullsTheStepInsteadOfDeletingIt() {
        UUID projectA = createProject("Checkout").id();
        BlockInstanceDto.Response blockA = createBlock(projectA, "CheckoutApi");
        EntryPointDto.Response entryPointA = createEntryPoint(blockA.id(), "POST /checkout");
        ScenarioDto.Response scenario = createScenario("Checkout flow", entryPointA.id());

        UUID projectB = createProject("Payments").id();
        BlockInstanceDto.Response blockB = createBlock(projectB, "PaymentApi");
        EntryPointDto.Response entryPointB = createEntryPoint(blockB.id(), "POST /charge");

        ScenarioStepDto.Response callStep = createCallStep(scenario.id(), entryPointB.id());

        ResponseEntity<Void> deleted = rest.exchange(
                "/api/v1/entry-points/{id}?confirm=true", org.springframework.http.HttpMethod.DELETE,
                null, Void.class, entryPointB.id());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ScenarioStepDto.Response[] stepsAfter =
                rest.getForObject("/api/v1/scenarios/{id}/steps", ScenarioStepDto.Response[].class, scenario.id());
        ScenarioStepDto.Response updatedStep = List.of(stepsAfter).stream()
                .filter(s -> s.id().equals(callStep.id()))
                .findFirst()
                .orElseThrow();

        assertThat(updatedStep.calledEntryPointId()).isNull(); // ON DELETE SET NULL сработал
        assertThat(updatedStep.stepType()).isEqualTo(ScenarioStep.StepType.CALL); // а не исчез целиком

        // Сам сценарий (реализующий entryPointA, не затронутый) — жив
        ResponseEntity<ScenarioDto.Response> scenarioAfter =
                rest.getForEntity("/api/v1/scenarios/{id}", ScenarioDto.Response.class, scenario.id());
        assertThat(scenarioAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ProjectDto.Response createProject(String name) {
        return rest.postForObject("/api/v1/projects", new ProjectDto.CreateRequest(name, null), ProjectDto.Response.class);
    }

    private BlockInstanceDto.Response createBlock(UUID projectId, String label) {
        return rest.postForObject("/api/v1/projects/{projectId}/blocks",
                new BlockInstanceDto.CreateRequest(microserviceBlockTypeId, label, 0, 0),
                BlockInstanceDto.Response.class, projectId);
    }

    private EntryPointDto.Response createEntryPoint(UUID blockId, String name) {
        return rest.postForObject("/api/v1/blocks/{blockId}/entry-points",
                new EntryPointDto.CreateRequest(name, EntryPoint.Kind.SYNC_METHOD),
                EntryPointDto.Response.class, blockId);
    }

    private ScenarioDto.Response createScenario(String name, UUID entryPointId) {
        return rest.postForObject("/api/v1/scenarios",
                new ScenarioDto.CreateRequest(name, entryPointId),
                ScenarioDto.Response.class);
    }

    private ScenarioStepDto.Response createCallStep(UUID scenarioId, UUID calledEntryPointId) {
        return rest.postForObject("/api/v1/scenarios/{scenarioId}/steps",
                new ScenarioStepDto.CreateRequest(ScenarioStep.StepType.CALL, null, calledEntryPointId, null, null, null, null),
                ScenarioStepDto.Response.class, scenarioId);
    }
}
