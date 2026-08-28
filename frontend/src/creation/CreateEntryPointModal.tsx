import { useState } from 'react';
import { Modal } from './Modal';
import { createEntryPoint } from '../api/creation';
import { EntryPoint, EntryPointKind } from '../api/types';

const KINDS: { value: EntryPointKind; label: string }[] = [
  { value: 'SYNC_METHOD', label: 'Синхронный метод' },
  { value: 'ASYNC_EVENT', label: 'Асинхронное событие' },
  { value: 'WEBSOCKET_CHANNEL', label: 'WebSocket-канал' },
];

export function CreateEntryPointModal({
  blockId,
  blockLabel,
  onClose,
  onCreated,
}: {
  blockId: string;
  blockLabel: string;
  onClose: () => void;
  onCreated: (ep: EntryPoint) => void;
}) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState<EntryPointKind>('SYNC_METHOD');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setPending(true);
    setError(null);
    createEntryPoint(blockId, name, kind)
      .then((ep) => {
        onCreated(ep);
        onClose();
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setPending(false));
  };

  return (
    <Modal title={`Новый entry point — ${blockLabel}`} onClose={onClose}>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <input
          placeholder="Например, POST /orders"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
          autoFocus
        />
        <select value={kind} onChange={(e) => setKind(e.target.value as EntryPointKind)}>
          {KINDS.map((k) => (
            <option key={k.value} value={k.value}>
              {k.label}
            </option>
          ))}
        </select>
        {error && <div style={{ color: '#dc2626', fontSize: 13 }}>{error}</div>}
        <button type="submit" disabled={pending}>
          {pending ? 'Создаю…' : 'Создать'}
        </button>
      </form>
    </Modal>
  );
}
