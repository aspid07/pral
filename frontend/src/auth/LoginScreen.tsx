import { useState } from 'react';
import { login, register } from '../api/auth';

// Ревью CTO, п.2.11: backend требует аутентификации везде кроме /auth/** —
// без этого экрана фронтенд не может пройти дальше стартовой загрузки
// (любой запрос вернёт 401).
export function LoginScreen() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setPending(true);
    const action = mode === 'login' ? login(email, password) : register(email, password, displayName);
    action.catch((err) => setError(err instanceof Error ? err.message : String(err))).finally(() => setPending(false));
    // onAuthChange (см. api/auth.ts) сам обновит App.tsx при успехе — здесь
    // ничего дополнительно возвращать/устанавливать не нужно.
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: '100vh',
        background: '#f8fafc',
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          background: 'white',
          padding: 32,
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          width: 320,
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        <h2 style={{ margin: 0, marginBottom: 8 }}>{mode === 'login' ? 'Вход' : 'Регистрация'}</h2>

        {mode === 'register' && (
          <input
            type="text"
            placeholder="Имя"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
          />
        )}
        <input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input
          type="password"
          placeholder="Пароль (минимум 8 символов)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          minLength={8}
          required
        />

        {error && <div style={{ color: '#dc2626', fontSize: 13 }}>{error}</div>}

        <button type="submit" disabled={pending}>
          {pending ? 'Подождите…' : mode === 'login' ? 'Войти' : 'Зарегистрироваться'}
        </button>

        <button
          type="button"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login');
            setError(null);
          }}
          style={{ background: 'none', border: 'none', color: '#2563eb', cursor: 'pointer', fontSize: 13 }}
        >
          {mode === 'login' ? 'Нет аккаунта? Зарегистрироваться' : 'Уже есть аккаунт? Войти'}
        </button>
      </form>
    </div>
  );
}
