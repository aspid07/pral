package com.lowcode.platform.domain.api;

import com.lowcode.platform.domain.repository.BlockTypeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/block-types")
public class BlockTypeController {

    private final BlockTypeRepository blockTypeRepository;

    public BlockTypeController(BlockTypeRepository blockTypeRepository) {
        this.blockTypeRepository = blockTypeRepository;
    }

    @GetMapping
    public List<BlockTypeDto.Response> list() {
        return blockTypeRepository.findAll().stream()
                .map(bt -> new BlockTypeDto.Response(bt.getId(), bt.getCode(), bt.getDisplayName()))
                .toList();
    }
}
