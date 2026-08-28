// Соответствует backend DTO (domain/api/*.java). Держим в одном месте,
// чтобы SchemeCanvas и будущие экраны (Scenario editor) не дублировали форму.

export type IntegrationType = 'API' | 'ASYNC' | 'WEBSOCKET';

export interface Project {
  id: string;
  name: string;
  description: string | null;
  schemeId: string | null;
  createdAt: string;
}

export interface BlockType {
  id: string;
  code: string;
  displayName: string;
}

export interface BlockInstance {
  id: string;
  schemeId: string;
  blockTypeId: string;
  blockTypeCode: string | null;
  label: string;
  x: number;
  y: number;
}

export interface Connection {
  id: string;
  schemeId: string;
  sourceBlockId: string;
  targetBlockId: string;
  integrationType: IntegrationType;
  isExternal: boolean;
}

export type StepType = 'CALL' | 'ALT' | 'PARALLEL' | 'RETRY' | 'TIMEOUT';

export interface ScenarioStep {
  id: string;
  scenarioId: string;
  orderIndex: number;
  parentStepId: string | null;
  stepType: StepType;
  calledEntryPointId: string | null;
  conditionLabel: string | null;
  parallelGroupId: string | null;
  maxAttempts: number | null;
  timeoutMs: number | null;
}

export type EntryPointKind = 'SYNC_METHOD' | 'ASYNC_EVENT' | 'WEBSOCKET_CHANNEL';

export interface EntryPoint {
  id: string;
  blockInstanceId: string;
  name: string;
  kind: EntryPointKind;
}

export interface Scenario {
  id: string;
  name: string;
  entryPointId: string;
  ownerId: string;
}

export interface Scheme {
  id: string;
  projectId: string;
  blocks: BlockInstance[];
  connections: Connection[];
  entryPoints: EntryPoint[];
}

export interface GraphBlockRef {
  id: string;
  label: string;
  blockTypeCode: string | null;
}

export interface GraphProjectGroup {
  id: string;
  name: string;
  blocks: GraphBlockRef[];
}

export interface GraphEdge {
  sourceBlockId: string;
  targetBlockId: string;
  sourceEntryPointId: string;
  targetEntryPointId: string;
  label: string | null;
}

// Derived-граф участников сценария (GET /scenarios/{id}/graph) — НЕ хранится
// в БД как Scheme, вычисляется обходом ScenarioStep. См. ScenarioGraphService.
export interface ScenarioGraph {
  scenarioId: string;
  projects: GraphProjectGroup[];
  edges: GraphEdge[];
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page?: number;
  size?: number;
}

// Зеркалит RunDto.StatusResponse — резервный источник статуса, если WS оборвался.
export interface RunStatus {
  runId: string;
  scenarioId: string;
  runNumber: number;
  status: 'running' | 'paused' | 'completed' | 'failed' | 'stopped';
  errorMessage: string | null;
}
