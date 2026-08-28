package com.lowcode.platform.execution;

import java.util.List;
import java.util.UUID;

/**
 * "Единый холст со всеми участвующими проектами" (vision-and-scope.md, UC5).
 * В отличие от SchemeDto (одна Общая схема одного проекта, topology only),
 * это derived-представление: не хранится в БД, а вычисляется обходом дерева
 * ScenarioStep — граф вызовов между блоками, потенциально из разных проектов.
 */
public class ScenarioGraphDto {

    public record BlockRef(UUID id, String label, String blockTypeCode) {}

    public record ProjectGroup(UUID id, String name, List<BlockRef> blocks) {}

    public record Edge(UUID sourceBlockId, UUID targetBlockId,
                        UUID sourceEntryPointId, UUID targetEntryPointId, String label) {}

    public record Response(UUID scenarioId, List<ProjectGroup> projects, List<Edge> edges) {}
}
