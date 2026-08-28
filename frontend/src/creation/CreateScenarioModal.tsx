import { useEffect, useState } from 'react';
import { Modal } from './Modal';
import { fetchProjects, fetchScheme } from '../api/projects';
import { createScenario } from '../api/creation';
import { EntryPoint, Project, Scenario } from '../api/types';

export function CreateScenarioModal({ onClose, onCreated }: { onClose: () => void; onCreated: (s: Scenario) => void }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState('');
  const [entryPoints, setEntryPoints] = useState<EntryPoint[]>([]);
  const [entryPointId, setEntryPointId] = useState('');
  const [name, setName] = useState('');
  const [loadingEntryPoints, setLoadingEntryPoints] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    fetchProjects()
      .then((page) => {
        setProjects(page.content);
        setProjectId((current) => current || page.content[0]?.id || '');
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  useEffect(() => {
    if (!projectId) return;
    setEntryPoints([]);
    setEntryPointId('');
    setLoadingEntryPoints(true);
    fetchScheme(projectId)
      .then((scheme) => {
        setEntryPoints(scheme.entryPoints);
        setEntryPointId(scheme.entryPoints[0]?.id ?? '');
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setLoadingEntryPoints(false));
  }, [projectId]);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setPending(true);
    setError(null);
    createScenario(name, entryPointId)
      .then((s) => {
        onCreated(s);
        onClose();
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setPending(false));
  };

  return (
    <Modal title="Новый сценарий" onClose={onClose}>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <input placeholder="Название сценария" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />

        <label style={{ fontSize: 12, color: '#64748b' }}>Проект</label>
        <select value={projectId} onChange={(e) => setProjectId(e.target.value)} disabled={projects.length === 0}>
          {projects.length === 0 && <option value="">Нет доступных проектов</option>}
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>

        <label style={{ fontSize: 12, color: '#64748b' }}>Entry point, который реализует сценарий</label>
        <select
          value={entryPointId}
          onChange={(e) => setEntryPointId(e.target.value)}
          disabled={loadingEntryPoints || entryPoints.length === 0}
        >
          {loadingEntryPoints && <option value="">Загрузка…</option>}
          {!loadingEntryPoints && entryPoints.length === 0 && (
            <option value="">В этом проекте нет ни одного entry point</option>
          )}
          {entryPoints.map((ep) => (
            <option key={ep.id} value={ep.id}>
              {ep.name}
            </option>
          ))}
        </select>
        {!loadingEntryPoints && projectId && entryPoints.length === 0 && (
          <div style={{ fontSize: 12, color: '#64748b' }}>
            Сначала создайте блок и entry point на нём в этом проекте (Редактор схемы).
          </div>
        )}

        {error && <div style={{ color: '#dc2626', fontSize: 13 }}>{error}</div>}
        <button type="submit" disabled={pending || !entryPointId}>
          {pending ? 'Создаю…' : 'Создать'}
        </button>
      </form>
    </Modal>
  );
}
