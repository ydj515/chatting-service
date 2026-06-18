import React, { useState, useEffect, useCallback } from 'react';
import { User, ChatRoom, LoginResponse, Notification } from './types/index';
import LoginForm from './components/LoginForm.tsx';
import ChatRoomList from './components/ChatRoomList.tsx';
import { ChatWindow } from './components/ChatWindow.tsx';
import InfoTooltip from './components/ui/InfoTooltip.tsx';
import { healthApi, setSessionToken as setApiSessionToken } from './services/api.ts';
import { appConfig } from './config/appConfig.ts';
import { 
  AlertCircle, 
  CheckCircle, 
  X,
  LogOut,
  Moon,
  Sun
} from 'lucide-react';

const parseSessionExpiryMillis = (expiresAt: string): number => {
  const hasTimeZone = /(?:z|[+-]\d{2}:\d{2})$/i.test(expiresAt);
  const normalizedExpiresAt = hasTimeZone ? expiresAt : `${expiresAt}Z`;
  const parsed = Date.parse(normalizedExpiresAt);
  return Number.isFinite(parsed) ? parsed : 0;
};

function App() {
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [selectedChatRoom, setSelectedChatRoom] = useState<ChatRoom | null>(null);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [serverStatus, setServerStatus] = useState<'checking' | 'online' | 'offline'>('checking');
  const [lastErrorTime, setLastErrorTime] = useState<number>(0);
  const [healthCheckInterval, setHealthCheckInterval] = useState<number>(appConfig.healthCheck.intervalMs);
  const [consecutiveErrors, setConsecutiveErrors] = useState<number>(0);
  const [isInitializing, setIsInitializing] = useState<boolean>(true);
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    return (localStorage.getItem('chat_theme') as 'light' | 'dark') || 
           (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  });

  // localStorage 키 상수
  const STORAGE_KEYS = {
    USER: 'chat_current_user',
    SESSION_TOKEN: 'chat_session_token',
    SESSION_EXPIRES_AT: 'chat_session_expires_at',
    SELECTED_ROOM: 'chat_selected_room'
  };

  // localStorage에서 사용자 정보 불러오기
  const loadUserFromStorage = useCallback(() => {
    try {
      const savedUser = localStorage.getItem(STORAGE_KEYS.USER);
      const savedToken = localStorage.getItem(STORAGE_KEYS.SESSION_TOKEN);
      const savedTokenExpiresAt = localStorage.getItem(STORAGE_KEYS.SESSION_EXPIRES_AT);
      const savedRoom = localStorage.getItem(STORAGE_KEYS.SELECTED_ROOM);
      
      if (savedUser && savedToken && savedTokenExpiresAt && parseSessionExpiryMillis(savedTokenExpiresAt) > Date.now()) {
        const user = JSON.parse(savedUser);
        setCurrentUser(user);
        setSessionToken(savedToken);
        setApiSessionToken(savedToken);
        
        if (savedRoom) {
          const room = JSON.parse(savedRoom);
          setSelectedChatRoom(room);
        }
      }
    } catch (error) {
      console.error('Failed to load user from storage:', error);
      // 손상된 데이터 제거
      localStorage.removeItem(STORAGE_KEYS.USER);
      localStorage.removeItem(STORAGE_KEYS.SESSION_TOKEN);
      localStorage.removeItem(STORAGE_KEYS.SESSION_EXPIRES_AT);
      localStorage.removeItem(STORAGE_KEYS.SELECTED_ROOM);
    } finally {
      setIsInitializing(false);
    }
  }, []);

  // localStorage에 사용자 정보 저장
  const saveAuthToStorage = useCallback((response: LoginResponse | null) => {
    try {
      if (response) {
        localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(response.user));
        localStorage.setItem(STORAGE_KEYS.SESSION_TOKEN, response.sessionToken);
        localStorage.setItem(STORAGE_KEYS.SESSION_EXPIRES_AT, response.expiresAt);
      } else {
        localStorage.removeItem(STORAGE_KEYS.USER);
        localStorage.removeItem(STORAGE_KEYS.SESSION_TOKEN);
        localStorage.removeItem(STORAGE_KEYS.SESSION_EXPIRES_AT);
        localStorage.removeItem(STORAGE_KEYS.SELECTED_ROOM);
      }
    } catch (error) {
      console.error('Failed to save user to storage:', error);
    }
  }, []);

  // localStorage에 선택된 채팅방 저장
  const saveSelectedRoomToStorage = useCallback((room: ChatRoom | null) => {
    try {
      if (room && currentUser) {
        localStorage.setItem(STORAGE_KEYS.SELECTED_ROOM, JSON.stringify(room));
      } else {
        localStorage.removeItem(STORAGE_KEYS.SELECTED_ROOM);
      }
    } catch (error) {
      console.error('Failed to save selected room to storage:', error);
    }
  }, [currentUser]);

  // 앱 초기화 시 localStorage에서 데이터 로드
  useEffect(() => {
    loadUserFromStorage();
  }, [loadUserFromStorage]);

  // 테마 적용 (DOM 클래스만 토글 — 저장은 수동 토글 시에만)
  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }, [theme]);

  // OS 테마 변경 실시간 반영 — 사용자가 직접 토글한 적이 없을 때만 OS를 따른다.
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (event: MediaQueryListEvent) => {
      if (!localStorage.getItem('chat_theme')) {
        setTheme(event.matches ? 'dark' : 'light');
      }
    };
    mq.addEventListener?.('change', handler);
    return () => mq.removeEventListener?.('change', handler);
  }, []);

  const toggleTheme = () => {
    setTheme(prev => {
      const next = prev === 'light' ? 'dark' : 'light';
      try {
        localStorage.setItem('chat_theme', next);
      } catch {
        // localStorage 비활성 환경은 무시
      }
      return next;
    });
  };

  // 서버 상태 확인
  const checkServerHealth = useCallback(async () => {
    try {
      await healthApi.check();
      setServerStatus('online');
      setConsecutiveErrors(0);
      setHealthCheckInterval(appConfig.healthCheck.intervalMs);
    } catch (error) {
      setServerStatus('offline');
      setConsecutiveErrors(prev => {
        const newErrorCount = prev + 1;
        // 연속 에러 시 헬스체크 간격을 점진적으로 늘림 (최대 5분)
        const newInterval = Math.min(
          appConfig.healthCheck.intervalMs * Math.pow(appConfig.healthCheck.backoffMultiplier, newErrorCount),
          appConfig.healthCheck.maxIntervalMs,
        );
        setHealthCheckInterval(newInterval);
        return newErrorCount;
      });
      
      // 마지막 에러로부터 30초 이상 지났을 때만 새 알림 표시
      setLastErrorTime(prev => {
        const now = Date.now();
        if (now - prev > appConfig.notification.dedupWindowMs) {
          addNotification({
            id: now.toString(),
            type: 'error',
            title: '서버 연결 오류',
            message: '서버에 연결할 수 없습니다. 잠시 후 자동으로 재시도됩니다.',
            timestamp: now,
            read: false,
          });
          return now;
        }
        return prev;
      });
    }
  }, []); // 의존성 제거하고 내부에서 setState 함수 사용

  // 알림 추가 (중복 방지)
  const addNotification = (notification: Notification) => {
    setNotifications(prev => {
      // 같은 타입과 제목의 알림이 이미 있는지 확인
      const isDuplicate = prev.some(n => 
        n.type === notification.type && 
        n.title === notification.title &&
        Date.now() - n.timestamp < appConfig.notification.dedupWindowMs
      );
      
      if (isDuplicate) {
        return prev; // 중복이면 추가하지 않음
      }
      
      // 최대 3개만 유지 (에러 알림 스팸 방지)
      return [notification, ...prev.slice(0, 2)];
    });
    
    // 자동 제거 시간 설정
    const autoRemoveTime = notification.type === 'error'
      ? appConfig.notification.errorAutoRemoveMs
      : appConfig.notification.autoRemoveMs;
    setTimeout(() => {
      removeNotification(notification.id);
    }, autoRemoveTime);
  };

  // 알림 제거
  const removeNotification = (id: string) => {
    setNotifications(prev => prev.filter(n => n.id !== id));
  };

  // 에러 처리 (중복 방지)
  const handleError = useCallback((errorMessage: string) => {
    addNotification({
      id: Date.now().toString(),
      type: 'error',
      title: '오류',
      message: errorMessage,
      timestamp: Date.now(),
      read: false,
    });
  }, []);

  // 성공 메시지
  const handleSuccess = useCallback((message: string) => {
    addNotification({
      id: Date.now().toString(),
      type: 'system',
      title: '성공',
      message,
      timestamp: Date.now(),
      read: false,
    });
  }, []);

  // 로그인 처리
  const handleLogin = useCallback((response: LoginResponse) => {
    setCurrentUser(response.user);
    setSessionToken(response.sessionToken);
    setApiSessionToken(response.sessionToken);
    saveAuthToStorage(response);
    handleSuccess(`${response.user.displayName}님, 환영합니다!`);
  }, [saveAuthToStorage, handleSuccess]);

  // 로그아웃 처리
  const handleLogout = useCallback(() => {
    setCurrentUser(null);
    setSessionToken(null);
    setApiSessionToken(null);
    setSelectedChatRoom(null);
    setNotifications([]);
    saveAuthToStorage(null);
    handleSuccess('로그아웃되었습니다.');
  }, [saveAuthToStorage, handleSuccess]);

  // 채팅방 선택 처리
  const handleChatRoomSelect = useCallback((chatRoom: ChatRoom) => {
    setSelectedChatRoom(chatRoom);
    saveSelectedRoomToStorage(chatRoom);
  }, [saveSelectedRoomToStorage]);

  useEffect(() => {
    checkServerHealth();
    // 동적 간격으로 서버 상태 확인
    const interval = setInterval(checkServerHealth, healthCheckInterval);
    return () => clearInterval(interval);
  }, [healthCheckInterval]); // checkServerHealth 의존성 제거

  // 알림 아이콘 선택
  const getNotificationIcon = (type: string) => {
    switch (type) {
      case 'error':
        return <AlertCircle size={16} />;
      case 'system':
        return <CheckCircle size={16} />;
      default:
        return <AlertCircle size={16} />;
    }
  };

  // 알림 아이콘 색상 클래스
  const getNotificationIconClass = (type: string) => {
    switch (type) {
      case 'error': return 'text-error';
      case 'system': return 'text-success';
      default: return 'text-primary';
    }
  };

  // 상태바 배경색 클래스
  const getStatusBarClass = () => {
    switch (serverStatus) {
      case 'online': return 'bg-success';
      case 'offline': return 'bg-error';
      default: return 'bg-warning';
    }
  };

  // 초기화 로딩 화면
  if (isInitializing) {
    return (
      <div className="flex justify-center items-center h-screen bg-background font-sans">
        <div className="text-center p-8">
          <div className="text-5xl mb-6 animate-pulse-slow">
            💬
          </div>
          <h2 className="text-text-primary text-xl font-semibold mb-2">
            채팅 앱 로딩 중...
          </h2>
          <p className="text-text-secondary text-base">
            잠시만 기다려주세요
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-screen font-sans bg-background overflow-hidden">

      {/* 알림 */}
      {notifications.length > 0 && (
        <div className="fixed top-4 right-4 z-[1060] flex flex-col gap-1 max-w-[400px]">
          {notifications.map((notification) => (
            <div
              key={notification.id}
              className="p-4 rounded-md shadow-lg border border-border bg-bg-secondary flex items-start gap-2 max-w-full animate-slide-in-right"
            >
              <div className={`shrink-0 mt-0.5 ${getNotificationIconClass(notification.type)}`}>
                {getNotificationIcon(notification.type)}
              </div>
              <div className="flex-1 min-w-0">
                <div className="font-semibold text-text-primary text-sm mb-1">
                  {notification.title}
                </div>
                <div className="text-sm text-text-secondary leading-normal">
                  {notification.message}
                </div>
              </div>
              <button
                onClick={() => removeNotification(notification.id)}
                className="cursor-pointer text-text-tertiary shrink-0 p-0.5 rounded-sm transition-all duration-150 hover:text-text-primary bg-transparent border-none"
              >
                <X size={14} />
              </button>
            </div>
          ))}
        </div>
      )}

      {/* 상단 우측 버튼 그룹 */}
      <div className="fixed top-4 right-4 flex items-center gap-2 z-[1030] animate-fade-in">
        <button
          onClick={toggleTheme}
          className="p-1.5 bg-bg-secondary text-text-secondary border border-border rounded-md cursor-pointer flex items-center justify-center transition-all duration-200 shadow-sm hover:bg-bg-tertiary"
          aria-label="테마 변경"
          title={theme === 'light' ? '다크 모드로 변경' : '라이트 모드로 변경'}
        >
          {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
        </button>
        {currentUser && (
          <button 
            onClick={handleLogout} 
            className="px-2 py-1 bg-bg-secondary text-text-secondary border border-border rounded-md cursor-pointer text-sm flex items-center gap-1 transition-all duration-200 shadow-sm hover:bg-bg-tertiary"
          >
            <LogOut size={14} />
            로그아웃
          </button>
        )}
      </div>

      {/* 메인 컨텐츠 */}
      {!currentUser ? (
        // 로그인 화면
        <div className="flex justify-center items-center h-screen w-screen bg-bg-secondary p-6">
          {serverStatus === 'offline' ? (
            <div className="text-center p-8 bg-bg-secondary rounded-lg shadow-lg max-w-[400px]">
              <div className="text-5xl mb-4">
                🔌
              </div>
              <h2 className="text-text-primary text-xl font-semibold mb-2">
                서버에 연결할 수 없습니다
              </h2>
              <p className="text-text-secondary text-base mb-6 leading-relaxed">
                서버가 실행되지 않았거나 네트워크에 문제가 있습니다.<br/>
                서버를 실행한 후 페이지를 새로고침해주세요.
              </p>
              <button
                onClick={() => window.location.reload()}
                className="px-6 py-2 bg-primary text-white border-none rounded-md text-base font-medium cursor-pointer transition-all duration-200 hover:bg-primary-hover"
              >
                새로고침
              </button>
            </div>
          ) : (
            <LoginForm 
              onLogin={handleLogin}
              onError={handleError}
            />
          )}
        </div>
      ) : (
        // 채팅 화면
        <div className="flex w-full h-screen overflow-hidden">
          <ChatRoomList 
            currentUser={currentUser} 
            selectedChatRoom={selectedChatRoom || undefined}
            onChatRoomSelect={handleChatRoomSelect}
            onError={handleError}
          />
          {selectedChatRoom && sessionToken ? (
            <ChatWindow
              chatRoom={selectedChatRoom}
              currentUser={currentUser}
              sessionToken={sessionToken}
              onError={handleError}
              onSuccess={handleSuccess}
            />
          ) : (
            <div className="flex-1 flex flex-col justify-center items-center bg-bg-tertiary text-text-secondary p-8 animate-fade-in">
              <div className="text-[64px] mb-6">💬</div>
              <h2 className="text-2xl font-bold text-text-primary mb-2">채팅을 시작해보세요!</h2>
              <p className="text-base text-text-secondary text-center leading-relaxed max-w-[400px]">
                왼쪽에서 채팅방을 선택하거나 새로운 채팅방을 만들어서
                실시간 대화를 시작할 수 있습니다.
              </p>
              
              <div className="mt-8 p-6 bg-bg-secondary rounded-lg border border-border max-w-[350px] text-left">
                <h4 className="m-0 mb-4 text-text-primary text-base font-semibold">
                  주요 기능
                </h4>
                <ul className="m-0 flex flex-col gap-1.5 text-sm text-text-secondary leading-relaxed list-none p-0">
                  <li className="flex items-center gap-1.5">실시간 메시지 전송</li>
                  <li className="flex items-center gap-1.5">
                    타이핑 인디케이터
                    <InfoTooltip tip="상대방이 메시지를 입력 중일 때 '입력 중...' 표시를 실시간으로 보여주는 기능입니다." />
                  </li>
                  <li className="flex items-center gap-1.5">채팅방 생성 및 관리</li>
                  <li className="flex items-center gap-1.5">
                    분산 서버 지원
                    <InfoTooltip tip="여러 대의 채팅 서버에 연결이 나뉘어도 메시지가 모든 서버의 사용자에게 전달됩니다. 트래픽이 늘어나면 서버를 늘려 수평 확장할 수 있습니다." />
                  </li>
                  <li className="flex items-center gap-1.5">
                    커서 기반 페이징
                    <InfoTooltip tip="페이지 번호 대신 마지막으로 읽은 메시지 위치(커서)를 기준으로 다음 묶음을 불러오는 방식입니다. 메시지가 계속 쌓여도 누락이나 중복 없이 안정적으로 페이징됩니다." />
                  </li>
                </ul>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default App;
