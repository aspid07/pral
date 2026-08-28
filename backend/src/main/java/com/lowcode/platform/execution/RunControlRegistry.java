package com.lowcode.platform.execution;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * По одному RunControl на runId, живёт ровно столько, сколько run находится
 * в процессе исполнения (RunService регистрирует при старте, снимает в
 * finally — см. RunService.start).
 *
 * НЕ персистентно и не переживает перезапуск backend — сознательное
 * ограничение этой итерации (см. README, раздел "Стоп/Пауза исполнения"):
 * пауза, пережившая рестарт процесса, потребовала бы сериализовать полное
 * состояние обхода дерева (ExecutionEngine.RunContext.callStack и текущую
 * позицию) во внешнее хранилище и восстанавливать оттуда — отдельная,
 * более крупная задача.
 */
@Component
class RunControlRegistry {

    private final Map<UUID, RunControl> controls = new ConcurrentHashMap<>();

    RunControl register(UUID runId) {
        RunControl control = new RunControl();
        controls.put(runId, control);
        return control;
    }

    void unregister(UUID runId) {
        controls.remove(runId);
    }

    Optional<RunControl> find(UUID runId) {
        return Optional.ofNullable(controls.get(runId));
    }
}
