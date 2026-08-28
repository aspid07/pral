-- Ревью CTO, п.2.8: Postgres не создаёт индексы на внешние ключи
-- автоматически (в отличие от MySQL). Без них — seq scan на каждый
-- ExecutionEngine.projectOf() (entry_point -> block_instance -> scheme) и на
-- каждое каскадное удаление (Postgres проверяет каждую дочернюю таблицу
-- полным сканом). Пока таблицы маленькие — незаметно, на реальном объёме
-- данных — сильно бьёт по производительности.
create index block_instance_scheme_id_idx on block_instance (scheme_id);
create index entry_point_block_instance_id_idx on entry_point (block_instance_id);
create index connection_scheme_id_idx on connection (scheme_id);
create index connection_source_block_id_idx on connection (source_block_id);
create index connection_target_block_id_idx on connection (target_block_id);

-- scenario_step_sibling_order_idx (V5) начинается с coalesce(parent_step_id, ...)
-- под задачу уникальности с NULL — для поиска "дети по parent_step_id"
-- (ScenarioStepTree.orderedChildren) бесполезен, нужен отдельный индекс.
create index scenario_step_parent_step_id_idx on scenario_step (parent_step_id);

-- unique(scenario_id, user_id) / unique(project_id, user_id) начинаются с
-- scenario_id/project_id — не помогают поиску "все роли этого user_id"
-- (понадобится в Stage 4+ для проверок прав по пользователю).
create index collaborator_user_id_idx on collaborator (user_id);
create index project_member_user_id_idx on project_member (user_id);
