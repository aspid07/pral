// Ревью CTO, п.2.11: фронтенд вообще не знал про аутентификацию — не было
// ни экрана логина, ни хранения токена, ни заголовка Authorization.
//
// Хранение ACCESS-токена — ОСОЗНАННЫЙ выбор, не дефолт "как получилось":
// в памяти (модульная переменная), НЕ localStorage/sessionStorage — оба
// уязвимы к XSS (любой инжектированный скрипт может их прочитать).
//
// Access/refresh (эта итерация): backend теперь выдаёт ДВА токена —
// короткоживущий access (в теле JSON-ответа, попадает сюда, в память) и
// refresh (httpOnly-cookie, недоступна из JS вообще — см. AuthController на
// бэкенде). Раньше единственный токен жил только в памяти вкладки, и F5
// разлогинивал — целевое решение "access в памяти + refresh в httpOnly-cookie"
// было явно описано как план (см. README, "Stage 4"/backlog) именно ради
// того, чтобы решить эту проблему, не жертвуя защитой от XSS: refresh
// пережимвает перезагрузку страницы сам (браузер хранит cookie), а
// восстановление access-токена после F5 делает silentRestore() ниже, не
// требуя от пользователя повторного ввода пароля — в пределах sliding
// 7-дневного окна (см. RefreshTokenService на бэкенде).
let currentToken: string | null = null;
let currentUser: { userId: string; email: string; displayName: string } | null = null;
const listeners = new Set<() => void>();

export function getToken(): string | null {
  return currentToken;
}

export function getCurrentUser() {
  return currentUser;
}

export function onAuthChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function setSession(token: string | null, user: typeof currentUser) {
  currentToken = token;
  currentUser = user;
  listeners.forEach((l) => l());
}

interface TokenResponse {
  accessToken: string;
  userId: string;
  email: string;
  displayName: string;
}

const AUTH_BASE = '/api/v1/auth';

export async function login(email: string, password: string): Promise<void> {
  const res = await fetch(`${AUTH_BASE}/login`, {
    method: 'POST',
    credentials: 'same-origin', // явно: без этого refresh-cookie не будет ни отправлена, ни принята
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    throw new Error(res.status === 401 ? 'Неверный email или пароль' : `Ошибка входа: ${res.status}`);
  }
  const data: TokenResponse = await res.json();
  setSession(data.accessToken, { userId: data.userId, email: data.email, displayName: data.displayName });
}

export async function register(email: string, password: string, displayName: string): Promise<void> {
  const res = await fetch(`${AUTH_BASE}/register`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, displayName }),
  });
  if (!res.ok) {
    throw new Error(res.status === 409 ? 'Этот email уже зарегистрирован' : `Ошибка регистрации: ${res.status}`);
  }
  const data: TokenResponse = await res.json();
  setSession(data.accessToken, { userId: data.userId, email: data.email, displayName: data.displayName });
}

/**
 * Обменивает refresh-cookie (её саму мы даже не видим — браузер прикладывает
 * её сам) на новый access-токен. Используется в двух местах:
 * 1) silentRestore() при монтировании App — "переживает" перезагрузку страницы;
 * 2) client.ts — по 401 от истёкшего access-токена, один раз, перед retry.
 *
 * Возвращает false вместо того, чтобы бросать — отсутствие/протухание
 * refresh-сессии здесь ОЖИДАЕМЫЙ штатный исход (сессии не было, либо истекло
 * все 7 дней sliding-окна), не аварийная ситуация, которую нужно логировать
 * как ошибку в каждом месте вызова.
 */
export async function refresh(): Promise<boolean> {
  try {
    const res = await fetch(`${AUTH_BASE}/refresh`, { method: 'POST', credentials: 'same-origin' });
    if (!res.ok) {
      setSession(null, null);
      return false;
    }
    const data: TokenResponse = await res.json();
    setSession(data.accessToken, { userId: data.userId, email: data.email, displayName: data.displayName });
    return true;
  } catch {
    // Сеть недоступна — не разлогиниваем агрессивно: текущий access-токен
    // (если ещё не истёк) продолжает работать сам по себе до следующей попытки.
    return false;
  }
}

/**
 * Once-per-app-load попытка восстановить сессию по refresh-cookie, не
 * трогая уже существующий access-токен в памяти (актуально для HMR в dev —
 * незачем дёргать backend, если токен и так есть). Вызывается из App.tsx при
 * монтировании — см. комментарий там про экран restoringSession.
 */
export async function silentRestore(): Promise<void> {
  if (currentToken !== null) return;
  await refresh();
}

export async function logout(): Promise<void> {
  setSession(null, null);
  try {
    await fetch(`${AUTH_BASE}/logout`, { method: 'POST', credentials: 'same-origin' });
  } catch {
    // Backend недоступен — локальная сессия уже сброшена строкой выше;
    // серверная refresh-запись просто доживёт до естественного истечения TTL.
  }
}
