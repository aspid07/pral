import { useEffect, useState } from 'react';
import { Modal } from './Modal';
import { createBlock, fetchBlockTypes } from '../api/creation';
import { BlockInstance, BlockType } from '../api/types';

export function CreateBlockModal({
  projectId,
  existingCount,
  onClose,
  onCreated,
}: {
  projectId: string;
  existingCount: number;
  onClose: () => void;
  onCreated: (b: BlockInstance) => void;
}) {
  const [blockTypes, setBlockTypes] = useState<BlockType[]>([]);
  const [blockTypeId, setBlockTypeId] = useState('');
  const [label, setLabel] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    fetchBlockTypes()
      .then((types) => {
        setBlockTypes(types);
        setBlockTypeId((current) => current || types[0]?.id || '');
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setPending(true);
    setError(null);
    // Координаты не спрашиваем у пользователя — просто раскладываем новые
    // блоки по сетке слева направо; передвинуть руками можно сразу на канвасе
    // (перетаскивание в editor mode уже работает, PATCH .../blocks/{id} — TODO,
    // см. README про то, что drag пока не персистится).
    const x = 100 + (existingCount % 5) * 180;
    const y = 100 + Math.floor(existingCount / 5) * 140;
    createBlock(projectId, blockTypeId, label, x, y)
      .then((b) => {
        onCreated(b);
        onClose();
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setPending(false));
  };

  return (
    <Modal title="Новый блок" onClose={onClose}>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <input placeholder="Название блока" value={label} onChange={(e) => setLabel(e.target.value)} required autoFocus />
        <select value={blockTypeId} onChange={(e) => setBlockTypeId(e.target.value)} required>
          {blockTypes.map((t) => (
            <option key={t.id} value={t.id}>
              {t.displayName}
            </option>
          ))}
        </select>
        {error && <div style={{ color: '#dc2626', fontSize: 13 }}>{error}</div>}
        <button type="submit" disabled={pending || !blockTypeId}>
          {pending ? 'Создаю…' : 'Создать'}
        </button>
      </form>
    </Modal>
  );
}
