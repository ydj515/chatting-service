export type Theme = 'light' | 'dark';

interface ThemeElement {
  setAttribute(name: string, value: string): void;
  classList?: {
    remove(name: string): void;
  };
}

export function isTheme(value: string | null): value is Theme {
  return value === 'light' || value === 'dark';
}

export function resolveInitialTheme(storedTheme: string | null, prefersDark: boolean): Theme {
  if (isTheme(storedTheme)) {
    return storedTheme;
  }
  return prefersDark ? 'dark' : 'light';
}

export function getNextTheme(theme: Theme): Theme {
  return theme === 'light' ? 'dark' : 'light';
}

export function syncDocumentTheme(element: ThemeElement, theme: Theme): void {
  element.setAttribute('data-theme', theme);
  element.classList?.remove('dark');
}
