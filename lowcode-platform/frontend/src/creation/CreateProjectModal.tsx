import { useState } from 'react';
import { Modal } from './Modal';
import { createProject } from '../api/creation';
import { Project } from '../api/types';

export function CreateProjectModal({ onClose, onCreated }: { onClose: () => void; onCreated: (p: Project) => void }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setPending(true);
    setError(null);
    createProject(name, description)
      .then((p) => {
        onCreated(p);
        onClose();
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setPending(false));
  };

  return (
    <Modal title="Новый проект" onClose={onClose}>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <input placeholder="Название" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
        <textarea
          placeholder="Описание (необязательно)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={3}
        />
        {error && <div style={{ color: '#dc2626', fontSize: 13 }}>{error}</div>}
        <button type="submit" disabled={pending}>
          {pending ? 'Создаю…' : 'Создать'}
        </button>
      </form>
    </Modal>
  );
}
