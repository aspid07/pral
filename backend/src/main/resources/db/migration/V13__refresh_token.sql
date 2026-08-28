-- Access/refresh токены (см. README, Ревью CTO п.1.6 — "Полноценный
-- refresh-token флоу с отзывом — отдельная задача следующей итерации").
-- Refresh-токен — непрозрачный секрет, не JWT: в отличие от access-токена
-- его нужно уметь ОТОЗВАТЬ до истечения TTL (logout, обнаружение кражи по
-- повторному использованию), а подписанный JWT отозвать нельзя без
-- отдельного blacklist'а. Хранится хеш (SHA-256), не сырое значение — по
-- той же причине, что и password_hash: компрометация БД/дампа/лога не
-- должна давать готовый к использованию секрет.
create table refresh_token (
    id           uuid primary key,
    user_id      uuid not null references app_user(id) on delete cascade,
    token_hash   varchar(255) not null unique,
    issued_at    timestamptz not null,
    -- Sliding window: на каждый успешный refresh пересчитывается заново от
    -- текущего момента (см. RefreshTokenService.rotate), а не фиксируется
    -- один раз при первом login — сессия живёт, пока продуктом пользуются
    -- хотя бы раз в 7 дней, а не ровно 7 дней от первого входа.
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    -- Цепочка ротации: на каждый refresh текущий токен помечается revoked и
    -- получает ссылку на токен, который пришёл ему на смену. Нужно для
    -- reuse-detection — если уже отозванный (использованный) токен предъявят
    -- повторно, значит его кто-то скопировал, и вся цепочка ротации от этой
    -- точки отзывается целиком (см. RefreshTokenService.rotate/revokeChainFrom).
    replaced_by  uuid references refresh_token(id)
);

create index idx_refresh_token_user_id on refresh_token(user_id);
-- Для будущей периодической очистки протухших строк (не реализована в этой
-- итерации — таблица будет расти без предела без неё; см. README-бэклог).
create index idx_refresh_token_expires_at on refresh_token(expires_at);
