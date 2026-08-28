import { getToken, logout, refresh } from './auth';

const BASE = '/api/v1';

// ApiExceptionHandler на бэкенде возвращает { timestamp, message, ... } с
// содержательным текстом ("Scheme for project not found: ...") — раньше эта
// функция его игнорировала и кидала голое "GET ... failed: 404", теряя
// именно то сообщение, ради которого backend вообще формирует структурный
// ответ на ошибку.
async function readErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = await res.json();
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      return body.message;
    }
  } catch {
    // тело не JSON или пустое — используем fallback ниже
  }
  return fallback;
}

// Ревью CTO, п.2.11: раньше не было ни заголовка Authorization, ни
// PATCH/DELETE вообще — контракт их описывает, backend их реализует, фронт
// ими не пользовался. Единая точка запроса вместо четырёх похожих функций.
//
// Access/refresh (эта итерация): access-токен теперь короткоживущий (15 мин,
// см. JwtService на бэкенде) — 401 от него больше не значит "сессия точно
// кончилась", а типично значит "пора освежить access по refresh-cookie".
// isRetry — защита от бесконечного цикла, если backend по какой-то причине
// продолжает отдавать 401 и ПОСЛЕ успешного refresh (не должно случаться, но
// без флага один такой баг на backend превратился бы в зависший цикл
// запросов на фронте, а не в понятную ошибку).
async function request<T>(method: string, path: string, body?: unknown, isRetry = false): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'same-origin',
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) {
    if (!isRetry && (await refresh())) {
      return request<T>(method, path, body, true);
    }
    // Либо повторный 401 после уже освежённого токена (backend всё равно не
    // принимает — реальный конец сессии), либо refresh-cookie сама протухла/
    // отсутствует (7-дневный sliding window истёк, либо это вообще первый
    // запрос без сессии). В обоих случаях сессия в памяти бесполезна.
    await logout();
    throw new Error('Сессия истекла, войдите заново');
  }
  if (!res.ok) throw new Error(await readErrorMessage(res, `${method} ${path} failed: ${res.status}`));
  // 204 (DELETE) — тела нет по определению. 202 (Стоп/Пауза/Продолжить —
  // RunController — эффект асинхронный, подтверждение приходит по WS, не в
  // теле ответа) — тела тоже нет, но нет и отдельного статус-кода под это,
  // поэтому проверяем содержимое, а не жёстко зашитый список кодов: res.json()
  // на пустом теле бросил бы SyntaxError ("Unexpected end of JSON input").
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>('GET', path);
}

export function apiPost<T>(path: string, body: unknown): Promise<T> {
  return request<T>('POST', path, body);
}

export function apiPatch<T>(path: string, body: unknown): Promise<T> {
  return request<T>('PATCH', path, body);
}

export function apiDelete<T = void>(path: string): Promise<T> {
  return request<T>('DELETE', path);
}
