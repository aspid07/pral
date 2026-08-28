import { useCallback, useEffect, useMemo, useRef, useState, CSSProperties } from 'react';
import ReactFlow, { applyNodeChanges, Background, Controls, Edge, Node, NodeChange, NodeProps } from 'reactflow';
import 'reactflow/dist/style.css';
import { fetchScheme } from '../api/projects';
import { updateBlockPosition } from '../api/creation';
import { fetchScenarios, fetchScenarioSteps, fetchScenarioGraph, fetchRunStatus, startRun, pauseRun, resumeRun, stopRun } from '../api/runs';
import { Scenario, ScenarioStep, Scheme } from '../api/types';
import { ExecutionEvent, subscribeToRun } from '../ws/executionSocket';
import { layoutScenarioGraph } from './graphLayout';
import { DEFAULT_STEP_DELAY_MS, MAX_STEP_DELAY_MS, MIN_STEP_DELAY_MS } from './playbackConfig';
import { TokenEdge } from './TokenEdge';
import { CreateBlockModal } from '../creation/CreateBlockModal';
import { CreateEntryPointModal } from '../creation/CreateEntryPointModal';
import { CreateScenarioModal } from '../creation/CreateScenarioModal';

// TODO (editor mode): подключение к /ws/schemes/{schemeId}/collab через
// y-websocket для realtime co-editing позиций блоков и связей, включая
// PATCH .../blocks/{blockId} при drag&drop (сейчас позиции только читаются).
//
// Editor mode — позиции блоков берутся напрямую из BlockInstance.x/y
// (BlockInstance хранит их персистентно, см. V1__init.sql), без раскладки.
//
// Run mode — единый мульти-проектный холст (UC5, vision-and-scope.md):
// граф участников сценария (GET /scenarios/{id}/graph, вычисляется обходом
// ScenarioStep, не хранится в БД) компонуется через elkjs в compound-граф
// (проекты — контейнеры-обёртки, блоки — вложенные узлы), см. graphLayout.ts.

type NodeStatus = 'active' | 'visited';

const INTEGRATION_COLOR: Record<string, string> = {
  API: '#2563eb',
  ASYNC: '#d97706',
  WEBSOCKET: '#7c3aed',
};

function ProjectGroupNode({ data }: NodeProps<{ label: string }>) {
  return <div style={{ fontSize: 12, fontWeight: 600, color: '#475569', padding: '4px 8px' }}>{data.label}</div>;
}

const nodeTypes = { projectGroup: ProjectGroupNode };
const edgeTypes = { token: TokenEdge };

function toEditorEdges(scheme: Scheme): Edge[] {
  return scheme.connections.map((conn) => ({
    id: conn.id,
    source: conn.sourceBlockId,
    target: conn.targetBlockId,
    label: conn.integrationType,
    animated: conn.integrationType === 'ASYNC',
    style: {
      stroke: INTEGRATION_COLOR[conn.integrationType] ?? '#64748b',
      strokeDasharray: conn.isExternal ? '4 4' : undefined,
    },
  }));
}

function blockNodeStyle(status: NodeStatus | undefined, dimmed: boolean): CSSProperties | undefined {
  if (status === 'active') {
    return { background: '#fde68a', borderColor: '#d97706', borderWidth: 2 };
  }
  if (status === 'visited') {
    return { background: '#bbf7d0', borderColor: '#16a34a', borderWidth: 2 };
  }
  if (dimmed) {
    return { background: '#f1f5f9', color: '#94a3b8', borderColor: '#e2e8f0' };
  }
  return undefined;
}

function formatEvent(e: ExecutionEvent): string | null {
  switch (e.type) {
    case 'STEP_STARTED':
      return `→ ${e.label} (${e.kind}${e.parallelGroupId ? ', ∥' : ''}${e.timeoutMs ? `, timeout ${e.timeoutMs}ms` : ''})`;
    case 'STEP_RETRYING':
      return `⟳ попытка ${e.attempt}/${e.maxAttempts}`;
    case 'STEP_TIMEOUT':
      return `⏱ таймаут шага`;
    case 'CLUSTER_ENTERED':
      return `▢ переход в другой проект`;
    case 'RUN_ERROR':
      return `✕ ошибка: ${e.message}`;
    case 'RUN_COMPLETED':
      return `✓ сценарий выполнен`;
    case 'RUN_PAUSED':
      return `⏸ пауза`;
    case 'RUN_RESUMED':
      return `▶ возобновлено`;
    case 'RUN_STOPPED':
      return `⏹ остановлено пользователем`;
    default:
      return null; // RUN_STARTED / STEP_COMPLETED — не логируем текстом, видно по подсветке
  }
}

// ALT-узлы сценария вместе с их ветками (дочерними шагами), отсортированными
// по order_index — для формы выбора ветки перед стартом run. Ищем ALT на всех
// уровнях вложенности (внутри PARALLEL/RETRY/TIMEOUT тоже могут быть ALT).
function findAltGroups(steps: ScenarioStep[]): { alt: ScenarioStep; branches: ScenarioStep[] }[] {
  return steps
    .filter((s) => s.stepType === 'ALT')
    .map((alt) => ({
      alt,
      branches: steps.filter((s) => s.parentStepId === alt.id).sort((a, b) => a.orderIndex - b.orderIndex),
    }))
    .filter((group) => group.branches.length > 0);
}

function branchLabel(step: ScenarioStep, index: number): string {
  return step.conditionLabel ?? `Ветка ${index + 1}`;
}

export function SchemeCanvas({ mode, projectId }: { mode: 'editor' | 'run'; projectId: string | null }) {
  // --- Editor mode: одна схема одного проекта ---
  const [scheme, setScheme] = useState<Scheme | null>(null);
  const [schemeError, setSchemeError] = useState<string | null>(null);
  const [schemeLoading, setSchemeLoading] = useState(false);
  // Раньше в editor mode не было вообще никакого CRUD UI — только просмотр
  // того, что уже создано через API. showCreateBlock/entryPointTargetBlock/
  // showCreateScenario — состояние трёх модалок создания (см. creation/).
  const [showCreateBlock, setShowCreateBlock] = useState(false);
  const [entryPointTargetBlock, setEntryPointTargetBlock] = useState<{ id: string; label: string } | null>(null);
  const [showCreateScenario, setShowCreateScenario] = useState(false);

  const refetchScheme = useCallback(() => {
    if (!projectId) return;
    fetchScheme(projectId)
      .then((s) => setScheme(s))
      .catch((e) => setSchemeError(e instanceof Error ? e.message : String(e)));
  }, [projectId]);

  useEffect(() => {
    if (mode !== 'editor') return;
    if (!projectId) {
      setScheme(null);
      return;
    }
    let cancelled = false;
    setSchemeLoading(true);
    setSchemeError(null);
    fetchScheme(projectId)
      .then((s) => {
        if (!cancelled) setScheme(s);
      })
      .catch((e) => {
        if (!cancelled) setSchemeError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setSchemeLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [mode, projectId]);

  // editorNodes — состояние (не useMemo от scheme), т.к. react-flow в
  // управляемом режиме нуждается в onNodesChange, чтобы позиция реально
  // держалась во время drag, а не откатывалась на следующий ре-рендер. Раньше
  // это был чистый useMemo — drag выглядел рабочим только пока не происходило
  // больше вообще ничего, а сохранение на backend не вызывалось никогда.
  const [editorNodes, setEditorNodes] = useState<Node[]>([]);
  useEffect(() => {
    setEditorNodes(
      scheme
        ? scheme.blocks.map((block) => ({
            id: block.id,
            position: { x: block.x, y: block.y },
            data: { label: `${block.label}${block.blockTypeCode ? ` · ${block.blockTypeCode}` : ''}` },
          }))
        : [],
    );
  }, [scheme]);

  const onEditorNodesChange = useCallback((changes: NodeChange[]) => {
    setEditorNodes((nodes) => applyNodeChanges(changes, nodes));
  }, []);

  // Сохраняем на backend только по отпусканию мыши (не на каждый промежуточный
  // пиксель во время самого перетаскивания) — PATCH .../blocks/{id} ждёт
  // финальную позицию, а не поток из десятков запросов на один drag.
  const onEditorNodeDragStop = useCallback(
    (_event: unknown, node: Node) => {
      if (!projectId) return;
      updateBlockPosition(projectId, node.id, node.position.x, node.position.y).catch((e) => {
        setSchemeError(e instanceof Error ? e.message : String(e));
      });
    },
    [projectId],
  );

  const editorEdges = useMemo(() => (scheme ? toEditorEdges(scheme) : []), [scheme]);

  // --- Run mode: сценарий, мульти-проектный граф, живые события ---
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [scenariosError, setScenariosError] = useState<string | null>(null);
  const [selectedScenarioId, setSelectedScenarioId] = useState<string | null>(null);
  const [steps, setSteps] = useState<ScenarioStep[]>([]);
  const [stepsError, setStepsError] = useState<string | null>(null);
  const [branchSelections, setBranchSelections] = useState<Record<string, string>>({});

  const [graphNodes, setGraphNodes] = useState<Node[]>([]);
  const [graphEdges, setGraphEdges] = useState<Edge[]>([]);
  const [graphLoading, setGraphLoading] = useState(false);
  const [graphError, setGraphError] = useState<string | null>(null);

  // Стоп/Пауза (эта итерация): 'paused'/'stopped' — новые терминальные-в-моменте
  // состояния между 'running' и 'completed'/'error'. 'stopped' — отдельно от
  // 'error': явная остановка ПОЛЬЗОВАТЕЛЕМ, не сбой сценария.
  const [runStatus, setRunStatus] = useState<'idle' | 'running' | 'paused' | 'completed' | 'error' | 'stopped'>('idle');
  const [nodeStatuses, setNodeStatuses] = useState<Record<string, NodeStatus>>({});
  const [activeProjectId, setActiveProjectId] = useState<string | null>(null);
  const [activeEdgeId, setActiveEdgeId] = useState<string | null>(null);
  const [activeEdgeLabel, setActiveEdgeLabel] = useState<string | null>(null);
  const [eventLog, setEventLog] = useState<string[]>([]);
  const [runError, setRunError] = useState<string | null>(null);
  const [currentRunId, setCurrentRunId] = useState<string | null>(null);
  const [currentRunNumber, setCurrentRunNumber] = useState<number | null>(null);
  const [wsConnectionError, setWsConnectionError] = useState<string | null>(null);
  const [statusCheckPending, setStatusCheckPending] = useState(false);
  const [playbackSpeedMs, setPlaybackSpeedMs] = useState(DEFAULT_STEP_DELAY_MS);

  const entryPointToBlockRef = useRef<Record<string, string>>({});
  // "sourceEntryPointId->targetEntryPointId" -> edge id, для поиска ТОЙ САМОЙ
  // стрелки под токен (см. TokenEdge.tsx) — по паре блоков было бы неоднозначно,
  // если между теми же двумя блоками есть несколько рёбер от разных entry point.
  const edgeByEntryPointPairRef = useRef<Record<string, string>>({});
  const activeStepTargetsRef = useRef<Record<string, string>>({});
  const unsubscribeRef = useRef<(() => void) | null>(null);

  // Очередь "проигрывания" событий — см. playbackConfig.ts. События из WS
  // приходят пачкой почти мгновенно (backend ничего специально не задерживает
  // между шагами), поэтому применяем их к состоянию по одному, с паузой, а не
  // как только пришли — иначе на глаз это выглядит как мгновенная вспышка,
  // а не анимация.
  const eventQueueRef = useRef<ExecutionEvent[]>([]);
  const pumpActiveRef = useRef(false);
  const pumpTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const playbackSpeedRef = useRef(playbackSpeedMs);
  useEffect(() => {
    playbackSpeedRef.current = playbackSpeedMs;
  }, [playbackSpeedMs]);

  useEffect(() => {
    if (mode !== 'run') return;
    setScenariosError(null);
    fetchScenarios()
      .then((page) => {
        setScenarios(page.content);
        setSelectedScenarioId((current) => current ?? page.content[0]?.id ?? null);
      })
      .catch((e) => {
        setScenarios([]);
        setScenariosError(e instanceof Error ? e.message : String(e));
      });
  }, [mode]);

  useEffect(() => {
    if (mode !== 'run' || !selectedScenarioId) {
      setSteps([]);
      setBranchSelections({});
      setStepsError(null);
      return;
    }
    setStepsError(null);
    fetchScenarioSteps(selectedScenarioId)
      .then((loadedSteps) => {
        setSteps(loadedSteps);
        // По умолчанию — первая ветка каждого ALT (совпадает с поведением
        // движка без явного выбора, см. ExecutionEngine.executeAlt).
        const defaults: Record<string, string> = {};
        findAltGroups(loadedSteps).forEach(({ alt, branches }) => {
          defaults[alt.id] = branches[0].id;
        });
        setBranchSelections(defaults);
      })
      .catch((e) => {
        setSteps([]);
        setBranchSelections({});
        setStepsError(e instanceof Error ? e.message : String(e));
      });
  }, [mode, selectedScenarioId]);

  useEffect(() => {
    if (mode !== 'run' || !selectedScenarioId) {
      setGraphNodes([]);
      setGraphEdges([]);
      entryPointToBlockRef.current = {};
      return;
    }
    let cancelled = false;
    setGraphLoading(true);
    setGraphError(null);
    fetchScenarioGraph(selectedScenarioId)
      .then(async (g) => {
        if (cancelled) return;
        const map: Record<string, string> = {};
        const edgeMap: Record<string, string> = {};
        g.edges.forEach((e, i) => {
          map[e.sourceEntryPointId] = e.sourceBlockId;
          map[e.targetEntryPointId] = e.targetBlockId;
          // id рёбер в graphLayout.ts строится как `edge-${i}` в том же порядке — держим синхронно.
          edgeMap[`${e.sourceEntryPointId}->${e.targetEntryPointId}`] = `edge-${i}`;
        });
        entryPointToBlockRef.current = map;
        edgeByEntryPointPairRef.current = edgeMap;

        const { nodes, edges } = await layoutScenarioGraph(g);
        if (!cancelled) {
          setGraphNodes(nodes);
          setGraphEdges(edges);
        }
      })
      .catch((e) => {
        if (!cancelled) setGraphError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setGraphLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [mode, selectedScenarioId]);

  // Отписка от предыдущего run и остановка проигрывания очереди при размонтировании.
  useEffect(
    () => () => {
      unsubscribeRef.current?.();
      if (pumpTimeoutRef.current) clearTimeout(pumpTimeoutRef.current);
    },
    [],
  );

  // Собственно применение одного события к состоянию — раньше это и был
  // handleEvent целиком; теперь это то, что вызывает pump() по одному, с паузой.
  const applyEvent = useCallback((e: ExecutionEvent) => {
    const line = formatEvent(e);
    if (line) setEventLog((log) => [...log, line]);

    if (e.type === 'STEP_STARTED') {
      activeStepTargetsRef.current[e.stepId] = e.targetEntryPointId;
      const blockId = entryPointToBlockRef.current[e.targetEntryPointId];
      if (blockId) setNodeStatuses((prev) => ({ ...prev, [blockId]: 'active' }));
      const edgeId = edgeByEntryPointPairRef.current[`${e.sourceEntryPointId}->${e.targetEntryPointId}`];
      setActiveEdgeId(edgeId ?? null);
      setActiveEdgeLabel(`${e.label} (${e.kind})`);
    } else if (e.type === 'STEP_COMPLETED') {
      const entryPointId = activeStepTargetsRef.current[e.stepId];
      const blockId = entryPointId ? entryPointToBlockRef.current[entryPointId] : undefined;
      if (blockId) setNodeStatuses((prev) => ({ ...prev, [blockId]: 'visited' }));
      setActiveEdgeId(null);
      setActiveEdgeLabel(null);
    } else if (e.type === 'CLUSTER_ENTERED') {
      setActiveProjectId(e.projectId);
    } else if (e.type === 'RUN_COMPLETED') {
      setRunStatus('completed');
      setActiveEdgeId(null);
      setActiveEdgeLabel(null);
      // Подсветку убираем не мгновенно, а с той же паузой — иначе последний
      // шаг просто исчезает, не успев "долетать" на глаз.
      setTimeout(() => {
        setNodeStatuses({});
        setActiveProjectId(null);
      }, playbackSpeedRef.current);
    } else if (e.type === 'RUN_ERROR') {
      setRunStatus('error');
      setRunError(e.message);
      setActiveEdgeId(null);
      setActiveEdgeLabel(null);
      // Подсветку узлов НЕ убираем при ошибке — последний активный блок
      // показывает, на каком шаге всё сломалось, это полезно для отладки.
    } else if (e.type === 'RUN_PAUSED') {
      setRunStatus('paused');
      setActiveEdgeId(null);
      setActiveEdgeLabel(null);
      // Подсветку НЕ убираем — та же логика, что и при ошибке: пользователь
      // должен видеть, на каком шаге всё встало, это и есть смысл паузы
      // (в т.ч. чтобы решить, продолжать или остановить).
    } else if (e.type === 'RUN_RESUMED') {
      setRunStatus('running');
    } else if (e.type === 'RUN_STOPPED') {
      setRunStatus('stopped');
      setActiveEdgeId(null);
      setActiveEdgeLabel(null);
      // Подсветку тоже оставляем — см. комментарий у RUN_PAUSED выше.
    }
  }, []);

  // Ставит событие в очередь вместо немедленного применения — см. комментарий
  // у eventQueueRef выше.
  const pump = useCallback(() => {
    if (eventQueueRef.current.length === 0) {
      pumpActiveRef.current = false;
      return;
    }
    const next = eventQueueRef.current.shift()!;
    applyEvent(next);
    pumpTimeoutRef.current = setTimeout(pump, playbackSpeedRef.current);
  }, [applyEvent]);

  const handleEvent = useCallback(
    (e: ExecutionEvent) => {
      eventQueueRef.current.push(e);
      if (!pumpActiveRef.current) {
        pumpActiveRef.current = true;
        pump();
      }
    },
    [pump],
  );

  // Живой канал оборвался/не установился — НЕ то же самое, что RUN_ERROR
  // (тот означает "сценарий упал", это — "мы перестали видеть, что с ним
  // происходит"). runStatus намеренно не трогаем: сценарий вполне может
  // продолжать выполняться на backend, просто мы больше не видим событий.
  const handleConnectionError = useCallback((message: string) => {
    setWsConnectionError(message);
  }, []);

  const handleCheckStatus = () => {
    if (!currentRunId) return;
    setStatusCheckPending(true);
    fetchRunStatus(currentRunId)
      .then((status) => {
        if (status.status === 'completed') {
          setRunStatus('completed');
          setWsConnectionError(null);
        } else if (status.status === 'failed') {
          setRunStatus('error');
          setRunError(status.errorMessage ?? 'Run failed');
          setWsConnectionError(null);
        } else if (status.status === 'stopped') {
          setRunStatus('stopped');
          setWsConnectionError(null);
        } else if (status.status === 'paused') {
          setRunStatus('paused');
          setWsConnectionError(null);
        }
        // status === 'running' — реально всё ещё выполняется, оставляем как есть,
        // пользователь может нажать проверку ещё раз позже.
      })
      .catch((e) => setWsConnectionError(e instanceof Error ? e.message : String(e)))
      .finally(() => setStatusCheckPending(false));
  };

  // Общий сброс "стека запуска" — используется и перед стартом нового прогона,
  // и при смене выбора ветки ALT (см. handleBranchSelectionChange): старый
  // лог/подсветка от прогона с другой веткой не должны путаться при выборе
  // новой ветки, ещё до нажатия "Запустить".
  const resetRunState = (nextStatus: 'idle' | 'running') => {
    unsubscribeRef.current?.();
    if (pumpTimeoutRef.current) clearTimeout(pumpTimeoutRef.current);
    eventQueueRef.current = [];
    pumpActiveRef.current = false;
    setNodeStatuses({});
    setActiveProjectId(null);
    setActiveEdgeId(null);
    setActiveEdgeLabel(null);
    setEventLog([]);
    setRunError(null);
    setWsConnectionError(null);
    setControlActionError(null);
    setCurrentRunId(null);
    setCurrentRunNumber(null);
    setRunStatus(nextStatus);
  };

  const handleBranchSelectionChange = (altId: string, value: string) => {
    setBranchSelections((prev) => ({ ...prev, [altId]: value }));
    resetRunState('idle');
  };

  // Стоп/Пауза (эта итерация): все три асинхронны на backend (202 Accepted —
  // "запрос принят", не "уже применилось", см. api/runs.ts) — runStatus сюда
  // намеренно НЕ пишем напрямую, ждём подтверждения через RUN_PAUSED/
  // RUN_RESUMED/RUN_STOPPED в WS (applyEvent выше). pending — только чтобы
  // не дать нажать кнопку повторно, пока первый запрос ещё в полёте.
  const [controlActionPending, setControlActionPending] = useState(false);
  const [controlActionError, setControlActionError] = useState<string | null>(null);

  const runControlAction = (action: (runId: string) => Promise<void>) => {
    if (!currentRunId) return;
    setControlActionPending(true);
    setControlActionError(null);
    action(currentRunId)
      .catch((e) => setControlActionError(e instanceof Error ? e.message : String(e)))
      .finally(() => setControlActionPending(false));
  };

  const handlePause = () => runControlAction(pauseRun);
  const handleResume = () => runControlAction(resumeRun);
  const handleStop = () => runControlAction(stopRun);

  const handleStart = () => {
    if (!selectedScenarioId) return;
    resetRunState('running');

    startRun(selectedScenarioId, branchSelections)
      .then(({ runId, runNumber }) => {
        setCurrentRunId(runId);
        setCurrentRunNumber(runNumber);
        unsubscribeRef.current = subscribeToRun(runId, handleEvent, handleConnectionError);
      })
      .catch((e) => {
        setRunStatus('error');
        setRunError(e instanceof Error ? e.message : String(e));
      });
  };

  const altGroups = useMemo(() => findAltGroups(steps), [steps]);

  const dimmed = runStatus !== 'idle';
  // 'paused' — тоже "занято": сценарий формально ещё не закончился, просто
  // стоит. Выбор ветки ALT/смена сценария/повторный старт посреди паузы
  // путали бы серверное состояние с тем, что видит пользователь на экране.
  const runBusy = runStatus === 'running' || runStatus === 'paused';
  const renderedRunNodes: Node[] = useMemo(
    () =>
      graphNodes.map((n) => {
        if (n.type === 'projectGroup') {
          const isActive = n.id === activeProjectId;
          return {
            ...n,
            style: {
              ...n.style,
              borderColor: isActive ? '#d97706' : '#94a3b8',
              borderWidth: isActive ? 2.5 : 1.5,
              borderStyle: isActive ? 'solid' : 'dashed',
            },
          };
        }
        return { ...n, style: { ...n.style, ...blockNodeStyle(nodeStatuses[n.id], dimmed) } };
      }),
    [graphNodes, nodeStatuses, activeProjectId, dimmed],
  );

  const renderedRunEdges: Edge[] = useMemo(
    () =>
      graphEdges.map((edge) => ({
        ...edge,
        data: {
          ...edge.data,
          active: edge.id === activeEdgeId,
          activeLabel: edge.id === activeEdgeId ? activeEdgeLabel ?? undefined : undefined,
          durationMs: playbackSpeedMs,
        },
      })),
    [graphEdges, activeEdgeId, activeEdgeLabel, playbackSpeedMs],
  );

  if (mode === 'editor' && !projectId) {
    return <div style={{ padding: 16 }}>Выберите проект, чтобы увидеть Общую схему.</div>;
  }

  const nodes = mode === 'editor' ? editorNodes : renderedRunNodes;
  const edges = mode === 'editor' ? editorEdges : renderedRunEdges;

  return (
    <div style={{ height: '80vh', position: 'relative', display: 'flex' }}>
      <div style={{ flex: 1, position: 'relative' }}>
        {mode === 'editor' && schemeLoading && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>Загрузка схемы…</div>
        )}
        {mode === 'editor' && schemeError && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1, color: 'crimson' }}>
            Не удалось загрузить схему: {schemeError}
          </div>
        )}
        {mode === 'editor' && scheme && scheme.blocks.length === 0 && !schemeLoading && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>В этом проекте пока нет блоков.</div>
        )}
        {mode === 'run' && graphLoading && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>Строим граф участников…</div>
        )}
        {mode === 'run' && graphError && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1, color: 'crimson' }}>
            Не удалось построить граф: {graphError}
          </div>
        )}
        {mode === 'run' && !graphLoading && !graphError && graphNodes.length === 0 && selectedScenarioId && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>
            У этого сценария пока нет ни одного вызова (CALL).
          </div>
        )}
        <ReactFlow
          key={mode === 'run' ? `run:${selectedScenarioId ?? 'none'}` : `editor:${projectId ?? 'none'}`}
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          nodesDraggable={mode !== 'run'}
          onNodesChange={mode === 'editor' ? onEditorNodesChange : undefined}
          onNodeDragStop={mode === 'editor' ? onEditorNodeDragStop : undefined}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>

      {mode === 'editor' && scheme && (
        <div style={{ width: 320, borderLeft: '1px solid #e2e8f0', padding: 12, overflowY: 'auto' }}>
          <button onClick={() => setShowCreateBlock(true)} style={{ marginBottom: 12, width: '100%' }}>
            + Блок
          </button>
          {scheme.blocks.map((block) => {
            const blockEntryPoints = scheme.entryPoints.filter((ep) => ep.blockInstanceId === block.id);
            return (
              <div key={block.id} style={{ marginBottom: 12, paddingBottom: 12, borderBottom: '1px solid #f1f5f9' }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>
                  {block.label}
                  {block.blockTypeCode ? ` · ${block.blockTypeCode}` : ''}
                </div>
                {blockEntryPoints.map((ep) => (
                  <div key={ep.id} style={{ fontSize: 12, color: '#64748b', paddingLeft: 8 }}>
                    {ep.name} ({ep.kind})
                  </div>
                ))}
                <button
                  onClick={() => setEntryPointTargetBlock({ id: block.id, label: block.label })}
                  style={{ marginTop: 4, fontSize: 12 }}
                >
                  + Entry point
                </button>
              </div>
            );
          })}
        </div>
      )}

      {mode === 'run' && (
        <div style={{ width: 320, borderLeft: '1px solid #e2e8f0', padding: 12, overflowY: 'auto' }}>
          <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
            <select
              value={selectedScenarioId ?? ''}
              onChange={(e) => setSelectedScenarioId(e.target.value || null)}
              disabled={scenarios.length === 0 || runBusy}
              style={{ flex: 1 }}
            >
              {scenarios.length === 0 && (
                <option value="">{scenariosError ? 'Ошибка загрузки сценариев' : 'Нет сценариев'}</option>
              )}
              {scenarios.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <button onClick={handleStart} disabled={!selectedScenarioId || runBusy}>
              {runStatus === 'running' ? 'Выполняется…' : runStatus === 'paused' ? 'На паузе' : 'Запустить'}
            </button>
            <button onClick={() => setShowCreateScenario(true)} disabled={runBusy}>
              + Сценарий
            </button>
          </div>

          {/* Стоп/Пауза (эта итерация): кнопки показываются только пока run
              реально идёт/стоит на паузе — вне этих двух статусов им нечего
              контролировать (currentRunId либо ещё не выдан, либо уже
              завершённый run, для которого backend отклонит запрос 409). */}
          {runBusy && (
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              {runStatus === 'running' && (
                <button onClick={handlePause} disabled={controlActionPending}>
                  {controlActionPending ? '…' : 'Пауза'}
                </button>
              )}
              {runStatus === 'paused' && (
                <button onClick={handleResume} disabled={controlActionPending}>
                  {controlActionPending ? '…' : 'Продолжить'}
                </button>
              )}
              <button onClick={handleStop} disabled={controlActionPending}>
                {controlActionPending ? '…' : 'Стоп'}
              </button>
            </div>
          )}

          {controlActionError && (
            <div style={{ marginBottom: 12, color: 'crimson', fontSize: 12 }}>{controlActionError}</div>
          )}

          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: 12, color: '#64748b', display: 'block', marginBottom: 2 }}>
              Скорость анимации: {playbackSpeedMs} мс/шаг
            </label>
            <input
              type="range"
              min={MIN_STEP_DELAY_MS}
              max={MAX_STEP_DELAY_MS}
              step={100}
              value={playbackSpeedMs}
              onChange={(e) => setPlaybackSpeedMs(Number(e.target.value))}
              style={{ width: '100%' }}
            />
          </div>

          {stepsError && (
            <div style={{ marginBottom: 8, color: 'crimson', fontSize: 12 }}>
              Не удалось загрузить шаги сценария: {stepsError}
            </div>
          )}

          {altGroups.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: '#64748b', marginBottom: 4 }}>Ветвления (ALT)</div>
              {altGroups.map(({ alt, branches }) => (
                <div key={alt.id} style={{ marginBottom: 6 }}>
                  <label style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>
                    {alt.conditionLabel ?? `ALT ${alt.id.slice(0, 8)}…`}
                  </label>
                  <select
                    value={branchSelections[alt.id] ?? branches[0].id}
                    onChange={(e) => handleBranchSelectionChange(alt.id, e.target.value)}
                    disabled={runBusy}
                    style={{ width: '100%' }}
                  >
                    {branches.map((branch, i) => (
                      <option key={branch.id} value={branch.id}>
                        {branchLabel(branch, i)}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
          )}

          {wsConnectionError && (
            <div style={{ marginBottom: 8, padding: 8, background: '#fef3c7', borderRadius: 4, fontSize: 12 }}>
              <div style={{ marginBottom: 4 }}>
                Соединение с событиями потеряно ({wsConnectionError}). Сценарий мог продолжить выполняться на сервере.
              </div>
              <button onClick={handleCheckStatus} disabled={statusCheckPending}>
                {statusCheckPending ? 'Проверяем…' : 'Проверить статус'}
              </button>
            </div>
          )}

          {runStatus !== 'idle' && (
            <div
              style={{
                marginBottom: 8,
                fontWeight: 600,
                color:
                  runStatus === 'error'
                    ? 'crimson'
                    : runStatus === 'completed'
                      ? '#16a34a'
                      : runStatus === 'paused'
                        ? '#d97706'
                        : runStatus === 'stopped'
                          ? '#64748b'
                          : '#334155',
              }}
            >
              {runStatus === 'running' && `Запуск #${currentRunNumber ?? '…'} — Выполняется…`}
              {runStatus === 'paused' && `Запуск #${currentRunNumber} — На паузе`}
              {runStatus === 'completed' && `Запуск #${currentRunNumber} — Сценарий выполнен`}
              {runStatus === 'error' && `Запуск #${currentRunNumber} — Ошибка: ${runError}`}
              {runStatus === 'stopped' && `Запуск #${currentRunNumber} — Остановлено пользователем`}
            </div>
          )}

          <div style={{ fontFamily: 'monospace', fontSize: 12, lineHeight: 1.5 }}>
            {eventLog.map((line, i) => (
              <div key={i}>
                {i + 1}. {line}
              </div>
            ))}
          </div>
        </div>
      )}

      {showCreateBlock && projectId && (
        <CreateBlockModal
          projectId={projectId}
          existingCount={scheme?.blocks.length ?? 0}
          onClose={() => setShowCreateBlock(false)}
          onCreated={refetchScheme}
        />
      )}
      {entryPointTargetBlock && (
        <CreateEntryPointModal
          blockId={entryPointTargetBlock.id}
          blockLabel={entryPointTargetBlock.label}
          onClose={() => setEntryPointTargetBlock(null)}
          onCreated={refetchScheme}
        />
      )}
      {showCreateScenario && (
        <CreateScenarioModal
          onClose={() => setShowCreateScenario(false)}
          onCreated={(s) => {
            setScenarios((prev) => [...prev, s]);
            setSelectedScenarioId(s.id);
          }}
        />
      )}
    </div>
  );
}
