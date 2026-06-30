import React from 'react';
import { Moon, Sun } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme.ts';

interface LayoutProps {
  notice: string;
  noticeError: boolean;
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({
  notice,
  noticeError,
  children,
}) => {
  const { theme, toggle } = useTheme();

  return (
    <div className="min-h-screen grid grid-rows-[auto_1fr] font-sans">
      <header className="flex items-center justify-between gap-4 px-6 py-4 bg-background border-b border-border">
        <h1 className="m-0 text-xl font-bold text-text-primary">Chat Admin</h1>
        <div className="flex items-center gap-3">
          <div
            className={`min-w-[220px] text-right text-[13px] ${
              noticeError ? 'text-error' : 'text-text-secondary'
            }`}
          >
            {notice}
          </div>
          <button
            className="w-10 h-10 inline-flex items-center justify-center bg-background text-text-secondary border border-border rounded-md cursor-pointer transition-all duration-200 hover:bg-primary-light hover:border-primary hover:text-primary"
            type="button"
            onClick={toggle}
            aria-label="테마 변경"
            title={theme === 'light' ? '다크 모드로 변경' : '라이트 모드로 변경'}
          >
            {theme === 'light' ? <Moon size={18} /> : <Sun size={18} />}
          </button>
        </div>
      </header>

      <main className="flex flex-col gap-4 p-5">
        {children}
      </main>
    </div>
  );
};

export default Layout;
