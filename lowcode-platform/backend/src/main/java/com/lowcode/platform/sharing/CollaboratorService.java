package com.lowcode.platform.sharing;

import com.lowcode.platform.domain.exception.EntityNotFoundException;
import com.lowcode.platform.domain.repository.ScenarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * UC9 (Must по MoSCoW, functional-requirements.md): шаринг Сценария по ссылке
 * с ролями Owner/Editor/Reader. OWNER проставляется один раз при создании
 * сценария (см. ScenarioService.create()) и не выдаётся/не отзывается через
 * этот сервис — только Editor/Reader.
 *
 * unique(scenario_id, user_id) в БД (V1__init.sql) — "поделиться" с уже
 * добавленным пользователем обновляет его роль (upsert), а не плодит дубли.
 *
 * Второе ревью CTO, ASAP-2 (эскалация привилегий): share()/revoke() раньше
 * проверяли только то, что НАЗНАЧАЕМАЯ роль не OWNER — но не то, что
 * ВЫЗЫВАЮЩИЙ вообще имеет право кем-либо распоряжаться на этом scenarioId.
 * Итог: любой аутентифицированный пользователь мог вызвать POST
 * .../share {"userId": <свой>, "role": "EDITOR"} и добавить себя в чужой
 * сценарий сам, не будучи приглашённым — обходя даже честно исправленный
 * BOLA (ASAP-1), потому что членство добывалось напрямую, а не через
 * "просмотр/редактирование уже видимого". callerUserId теперь обязателен и
 * проверяется ПЕРВЫМ в share()/revoke() — physически невозможно забыть,
 * раз сигнатура его требует (тот же приём, что и в ScenarioService/
 * ProjectService после первого прохода фикса).
 */
@Service
public class CollaboratorService {

    private final CollaboratorRepository collaboratorRepository;
    private final ScenarioRepository scenarioRepository;
    private final PermissionService permissionService;

    public CollaboratorService(CollaboratorRepository collaboratorRepository, ScenarioRepository scenarioRepository,
                                PermissionService permissionService) {
        this.collaboratorRepository = collaboratorRepository;
        this.scenarioRepository = scenarioRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<CollaboratorDto.Response> list(UUID scenarioId, UUID callerUserId) {
        permissionService.requireOnScenario(scenarioId, callerUserId, Role.READER);
        requireScenario(scenarioId);
        return collaboratorRepository.findByScenarioId(scenarioId).stream().map(this::toResponse).toList();
    }

    /**
     * EDITOR — минимум, необходимый, чтобы приглашать кого-либо в сценарий
     * (см. class-javadoc, ASAP-2). Сам вызывающий не может назначить себе
     * роль ВЫШЕ EDITOR через этот путь (см. проверку request.role() == OWNER
     * ниже) — то есть даже EDITOR не может тайно повысить себя до фактического
     * контроля наравне с OWNER, только другого пользователя до своего уровня
     * или ниже.
     */
    @Transactional
    public CollaboratorDto.Response share(UUID scenarioId, CollaboratorDto.ShareRequest request, UUID callerUserId) {
        permissionService.requireOnScenario(scenarioId, callerUserId, Role.EDITOR);
        requireScenario(scenarioId);
        if (request.role() == Role.OWNER) {
            throw new IllegalArgumentException("Cannot grant OWNER via share — it is set once at scenario creation");
        }

        Collaborator collaborator = collaboratorRepository.findByScenarioIdAndUserId(scenarioId, request.userId())
                .orElseGet(() -> {
                    Collaborator c = new Collaborator();
                    c.setScenarioId(scenarioId);
                    c.setUserId(request.userId());
                    return c;
                });

        if (collaborator.getId() != null && collaborator.getRole() == Role.OWNER) {
            throw new IllegalStateException("Cannot change role of the scenario owner via share");
        }

        collaborator.setRole(request.role());
        collaborator = collaboratorRepository.save(collaborator);
        return toResponse(collaborator);
    }

    /** userId, не id самой строки Collaborator — так задокументировано в api-contract.md. */
    @Transactional
    public void revoke(UUID scenarioId, UUID userId, UUID callerUserId) {
        permissionService.requireOnScenario(scenarioId, callerUserId, Role.EDITOR);
        Collaborator collaborator = collaboratorRepository.findByScenarioIdAndUserId(scenarioId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Collaborator", userId));
        if (collaborator.getRole() == Role.OWNER) {
            throw new IllegalStateException("Cannot revoke the scenario owner");
        }
        collaboratorRepository.delete(collaborator);
    }

    /** Вызывается из ScenarioService.create() — единственное место, где выдаётся OWNER; без callerUserId/guard намеренно — не пользовательский эндпоинт. */
    @Transactional
    public void grantOwner(UUID scenarioId, UUID ownerUserId) {
        Collaborator owner = new Collaborator();
        owner.setScenarioId(scenarioId);
        owner.setUserId(ownerUserId);
        owner.setRole(Role.OWNER);
        collaboratorRepository.save(owner);
    }

    private void requireScenario(UUID scenarioId) {
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new EntityNotFoundException("Scenario", scenarioId);
        }
    }

    private CollaboratorDto.Response toResponse(Collaborator c) {
        return new CollaboratorDto.Response(c.getId(), c.getScenarioId(), c.getUserId(), c.getRole());
    }
}
