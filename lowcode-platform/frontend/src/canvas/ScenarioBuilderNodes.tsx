import { Handle, NodeProps, Position } from 'reactflow';
import { EntryPoint } from '../api/types';

// Каждый entry point — отдельная "розетка" с двумя ручками (можно и принимать,
// и начинать стрелку с любого entry point — источник ограничивается не здесь,
// а в ScenarioBuilder.onConnect/expectedSourceNodeId — только от блока,
// в который зашли на предыдущем шаге, чтобы получалась цепочка, а не веер).
export interface EntryPointBlockData {
  label: string;
  entryPoints: EntryPoint[];
  isChainTail: boolean;
}

export function EntryPointBlockNode({ data }: NodeProps<EntryPointBlockData>) {
  return (
    <div
      style={{
        border: data.isChainTail ? '2px solid #16a34a' : '1px solid #94a3b8',
        borderRadius: 6,
        background: 'white',
        minWidth: 190,
        fontSize: 12,
      }}
    >
      <div
        style={{
          padding: '6px 10px',
          fontWeight: 600,
          borderBottom: '1px solid #e2e8f0',
          background: data.isChainTail ? '#f0fdf4' : '#f8fafc',
          borderRadius: '6px 6px 0 0',
        }}
      >
        {data.label}
      </div>
      {data.entryPoints.length === 0 && (
        <div style={{ padding: '6px 10px', color: '#94a3b8' }}>нет entry point — добавьте в "Редакторе схемы"</div>
      )}
      {data.entryPoints.map((ep) => (
        <div key={ep.id} style={{ position: 'relative', padding: '6px 10px', borderBottom: '1px solid #f1f5f9' }}>
          <Handle type="target" position={Position.Left} id={ep.id} style={{ background: '#2563eb' }} />
          {ep.name}
          <Handle type="source" position={Position.Right} id={ep.id} style={{ background: '#16a34a' }} />
        </div>
      ))}
    </div>
  );
}

export function StartNode({ data }: NodeProps<{ label: string; isChainTail: boolean }>) {
  return (
    <div
      style={{
        border: data.isChainTail ? '3px solid #16a34a' : '2px solid #86efac',
        borderRadius: 20,
        padding: '8px 16px',
        background: '#f0fdf4',
        fontSize: 12,
        fontWeight: 600,
        position: 'relative',
      }}
    >
      <Handle type="source" position={Position.Right} id="start" style={{ background: '#16a34a' }} />▶ {data.label}
    </div>
  );
}
