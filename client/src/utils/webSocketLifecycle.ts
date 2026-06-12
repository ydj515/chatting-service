export const shouldIgnoreWebSocketEvent = <T extends object>(
  currentSocket: T | null,
  eventSocket: T,
  intentionallyClosedSockets: WeakSet<T>,
): boolean => {
  return currentSocket !== eventSocket || intentionallyClosedSockets.has(eventSocket);
};
