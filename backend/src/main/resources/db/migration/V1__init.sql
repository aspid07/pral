create table project (
    id          uuid primary key,
    name        varchar(255) not null,
    description text,
    created_at  timestamptz not null default now()
);

create table scheme (
    id         uuid primary key,
    project_id uuid not null unique references project(id) on delete cascade
);

create table block_type (
    id           uuid primary key,
    code         varchar(64) not null unique,
    display_name varchar(255) not null
);

create table block_instance (
    id            uuid primary key,
    scheme_id     uuid not null references scheme(id) on delete cascade,
    block_type_id uuid not null references block_type(id),
    label         varchar(255) not null,
    x             double precision not null default 0,
    y             double precision not null default 0
);

create table connection (
    id                uuid primary key,
    scheme_id         uuid not null references scheme(id) on delete cascade,
    source_block_id   uuid not null references block_instance(id) on delete cascade,
    target_block_id   uuid not null references block_instance(id) on delete cascade,
    integration_type  varchar(32) not null,
    is_external       boolean not null default false
);

create table entry_point (
    id                 uuid primary key,
    block_instance_id  uuid not null references block_instance(id) on delete cascade,
    name               varchar(255) not null,
    kind               varchar(32) not null
);

create table scenario (
    id             uuid primary key,
    name           varchar(255) not null,
    entry_point_id uuid not null unique references entry_point(id),
    owner_id       uuid not null
);

create table scenario_step (
    id                     uuid primary key,
    scenario_id            uuid not null references scenario(id) on delete cascade,
    order_index            int not null,
    parent_step_id         uuid references scenario_step(id),
    step_type              varchar(32) not null,
    called_entry_point_id  uuid references entry_point(id),
    condition_label        varchar(255),
    parallel_group_id      varchar(64)
);

create table collaborator (
    id           uuid primary key,
    scenario_id  uuid not null references scenario(id) on delete cascade,
    user_id      uuid not null,
    role         varchar(16) not null,
    unique (scenario_id, user_id)
);

create table scenario_version (
    id              uuid primary key,
    scenario_id     uuid not null references scenario(id) on delete cascade,
    version_number  int not null,
    snapshot_json   text not null,
    created_at      timestamptz not null default now(),
    unique (scenario_id, version_number)
);

insert into block_type (id, code, display_name) values
    (gen_random_uuid(), 'ACTOR', 'Актор'),
    (gen_random_uuid(), 'MICROSERVICE', 'Микросервис'),
    (gen_random_uuid(), 'DATABASE', 'База данных'),
    (gen_random_uuid(), 'MESSAGE_BROKER', 'Брокер сообщений'),
    (gen_random_uuid(), 'CACHE', 'Кэш');
