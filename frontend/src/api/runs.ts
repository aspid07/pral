import { apiGet, apiPost } from './client';
import { Page, RunStatus, Scenario, ScenarioGraph, ScenarioStep } from './types';

export function fetchScenarios(): Promise<Page<Scenario>> {
  return apiGet<Page<Scenario>>('/scenarios?size=50');
}

export function fetchScenarioSteps(scenarioId: string): Promise<ScenarioStep[]> {
  return apiGet<ScenarioStep[]>(`/scenarios/${scenarioId}/steps`);
}

// Мульти-проектный граф участников сценария — для compound-раскладки на
// канвасе (см. canvas/graphLayout.ts). Не входит в исходный api-contract.md.
export function fetchScenarioGraph(scenarioId: string): Promise<ScenarioGraph> {
  return apiGet<ScenarioGraph>(`/scenarios/${scenarioId}/graph`);
}

// branchSelections: id ALT-шага -> id выбранной дочерней ветки. Для ALT-узлов
// без явного выбора backend берёт первую ветку по order_index (см. api-contract.md
// и ExecutionEngine.executeAlt).
export function startRun(scenarioId: string, branchSelections?: Record<string, string>): Promise<{ runId: string; runNumber: number }> {
  return apiPost<{ runId: string; runNumber: number }>(`/scenarios/${scenarioId}/runs`, { branchSelections });
}

// Резервный путь на случай обрыва WS-соединения (см. ws/executionSocket.ts,
// onConnectionError) — иначе при потере live-канала узнать, чем закончился
// запуск, было бы неоткуда.
export function fetchRunStatus(runId: string): Promise<RunStatus> {
  return apiGet<RunStatus>(`/runs/${runId}`);
}

// Стоп/Пауза (эта итерация): все три асинхронны на backend — 202 Accepted
// означает "запрос принят", не "уже применилось" (см. RunController).
// Живое подтверждение — события RUN_PAUSED/RUN_RESUMED/RUN_STOPPED в WS
// (см. ws/executionSocket.ts), не ответ этих вызовов.
export function pauseRun(runId: string): Promise<void> {
  return apiPost<void>(`/runs/${runId}/pause`, undefined);
}

export function resumeRun(runId: string): Promise<void> {
  return apiPost<void>(`/runs/${runId}/resume`, undefined);
}

export function stopRun(runId: string): Promise<void> {
  return apiPost<void>(`/runs/${runId}/stop`, undefined);
}
