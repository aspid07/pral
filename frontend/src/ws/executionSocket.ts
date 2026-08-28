import { Client, IFrame } from '@stomp/stompjs';

// Схема событий — см. api-contract.md, "WebSocket: события исполнения".
// Форма JSON зеркалит backend-записи в ExecutionEventPublisher (каждая несёт
// "type" как дискриминатор — все события идут в один топик /topic/runs/{runId}).
export type ExecutionEvent =
  | { type: 'RUN_STARTED'; runId: string; scenarioId: string }
  | {
      type: 'STEP_STARTED';
      runId: string;
      stepId: string;
      sourceEntryPointId: string;
      targetEntryPointId: string;
      label: string;
      kind: 'sync' | 'async' | 'external';
      parallelGroupId?: string;
      timeoutMs?: number;
    }
  | { type: 'CLUSTER_ENTERED'; runId: string; projectId: string }
  | { type: 'STEP_COMPLETED'; runId: string; stepId: string }
  | { type: 'STEP_RETRYING'; runId: string; stepId: string; attempt: number; maxAttempts: number }
  | { type: 'STEP_TIMEOUT'; runId: string; stepId: string }
  | { type: 'RUN_COMPLETED'; runId: string }
  | { type: 'RUN_ERROR'; runId: string; stepId?: string; message: string }
  // Стоп/Пауза (эта итерация) — stepId в RUN_PAUSED/RUN_STOPPED: последний
  // РЕАЛЬНО завершённый шаг на момент остановки (см. ExecutionEngine.checkpoint
  // на backend) — undefined, если движок встал ещё до самого первого шага.
  | { type: 'RUN_PAUSED'; runId: string; stepId?: string }
  | { type: 'RUN_RESUMED'; runId: string }
  | { type: 'RUN_STOPPED'; runId: string; stepId?: string };

/**
 * onConnectionError — отдельно от onEvent: сигнализирует, что живой канал
 * оборвался/не установился (это НЕ то же самое, что событие RUN_ERROR — тот
 * означает "сценарий упал", а это "мы перестали видеть, что с ним происходит").
 * Раньше таких колбэков не было вообще: неудачный handshake или обрыв
 * соединения посреди забега оставляли UI молча висеть в состоянии "Выполняется…"
 * без единого сигнала пользователю.
 */
export function subscribeToRun(
  runId: string,
  onEvent: (e: ExecutionEvent) => void,
  onConnectionError?: (message: string) => void,
): () => void {
  const client = new Client({
    brokerURL: `${location.origin.replace('http', 'ws')}/ws/runs`,
    // Best-effort реконнект на транзиентные обрывы сети. История событий за
    // время разрыва не реплеится (backend её не хранит) — если run уже
    // завершился к моменту переподключения, RUN_COMPLETED/RUN_ERROR можно и
    // не увидеть; частичное восстановление лучше, чем никакого.
    reconnectDelay: 4000,
    onConnect: () => {
      client.subscribe(`/topic/runs/${runId}`, (message) => {
        try {
          onEvent(JSON.parse(message.body));
        } catch (e) {
          onConnectionError?.(e instanceof Error ? e.message : 'Malformed event payload');
        }
      });
    },
    onStompError: (frame: IFrame) => {
      onConnectionError?.(frame.headers['message'] ?? 'STOMP protocol error');
    },
    onWebSocketError: () => {
      onConnectionError?.('WebSocket connection failed');
    },
  });
  client.activate();
  return () => client.deactivate();
}
