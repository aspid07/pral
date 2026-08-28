package com.lowcode.platform.domain.api;

import java.util.UUID;

public class BlockTypeDto {
    public record Response(UUID id, String code, String displayName) {}
}
