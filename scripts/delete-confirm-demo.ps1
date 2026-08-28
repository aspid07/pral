# Демонстрация confirm=true при удалении entry point, на который ссылается
# сценарий (см. docs/local-setup.md, "Юз-кейс: проверка confirm=true").
# Требует EP_CHARGE id и токен, которые печатает seed.ps1 в конце вывода.
#
# Запуск:
#   .\delete-confirm-demo.ps1 -EntryPointId "<guid из вывода seed.ps1>" -Token "<токен из вывода seed.ps1>"

param(
    [Parameter(Mandatory)] [string] $EntryPointId,
    [Parameter(Mandatory)] [string] $Token,
    [string] $ScenarioId
)

$ErrorActionPreference = 'Stop'
$API = 'http://localhost:8080/api/v1'
$authHeader = @{ Authorization = "Bearer $Token" }

Write-Host "Без confirm — ожидаем 409 со списком сценариев, которые используют этот entry point..."
try {
    Invoke-RestMethod -Uri "$API/entry-points/$EntryPointId" -Method Delete -Headers $authHeader
    Write-Host "Неожиданно: удалилось без confirm — не должно было."
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    Write-Host "Статус: $status (ожидали 409)"
    if ($_.ErrorDetails.Message) {
        Write-Host $_.ErrorDetails.Message
    }
}

Write-Host ""
Write-Host "С confirm=true — теперь реально удаляется (см. V4__fix_entry_point_delete_cascade.sql)..."
Invoke-RestMethod -Uri "$API/entry-points/$EntryPointId`?confirm=true" -Method Delete -Headers $authHeader
Write-Host "Удалено, без 500 от БД."

if ($ScenarioId) {
    Write-Host ""
    Write-Host "Шаги сценария, ссылавшиеся на этот entry point, теперь 'битые' (calledEntryPointId: null),"
    Write-Host "но сам сценарий и остальные шаги — целы:"
    $steps = Invoke-RestMethod -Uri "$API/scenarios/$ScenarioId/steps" -Method Get -Headers $authHeader
    $steps | Select-Object stepType, calledEntryPointId | Format-Table
}
