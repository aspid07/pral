-- execution_run.scenario_id был без ON DELETE CASCADE, в отличие от
-- scenario_step/collaborator/scenario_version (все три — V1). Как только
-- появилась возможность реально удалить сценарий из UI, это стало реальной
-- проблемой: удаление сценария, который хоть раз запускали, падало бы на
-- FK-constraint. История запусков без самого сценария не несёт смысла —
-- каскадим, как и остальные зависимые таблицы.
alter table execution_run
    drop constraint execution_run_scenario_id_fkey;

alter table execution_run
    add constraint execution_run_scenario_id_fkey
        foreign key (scenario_id) references scenario(id) on delete cascade;
