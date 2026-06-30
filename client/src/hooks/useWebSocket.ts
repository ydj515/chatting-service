import { useState, useEffect, useRef, useCallback } from 'react';
import { WebSocketMessage } from '@/types/index.ts';
import { appConfig, buildWebSocketUrl } from '@/config/appConfig.ts';
import { webSocketTicketApi } from '@/services/api.ts';
import { nextReconnectDelayMs, shouldIgnoreWebSocketEvent } from '@/utils/webSocketLifecycle.ts';

interface UseWebSocketProps {
  sessionToken: string;
  onMessage?: (message: WebSocketMessage) => void;
  onConnect?: () => void;
  onDisconnect?: () => void;
  onError?: (error: string) => void;
}

interface UseWebSocketReturn {
  isConnected: boolean;
  lastMessage: WebSocketMessage | null;
  sendMessage: (message: WebSocketMessage) => boolean;
  connect: () => void;
  disconnect: () => void;
  error?: string;
}

export const useWebSocket = ({
  sessionToken,
  onMessage,
  onConnect,
  onDisconnect,
  onError,
}: UseWebSocketProps): UseWebSocketReturn => {
  const wsRef = useRef<WebSocket | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null);
  const [error, setError] = useState<string | undefined>(undefined);
  const reconnectAttempts = useRef(0);
  const reconnectTimeoutRef = useRef<number>();
  const isConnectingRef = useRef(false);
  const connectionAttemptRef = useRef(0);
  const intentionallyClosedSocketsRef = useRef<WeakSet<WebSocket>>(new WeakSet());

  // onMessage 콜백을 ref로 관리하여 항상 최신 함수를 참조하도록 함
  const onMessageRef = useRef(onMessage);
  useEffect(() => {
    onMessageRef.current = onMessage;
  }, [onMessage]);

  const onConnectRef = useRef(onConnect);
  useEffect(() => {
    onConnectRef.current = onConnect;
  }, [onConnect]);

  const onDisconnectRef = useRef(onDisconnect);
  useEffect(() => {
    onDisconnectRef.current = onDisconnect;
  }, [onDisconnect]);

  const onErrorRef = useRef(onError);
  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  const scheduleReconnect = useCallback((connectFn: () => void): boolean => {
    const delay = nextReconnectDelayMs({
      reconnectAttempts: reconnectAttempts.current,
      maxReconnectAttempts: appConfig.webSocket.maxReconnectAttempts,
      reconnectBaseDelayMs: appConfig.webSocket.reconnectBaseDelayMs,
      reconnectMaxDelayMs: appConfig.webSocket.reconnectMaxDelayMs,
    });

    if (delay === null) {
      setError('최대 재연결 시도 횟수를 초과했습니다.');
      return false;
    }

    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
    }

    reconnectTimeoutRef.current = setTimeout(() => {
      reconnectAttempts.current++;
      connectFn();
    }, delay);

    return true;
  }, []);

  /**
   * 커넥션 연결
   */
  const connect = useCallback(() => {
    if (!sessionToken) {
      console.warn("WebSocket connect: session token is missing, connection deferred.");
      return;
    }

    if (isConnectingRef.current) {
      console.log('WebSocket connection is already being prepared.');
      return;
    }

    if (
      wsRef.current?.readyState === WebSocket.OPEN ||
      wsRef.current?.readyState === WebSocket.CONNECTING
    ) {
      console.log('WebSocket already connected.');
      return;
    }

    // 이전 연결이 있다면 정리
    if (wsRef.current) {
      intentionallyClosedSocketsRef.current.add(wsRef.current);
      wsRef.current.close(1000, 'Replacing WebSocket connection');
      wsRef.current = null;
    }

    const attemptId = ++connectionAttemptRef.current;
    isConnectingRef.current = true;

    void (async () => {
      try {
        const ticket = await webSocketTicketApi.issue();
        if (attemptId !== connectionAttemptRef.current) {
          return;
        }

        const wsUrl = buildWebSocketUrl(ticket.ticket);
        const socket = new WebSocket(wsUrl);
        wsRef.current = socket;
        isConnectingRef.current = false;

        socket.onopen = () => {
          if (shouldIgnoreWebSocketEvent(wsRef.current, socket, intentionallyClosedSocketsRef.current)) {
            return;
          }

          console.log('✅ WebSocket connected');
          setIsConnected(true);
          setError(undefined);
          reconnectAttempts.current = 0;
          onConnectRef.current?.();
        };

        socket.onmessage = (event) => {
          if (shouldIgnoreWebSocketEvent(wsRef.current, socket, intentionallyClosedSocketsRef.current)) {
            return;
          }

          console.log('🔌 WebSocket message received:', event.data);
          try {
            const message: WebSocketMessage = JSON.parse(event.data);
            setLastMessage(message);
            onMessageRef.current?.(message);
          } catch (err) {
            console.error('Failed to parse WebSocket message:', err);
            setError('메시지 파싱 실패');
          }
        };

        socket.onclose = (event) => {
          if (shouldIgnoreWebSocketEvent(wsRef.current, socket, intentionallyClosedSocketsRef.current)) {
            return;
          }

          console.log(`🔌 WebSocket disconnected: Code=${event.code}, Reason=${event.reason}`);
          setIsConnected(false);
          wsRef.current = null;
          onDisconnectRef.current?.();

          // 정상적인 종료가 아닌 경우만 재연결 시도
          if (!appConfig.webSocket.normalCloseCodes.includes(event.code)) {
            scheduleReconnect(connect);
          }
        };

        socket.onerror = (event) => {
          if (shouldIgnoreWebSocketEvent(wsRef.current, socket, intentionallyClosedSocketsRef.current)) {
            return;
          }

          console.error('💥 WebSocket error:', event);
          setError('WebSocket 연결 오류');
          setIsConnected(false);
          onErrorRef.current?.(event.type);
        };

      } catch (err) {
        if (attemptId !== connectionAttemptRef.current) {
          return;
        }
        console.error('Failed to create WebSocket connection:', err);
        setError('WebSocket 연결 생성 실패');
        setIsConnected(false);
        onErrorRef.current?.('ticket');
        scheduleReconnect(connect);
      } finally {
        if (attemptId === connectionAttemptRef.current) {
          isConnectingRef.current = false;
        }
      }
    })();
  }, [scheduleReconnect, sessionToken]);

  const disconnect = useCallback(() => {
    reconnectAttempts.current = 0; // 수동 disconnect 시 재연결 시도 초기화
    connectionAttemptRef.current++;
    isConnectingRef.current = false;
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
    }

    if (wsRef.current) {
      intentionallyClosedSocketsRef.current.add(wsRef.current);
      wsRef.current.close(1000, 'User disconnected');
      wsRef.current = null;
    }

    setIsConnected(false);
  }, []);

  const sendMessage = useCallback((message: WebSocketMessage) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      try {
        const messageStr = JSON.stringify(message);
        wsRef.current.send(messageStr);
        return true; // 전송 성공
      } catch (err) {
        console.error('Failed to send WebSocket message:', err);
        setError('메시지 전송 실패');
        return false; // 전송 실패
      }
    } else {
      console.warn('WebSocket is not connected');
      setError('WebSocket이 연결되지 않았습니다');
      return false; // 연결되지 않음
    }
  }, []);

  // 컴포넌트 마운트 시 자동 연결 (session token 변경 시에도 재연결)
  useEffect(() => {
    connect();

    return () => {
      disconnect();
    };
  }, [sessionToken, connect, disconnect]);

  // 페이지 언로드 시 정리
  useEffect(() => {
    const handleBeforeUnload = () => {
      disconnect();
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    };
  }, [disconnect]);

  return {
    isConnected,
    lastMessage,
    sendMessage,
    connect,
    disconnect,
    error,
  };
};
