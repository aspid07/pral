-- Роли на уровне Project (грубая гранулярность) — дополняют уже существующие
-- Collaborator-роли на уровне Scenario (точечная гранулярность). Stage 3
-- (PermissionService, ещё не реализован) будет брать эффективную роль как
-- max() из обоих источников — см. обсуждение "гибрид пространство+сценарий".
create table project_member (
    id          uuid primary key,
    project_id  uuid not null references project(id) on delete cascade,
    user_id     uuid not null references app_user(id),
    role        varchar(16) not null,
    unique (project_id, user_id)
);
