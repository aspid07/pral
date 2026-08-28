package com.lowcode.platform.domain.exception;

import com.lowcode.platform.domain.model.Scenario;

import java.util.List;

/**
 * Брошено при попытке удалить Block/EntryPoint, на который ссылаются
 * ScenarioStep.calledEntryPointId из одного или нескольких сценариев
 * (в т.ч. чужих проектов), без явного подтверждения (?confirm=true).
 * См. api-contract.md, "Решение: удаление блока с внешними ссылками".
 */
public class ReferencedByScenariosException extends RuntimeException {

    private final List<Scenario> referencingScenarios;

    public ReferencedByScenariosException(String message, List<Scenario> referencingScenarios) {
        super(message);
        this.referencingScenarios = referencingScenarios;
    }

    public List<Scenario> getReferencingScenarios() {
        return referencingScenarios;
    }
}
