# PowerShell-версия скрипта наполнения тестовыми данными из docs/local-setup.md
# (раздел 5). Зеркалит bash-версию 1:1 — те же сущности, тот же сценарий со
# всеми типами шагов (CALL/ALT/RETRY/TIMEOUT/PARALLEL) и кросс-проектным вызовом.
#
# Запуск:
#   cd scripts
#   .\seed.ps1
#
# Требует поднятый backend на localhost:8080 (см. docs/local-setup.md, разделы 2-3).
# Stage 4: все /api/v1/** кроме /auth/** теперь требуют аутентификации —
# скрипт сначала регистрирует пользователя и дальше шлёт его токен на каждый запрос.

$ErrorActionPreference = 'Stop'
$API = 'http://localhost:8080/api/v1'

# Invoke-RestMethod по умолчанию может отправить тело не в UTF-8 (особенно в
# Windows PowerShell 5.1) — здесь кириллица (например "Способ оплаты"), поэтому
# кодируем явно, а не полагаемся на дефолт.
function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)] [string] $Uri,
        [Parameter(Mandatory)] [hashtable] $Body,
        [string] $Token
    )
    $json = $Body | ConvertTo-Json -Depth 10
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    Invoke-RestMethod -Uri $Uri -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -Headers $headers
}

function Invoke-AuthGet {
    param([Parameter(Mandatory)] [string] $Uri, [Parameter(Mandatory)] [string] $Token)
    Invoke-RestMethod -Uri $Uri -Method Get -Headers @{ Authorization = "Bearer $Token" }
}

# --- Регистрация: источник ownerId для сценариев теперь токен, не тело запроса ---
Write-Host "Регистрирую пользователя..."
$randomSuffix = Get-Random
$registerResponse = Invoke-JsonPost "$API/auth/register" @{
    email = "seed-user-$randomSuffix@example.com"; password = "correct horse battery staple"; displayName = "Seed User"
}
$token = $registerResponse.accessToken
# (access/refresh, эта итерация: поле переименовано token -> accessToken;
# Invoke-RestMethod по умолчанию не сохраняет cookies между вызовами без
# -WebSession, так что httpOnly refresh-cookie в ответе просто игнорируется —
# для сидинга он не нужен, access-токена на 15 минут достаточно для всего скрипта)

Write-Host "Тип блока MICROSERVICE..."
$blockTypes = Invoke-AuthGet "$API/block-types" $token
$microserviceId = ($blockTypes | Where-Object { $_.code -eq 'MICROSERVICE' }).id

# --- Проект A: Order Service ---
Write-Host "Создаю Order Service..."
$projectA = Invoke-JsonPost "$API/projects" @{ name = 'Order Service'; description = 'Приём заказов' } -Token $token

$blockOrder = Invoke-JsonPost "$API/projects/$($projectA.id)/blocks" @{
    blockTypeId = $microserviceId; label = 'OrderApi'; x = 100; y = 100
} -Token $token
$blockOrderDb = Invoke-JsonPost "$API/projects/$($projectA.id)/blocks" @{
    blockTypeId = $microserviceId; label = 'OrderDb'; x = 300; y = 100
} -Token $token

$epOrder = Invoke-JsonPost "$API/blocks/$($blockOrder.id)/entry-points" @{
    name = 'POST /orders'; kind = 'SYNC_METHOD'
} -Token $token
$epOrderDb = Invoke-JsonPost "$API/blocks/$($blockOrderDb.id)/entry-points" @{
    name = 'SELECT order'; kind = 'SYNC_METHOD'
} -Token $token

# Связь внутри проекта A (чисто для картинки в editor mode)
Invoke-JsonPost "$API/projects/$($projectA.id)/connections" @{
    sourceBlockId = $blockOrder.id; targetBlockId = $blockOrderDb.id; integrationType = 'API'
} -Token $token | Out-Null

# --- Проект B: Payment Service (для кросс-проектного вызова) ---
Write-Host "Создаю Payment Service..."
$projectB = Invoke-JsonPost "$API/projects" @{ name = 'Payment Service'; description = 'Списание денег' } -Token $token

$blockPayment = Invoke-JsonPost "$API/projects/$($projectB.id)/blocks" @{
    blockTypeId = $microserviceId; label = 'PaymentApi'; x = 100; y = 100
} -Token $token
$epCharge = Invoke-JsonPost "$API/blocks/$($blockPayment.id)/entry-points" @{
    name = 'POST /charge'; kind = 'SYNC_METHOD'
} -Token $token

# --- Сценарий "Place order", реализующий EP_ORDER ---
# ownerId в теле запроса больше не принимается (Stage 4) — берётся из токена.
Write-Host "Создаю сценарий Place order..."
$scenario = Invoke-JsonPost "$API/scenarios" @{
    name = 'Place order'; entryPointId = $epOrder.id
} -Token $token

# Шаг 1: CALL -> проверить заказ в БД (в рамках того же проекта)
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; calledEntryPointId = $epOrderDb.id
} -Token $token | Out-Null

# Шаг 2: ALT с двумя ветками — выбор способа оплаты
$altStep = Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'ALT'; conditionLabel = 'Способ оплаты'
} -Token $token
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $altStep.id; calledEntryPointId = $epCharge.id; conditionLabel = 'Картой'
} -Token $token | Out-Null
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $altStep.id; calledEntryPointId = $epOrderDb.id
    conditionLabel = 'Наличными (просто спишем со склада)'
} -Token $token | Out-Null

# Шаг 3: RETRY на 3 попытки вокруг платежа — увидим STEP_RETRYING в логе
$retryStep = Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'RETRY'; maxAttempts = 3
} -Token $token
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $retryStep.id; calledEntryPointId = $epCharge.id
} -Token $token | Out-Null

# Шаг 4: TIMEOUT с бюджетом 3000мс вокруг обращения к БД
$timeoutStep = Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'TIMEOUT'; timeoutMs = 3000
} -Token $token
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $timeoutStep.id; calledEntryPointId = $epOrderDb.id
} -Token $token | Out-Null

# Шаг 5: PARALLEL — два вызова с общим parallelGroupId
$parallelStep = Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'PARALLEL'
} -Token $token
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $parallelStep.id; calledEntryPointId = $epCharge.id
} -Token $token | Out-Null
Invoke-JsonPost "$API/scenarios/$($scenario.id)/steps" @{
    stepType = 'CALL'; parentStepId = $parallelStep.id; calledEntryPointId = $epOrderDb.id
} -Token $token | Out-Null

Write-Host ""
Write-Host "Готово."
Write-Host "Project A (Order Service): $($projectA.id)"
Write-Host "Scenario (Place order):    $($scenario.id)"
Write-Host ""
Write-Host "Токен (для delete-confirm-demo.ps1 и ручных curl/Postman-запросов): $token"
Write-Host "EP_CHARGE id (для юз-кейса про confirm=true при удалении): $($epCharge.id)"
