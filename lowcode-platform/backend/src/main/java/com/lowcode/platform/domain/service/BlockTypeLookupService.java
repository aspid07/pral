package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.model.BlockType;
import com.lowcode.platform.domain.repository.BlockTypeRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * block_type — маленький справочник без CRUD (см. block_type в V1__init.sql,
 * заполняется один раз миграцией). BlockInstanceService и SchemeService раньше
 * независимо дёргали blockTypeRepository.findAll() на КАЖДЫЙ запрос и сами
 * собирали Map — вынесено сюда и закешировано на весь uptime приложения:
 * инвалидации нет и не нужна, т.к. изменить block_type через API нельзя.
 */
@Service
public class BlockTypeLookupService {

    private final BlockTypeRepository blockTypeRepository;

    public BlockTypeLookupService(BlockTypeRepository blockTypeRepository) {
        this.blockTypeRepository = blockTypeRepository;
    }

    @Cacheable("blockTypes")
    public Map<UUID, BlockType> byId() {
        return blockTypeRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(BlockType::getId, Function.identity()));
    }
}
