package com.lowcode.platform.domain.service;

import com.lowcode.platform.domain.api.ConnectionDto;
import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.model.BlockInstance;
import com.lowcode.platform.domain.model.Connection;
import com.lowcode.platform.domain.model.Scheme;
import com.lowcode.platform.domain.repository.BlockInstanceRepository;
import com.lowcode.platform.domain.repository.ConnectionRepository;
import com.lowcode.platform.domain.repository.SchemeRepository;
import com.lowcode.platform.sharing.PermissionService;
import com.lowcode.platform.sharing.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ConnectionService {

    private final ConnectionRepository connectionRepository;
    private final BlockInstanceRepository blockInstanceRepository;
    private final SchemeRepository schemeRepository;
    private final PermissionService permissionService;

    public ConnectionService(ConnectionRepository connectionRepository,
                              BlockInstanceRepository blockInstanceRepository,
                              SchemeRepository schemeRepository,
                              PermissionService permissionService) {
        this.connectionRepository = connectionRepository;
        this.blockInstanceRepository = blockInstanceRepository;
        this.schemeRepository = schemeRepository;
        this.permissionService = permissionService;
    }

    /**
     * Второе ревью CTO, ASAP-1 (BOLA): connectionId (в отличие от blockId)
     * приходит БЕЗ projectId в пути (см. class-javadoc ConnectionController —
     * "операции над конкретной связью — глобально по /connections/{id}") —
     * проект приходится резолвить через scheme, а не брать из URL напрямую,
     * иначе guard нечем было бы вызвать.
     */
    @Transactional(readOnly = true)
    public ConnectionDto.Response get(UUID connectionId, UUID userId) {
        Connection connection = findOrThrow(connectionId);
        permissionService.requireOnProject(projectIdOf(connection), userId, Role.READER);
        return toResponse(connection);
    }

    /**
     * Ручное создание связи — всегда is_external=false: связи с is_external=true
     * появляются автоматически из межпроектных вызовов Сценария (см. vision-and-scope.md),
     * не рисуются руками через этот эндпоинт.
     */
    @Transactional
    public ConnectionDto.Response create(UUID projectId, ConnectionDto.CreateRequest request, UUID userId) {
        permissionService.requireOnProject(projectId, userId, Role.EDITOR);
        Scheme scheme = schemeRepository.findByProjectId(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Scheme for project", projectId));

        BlockInstance source = findBlockInSchemeOrThrow(scheme.getId(), request.sourceBlockId());
        BlockInstance target = findBlockInSchemeOrThrow(scheme.getId(), request.targetBlockId());

        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Source and target block must differ");
        }

        Connection connection = new Connection();
        connection.setSchemeId(scheme.getId());
        connection.setSourceBlockId(source.getId());
        connection.setTargetBlockId(target.getId());
        connection.setIntegrationType(request.integrationType());
        connection.setExternal(false);
        connection = connectionRepository.save(connection);

        return toResponse(connection);
    }

    @Transactional
    public void delete(UUID connectionId, UUID userId) {
        Connection connection = findOrThrow(connectionId);
        permissionService.requireOnProject(projectIdOf(connection), userId, Role.EDITOR);
        connectionRepository.delete(connection);
    }

    private UUID projectIdOf(Connection connection) {
        return schemeRepository.findById(connection.getSchemeId())
                .orElseThrow(() -> new EntityNotFoundException("Scheme", connection.getSchemeId()))
                .getProjectId();
    }

    private BlockInstance findBlockInSchemeOrThrow(UUID schemeId, UUID blockId) {
        return blockInstanceRepository.findByIdAndSchemeId(blockId, schemeId)
                .orElseThrow(() -> new EntityNotFoundException("BlockInstance", blockId));
    }

    private Connection findOrThrow(UUID connectionId) {
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("Connection", connectionId));
    }

    private ConnectionDto.Response toResponse(Connection c) {
        return new ConnectionDto.Response(
                c.getId(), c.getSchemeId(), c.getSourceBlockId(), c.getTargetBlockId(),
                c.getIntegrationType(), c.isExternal());
    }
}
