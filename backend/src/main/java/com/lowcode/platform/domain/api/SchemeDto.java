package com.lowcode.platform.domain.api;

import java.util.List;
import java.util.UUID;

public class SchemeDto {
    public record Response(
            UUID id,
            UUID projectId,
            List<BlockInstanceDto.Response> blocks,
            List<ConnectionDto.Response> connections,
            // Нужно фронтенду в run mode, чтобы по targetEntryPointId из STEP_STARTED
            // найти, какой узел на канвасе подсветить (см. SchemeCanvas.tsx).
            List<EntryPointDto.Response> entryPoints) {}
}
