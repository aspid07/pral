import { BaseEdge, EdgeLabelRenderer, EdgeProps, getBezierPath } from 'reactflow';

// Данные конкретного ребра (см. graphLayout.ts) + то, что подмешивает
// SchemeCanvas при рендере (active/activeLabel) — см. renderedRunEdges.
export interface TokenEdgeData {
  sourceEntryPointId?: string;
  targetEntryPointId?: string;
  active?: boolean;
  activeLabel?: string;
  // Длительность одного прохода токена по стрелке — синхронизирована с
  // playbackSpeedMs (см. playbackConfig.ts), чтобы токен не бежал быстрее/
  // медленнее, чем меняется подсветка блоков.
  durationMs?: number;
}

/**
 * "Живость" схемы (см. vision-and-scope.md) — токен, бегущий по стрелке
 * вызова, а не просто подсветка блоков. Реализовано через SVG <animateMotion>
 * вдоль того же path, что рисует саму стрелку — стандартный паттерн для
 * react-flow (нет built-in компонента под это, только BaseEdge/getBezierPath
 * как строительные блоки).
 */
export function TokenEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style,
  markerEnd,
  label,
  data,
}: EdgeProps<TokenEdgeData>) {
  const [edgePath] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  const durationSec = (data?.durationMs ?? 900) / 1000;

  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} style={style} label={label} />
      {data?.active && (
        <>
          <circle r={6} fill="#d97706" stroke="#78350f" strokeWidth={1}>
            <animateMotion dur={`${durationSec}s`} repeatCount="indefinite" path={edgePath} />
          </circle>
          {data.activeLabel && (
            <EdgeLabelRenderer>
              {/* EdgeLabelRenderer рисует в фиксированной точке (не следует за
                  токеном — синхронизировать HTML-оверлей с SMIL-анимацией пути
                  без доп. JS-тикера не выйдет), поэтому подпись — рядом с самим
                  ребром, а не "приклеена" к движущейся точке. Для MVP этого
                  достаточно: видно, ЧТО выполняется, пока стрелка активна. */}
              <div
                style={{
                  position: 'absolute',
                  transform: `translate(-50%, -50%) translate(${(sourceX + targetX) / 2}px, ${
                    (sourceY + targetY) / 2 - 14
                  }px)`,
                  fontSize: 11,
                  fontWeight: 600,
                  color: '#78350f',
                  background: '#fef3c7',
                  padding: '1px 6px',
                  borderRadius: 4,
                  whiteSpace: 'nowrap',
                  pointerEvents: 'none',
                }}
              >
                {data.activeLabel}
              </div>
            </EdgeLabelRenderer>
          )}
        </>
      )}
    </>
  );
}
