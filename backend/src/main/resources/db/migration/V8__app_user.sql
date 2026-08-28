-- Первая настоящая таблица пользователей в проекте. До сих пор
-- scenario.owner_id и collaborator.user_id были голыми uuid БЕЗ FK — "честное
-- слово" клиента, что такой пользователь существует.
--
-- NOT VALID: если в БД уже есть scenario/collaborator, созданные ДО этой
-- миграции (а они почти наверняка есть — весь curl/PowerShell-сценарий из
-- local-setup.md передавал произвольный ownerId, а никакого app_user тогда
-- не существовало), обычный ADD CONSTRAINT упал бы на валидации этих старых
-- строк. NOT VALID пропускает проверку существующих данных, но требует
-- соответствия для ЛЮБЫХ новых вставок начиная с этого момента.
-- Важное следствие: пока Stage 4 (ownerId из SecurityContext, а не из тела
-- запроса) не реализован, создание нового Scenario требует реального
-- app_user.id в ownerId — сначала зарегистрируйте пользователя через
-- POST /auth/register, потом используйте его id.
create table app_user (
    id            uuid primary key,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    display_name  varchar(255) not null,
    created_at    timestamptz not null default now()
);

alter table scenario
    add constraint scenario_owner_id_fkey foreign key (owner_id) references app_user(id) not valid;

alter table collaborator
    add constraint collaborator_user_id_fkey foreign key (user_id) references app_user(id) not valid;
