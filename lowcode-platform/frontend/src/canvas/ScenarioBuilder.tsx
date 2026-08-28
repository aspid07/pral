import { useCallback, useEffect, useMemo, useState } from 'react';
import ReactFlow, { Background, Connection, Controls, Edge, Node, NodeChange } from 'reactflow';
import 'reactflow/dist/style.css';
import { fetchScheme } from '../api/projects';
import { fetchScenarios, fetchScenarioSteps } from '../api/runs';
import { createCallStep, createScenario, deleteScenario, deleteStep, retargetStep, updateBlockPosition } from '../api/creation';
import { Scenario, Scheme, ScenarioStep } from '../api/types';
import { EntryPointBlockData, EntryPointBlockNode, StartNode } from './ScenarioBuilderNodes';
import { CreateScenarioModal } from '../creation/CreateScenarioModal';

const nodeTypes = { entryPointBlock: EntryPointBlockNode, start: StartNode };

const START_NODE_ID = '__start__';

interface ChainStepEntry {
  step: ScenarioStep;
  scenarioId: string;
}

/**
 * До этого компонента в UI не было вообще никакого способа создать
 * ScenarioStep — CreateScenarioModal создавала только "оболочку" сценария
 * (имя + entry point), шаги можно было добавить только через curl/API.
 *
 * Первая версия заставляла вручную создавать ОТДЕЛЬНЫЙ сценарий на каждый
 * хоп цепочки (кнопка "+ Сценарий" + диалог с именем на каждый блок) —
 * технически правильно (в этом движке "A вызывает B, который сам вызывает
 * C" — это буквально отдельный Scenario на entry point каждого блока,
 * ExecutionEngine рекурсивно заходит в сценарий вызываемого entry point,
 * если он есть), но по фидбэку "очень трудно и не user friendly" — три
 * ручных создания сценария ради одной цепочки из трёх стрелок.
 *
 * Эта версия делает ту же самую операцию автоматически: выбираете/создаёте
 * ОДИН раз стартовый сценарий (это "с чего всё начинается" — сознательно
 * остаётся явным шагом), а дальше просто тянете стрелки одну за другой.
 * Если очередная стрелка идёт от блока, у entry point которого ещё нет
 * своего сценария — он создаётся молча, без диалогов, и шаг сразу
 * добавляется в него. Видно это как одну непрерывную цепочку на канвасе,
 * хотя "под капотом" это по-прежнему N отдельных объектов Scenario.
 *
 * ALT/PARALLEL/RETRY/TIMEOUT и вложенные (не root-level) шаги пока не
 * визуализируются — для них по-прежнему нужен API напрямую (см. local-setup.md).
 */
export function ScenarioBuilder({ projectId }: { projectId: string | null }) {
  const [scheme, setScheme] = useState<Scheme | null>(null);
  const [schemeError, setSchemeError] = useState<string | null>(null);
  // Позиция блоков — отдельное состояние, не часть nodes-useMemo напрямую:
  // nodes пересчитывается на каждое изменение цепочки (подсветка "хвоста"),
  // и если бы позиция жила там же, drag откатывался бы визуально после
  // каждого нового шага, ещё не дождавшись реального перезапроса scheme.
  const [blockPositions, setBlockPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [rootScenarioId, setRootScenarioId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [showCreateScenario, setShowCreateScenario] = useState(false);

  // Вся цепочка, начиная от выбранного "стартового" сценария — обходим её
  // сами: у последнего root-level CALL каждого сценария смотрим на цель,
  // если у неё есть СВОЙ сценарий — идём дальше в него, пока не упрёмся в
  // лист (у цели нет сценария) или в уже посещённый (защита от зависания
  // самого обхода на кросс-ссылках — это ОТДЕЛЬНО от runtime-проверки цикла).
  const [chainScenarios, setChainScenarios] = useState<Scenario[]>([]);
  const [chainStepsByScenario, setChainStepsByScenario] = useState<Record<string, ScenarioStep[]>>({});

  useEffect(() => {
    if (!projectId) {
      setScheme(null);
      return;
    }
    fetchScheme(projectId)
      .then((s) => {
        setScheme(s);
        setBlockPositions(Object.fromEntries(s.blocks.map((b) => [b.id, { x: b.x, y: b.y }])));
      })
      .catch((e) => setSchemeError(e instanceof Error ? e.message : String(e)));
  }, [projectId]);

  useEffect(() => {
    if (!scheme) {
      setScenarios([]);
      return;
    }
    // GET /scenarios отдаёт ВСЕ видимые пользователю сценарии (см. Stage 4,
    // фильтрация по правам) — среди разных проектов. Тут нужны только те,
    // чей entry point принадлежит блокам ТЕКУЩЕГО проекта — фильтруем на
    // клиенте, отдельного backend-эндпоинта "сценарии этого проекта" нет.
    const projectEntryPointIds = new Set(scheme.entryPoints.map((ep) => ep.id));
    fetchScenarios()
      .then((page) => {
        const filtered = page.content.filter((s) => projectEntryPointIds.has(s.entryPointId));
        setScenarios(filtered);
        setRootScenarioId((current) => current ?? filtered[0]?.id ?? null);
      })
      .catch((e) => setSchemeError(e instanceof Error ? e.message : String(e)));
  }, [scheme]);

  const rootScenario = scenarios.find((s) => s.id === rootScenarioId) ?? null;

  const refetchChain = useCallback(async () => {
    if (!rootScenarioId || !scheme) {
      setChainScenarios([]);
      setChainStepsByScenario({});
      return;
    }
    // ИНФИНИТНЫЙ ЦИКЛ ЗАПРОСОВ был здесь: раньше зависимостью useCallback был
    // `rootScenario` (объект, `scenarios.find(...)`), а сама эта функция
    // вызывает setScenarios(freshScenarios) — с НОВЫМ массивом объектов на
    // каждый вызов. rootScenario получал новую ссылку -> useCallback
    // пересоздавал refetchChain -> useEffect ниже, подписанный на
    // refetchChain, срабатывал заново -> setScenarios снова -> без остановки.
    // Зависимость — rootScenarioId (стабильная строка, не пересоздаётся
    // побочным эффектом самой функции), а не производный от state объект.
    let freshScenarios: Scenario[];
    try {
      const projectEntryPointIds = new Set(scheme.entryPoints.map((ep) => ep.id));
      const page = await fetchScenarios();
      freshScenarios = page.content.filter((s) => projectEntryPointIds.has(s.entryPointId));
      setScenarios(freshScenarios);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
      return;
    }
    const freshRoot = freshScenarios.find((s) => s.id === rootScenarioId);
    if (!freshRoot) {
      setChainScenarios([]);
      setChainStepsByScenario({});
      return;
    }

    const chain: Scenario[] = [freshRoot];
    const stepsByScenario: Record<string, ScenarioStep[]> = {};
    const visited = new Set<string>();
    let current: Scenario | undefined = freshRoot;
    while (current && !visited.has(current.id)) {
      visited.add(current.id);
      let steps: ScenarioStep[];
      try {
        steps = await fetchScenarioSteps(current.id);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e));
        break;
      }
      stepsByScenario[current.id] = steps;
      const rootCalls = steps
        .filter((s) => s.stepType === 'CALL' && s.parentStepId === null && s.calledEntryPointId)
        .sort((a, b) => a.orderIndex - b.orderIndex);
      if (rootCalls.length === 0) break;
      const lastCall = rootCalls[rootCalls.length - 1];
      const next: Scenario | undefined = freshScenarios.find((s) => s.entryPointId === lastCall.calledEntryPointId);
      if (!next) break;
      chain.push(next);
      current = next;
    }
    setChainScenarios(chain);
    setChainStepsByScenario(stepsByScenario);
  }, [rootScenarioId, scheme]);

  useEffect(() => {
    refetchChain();
  }, [refetchChain]);

  // Все root-level CALL всей цепочки подряд, с привязкой к тому, какому
  // именно сценарию каждый принадлежит (нужно для удаления/переадресации).
  const chainCallSteps: ChainStepEntry[] = useMemo(() => {
    const entries: ChainStepEntry[] = [];
    for (const sc of chainScenarios) {
      const steps = (chainStepsByScenario[sc.id] ?? [])
        .filter((s) => s.stepType === 'CALL' && s.parentStepId === null && s.calledEntryPointId)
        .sort((a, b) => a.orderIndex - b.orderIndex);
      for (const step of steps) entries.push({ step, scenarioId: sc.id });
    }
    return entries;
  }, [chainScenarios, chainStepsByScenario]);

  // Хвост цепочки — блок и entry point, откуда можно вести следующую
  // стрелку. usedEntryPointIds — все entry point, уже встретившиеся в
  // цепочке (включая сам старт) — движок держит их все в ОДНОМ стеке
  // вызовов на весь run, так что повторный заход в любой из них с любого
  // места цепочки — гарантированный цикл, не только прямое A→A.
  const { tailBlockId, tailEntryPointId, usedEntryPointIds } = useMemo(() => {
    let blockId: string = START_NODE_ID;
    let epId: string | null = rootScenario?.entryPointId ?? null;
    const used = new Set<string>();
    if (rootScenario) used.add(rootScenario.entryPointId);
    for (const { step } of chainCallSteps) {
      const targetEp = scheme?.entryPoints.find((ep) => ep.id === step.calledEntryPointId);
      if (targetEp) blockId = targetEp.blockInstanceId;
      if (step.calledEntryPointId) {
        epId = step.calledEntryPointId;
        used.add(step.calledEntryPointId);
      }
    }
    return { tailBlockId: blockId, tailEntryPointId: epId, usedEntryPointIds: used };
  }, [rootScenario, chainCallSteps, scheme]);

  const nodes: Node[] = useMemo(() => {
    if (!scheme) return [];
    const blockNodes: Node<EntryPointBlockData>[] = scheme.blocks.map((block) => ({
      id: block.id,
      type: 'entryPointBlock',
      position: blockPositions[block.id] ?? { x: block.x, y: block.y },
      data: {
        label: block.label,
        entryPoints: scheme.entryPoints.filter((ep) => ep.blockInstanceId === block.id),
        isChainTail: rootScenario != null && block.id === tailBlockId,
      },
    }));
    if (!rootScenario) return blockNodes;
    const rootEntryPointName =
      scheme.entryPoints.find((ep) => ep.id === rootScenario.entryPointId)?.name ?? rootScenario.name;
    const startNode: Node = {
      id: START_NODE_ID,
      type: 'start',
      position: { x: -240, y: 40 },
      data: { label: rootEntryPointName, isChainTail: tailBlockId === START_NODE_ID },
    };
    return [startNode, ...blockNodes];
  }, [scheme, rootScenario, tailBlockId, blockPositions]);

  // Цепочка, а не веер: edge[0] идёт от "Старт", edge[i] (i>0) — от блока,
  // в который зашли на предыдущем шаге, ДАЖЕ ЕСЛИ этот шаг физически лежит
  // в другом (авто-созданном) Scenario. Порядок топологии рёбер строго
  // следует orderIndex внутри каждого сценария цепочки, а не тому, откуда
  // пользователь визуально тянул мышью.
  const edges: Edge[] = useMemo(() => {
    if (!rootScenario || !scheme) return [];
    let previousBlockId: string = START_NODE_ID;
    let previousEntryPointId: string | undefined = 'start';
    return chainCallSteps.map(({ step }, i) => {
      const targetEp = scheme.entryPoints.find((ep) => ep.id === step.calledEntryPointId);
      const edge: Edge = {
        id: step.id,
        source: previousBlockId,
        sourceHandle: previousEntryPointId,
        target: targetEp?.blockInstanceId ?? '',
        targetHandle: step.calledEntryPointId!,
        label: `${i + 1}. ${targetEp?.name ?? '?'}`,
        labelBgStyle: { fill: '#f0fdf4' },
      };
      if (targetEp) previousBlockId = targetEp.blockInstanceId;
      previousEntryPointId = step.calledEntryPointId!;
      return edge;
    });
  }, [chainCallSteps, rootScenario, scheme]);

  const onConnect = useCallback(
    (connection: Connection) => {
      setActionError(null);
      if (!rootScenario || !connection.targetHandle) return;
      if (connection.source !== tailBlockId) {
        const expectedLabel =
          tailBlockId === START_NODE_ID
            ? 'узла "Старт"'
            : `блока "${scheme?.blocks.find((b) => b.id === tailBlockId)?.label ?? '?'}" (последний в цепочке)`;
        setActionError(`Ведите стрелку от ${expectedLabel} — так цепочка вызовов остаётся последовательной.`);
        return;
      }
      if (usedEntryPointIds.has(connection.targetHandle)) {
        // ExecutionEngine держит ВСЮ цепочку вызовов в одном стеке на весь
        // run, не только текущий сценарий — повторный заход в любой entry
        // point, уже встретившийся раньше (не обязательно напрямую), даст
        // Cycle detected в рантайме.
        setActionError(
          'Этот entry point уже есть где-то раньше в цепочке — движок остановит повторный заход как цикл. Ведите стрелку в другой entry point.',
        );
        return;
      }

      const targetEntryPointId = connection.targetHandle;
      const owner = tailEntryPointId ? chainScenarios.find((s) => s.entryPointId === tailEntryPointId) : undefined;

      if (owner) {
        // У хвоста цепочки уже есть "хозяин"-сценарий (сам старт или ранее
        // авто-созданный вложенный) — просто добавляем шаг в него.
        createCallStep(owner.id, targetEntryPointId)
          .then(refetchChain)
          .catch((e) => setActionError(e instanceof Error ? e.message : String(e)));
        return;
      }

      if (!tailEntryPointId) return;
      // Хвостовой entry point ещё не был чьим-то корнем — раньше это
      // требовало ручного "+ Сценарий" с диалогом имени; теперь создаём
      // молча и сразу добавляем шаг в него.
      const tailEp = scheme?.entryPoints.find((ep) => ep.id === tailEntryPointId);
      createScenario(tailEp?.name ?? 'Сценарий', tailEntryPointId)
        .then((created) => createCallStep(created.id, targetEntryPointId))
        .then(refetchChain)
        .catch((e) => setActionError(e instanceof Error ? e.message : String(e)));
    },
    [rootScenario, tailBlockId, tailEntryPointId, usedEntryPointIds, chainScenarios, scheme, refetchChain],
  );

  // Раньше блоки на этом канвасе не двигались вообще (не было onNodesChange,
  // react-flow в управляемом режиме без него не держит позицию) — с
  // несколькими entry point на блоке они начинают наезжать друг на друга.
  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setBlockPositions((prev) => {
      let next = prev;
      for (const change of changes) {
        if (change.type === 'position' && change.position && change.id !== START_NODE_ID) {
          if (next === prev) next = { ...prev };
          next[change.id] = change.position;
        }
      }
      return next;
    });
  }, []);

  const onNodeDragStop = useCallback(
    (_event: unknown, node: Node) => {
      if (!projectId || node.id === START_NODE_ID) return;
      updateBlockPosition(projectId, node.id, node.position.x, node.position.y).catch((e) => {
        setActionError(e instanceof Error ? e.message : String(e));
      });
    },
    [projectId],
  );

  if (!projectId) {
    return <div style={{ padding: 16 }}>Выберите проект, чтобы собирать сценарии.</div>;
  }

  return (
    <div style={{ height: '80vh', display: 'flex' }}>
      <div style={{ flex: 1, position: 'relative' }}>
        {schemeError && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1, color: 'crimson' }}>{schemeError}</div>
        )}
        {rootScenario && (
          <div style={{ position: 'absolute', top: 8, left: 8, zIndex: 1, fontSize: 12, color: '#64748b' }}>
            Зелёная рамка — откуда сейчас можно вести следующую стрелку. Просто тяните дальше —
            промежуточные сценарии создаются сами.
          </div>
        )}
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onConnect={onConnect}
          onNodesChange={onNodesChange}
          onNodeDragStop={onNodeDragStop}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>

      <div style={{ width: 340, borderLeft: '1px solid #e2e8f0', padding: 12, overflowY: 'auto' }}>
        <div style={{ fontSize: 11, color: '#94a3b8', marginBottom: 4 }}>С чего начинается цепочка</div>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <select
            value={rootScenarioId ?? ''}
            onChange={(e) => setRootScenarioId(e.target.value || null)}
            disabled={scenarios.length === 0}
            style={{ flex: 1 }}
          >
            {scenarios.length === 0 && <option value="">Нет сценариев в этом проекте</option>}
            {scenarios.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <button onClick={() => setShowCreateScenario(true)}>+ Старт</button>
        </div>

        {rootScenario && (
          <button
            onClick={() => {
              if (!window.confirm(`Удалить сценарий "${rootScenario.name}" (начало цепочки)? Это необратимо.`)) return;
              setActionError(null);
              deleteScenario(rootScenario.id)
                .then(() => {
                  setScenarios((prev) => prev.filter((s) => s.id !== rootScenario.id));
                  setRootScenarioId(null);
                })
                .catch((e) => setActionError(e instanceof Error ? e.message : String(e)));
            }}
            style={{ marginBottom: 12, color: '#dc2626', width: '100%' }}
          >
            Удалить сценарий "{rootScenario.name}"
          </button>
        )}

        {actionError && <div style={{ color: '#dc2626', fontSize: 13, marginBottom: 12 }}>{actionError}</div>}

        {rootScenario && (
          <>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>Цепочка вызовов</div>
            <ol style={{ paddingLeft: 18, fontSize: 13 }}>
              {chainCallSteps.map(({ step, scenarioId }) => (
                <li key={step.id} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                  <span>→</span>
                  <select
                    value={step.calledEntryPointId ?? ''}
                    onChange={(e) => {
                      setActionError(null);
                      retargetStep(scenarioId, step.id, e.target.value)
                        .then(refetchChain)
                        .catch((err) => setActionError(err instanceof Error ? err.message : String(err)));
                    }}
                    style={{ flex: 1, fontSize: 12 }}
                  >
                    {scheme?.entryPoints
                      // Всё, что уже есть в цепочке где-либо — исключаем, кроме
                      // текущего выбора этого конкретного шага (иначе он бы
                      // пропал из списка) — та же защита от цикла, что и в onConnect.
                      .filter((ep) => ep.id === step.calledEntryPointId || !usedEntryPointIds.has(ep.id))
                      .map((ep) => (
                        <option key={ep.id} value={ep.id}>
                          {ep.name}
                        </option>
                      ))}
                  </select>
                  <button
                    onClick={() => {
                      setActionError(null);
                      deleteStep(scenarioId, step.id)
                        .then(refetchChain)
                        .catch((e) => setActionError(e instanceof Error ? e.message : String(e)));
                    }}
                    style={{ fontSize: 11, padding: '0 6px' }}
                    title="Удалить шаг"
                  >
                    ×
                  </button>
                </li>
              ))}
              {chainCallSteps.length === 0 && (
                <div style={{ color: '#94a3b8', listStyle: 'none' }}>Пока пусто — потяните стрелку на канвасе слева.</div>
              )}
            </ol>
            <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 8 }}>
              ALT/PARALLEL/RETRY/TIMEOUT и вложенные шаги здесь пока не редактируются — через API
              (см. docs/local-setup.md). Цель в другом проекте тоже пока не подхватится автоматически —
              переключите проект в шапке и продолжите оттуда вручную.
            </div>
          </>
        )}
      </div>

      {showCreateScenario && (
        <CreateScenarioModal
          onClose={() => setShowCreateScenario(false)}
          onCreated={(s) => {
            setScenarios((prev) => [...prev, s]);
            setRootScenarioId(s.id);
          }}
        />
      )}
    </div>
  );
}
