package com.lowcode.platform.execution;

import com.lowcode.platform.domain.model.ScenarioStep;
import com.lowcode.platform.domain.repository.ScenarioStepRepository;

import java.util.Comparator;
import java.util.List;

/**
 * Общая для ExecutionEngine и ScenarioGraphService часть обхода дерева
 * ScenarioStep — единственный кусок, который у них был буквально идентичен.
 * Остальная логика (что делать на CALL/ALT/PARALLEL/RETRY/TIMEOUT) сознательно
 * НЕ унифицирована через общий visitor: поведение расходится существенно
 * (публикация событий + симуляция retry/timeout vs чистое обнаружение графа),
 * и общая абстракция над этим добавила бы косвенность дороже, чем экономит.
 * Риск, который это дублирование создаёт — молча забыть обновить один из двух
 * switch по StepType при появлении нового типа — вместо этого закрыт через
 * default-ветки, которые бросают исключение, в обоих местах.
 */
final class ScenarioStepTree {

    private ScenarioStepTree() {
    }

    static List<ScenarioStep> orderedChildren(ScenarioStepRepository repository, ScenarioStep parent) {
        return repository.findByScenarioIdAndParentStepId(parent.getScenarioId(), parent.getId())
                .stream()
                .sorted(Comparator.comparingInt(ScenarioStep::getOrderIndex))
                .toList();
    }
}
