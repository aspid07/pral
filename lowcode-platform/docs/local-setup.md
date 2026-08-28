# Как запустить локально и пройти основные юз-кейсы

Два пути: **ручной** (рекомендуется — быстрее итерировать, именно так всё
проверялось при разработке) и **docker-compose** (одна команда, но собран и не
запускался в песочнице, где писался код — см. оговорку в конце).

Готовой UI-формы для создания проектов/блоков/сценариев пока нет (см.
`README.md`, "Что реализовано") — весь тестовый датасет создаётся через REST
API (`curl`), а фронтенд используется для просмотра схемы и запуска сценариев.
Именно поэтому ниже полный скрипт для наполнения БД тестовыми данными.

## 1. Что понадобится

- Java 21 (`java -version`)
- Node.js 20+ и npm (`node -v`)
- Docker (для Postgres; и для `gradlew test`, если захотите прогнать
  Testcontainers-тест — см. раздел 6)
- `curl` и `jq` (для прохождения юз-кейсов через API; `jq` можно поставить
  через `apt install jq` / `brew install jq`)

## 2. Поднимаем Postgres

```bash
docker run -d --name lowcode-postgres \
  -e POSTGRES_DB=lowcode -e POSTGRES_USER=lowcode -e POSTGRES_PASSWORD=lowcode \
  -p 5432:5432 postgres:16
```

Backend по умолчанию (см. `backend/src/main/resources/application.yml`) ждёт
именно `localhost:5432/lowcode` с пользователем `lowcode`/`lowcode` — при
таких значениях ничего больше не настраивать.

## 3. Backend

Gradle — единственная система сборки (см. README, "Итерация 14" — `pom.xml`
удалён по итогам ревью, чтобы не поддерживать вручную две параллельные
конфигурации). Через закоммиченный wrapper — ничего заранее ставить не
нужно, `gradlew` сам скачает нужную версию Gradle при первом запуске:

```bash
cd backend
./gradlew bootRun          # Linux/macOS
gradlew.bat bootRun        # Windows
```

При старте Flyway применит все миграции автоматически (на момент написания —
V1–V11). В логе должно быть видно `Flyway ... Successfully applied N
migrations` и дальше обычный Spring Boot старт на порту 8080. Проверка:

```bash
curl -s localhost:8080/api/v1/block-types | jq
```

Должны увидеть 5 типов блоков (`ACTOR`, `MICROSERVICE`, `DATABASE`,
`MESSAGE_BROKER`, `CACHE`, `USER`) — если ответ пустой или ошибка соединения,
backend не поднялся или не достучался до Postgres. Учтите: с недавней
итерации `/api/v1/**` (кроме `/api/v1/auth/**`) требует аутентификации — см.
раздел 5 ниже, там сначала регистрация, потом всё остальное.

Backend теперь реально собирается и тестируется — и локально (много раз
прогонялось по ходу разработки), и в CI (`.github/workflows/ci.yml`, гоняет
`./gradlew build` на каждый push/PR). Раньше (до появления CI) в среде, где
писался код, не было ни Gradle, ни Docker — все правки проверялись только
построчным чтением; эта эпоха закончилась, актуальные пробелы — то, что CI
ещё не подключён к репозиторию физически (сам workflow-файл в дереве есть,
но его нужно один раз запушить и включить в настройках GitHub).

## 4. Frontend

В отдельном терминале:

```bash
cd frontend
npm install
npm run dev
```

Откройте адрес, который выведет Vite (обычно `http://localhost:5173`).
`vite.config.ts` уже проксирует `/api` и `/ws` на `localhost:8080` — отдельно
настраивать CORS/прокси не нужно.

На этом этапе фронтенд открывается, но пусто: ни проектов, ни сценариев ещё
нет. Дальше — наполняем данными.

## 5. Юз-кейс: сквозной сценарий с ALT/RETRY/TIMEOUT/PARALLEL и кросс-проектным вызовом

**Windows/PowerShell**: не транслируйте bash ниже вручную — есть готовая
PowerShell-версия, `scripts/seed.ps1` (1:1 то же самое, плюс явная UTF-8
кодировка тела запроса — иначе кириллица вроде "Способ оплаты" может уйти
побитой). Запуск:
```powershell
cd scripts
.\seed.ps1
```
Печатает в конце `Project A`, `Scenario` и `EP_CHARGE id` — они понадобятся
дальше в UI и для юз-кейса про `confirm=true` (там же лежит
`delete-confirm-demo.ps1`, принимает эти id параметрами).

Весь блок ниже — один bash-скрипт, можно скопировать целиком и выполнить
построчно (или сохранить в файл `seed.sh` и запустить `bash seed.sh`).
Предполагается, что backend уже поднят на `localhost:8080`.

```bash
API=http://localhost:8080/api/v1

# Stage 4: все /api/v1/** кроме /auth/** теперь требуют аутентификации —
# регистрируемся первым делом и оборачиваем curl в хелпер, несущий токен,
# а не дописываем -H "Authorization: ..." в каждый вызов вручную.
REGISTER_RESPONSE=$(curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"seed-user-'$RANDOM'@example.com","password":"correct horse battery staple","displayName":"Seed User"}')
TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r .accessToken)
# (access/refresh, эта итерация: поле переименовано token -> accessToken —
# заодно в ответе теперь есть Set-Cookie с httpOnly refresh-токеном, curl -s
# без -c его просто отбрасывает, для сидинга он не нужен)

api() {
  local method=$1; local path=$2; local data=$3
  if [ -n "$data" ]; then
    curl -s -X "$method" "$API$path" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "$data"
  else
    curl -s -X "$method" "$API$path" -H "Authorization: Bearer $TOKEN"
  fi
}

# Тип блока "Микросервис" — нужен для создания BlockInstance
MICROSERVICE_ID=$(api GET /block-types | jq -r '.[] | select(.code=="MICROSERVICE") | .id')

# --- Проект A: Order Service ---
PROJECT_A=$(api POST /projects '{"name":"Order Service","description":"Приём заказов"}' | jq -r .id)

BLOCK_ORDER=$(api POST /projects/$PROJECT_A/blocks \
  "{\"blockTypeId\":\"$MICROSERVICE_ID\",\"label\":\"OrderApi\",\"x\":100,\"y\":100}" | jq -r .id)

BLOCK_ORDERDB=$(api POST /projects/$PROJECT_A/blocks \
  "{\"blockTypeId\":\"$MICROSERVICE_ID\",\"label\":\"OrderDb\",\"x\":300,\"y\":100}" | jq -r .id)

EP_ORDER=$(api POST /blocks/$BLOCK_ORDER/entry-points '{"name":"POST /orders","kind":"SYNC_METHOD"}' | jq -r .id)

EP_ORDERDB=$(api POST /blocks/$BLOCK_ORDERDB/entry-points '{"name":"SELECT order","kind":"SYNC_METHOD"}' | jq -r .id)

# Связь внутри проекта A (чисто для картинки в editor mode)
api POST /projects/$PROJECT_A/connections \
  "{\"sourceBlockId\":\"$BLOCK_ORDER\",\"targetBlockId\":\"$BLOCK_ORDERDB\",\"integrationType\":\"API\"}" > /dev/null

# --- Проект B: Payment Service (для кросс-проектного вызова) ---
PROJECT_B=$(api POST /projects '{"name":"Payment Service","description":"Списание денег"}' | jq -r .id)

BLOCK_PAYMENT=$(api POST /projects/$PROJECT_B/blocks \
  "{\"blockTypeId\":\"$MICROSERVICE_ID\",\"label\":\"PaymentApi\",\"x\":100,\"y\":100}" | jq -r .id)

EP_CHARGE=$(api POST /blocks/$BLOCK_PAYMENT/entry-points '{"name":"POST /charge","kind":"SYNC_METHOD"}' | jq -r .id)

# --- Сценарий "Place order", реализующий EP_ORDER ---
# ownerId в теле запроса больше не принимается (Stage 4) — берётся из токена.
SCENARIO=$(api POST /scenarios "{\"name\":\"Place order\",\"entryPointId\":\"$EP_ORDER\"}" | jq -r .id)

# Шаг 1: CALL -> проверить заказ в БД (в рамках того же проекта)
api POST /scenarios/$SCENARIO/steps "{\"stepType\":\"CALL\",\"calledEntryPointId\":\"$EP_ORDERDB\"}" > /dev/null

# Шаг 2: ALT с двумя ветками — выбор способа оплаты
ALT_STEP=$(api POST /scenarios/$SCENARIO/steps '{"stepType":"ALT","conditionLabel":"Способ оплаты"}' | jq -r .id)

api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$ALT_STEP\",\"calledEntryPointId\":\"$EP_CHARGE\",\"conditionLabel\":\"Картой\"}" > /dev/null

api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$ALT_STEP\",\"calledEntryPointId\":\"$EP_ORDERDB\",\"conditionLabel\":\"Наличными (просто спишем со склада)\"}" > /dev/null

# Шаг 3: RETRY на 3 попытки вокруг платежа — увидим STEP_RETRYING в логе
RETRY_STEP=$(api POST /scenarios/$SCENARIO/steps '{"stepType":"RETRY","maxAttempts":3}' | jq -r .id)

api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$RETRY_STEP\",\"calledEntryPointId\":\"$EP_CHARGE\"}" > /dev/null

# Шаг 4: TIMEOUT с бюджетом 3000мс вокруг обращения к БД
TIMEOUT_STEP=$(api POST /scenarios/$SCENARIO/steps '{"stepType":"TIMEOUT","timeoutMs":3000}' | jq -r .id)

api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$TIMEOUT_STEP\",\"calledEntryPointId\":\"$EP_ORDERDB\"}" > /dev/null

# Шаг 5: PARALLEL — два вызова с общим parallelGroupId
PARALLEL_STEP=$(api POST /scenarios/$SCENARIO/steps '{"stepType":"PARALLEL"}' | jq -r .id)

api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$PARALLEL_STEP\",\"calledEntryPointId\":\"$EP_CHARGE\"}" > /dev/null
api POST /scenarios/$SCENARIO/steps \
  "{\"stepType\":\"CALL\",\"parentStepId\":\"$PARALLEL_STEP\",\"calledEntryPointId\":\"$EP_ORDERDB\"}" > /dev/null

echo "Готово. Project A (Order Service): $PROJECT_A"
echo "Scenario (Place order): $SCENARIO"
echo "Токен (для остальных юз-кейсов ниже): $TOKEN"
```

Все дальнейшие `curl` в этом документе тоже требуют `-H "Authorization: Bearer $TOKEN"` — использован тот же `api()`-хелпер там, где это применимо.

Если какой-то `curl` вернул пусто/ошибку — проверьте `.id` в самом ответе
(`curl -s ... | jq`, без `-r .id`), скорее всего опечатка в JSON или backend
ещё не поднялся.

### Смотрим в UI

1. **Редактор схемы** → выбрать в выпадающем списке "Order Service" → должны
   появиться два блока (`OrderApi`, `OrderDb`) со связью между ними.
2. **Запуск сценария** → в выпадающем списке сценариев выбрать "Place order".
   Справа появится панель "Ветвления (ALT)" с выбором "Картой"/"Наличными…" —
   переключите на нужный вариант.
3. На канвасе должен появиться граф из **двух** пунктирных контейнеров
   (Order Service и Payment Service) с блоками внутри — это и есть
   мульти-проектный холст (`GET /scenarios/{id}/graph` + elkjs).
4. Над логом событий — слайдер **"Скорость анимации"** (мс/шаг, дефолт 900) —
   крутите на лету, если хотите быстрее/медленнее.
5. Нажмите **"Запустить"**. Ожидаемая картина:
   - статус сверху покажет **"Запуск #1 — Выполняется…"** (номер растёт с
     каждым новым запуском ЭТОГО сценария, переживает перезагрузку страницы);
   - блоки по очереди подсвечиваются жёлтым (выполняется) → зелёным (пройден),
     с паузой между шагами (регулируется слайдером выше, не мгновенно);
   - при выборе ветки "Картой" — в какой-то момент подсветится жирной рамкой
     контейнер **Payment Service** (событие `CLUSTER_ENTERED`, кросс-проектный
     вызов), а в логе справа — `→ POST /charge (external)`;
   - в логе будет `⟳ попытка 1/3`, `⟳ попытка 2/3` (симуляция RETRY), затем
     обычный `→ POST /charge (...)` на третьей попытке;
   - для TIMEOUT-шага в логе будет `..., timeout 3000ms` в скобках у
     `STEP_STARTED`;
   - для PARALLEL-шага оба вызова выполнятся один за другим, но помечены общим
     `parallelGroupId` (видно, если открыть devtools → Network → WS-фрейм, в
     UI это пока не визуализируется отдельно);
   - в конце — **"Запуск #1 — Сценарий выполнен"** зелёным, и подсветка блоков
     гаснет (с той же паузой). Запустите ещё раз — увидите "Запуск #2".
     Если специально сломать сценарий (см. юз-кейс с `confirm=true` ниже, а
     потом попробовать запустить сценарий с "битым" шагом) — подсветка
     на месте ошибки **не** гаснет, это осознанно (видно, где сломалось).

### Юз-кейс: проверка confirm=true при удалении (тот самый критичный фикс)

**Windows/PowerShell**: `scripts/delete-confirm-demo.ps1 -EntryPointId <из вывода seed.ps1> -Token <тоже из вывода> -ScenarioId <тоже из вывода>`.

```bash
# Без confirm — должны получить 409 со списком сценариев, которые используют EP_CHARGE
api DELETE "/entry-points/$EP_CHARGE"

# С confirm=true — теперь реально удаляется (см. V4__fix_entry_point_delete_cascade.sql),
# а не падает с 500
api DELETE "/entry-points/$EP_CHARGE?confirm=true"

# Шаги сценария, ссылавшиеся на EP_CHARGE, теперь "битые" (calledEntryPointId: null),
# но сам сценарий и остальные шаги — целы
api GET "/scenarios/$SCENARIO/steps" | jq '.[] | {stepType, calledEntryPointId}'
```

### Юз-кейс: шаринг и история версий (backend-only, без UI)

```bash
# collaborator.user_id тоже получил FK на app_user (миграция V8) — делимся с
# РЕАЛЬНЫМ вторым пользователем, не случайным UUID.
SECOND_USER=$(curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"seed-editor-'$RANDOM'@example.com","password":"correct horse battery staple","displayName":"Editor User"}' \
  | jq -r .userId)

# Поделиться сценарием как Editor
api POST "/scenarios/$SCENARIO/share" "{\"userId\":\"$SECOND_USER\",\"role\":\"EDITOR\"}"

# Список коллабораторов — должен быть Owner (создатель) + только что добавленный Editor
api GET "/scenarios/$SCENARIO/collaborators" | jq

# История версий — по одной записи на каждую мутацию сценария/его шагов, включая создание
api GET "/scenarios/$SCENARIO/versions" | jq
# Снэпшот конкретной версии (id из предыдущего ответа)
api GET "/scenarios/$SCENARIO/versions/<versionId>" | jq
```

## 6. Тесты

```bash
cd backend
./gradlew test
```

Юнит-тесты (Mockito) отработают всегда. Один тест —
`BlockAndEntryPointDeleteCascadeIntegrationTest` — поднимает настоящий
Postgres через Testcontainers и требует **Docker** в окружении, где
выполняется `./gradlew test`; без Docker этот конкретный тест упадёт с
ошибкой подключения к Docker daemon, остальные не затронуты.

## 7. Альтернатива: docker-compose

```bash
docker compose up --build
```

`backend/Dockerfile` собирает через закоммиченный Gradle wrapper (не тянет
отдельный Maven/Gradle образ — только JDK), `frontend/Dockerfile` +
`frontend/nginx.conf` проксируют `/api` и `/ws` с nginx на backend-контейнер.
Реально поднималось и гонялось несколько раз по ходу разработки (в первый
раз — после фикса `@Lob`/Hibernate и рассинхрона SockJS/WebSocket, см.
историю правок в README) — рабочая связка, не теоретическая.

При этом пути фронтенд будет на `http://localhost:3000`, backend — на
`localhost:8080`, Postgres — на `localhost:5432`, все данные из миграций и
куски выше применятся ровно так же через `curl localhost:8080/api/v1/...`.

## Возможные проблемы

- **`GET /block-types` возвращает пусто или соединение не устанавливается** —
  backend не поднялся или не достучался до Postgres; проверьте лог backend на
  ошибки Flyway/подключения.
- **CORS-ошибки в консоли браузера при `npm run dev`** — проверьте, что
  реально открываете адрес, который вывел Vite (с проксированием), а не
  ходите на `localhost:8080` напрямую из фронтенд-кода.
- **WS не подключается (панель "Запуск сценария" не подсвечивает блоки)** —
  проверьте вкладку Network → WS в devtools: если хэндшейк на `/ws/runs` не
  проходит, скорее всего фронтенд открыт не через Vite dev-сервер (прокси
  из `vite.config.ts` работает только там) — см. раздел 4.
