-- Баг: DELETE .../blocks/{id}?confirm=true и DELETE .../entry-points/{id}?confirm=true
-- (BlockInstanceService/EntryPointService) обещают удалить сущность даже если её
-- используют сценарии — но каскад block_instance -> entry_point упирался в FK без
-- ON DELETE у scenario.entry_point_id и scenario_step.called_entry_point_id
-- (по умолчанию NO ACTION), и Postgres отклонял удаление DataIntegrityViolationException.
--
-- Семантика чинится по-разному для двух FK, т.к. они значат разное:
--  - scenario_step.called_entry_point_id — "шаг, который ВЫЗЫВАЕТ эту точку".
--    Колонка nullable — шаг может стать "битым" (calledEntryPointId = null),
--    но сам сценарий и его структура сохраняются. Это буквально то, что
--    описано в api-contract.md: "приведёт к невозможности воспроизвести
--    сценарии целиком" — сценарий остаётся видимым, просто ломается.
--  - scenario.entry_point_id — "точка, которую сценарий РЕАЛИЗУЕТ". Колонка
--    NOT NULL: сценарий без реализуемой точки — невалидное состояние по
--    определению (functional-requirements.md), значит удаление entry point
--    должно удалить и сам сценарий (а дальше каскадом — его шаги/версии/
--    коллабораторов, у них уже стоит on delete cascade на scenario_id).
alter table scenario_step
    drop constraint scenario_step_called_entry_point_id_fkey,
    add constraint scenario_step_called_entry_point_id_fkey
        foreign key (called_entry_point_id) references entry_point(id) on delete set null;

alter table scenario
    drop constraint scenario_entry_point_id_fkey,
    add constraint scenario_entry_point_id_fkey
        foreign key (entry_point_id) references entry_point(id) on delete cascade;
