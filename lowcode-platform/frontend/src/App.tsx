import { useEffect, useState } from 'react';
import { SchemeCanvas } from './canvas/SchemeCanvas';
import { ScenarioBuilder } from './canvas/ScenarioBuilder';
import { fetchProjects } from './api/projects';
import { Project } from './api/types';
import { getCurrentUser, getToken, logout, onAuthChange, silentRestore } from './api/auth';
import { LoginScreen } from './auth/LoginScreen';
import { CreateProjectModal } from './creation/CreateProjectModal';

// Три экрана: "Редактор схемы" (блоки/entry points), "Сценарии" (сборка
// ScenarioStep стрелками — см. ScenarioBuilder.tsx, добавлен по фидбэку "не
// понимаю, как вообще создать сценарий"), "Запуск сценария" (визуализация
// исполнения).

type View = 'editor' | 'build' | 'run';

export default function App() {
  // Ревью CTO, п.2.11: backend требует аутентификации везде кроме /auth/** —
  // без этого гейта весь остальной UI сразу получал бы 401 на каждый запрос.
  // onAuthChange подписывается на api/auth.ts (модульная переменная токена в
  // памяти) — логин/логаут/протухание токена (401 в client.ts) сразу
  // перерисовывают этот компонент.
  const [authed, setAuthed] = useState(() => getToken() !== null);
  useEffect(() => onAuthChange(() => setAuthed(getToken() !== null)), []);

  // Access/refresh (эта итерация): access-токен живёт только в памяти и
  // теряется на F5, но refresh — в httpOnly-cookie, которая переживает
  // перезагрузку. Один молчаливый POST /auth/refresh на монтировании
  // превращает "F5 = разлогинило" (известное ограничение, см. api/auth.ts,
  // предыдущая версия комментария) в "F5 = сессия тихо восстановилась, пока
  // не истёк 7-дневный sliding window". restoringSession — чтобы за то
  // недолгое время, пока идёт этот запрос, экран не мигал LoginScreen для
  // человека, у которого сессия на самом деле жива.
  const [restoringSession, setRestoringSession] = useState(() => getToken() === null);
  useEffect(() => {
    if (getToken() !== null) {
      setRestoringSession(false);
      return;
    }
    silentRestore().finally(() => setRestoringSession(false));
  }, []);

  const [view, setView] = useState<View>('run');
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [projectsError, setProjectsError] = useState<string | null>(null);
  const [projectsLoaded, setProjectsLoaded] = useState(false);
  const [showCreateProject, setShowCreateProject] = useState(false);

  // И "Редактор схемы", и "Сценарии" привязаны к конкретному проекту —
  // загружаем список лениво при первом заходе в любой из них, один раз.
  const needsProjectPicker = view === 'editor' || view === 'build';

  useEffect(() => {
    if (!authed || !needsProjectPicker || projectsLoaded) return;
    fetchProjects()
      .then((page) => {
        setProjects(page.content);
        setProjectId((current) => current ?? page.content[0]?.id ?? null);
        setProjectsLoaded(true);
      })
      .catch((e) => setProjectsError(e instanceof Error ? e.message : String(e)));
  }, [authed, needsProjectPicker, projectsLoaded]);

  if (restoringSession) {
    return null; // короткий момент silentRestore() — не мигаем LoginScreen без нужды
  }

  if (!authed) {
    return <LoginScreen />;
  }

  const user = getCurrentUser();

  return (
    <div>
      <header style={{ display: 'flex', gap: 12, alignItems: 'center', padding: 8 }}>
        <button onClick={() => setView('editor')} disabled={view === 'editor'}>
          Редактор схемы
        </button>
        <button onClick={() => setView('build')} disabled={view === 'build'}>
          Сценарии
        </button>
        <button onClick={() => setView('run')} disabled={view === 'run'}>
          Запуск сценария
        </button>

        {needsProjectPicker && (
          <>
            <select
              value={projectId ?? ''}
              onChange={(e) => setProjectId(e.target.value || null)}
              disabled={projects.length === 0}
            >
              {projects.length === 0 && (
                <option value="">{projectsError ? 'Ошибка загрузки проектов' : 'Нет проектов'}</option>
              )}
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <button onClick={() => setShowCreateProject(true)}>+ Проект</button>
          </>
        )}

        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: '#64748b' }}>
          {user?.displayName}
          <button onClick={logout}>Выйти</button>
        </div>
      </header>

      {view === 'editor' && <SchemeCanvas mode="editor" projectId={projectId} />}
      {view === 'build' && <ScenarioBuilder projectId={projectId} />}
      {view === 'run' && <SchemeCanvas mode="run" projectId={projectId} />}

      {showCreateProject && (
        <CreateProjectModal
          onClose={() => setShowCreateProject(false)}
          onCreated={(p) => {
            setProjects((prev) => [...prev, p]);
            setProjectId(p.id);
          }}
        />
      )}
    </div>
  );
}
