# API-контракт

Базовый путь REST: `/api/v1`. Формат — JSON. Аутентификация не детализируется здесь отдельно (JWT в заголовке `Authorization`), она предмет отдельного security-документа.

## REST: Проекты и Общая схема

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/projects` | Список проектов, доступных пользователю |
| POST | `/projects` | Создать проект (создаёт и пустую Scheme) |
| GET | `/projects/{projectId}` | Детали проекта |
| PATCH | `/projects/{projectId}` | Обновить проект |
| DELETE | `/projects/{projectId}` | Удалить проект |
| GET | `/projects/{projectId}/scheme` | Общая схема: блоки + связи |
| GET | `/block-types` | Справочник типов блоков (Actor, Microservice, DB, Broker, Cache, ...) |

## REST: Блоки и Entry Point

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/projects/{projectId}/blocks` | Создать Block Instance на схеме |
| GET / PATCH / DELETE | `/projects/{projectId}/blocks/{blockId}` | Операции над блоком |
| POST | `/projects/{projectId}/connections` | Создать связь между блоками схемы |
| GET / DELETE | `/connections/{connectionId}` | Операции над связью |
| POST | `/blocks/{blockId}/entry-points` | Добавить Entry Point (метод/событие/WS-канал) на блок |
| GET | `/entry-points/{entryPointId}` | Entry Point (глобально адресуемый — на него ссылаются сценарии из любых проектов) |
| PATCH / DELETE | `/entry-points/{entryPointId}` | Операции над Entry Point |

## REST: Сценарии

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/scenarios` | Список сценариев, доступных пользователю |
| POST | `/scenarios` | Создать сценарий, привязанный к `entry_point_id` |
| GET / PATCH / DELETE | `/scenarios/{scenarioId}` | Операции над сценарием (DELETE — только Owner) |
| GET | `/scenarios/{scenarioId}/steps` | Упорядоченный список шагов (с вложенностью alt/parallel) |
| POST | `/scenarios/{scenarioId}/steps` | Добавить шаг (`called_entry_point_id`, `step_type`, `parent_step_id?`) |
| PATCH / DELETE | `/scenarios/{scenarioId}/steps/{stepId}` | Операции над шагом |
| GET | `/scenarios/{scenarioId}/versions` | История версий сценария |
| GET | `/scenarios/{scenarioId}/versions/{versionId}` | Снэпшот конкретной версии |
| POST | `/scenarios/{scenarioId}/share` | Создать ссылку-приглашение с ролью (Editor / Reader) |
| GET | `/scenarios/{scenarioId}/collaborators` | Список Owner / Editor / Reader |
| DELETE | `/scenarios/{scenarioId}/collaborators/{userId}` | Отозвать доступ |

## REST: Запуск сценария

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/scenarios/{scenarioId}/runs` | Запустить сценарий → возвращает `{ runId }` |
| GET | `/runs/{runId}` | Статус запуска (running / paused / completed / failed / stopped) |
| POST | `/runs/{runId}/pause` | Поставить run на паузу (202 Accepted — асинхронно, требует статус `running`) |
| POST | `/runs/{runId}/resume` | Продолжить run с места остановки (202 Accepted, требует статус `paused`) |
| POST | `/runs/{runId}/stop` | Остановить run без возможности продолжить (202 Accepted, из `running` или `paused`) |

После получения `runId` фронтенд подключается к WebSocket-каналу для получения live-событий исполнения.

## WebSocket: события исполнения

Канал: `/ws/runs/{runId}` (STOMP). Сообщения приходят в порядке исполнения — фронтенд не запрашивает, а подписывается.

| Тип события | Payload | Когда отправляется |
|---|---|---|
| `RUN_STARTED` | `{ runId, scenarioId }` | Сразу после старта |
| `STEP_STARTED` | `{ runId, stepId, sourceEntryPointId, targetEntryPointId, label, kind: "sync"\|"async"\|"external" }` | Токен начинает движение к следующему Entry Point |
| `CLUSTER_ENTERED` | `{ runId, projectId }` | Токен впервые входит в блок, принадлежащий другому проекту в рамках этого run |
| `STEP_COMPLETED` | `{ runId, stepId }` | Вызов завершён, шаг помечается как visited |
| `RUN_COMPLETED` | `{ runId }` | Все шаги пройдены |
| `RUN_ERROR` | `{ runId, stepId?, message }` | Ошибка исполнения (например, недостижимый Entry Point) |
| `RUN_PAUSED` | `{ runId, stepId? }` | Движок реально встал на паузу (не в момент запроса `POST .../pause` — см. ниже); `stepId` — последний завершённый шаг, `undefined` если пауза до первого шага |
| `RUN_RESUMED` | `{ runId }` | Движок реально возобновился после `POST .../resume` |
| `RUN_STOPPED` | `{ runId, stepId? }` | Run остановлен пользователем (`POST .../stop`) — не бизнес-ошибка, отдельно от `RUN_ERROR` |

Эта схема — прямое отражение механики UI-прототипа: `STEP_STARTED`/`STEP_COMPLETED` управляют подсветкой блоков и движением токена, `CLUSTER_ENTERED` — подсветкой рамки проекта.

`RUN_PAUSED`/`RUN_RESUMED`/`RUN_STOPPED` (см. README, "Стоп/Пауза исполнения сценария"): `POST /runs/{runId}/pause|resume|stop` возвращают `202 Accepted` сразу — это подтверждение приёма запроса, не факта, что run уже встал/возобновился/остановился. Реальное подтверждение — соответствующее WS-событие, публикуемое backend'ом из потока исполнения в момент, когда это действительно произошло (между шагами дерева `ScenarioStep`, не в произвольный момент).

## Решение: пагинация
Списковые эндпоинты (`/projects`, `/scenarios`) используют стандартную Spring Data Pageable-конвенцию: query-параметры `?page=0&size=20&sort=...`, ответ — конверт `{ content: [...], totalElements, totalPages, page, size }`. Выбрано как встроенное поведение Spring Data, не требует отдельной реализации.

## Решение: совместное редактирование Общей схемы (realtime co-editing)
Подтверждено: MVP должен поддерживать полноценный realtime ко-эдитинг Scheme (как в Figma/Miro), а не только optimistic locking с конфликтами. Это самостоятельный, дорогой по разработке кусок системы — на уровне сложности Execution Engine.

Технически: строить OT/CRDT с нуля — нецелесообразно для соло-разработки. Прагматичный выбор — **Yjs** (зрелая CRDT-библиотека, JS/TS): держит согласованное состояние документа между клиентами без центрального арбитра конфликтов, есть готовый WebSocket-провайдер (`y-websocket`), можно поднять лёгкий relay-сервер на своей инфраструктуре — не требует внешних managed-сервисов и не нарушает NFR по self-hosted.

Отдельный канал (не путать с каналом исполнения сценария):

| Канал | Протокол | Назначение |
|---|---|---|
| `/ws/schemes/{schemeId}/collab` | Yjs binary sync protocol поверх WebSocket | Синхронизация позиций блоков, связей, курсоров соавторов в реальном времени |
| `/ws/runs/{runId}` | STOMP, JSON-события (см. выше) | Live-исполнение сценария |

Backend-роль в co-editing-канале — тонкий relay + периодическая персистенция снэпшота в PostgreSQL (не на каждое изменение, а по интервалу/по отключению последнего клиента), чтобы не создавать нагрузку на БД при активном редактировании.

## Решение: parallel-вызовы в WebSocket-схеме
Подтверждено: для MVP токены анимируются последовательно, но с явной пометкой "это часть parallel-группы" — без рендеринга нескольких одновременных токенов. Достаточно расширить `STEP_STARTED` одним полем:

```
STEP_STARTED: { runId, stepId, sourceEntryPointId, targetEntryPointId, label,
                kind: "sync"|"async"|"external", parallelGroupId?: string }
```

Если несколько шагов подряд несут один и тот же `parallelGroupId` — фронтенд помечает их визуально (например, значок "∥" рядом с токеном), не меняя механику движения. Отдельные `STEP_RETRYING { runId, stepId, attempt, maxAttempts }` и `STEP_TIMEOUT { runId, stepId }` — как самостоятельные типы событий, а не поля внутри `STEP_STARTED`, чтобы фронтенд мог однозначно реагировать (счётчик попыток, индикатор ошибки) без парсинга состояния.

## Решение: удаление блока с внешними ссылками
Перед удалением backend проверяет, есть ли сценарии (в том числе из других проектов), ссылающиеся через `called_entry_point_id` на Entry Point удаляемого блока. Если есть — DELETE не выполняется молча: фронтенд показывает предупреждение со списком затронутых сценариев ("Вы пытаетесь удалить блок, который используется внешними сценариями: [список]. Это действие приведёт к невозможности воспроизвести сценарии целиком. Уверены, что хотите удалить блок?"), и удаление происходит только после явного подтверждения.

`DELETE /projects/{projectId}/blocks/{blockId}` возвращает `409 Conflict` с телом `{ referencingScenarios: [{ id, name }] }`, если запрос пришёл без явного флага подтверждения; `DELETE .../blocks/{blockId}?confirm=true` выполняет удаление безусловно.

## Открытые вопросы для следующего этапа
- Разрешение конфликтов на уровне домена: Yjs решает конфликты позиционирования/структуры графа автоматически, но что если два человека одновременно удаляют один и тот же блок, на который уже успели сослаться в другом месте (например, в Entry Point другого сценария) — валидация целостности не входит в зону ответственности CRDT и должна быть отдельным серверным правилом
- Персистенция co-editing снэпшотов: интервал автосохранения, стратегия при сбое relay-сервера
