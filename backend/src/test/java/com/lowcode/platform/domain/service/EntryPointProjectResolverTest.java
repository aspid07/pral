package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.EntryPoint;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.EntryPointRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntryPointProjectResolverTest {

    @Mock private EntryPointRepository entryPointRepository;
    @Mock private BlockInstanceRepository blockInstanceRepository;
    @Mock private SchemeRepository schemeRepository;

    private EntryPointProjectResolver resolver() {
        return new EntryPointProjectResolver(entryPointRepository, blockInstanceRepository, schemeRepository);
    }

    @Test
    void resolveProjectId_walksTheFullChain() {
        UUID entryPointId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        EntryPoint entryPoint = mock(EntryPoint.class);
        when(entryPoint.getBlockInstanceId()).thenReturn(blockId);
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.of(entryPoint));

        BlockInstance block = mock(BlockInstance.class);
        when(block.getSchemeId()).thenReturn(schemeId);
        when(blockInstanceRepository.findById(blockId)).thenReturn(Optional.of(block));

        Scheme scheme = mock(Scheme.class);
        when(scheme.getProjectId()).thenReturn(projectId);
        when(schemeRepository.findById(schemeId)).thenReturn(Optional.of(scheme));

        assertThat(resolver().resolveProjectId(entryPointId)).isEqualTo(projectId);
    }

    @Test
    void resolveProjectId_unknownEntryPoint_throwsNotFound() {
        UUID entryPointId = UUID.randomUUID();
        when(entryPointRepository.findById(entryPointId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver().resolveProjectId(entryPointId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
