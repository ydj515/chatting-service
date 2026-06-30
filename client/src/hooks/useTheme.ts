import { useEffect, useState } from 'react';
import {
  getNextTheme,
  isTheme,
  resolveInitialTheme,
  syncDocumentTheme,
  type Theme,
} from '@/theme.ts';

const THEME_STORAGE_KEY = 'chat_theme';

const readStoredTheme = (): string | null => {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY);
  } catch {
    return null;
  }
};

const prefersDarkTheme = (): boolean => {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
};

export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() => resolveInitialTheme(readStoredTheme(), prefersDarkTheme()));

  useEffect(() => {
    syncDocumentTheme(document.documentElement, theme);
  }, [theme]);

  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (event: MediaQueryListEvent) => {
      if (!isTheme(readStoredTheme())) {
        setTheme(resolveInitialTheme(null, event.matches));
      }
    };
    mq.addEventListener?.('change', handler);
    return () => mq.removeEventListener?.('change', handler);
  }, []);

  const toggle = () => {
    setTheme((prev) => {
      const next = getNextTheme(prev);
      try {
        localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {
        // localStorage 비활성 환경은 무시
      }
      return next;
    });
  };

  return { theme, toggle };
}
