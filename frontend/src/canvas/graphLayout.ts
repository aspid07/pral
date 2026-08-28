import ELK, { ElkNode } from 'elkjs/lib/elk.bundled.js';
import { Edge, Node } from 'reactflow';
import { ScenarioGraph } from '../api/types';

const elk = new ELK();

const BLOCK_WIDTH = 180;
const BLOCK_HEIGHT = 52;
// top больше остальных — под заголовок проекта (см. ProjectGroupNode в SchemeCanvas).
const PROJECT_PADDING = 'top=40,left=16,bottom=16,right=16';

function projectIdOfBlock(graph: ScenarioGraph, blockId: string): string | undefined {
  return graph.projects.find((p) => p.blocks.some((b) => b.id === blockId))?.id;
}

/**
 * Компонует граф участников сценария в elkjs (compound-раскладка: проекты —
 * контейнеры верхнего уровня, блоки — вложенные узлы) и возвращает готовые
 * react-flow Node[]/Edge[]. Позиции блоков elk уже отдаёт относительно
 * родителя-проекта — это ровно то, что ожидает react-flow при parentNode/extent.
 */
export async function layoutScenarioGraph(graph: ScenarioGraph): Promise<{ nodes: Node[]; edges: Edge[] }> {
  if (graph.projects.length === 0) {
    return { nodes: [], edges: [] };
  }

  const elkGraph: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.spacing.nodeNode': '40',
      'elk.layered.spacing.nodeNodeBetweenLayers': '90',
    },
    children: graph.projects.map((project) => {
      const blockIds = new Set(project.blocks.map((b) => b.id));
      const internalEdges = graph.edges.filter(
        (e) => blockIds.has(e.sourceBlockId) && blockIds.has(e.targetBlockId),
      );
      return {
        id: project.id,
        layoutOptions: { 'elk.padding': `[${PROJECT_PADDING}]` },
        children: project.blocks.map((block) => ({ id: block.id, width: BLOCK_WIDTH, height: BLOCK_HEIGHT })),
        edges: internalEdges.map((e, i) => ({
          id: `${project.id}-e${i}`,
          sources: [e.sourceBlockId],
          targets: [e.targetBlockId],
        })),
      };
    }),
    edges: graph.edges
      .filter((e) => projectIdOfBlock(graph, e.sourceBlockId) !== projectIdOfBlock(graph, e.targetBlockId))
      .map((e, i) => ({ id: `cross-e${i}`, sources: [e.sourceBlockId], targets: [e.targetBlockId] })),
  };

  const layout = await elk.layout(elkGraph);

  const nodes: Node[] = [];
  for (const projectLayout of layout.children ?? []) {
    const project = graph.projects.find((p) => p.id === projectLayout.id);
    if (!project) continue;

    nodes.push({
      id: project.id,
      type: 'projectGroup',
      position: { x: projectLayout.x ?? 0, y: projectLayout.y ?? 0 },
      style: {
        width: projectLayout.width,
        height: projectLayout.height,
        background: 'rgba(148, 163, 184, 0.08)',
        border: '1.5px dashed #94a3b8',
        borderRadius: 8,
      },
      data: { label: project.name },
      selectable: false,
      draggable: false,
    });

    for (const blockLayout of projectLayout.children ?? []) {
      const block = project.blocks.find((b) => b.id === blockLayout.id);
      if (!block) continue;
      nodes.push({
        id: block.id,
        parentNode: project.id,
        extent: 'parent',
        position: { x: blockLayout.x ?? 0, y: blockLayout.y ?? 0 },
        data: { label: `${block.label}${block.blockTypeCode ? ` · ${block.blockTypeCode}` : ''}` },
        style: { width: BLOCK_WIDTH, height: BLOCK_HEIGHT },
      });
    }
  }

  const edges: Edge[] = graph.edges.map((e, i) => ({
    id: `edge-${i}`,
    source: e.sourceBlockId,
    target: e.targetBlockId,
    label: e.label ?? undefined,
    // 'token' — кастомный edge (см. TokenEdge.tsx), рисует бегущий по стрелке
    // токен, когда data.active === true (проставляет SchemeCanvas по приходу
    // STEP_STARTED/STEP_COMPLETED). sourceEntryPointId/targetEntryPointId —
    // чтобы найти ТУ САМУЮ стрелку среди возможных параллельных рёбер между
    // теми же двумя блоками (граф дедуплицирует рёбра по паре entry point,
    // а не по паре блоков).
    type: 'token',
    data: { sourceEntryPointId: e.sourceEntryPointId, targetEntryPointId: e.targetEntryPointId },
  }));

  return { nodes, edges };
}
