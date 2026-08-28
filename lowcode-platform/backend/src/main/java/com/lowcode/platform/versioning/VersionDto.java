package com.lowcode.platform.versioning;

import com.lowcode.platform.domain.api.ScenarioDto;
import com.lowcode.platform.domain.api.ScenarioStepDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class VersionDto {

    // То, что реально (де)сериализуется в/из snapshot_json — переиспользует уже
    // существующие DTO домена вместо параллельного набора полей.
    public record Snapshot(ScenarioDto.Response scenario, List<ScenarioStepDto.Response> steps) {}

    // Список версий — без тела снэпшота, чтобы не тащить потенциально большой
    // JSON на каждый GET .../versions.
    public record Summary(UUID id, int versionNumber, Instant createdAt) {}

    public record Detail(UUID id, int versionNumber, Instant createdAt, Snapshot snapshot) {}
}
