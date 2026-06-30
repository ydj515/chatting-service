import React from 'react';
import { AlertCircle, CheckCircle, LogOut, Moon, Sun, X } from 'lucide-react';
import type { Notification } from '@/types/index.ts';
import { useTheme } from '@/hooks/useTheme.ts';

interface LayoutProps {
  notifications: Notification[];
  isAuthenticated: boolean;
  onLogout: () => void;
  onDismissNotification: (id: string) => void;
  children: React.ReactNode;
}

const getNotificationIcon = (type: Notification['type']) => {
  switch (type) {
    case 'error':
      return <AlertCircle size={16} />;
    case 'system':
      return <CheckCircle size={16} />;
    default:
      return <AlertCircle size={16} />;
  }
};

const getNotificationIconClass = (type: Notification['type']) => {
  switch (type) {
    case 'error':
      return 'text-error';
    case 'system':
      return 'text-success';
    default:
      return 'text-primary';
  }
};

const Layout: React.FC<LayoutProps> = ({
  notifications,
  isAuthenticated,
  onLogout,
  onDismissNotification,
  children,
}) => {
  const { theme, toggle } = useTheme();

  return (
    <div className="flex h-screen font-sans bg-background overflow-hidden">
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
                onClick={() => onDismissNotification(notification.id)}
                className="cursor-pointer text-text-tertiary shrink-0 p-0.5 rounded-sm transition-all duration-150 hover:text-text-primary bg-transparent border-none"
                aria-label="알림 닫기"
              >
                <X size={14} />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="fixed top-4 right-4 flex items-center gap-2 z-[1030] animate-fade-in">
        <button
          onClick={toggle}
          className="p-1.5 bg-bg-secondary text-text-secondary border border-border rounded-md cursor-pointer flex items-center justify-center transition-all duration-200 shadow-sm hover:bg-bg-tertiary"
          aria-label="테마 변경"
          title={theme === 'light' ? '다크 모드로 변경' : '라이트 모드로 변경'}
        >
          {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
        </button>
        {isAuthenticated && (
          <button
            onClick={onLogout}
            className="px-2 py-1 bg-bg-secondary text-text-secondary border border-border rounded-md cursor-pointer text-sm flex items-center gap-1 transition-all duration-200 shadow-sm hover:bg-bg-tertiary"
          >
            <LogOut size={14} />
            로그아웃
          </button>
        )}
      </div>

      {children}
    </div>
  );
};

export default Layout;
