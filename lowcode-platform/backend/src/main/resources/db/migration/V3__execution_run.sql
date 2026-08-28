-- Персистентный статус запуска сценария (GET /runs/{runId}, api-contract.md).
-- Живые события хода исполнения не хранятся здесь — только в WebSocket-потоке.
create table execution_run (
    id            uuid primary key,
    scenario_id   uuid not null references scenario(id),
    status        varchar(16) not null,
    error_message text,
    started_at    timestamptz not null default now(),
    finished_at   timestamptz
);
