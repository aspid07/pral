package com.lowcode.platform.execution;

import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Project;
import com.lowcode.platform.domain.model.Scenario;
import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.BlockTypeRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.ProjectRepository;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScenarioGraphServiceTest {

    @Mock private ScenarioRepository scenarioRepository;
    @Mock private ScenarioStepRepository scenarioStepRepository;
    @Mock private EntryPointRepository entryPointRepository;
    @Mock private BlockInstanceRepository blockInstanceRepository;
    @Mock private SchemeRepository schemeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private BlockTypeRepository blockTypeRepository;
    // permissionService — noop-мок (успех по умолчанию): эти тесты про построение
    // графа, авторизация покрыта отдельно (см. блок в конце файла).
    @Mock private PermissionService permissionService;

    private ScenarioGraphService service;
    private final UUID userId = UUID.randomUUID();
    private UUID scenarioId;
    private UUID rootEntryPoint;
    private UUID rootBlock;
    private UUID rootScheme;
    private UUID rootProject;

    @BeforeEach
    void setUp() {
        service = new ScenarioGraphService(scenarioRepository, scenarioStepRepository, entryPointRepository,
                blockInstanceRepository, schemeRepository, projectRepository, blockTypeRepository, permissionService);
        when(blockTypeRepository.findAll()).thenReturn(List.of());

        scenarioId = UUID.randomUUID();
        rootEntryPoint = UUID.randomUUID();
        rootBlock = UUID.randomUUID();
        rootScheme = UUID.randomUUID();
        rootProject = UUID.randomUUID();

        Scenario scenario = mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenario.getEntryPointId()).thenReturn(rootEntryPoint);
        when(scenarioRepository.findById(scenarioId)).thenReturn(Optional.of(scenario));

        wireEntryPoint(rootEntryPoint, rootBlock, "Root");
        wireBlock(rootBlock, "RootBlock", rootScheme);
        wireProject(rootScheme, rootProject, "Root project");
    }

    // lenient() — см. подробный комментарий в ExecutionEngineTest: общие
    // helper-методы, не каждый стаб консьюмится в каждом конкретном тесте.
    private void wireEntryPoint(UUID id, UUID blockId, String name) {
        EntryPoint ep = mock(EntryPoint.class);
        lenient().when(ep.getId()).thenReturn(id);
        lenient().when(ep.getBlockInstanceId()).thenReturn(blockId);
        when(entryPointRepository.findById(id)).thenReturn(Optional.of(ep));
    }

    private void wireBlock(UUID blockId, String label, UUID schemeId) {
        BlockInstance block = mock(BlockInstance.class);
        lenient().when(block.getId()).thenReturn(blockId);
        lenient().when(block.getSchemeId()).thenReturn(schemeId);
        lenient().when(block.getLabel()).thenReturn(label);
        when(blockInstanceRepository.findById(blockId)).thenReturn(Optional.of(block));
    }

    private void wireProject(UUID schemeId, UUID projectId, String projectName) {
        Scheme scheme = mock(Scheme.class);
        lenient().when(scheme.getProjectId()).thenReturn(projectId);
        when(schemeRepository.findById(schemeId)).thenReturn(Optional.of(scheme));

        Project project = mock(Project.class);
        lenient().when(project.getId()).thenReturn(projectId);
        lenient().when(project.getName()).thenReturn(projectName);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    private ScenarioStep mockStep(UUID id, UUID scenarioId, ScenarioStep.StepType type, int orderIndex) {
        ScenarioStep step = mock(ScenarioStep.class);
        lenient().when(step.getId()).thenReturn(id);
        lenient().when(step.getScenarioId()).thenReturn(scenarioId);
        lenient().when(step.getStepType()).thenReturn(type);
        lenient().when(step.getOrderIndex()).thenReturn(orderIndex);
        return step;
    }

    @Test
    void call_toOtherProject_addsBothProjectsAndOneEdge() {
        UUID otherEntryPoint = UUID.randomUUID();
        UUID otherBlock = UUID.randomUUID();
        UUID otherScheme = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        wireEntryPoint(otherEntryPoint, otherBlock, "Other");
        wireBlock(otherBlock, "OtherBlock", otherScheme);
        wireProject(otherScheme, otherProject, "Other project");

        ScenarioStep call = mockStep(UUID.randomUUID(), scenarioId, ScenarioStep.StepType.CALL, 0);
        when(call.getCalledEntryPointId()).thenReturn(otherEntryPoint);
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));
        when(scenarioRepository.findByEntryPointId(otherEntryPoint)).thenReturn(Optional.empty());

        ScenarioGraphDto.Response response = service.build(scenarioId, userId);

        assertThat(response.projects()).extracting(ScenarioGraphDto.ProjectGroup::id)
                .containsExactlyInAnyOrder(rootProject, otherProject);
        assertThat(response.edges()).hasSize(1);
        assertThat(response.edges().get(0).sourceBlockId()).isEqualTo(rootBlock);
        assertThat(response.edges().get(0).targetBlockId()).isEqualTo(otherBlock);
    }

    @Test
    void alt_walksAllBranches_notJustFirst() {
        UUID altStepId = UUID.randomUUID();
        ScenarioStep altStep = mockStep(altStepId, scenarioId, ScenarioStep.StepType.ALT, 0);

        UUID branchAEntryPoint = UUID.randomUUID();
        UUID branchABlock = UUID.randomUUID();
        wireEntryPoint(branchAEntryPoint, branchABlock, "A");
        wireBlock(branchABlock, "BlockA", rootScheme);

        UUID branchBEntryPoint = UUID.randomUUID();
        UUID branchBBlock = UUID.randomUUID();
        wireEntryPoint(branchBEntryPoint, branchBBlock, "B");
        wireBlock(branchBBlock, "BlockB", rootScheme);

        ScenarioStep branchA = mockStep(UUID.randomUUID(), scenarioId, ScenarioStep.StepType.CALL, 0);
        when(branchA.getCalledEntryPointId()).thenReturn(branchAEntryPoint);
        ScenarioStep branchB = mockStep(UUID.randomUUID(), scenarioId, ScenarioStep.StepType.CALL, 1);
        when(branchB.getCalledEntryPointId()).thenReturn(branchBEntryPoint);

        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(altStep));
        when(scenarioStepRepository.findByScenarioIdAndParentStepId(scenarioId, altStepId))
                .thenReturn(List.of(branchA, branchB));
        when(scenarioRepository.findByEntryPointId(branchAEntryPoint)).thenReturn(Optional.empty());
        when(scenarioRepository.findByEntryPointId(branchBEntryPoint)).thenReturn(Optional.empty());

        ScenarioGraphDto.Response response = service.build(scenarioId, userId);

        assertThat(response.edges()).extracting(ScenarioGraphDto.Edge::targetBlockId)
                .containsExactlyInAnyOrder(branchABlock, branchBBlock);
    }

    @Test
    void selfReferencingCall_doesNotInfiniteLoop() {
        ScenarioStep call = mockStep(UUID.randomUUID(), scenarioId, ScenarioStep.StepType.CALL, 0);
        when(call.getCalledEntryPointId()).thenReturn(rootEntryPoint); // ссылается сам на себя
        when(scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId))
                .thenReturn(List.of(call));

        ScenarioGraphDto.Response response = service.build(scenarioId, userId);

        // Не должно повиснуть; ребро на себя всё равно фиксируется как факт вызова.
        assertThat(response.projects()).hasSize(1);
        assertThat(response.edges()).hasSize(1);
        assertThat(response.edges().get(0).sourceBlockId()).isEqualTo(rootBlock);
        assertThat(response.edges().get(0).targetBlockId()).isEqualTo(rootBlock);
    }
}
