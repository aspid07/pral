-- ScenarioStepService.create() вычисляет order_index как max(siblings)+1 —
-- классическая гонка read-then-write при параллельном создании двух соседних
-- шагов (актуально уже сейчас, а с будущим Yjs co-editing — тем более).
-- parent_step_id nullable, а Postgres не считает NULL = NULL в unique-проверке,
-- поэтому обычный unique(scenario_id, parent_step_id, order_index) НЕ поймал бы
-- дубли на верхнем уровне дерева (parent_step_id is null) — используем coalesce
-- к сентинел-UUID, чтобы null тоже участвовал в проверке уникальности.
--
-- При конфликте конкурентная вставка получает DataIntegrityViolationException
-- -> ApiExceptionHandler отдаёт честный 409 (см. V4-комментарий и ревью) —
-- ретрай на уровне API сознательно не делаем: retry-в-той-же-транзакции
-- ненадёжен в Hibernate после failed flush (persistence context остаётся
-- "отравлен"), а строить retry через отдельную REQUIRES_NEW-транзакцию ради
-- редкой гонки в MVP — избыточно. Клиент получает понятный 409 и повторяет запрос.
create unique index scenario_step_sibling_order_idx
    on scenario_step (scenario_id, coalesce(parent_step_id, '00000000-0000-0000-0000-000000000000'::uuid), order_index);
