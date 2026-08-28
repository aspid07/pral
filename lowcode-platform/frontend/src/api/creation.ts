import { apiDelete, apiGet, apiPatch, apiPost } from './client';
import { BlockInstance, BlockType, EntryPoint, EntryPointKind, Project, Scenario } from './types';

export function createProject(name: string, description: string): Promise<Project> {
  return apiPost<Project>('/projects', { name, description: description || null });
}

export function fetchBlockTypes(): Promise<BlockType[]> {
  return apiGet<BlockType[]>('/block-types');
}

export function createBlock(
  projectId: string,
  blockTypeId: string,
  label: string,
  x: number,
  y: number,
): Promise<BlockInstance> {
  return apiPost<BlockInstance>(`/projects/${projectId}/blocks`, { blockTypeId, label, x, y });
}

export function createEntryPoint(blockId: string, name: string, kind: EntryPointKind): Promise<EntryPoint> {
  return apiPost<EntryPoint>(`/blocks/${blockId}/entry-points`, { name, kind });
}

export function createScenario(name: string, entryPointId: string): Promise<Scenario> {
  return apiPost<Scenario>('/scenarios', { name, entryPointId });
}

export function updateBlockPosition(projectId: string, blockId: string, x: number, y: number): Promise<BlockInstance> {
  // PATCH-семантика бэкенда: null/отсутствующее поле = "не менять" — label
  // сюда не передаём вообще, только x/y.
  return apiPatch<BlockInstance>(`/projects/${projectId}/blocks/${blockId}`, { x, y });
}

// Только root-level CALL (parentStepId не передаём) — визуальный редактор
// шагов (ScenarioBuilder) пока умеет только линейную последовательность
// вызовов, не вложенные ALT/PARALLEL/RETRY/TIMEOUT. См. README про то, что
// это осознанно урезанный v1, не полный паритет с curl/API.
export function createCallStep(scenarioId: string, calledEntryPointId: string) {
  return apiPost(`/scenarios/${scenarioId}/steps`, { stepType: 'CALL', calledEntryPointId });
}

export function deleteStep(scenarioId: string, stepId: string) {
  return apiDelete(`/scenarios/${scenarioId}/steps/${stepId}`);
}

export function deleteScenario(scenarioId: string) {
  return apiDelete(`/scenarios/${scenarioId}`);
}

// PATCH-семантика: остальные поля не передаём — они и так не меняются
// (null на бэкенде значит "не менять", см. ScenarioStepDto.UpdateRequest).
export function retargetStep(scenarioId: string, stepId: string, calledEntryPointId: string) {
  return apiPatch(`/scenarios/${scenarioId}/steps/${stepId}`, { calledEntryPointId });
}
