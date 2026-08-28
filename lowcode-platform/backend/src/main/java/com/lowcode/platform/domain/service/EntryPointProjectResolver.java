package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * entry point → block → scheme → project. Эта цепочка резолвится уже третий
 * раз в проекте (свои копии есть в ExecutionEngine.projectOf() и
 * ScenarioGraphService.Accumulator.visitEntryPoint()) — третье появление
 * одного и того же паттерна стоит выносить, в отличие от двух (см. решение
 * не абстрагировать CollaboratorService/ProjectMemberService). Существующие
 * два места сознательно НЕ переведены на этот класс: это уже протестированный,
 * работающий код, а без компилятора под рукой рефакторить его — лишний риск
 * ради чистоты, не ради нового поведения.
 */
@Service
public class EntryPointProjectResolver {

    private final EntryPointRepository entryPointRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final SchemeRepository schemeRepository;

    public EntryPointProjectResolver(EntryPointRepository entryPointRepository,
                                      BlockInstanceRepository blockInstanceRepository,
                                      SchemeRepository schemeRepository) {
        this.entryPointRepository = entryPointRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.schemeRepository = schemeRepository;
    }

    public UUID resolveProjectId(UUID entryPointId) {
        EntryPoint entryPoint = entryPointRepository.findById(entryPointId)
                .orElseThrow(() -> new EntityNotFoundException("EntryPoint", entryPointId));
        BlockInstance block = blockInstanceRepository.findById(entryPoint.getBlockInstanceId())
                .orElseThrow(() -> new EntityNotFoundException("BlockInstance", entryPoint.getBlockInstanceId()));
        Scheme scheme = schemeRepository.findById(block.getSchemeId())
                .orElseThrow(() -> new EntityNotFoundException("Scheme", block.getSchemeId()));
        return scheme.getProjectId();
    }
}
