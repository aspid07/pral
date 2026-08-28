package com.lowcode.platform.execution;

import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.BlockType;
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
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Строит "карту участников" сценария для мульти-проектного холста (UC5,
 * "единый холст со всеми участвующими проектами"). В отличие от ExecutionEngine:
 *  - не публикует события, ничего не исполняет — чистый read-only обход;
 *  - для ALT разворачивает ВСЕ ветки, а не одну выбранную — цель здесь
 *    "что вообще может быть задействовано", а не "что произошло в конкретном run";
 *  - при обнаружении цикла (Entry Point уже в стеке обхода) просто не
 *    разворачивает его повторно, а не завершается ошибкой — это карта, а не
 *    исполнение, зацикленность реального запуска увидят через RUN_ERROR.
 */
@Service
public class ScenarioGraphService {

    private final ScenarioRepository scenarioRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final EntryPointRepository entryPointRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final SchemeRepository schemeRepository;
    private final ProjectRepository projectRepository;
    private final BlockTypeRepository blockTypeRepository;
    private final PermissionService permissionService;

    public ScenarioGraphService(ScenarioRepository scenarioRepository,
                                 ScenarioStepRepository scenarioStepRepository,
                                 EntryPointRepository entryPointRepository,
                                 BlockInstanceRepository blockInstanceRepository,
                                 SchemeRepository schemeRepository,
                                 ProjectRepository projectRepository,
                                 BlockTypeRepository blockTypeRepository,
                                 PermissionService permissionService) {
        this.scenarioRepository = scenarioRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.entryPointRepository = entryPointRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.schemeRepository = schemeRepository;
        this.projectRepository = projectRepository;
        this.blockTypeRepository = blockTypeRepository;
        this.permissionService = permissionService;
    }

    /** Без @Transactional на весь метод — та же логика, что и в ExecutionEngine.run()
     *  (см. его комментарий): не нужна атомарность единого снэпшота на весь обход,
     *  а каждый repository-вызов и так транзакционен сам по себе. Здесь это менее
     *  критично, чем в run() (этот метод не уходит в фоновый поток — выполняется
     *  синхронно в потоке HTTP-запроса), но незачем плодить несогласованность.
     *
     *  Второе ревью CTO, ASAP-1 (BOLA) — см. javadoc PermissionService.requireOnScenario:
     *  граф участников сценария (какие проекты/блоки задействованы) — ровно та
     *  "чувствительная архитектурная информация", о которой прямо предупреждает
     *  ревью, поэтому доступ ограничен так же, как get() самого сценария. */
    public ScenarioGraphDto.Response build(UUID scenarioId, UUID userId) {
        permissionService.requireOnScenario(scenarioId, userId, Role.READER);

        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario", scenarioId));

        Accumulator acc = new Accumulator();
        acc.visitEntryPoint(scenario.getEntryPointId());

        Deque<UUID> callStack = new ArrayDeque<>();
        callStack.push(scenario.getEntryPointId());
        walkScenario(scenario.getId(), scenario.getEntryPointId(), acc, callStack);
        callStack.pop();

        return acc.toResponse(scenarioId);
    }

    private void walkScenario(UUID scenarioId, UUID sourceEntryPointId, Accumulator acc, Deque<UUID> callStack) {
        List<ScenarioStep> roots =
                scenarioStepRepository.findByScenarioIdAndParentStepIdIsNullOrderByOrderIndexAsc(scenarioId);
        walkSteps(roots, sourceEntryPointId, acc, callStack);
    }

    private void walkSteps(List<ScenarioStep> steps, UUID sourceEntryPointId, Accumulator acc, Deque<UUID> callStack) {
        for (ScenarioStep step : steps) {
            switch (step.getStepType()) {
                case CALL -> walkCall(step, sourceEntryPointId, acc, callStack);
                case ALT -> {
                    for (ScenarioStep branch : orderedChildren(step)) {
                        walkSteps(List.of(branch), sourceEntryPointId, acc, callStack);
                    }
                }
                case PARALLEL, RETRY, TIMEOUT -> walkSteps(orderedChildren(step), sourceEntryPointId, acc, callStack);
                // См. аналогичный комментарий в ExecutionEngine — без default новый
                // StepType молча ничего не сделал бы вместо явной ошибки.
                default -> throw new IllegalStateException("Unhandled ScenarioStep.StepType: " + step.getStepType());
            }
        }
    }

    private void walkCall(ScenarioStep step, UUID sourceEntryPointId, Accumulator acc, Deque<UUID> callStack) {
        UUID targetEntryPointId = step.getCalledEntryPointId();
        if (targetEntryPointId == null) {
            return; // не должно происходить — валидируется в ScenarioStepService при создании шага
        }

        acc.visitEntryPoint(targetEntryPointId);
        acc.addEdge(sourceEntryPointId, targetEntryPointId, step.getConditionLabel());

        if (callStack.contains(targetEntryPointId)) {
            return; // цикл — не разворачиваем повторно (см. класс-комментарий)
        }
        callStack.push(targetEntryPointId);
        try {
            scenarioRepository.findByEntryPointId(targetEntryPointId)
                    .ifPresent(nested -> walkScenario(nested.getId(), targetEntryPointId, acc, callStack));
        } finally {
            callStack.pop();
        }
    }

    private List<ScenarioStep> orderedChildren(ScenarioStep parent) {
        return ScenarioStepTree.orderedChildren(scenarioStepRepository, parent);
    }

    /** Копит участников (проекты + блоки) и рёбра вызовов по мере обхода дерева. */
    private final class Accumulator {
        private final Map<UUID, ProjectAcc> projects = new LinkedHashMap<>();
        private final Map<UUID, UUID> entryPointToBlock = new LinkedHashMap<>();
        private final Set<UUID> visitedEntryPoints = new LinkedHashSet<>();
        private final Set<String> edgeKeys = new LinkedHashSet<>();
        private final List<ScenarioGraphDto.Edge> edges = new java.util.ArrayList<>();
        private Map<UUID, BlockType> blockTypesById;

        void visitEntryPoint(UUID entryPointId) {
            if (visitedEntryPoints.contains(entryPointId)) {
                return;
            }
            EntryPoint entryPoint = entryPointRepository.findById(entryPointId)
                    .orElseThrow(() -> new EntityNotFoundException("EntryPoint", entryPointId));
            BlockInstance block = blockInstanceRepository.findById(entryPoint.getBlockInstanceId())
                    .orElseThrow(() -> new EntityNotFoundException("BlockInstance", entryPoint.getBlockInstanceId()));
            Scheme scheme = schemeRepository.findById(block.getSchemeId())
                    .orElseThrow(() -> new EntityNotFoundException("Scheme", block.getSchemeId()));
            Project project = projectRepository.findById(scheme.getProjectId())
                    .orElseThrow(() -> new EntityNotFoundException("Project", scheme.getProjectId()));

            projects.computeIfAbsent(project.getId(), id -> new ProjectAcc(project.getName()))
                    .blocks.putIfAbsent(block.getId(), block);
            entryPointToBlock.put(entryPointId, block.getId());
            visitedEntryPoints.add(entryPointId);
        }

        void addEdge(UUID sourceEntryPointId, UUID targetEntryPointId, String label) {
            UUID sourceBlockId = entryPointToBlock.get(sourceEntryPointId);
            UUID targetBlockId = entryPointToBlock.get(targetEntryPointId);
            if (sourceBlockId == null || targetBlockId == null) {
                return; // visitEntryPoint должен был отработать раньше для обоих концов
            }
            String key = sourceEntryPointId + "->" + targetEntryPointId;
            if (edgeKeys.add(key)) {
                edges.add(new ScenarioGraphDto.Edge(sourceBlockId, targetBlockId, sourceEntryPointId, targetEntryPointId, label));
            }
        }

        ScenarioGraphDto.Response toResponse(UUID scenarioId) {
            if (blockTypesById == null) {
                blockTypesById = new LinkedHashMap<>();
                blockTypeRepository.findAll().forEach(bt -> blockTypesById.put(bt.getId(), bt));
            }

            List<ScenarioGraphDto.ProjectGroup> projectGroups = projects.entrySet().stream()
                    .map(e -> {
                        List<ScenarioGraphDto.BlockRef> blockRefs = e.getValue().blocks.values().stream()
                                .map(b -> {
                                    BlockType type = blockTypesById.get(b.getBlockTypeId());
                                    return new ScenarioGraphDto.BlockRef(
                                            b.getId(), b.getLabel(), type != null ? type.getCode() : null);
                                })
                                .toList();
                        return new ScenarioGraphDto.ProjectGroup(e.getKey(), e.getValue().name, blockRefs);
                    })
                    .toList();

            return new ScenarioGraphDto.Response(scenarioId, projectGroups, List.copyOf(edges));
        }
    }

    private static final class ProjectAcc {
        final String name;
        final Map<UUID, BlockInstance> blocks = new LinkedHashMap<>();

        ProjectAcc(String name) {
            this.name = name;
        }
    }
}
