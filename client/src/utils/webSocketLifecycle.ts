export const shouldIgnoreWebSocketEvent = <T extends object>(
  currentSocket: T | null,
  eventSocket: T,
  intentionallyClosedSockets: WeakSet<T>,
): boolean => {
  return currentSocket !== eventSocket || intentionallyClosedSockets.has(eventSocket);
};

interface ReconnectDelayOptions {
  reconnectAttempts: number;
  maxReconnectAttempts: number;
  reconnectBaseDelayMs: number;
  reconnectMaxDelayMs: number;
}

export const nextReconnectDelayMs = ({
  reconnectAttempts,
  maxReconnectAttempts,
  reconnectBaseDelayMs,
  reconnectMaxDelayMs,
}: ReconnectDelayOptions): number | null => {
  if (reconnectAttempts >= maxReconnectAttempts) {
    return null;
  }

  return Math.min(
    reconnectBaseDelayMs * Math.pow(2, reconnectAttempts),
    reconnectMaxDelayMs,
  );
};
