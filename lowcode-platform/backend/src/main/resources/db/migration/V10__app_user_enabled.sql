-- Ревью CTO, п.1.6: у AppUser не было способа деактивировать пользователя —
-- JWT с валидной подписью работал до истечения TTL (24ч) вне зависимости от
-- того, существует ли ещё аккаунт. Полноценный refresh-token флоу с отзывом —
-- отдельная задача следующей итерации; enabled — минимальная защита прямо
-- сейчас (проверяется в JwtAuthenticationFilter через UserStatusCache).
-- Пока нет API для деактивации — только колонка и проверка в фильтре.
alter table app_user
    add column enabled boolean not null default true;
