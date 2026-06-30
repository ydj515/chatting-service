import React, { useCallback, useEffect, useState } from 'react';
import type { ChatRoom, LoginResponse, Notification } from '@/types/index.ts';
import Layout from '@/components/layout/Layout.tsx';
import AuthGate from '@/components/AuthGate.tsx';
import ChatWorkspace from '@/components/ChatWorkspace.tsx';
import LoadingScreen from '@/components/LoadingScreen.tsx';
import { setSessionToken as setApiSessionToken } from '@/services/api.ts';
import { appConfig } from '@/config/appConfig.ts';
import { useServerHealth } from '@/hooks/useServerHealth.ts';
import { useChatStore } from '@/stores/chatStore.ts';
import { isApiSessionReady } from '@/utils/authSession.ts';

function ChatPage() {
  const currentUser = useChatStore((state) => state.currentUser);
  const sessionToken = useChatStore((state) => state.sessionToken);
  const selectedChatRoom = useChatStore((state) => state.selectedChatRoom);
  const notifications = useChatStore((state) => state.notifications);
  const isInitializing = useChatStore((state) => state.isInitializing);
  const lastErrorTime = useChatStore((state) => state.lastErrorTime);
  const hydrateAuth = useChatStore((state) => state.hydrateAuth);
  const login = useChatStore((state) => state.login);
  const logout = useChatStore((state) => state.logout);
  const selectChatRoom = useChatStore((state) => state.selectChatRoom);
  const addNotification = useChatStore((state) => state.addNotification);
  const removeNotification = useChatStore((state) => state.removeNotification);
  const setLastErrorTime = useChatStore((state) => state.setLastErrorTime);
  const { serverStatus, isError: healthCheckFailed, errorUpdatedAt } = useServerHealth();
  const [syncedSessionToken, setSyncedSessionToken] = useState<string | null>(null);

  useEffect(() => {
    hydrateAuth(localStorage);
  }, [hydrateAuth]);

  useEffect(() => {
    setApiSessionToken(sessionToken);
    setSyncedSessionToken(sessionToken);
  }, [sessionToken]);

  const apiSessionReady = isApiSessionReady(currentUser, sessionToken, syncedSessionToken);

  const enqueueNotification = useCallback(
    (notification: Notification) => {
      addNotification(notification);

      const autoRemoveTime = notification.type === 'error'
        ? appConfig.notification.errorAutoRemoveMs
        : appConfig.notification.autoRemoveMs;
      setTimeout(() => {
        removeNotification(notification.id);
      }, autoRemoveTime);
    },
    [addNotification, removeNotification],
  );

  useEffect(() => {
    if (!healthCheckFailed || errorUpdatedAt === 0) {
      return;
    }

    const now = Date.now();
    if (now - lastErrorTime <= appConfig.notification.dedupWindowMs) {
      return;
    }

    enqueueNotification({
      id: now.toString(),
      type: 'error',
      title: '서버 연결 오류',
      message: '서버에 연결할 수 없습니다. 잠시 후 자동으로 재시도됩니다.',
      timestamp: now,
      read: false,
    });
    setLastErrorTime(now);
  }, [enqueueNotification, errorUpdatedAt, healthCheckFailed, lastErrorTime, setLastErrorTime]);

  const handleError = useCallback(
    (errorMessage: string) => {
      enqueueNotification({
        id: Date.now().toString(),
        type: 'error',
        title: '오류',
        message: errorMessage,
        timestamp: Date.now(),
        read: false,
      });
    },
    [enqueueNotification],
  );

  const handleSuccess = useCallback(
    (message: string) => {
      enqueueNotification({
        id: Date.now().toString(),
        type: 'system',
        title: '성공',
        message,
        timestamp: Date.now(),
        read: false,
      });
    },
    [enqueueNotification],
  );

  const handleLogin = useCallback(
    (response: LoginResponse) => {
      setApiSessionToken(response.sessionToken);
      setSyncedSessionToken(response.sessionToken);
      login(response, localStorage);
      handleSuccess(`${response.user.displayName}님, 환영합니다!`);
    },
    [handleSuccess, login],
  );

  const handleLogout = useCallback(() => {
    setApiSessionToken(null);
    setSyncedSessionToken(null);
    logout(localStorage);
    handleSuccess('로그아웃되었습니다.');
  }, [handleSuccess, logout]);

  const handleChatRoomSelect = useCallback(
    (chatRoom: ChatRoom) => {
      selectChatRoom(chatRoom, localStorage);
    },
    [selectChatRoom],
  );

  if (isInitializing || !apiSessionReady) {
    return <LoadingScreen />;
  }

  return (
    <Layout
      notifications={notifications}
      isAuthenticated={Boolean(currentUser)}
      onLogout={handleLogout}
      onDismissNotification={removeNotification}
    >
      {!currentUser ? (
        <AuthGate
          serverStatus={serverStatus}
          onLogin={handleLogin}
          onError={handleError}
        />
      ) : (
        <ChatWorkspace
          currentUser={currentUser}
          selectedChatRoom={selectedChatRoom}
          sessionToken={sessionToken}
          onChatRoomSelect={handleChatRoomSelect}
          onError={handleError}
          onSuccess={handleSuccess}
        />
      )}
    </Layout>
  );
}

export default ChatPage;
