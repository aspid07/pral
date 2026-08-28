-- RETRY.maxAttempts и TIMEOUT.timeoutMs — параметры соответствующих
-- управляющих узлов ScenarioStep (см. ExecutionEngine). Оба nullable:
-- обязательны только для своего stepType, для остальных должны быть NULL
-- (проверяется в ScenarioStepService, не на уровне БД).
alter table scenario_step
    add column max_attempts int,
    add column timeout_ms   int;
