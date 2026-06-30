import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';

const clientRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function listSourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const absolutePath = join(dir, entry.name);
    if (entry.isDirectory()) {
      return listSourceFiles(absolutePath);
    }
    if (entry.name.endsWith('.test.ts')) {
      return [];
    }
    const extension = extname(entry.name);
    return ['.css', '.html', '.ts', '.tsx'].includes(extension) ? [absolutePath] : [];
  });
}

const checkedFiles = [
  join(clientRoot, 'index.html'),
  ...listSourceFiles(join(clientRoot, 'src')),
];

function listScriptFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const absolutePath = join(dir, entry.name);
    if (entry.isDirectory()) {
      return listScriptFiles(absolutePath);
    }
    const extension = extname(entry.name);
    return ['.ts', '.tsx'].includes(extension) ? [absolutePath] : [];
  });
}

function fileText(path: string) {
  return readFileSync(path, 'utf8');
}

function label(path: string) {
  return relative(clientRoot, path);
}

test('클라이언트 소스는 외부 폰트 CDN과 특정 디자인 시스템 워딩을 노출하지 않는다', () => {
  const sourceBrandName = ['Wan', 'ted'].join('');
  const sourceFontPackage = ['wan', 'ted-sans'].join('');
  const sourceOrgName = ['wan', 'teddev'].join('');
  const forbiddenPatterns = [
    /@import\s+url\(["']https?:/i,
    /cdn\.jsdelivr/i,
    new RegExp(`\\b${sourceBrandName}\\b`, 'i'),
    new RegExp(sourceOrgName, 'i'),
    new RegExp(sourceFontPackage, 'i'),
  ];

  const violations = checkedFiles.flatMap((path) => {
    const text = fileText(path);
    return forbiddenPatterns.some((pattern) => pattern.test(text)) ? [label(path)] : [];
  });

  assert.deepEqual(violations, []);
});

test('클라이언트 소스는 인라인 스타일 대신 CSS 클래스와 토큰을 사용한다', () => {
  const jsxStylePropPattern = new RegExp(`\\s${['sty', 'le'].join('')}=\\{`);
  const forbiddenPatterns = [
    /<style[\s>]/i,
    jsxStylePropPattern,
    /React\.CSSProperties/,
    /\.style\.[a-zA-Z]/,
  ];

  const violations = checkedFiles.flatMap((path) => {
    const text = fileText(path);
    return forbiddenPatterns.some((pattern) => pattern.test(text)) ? [label(path)] : [];
  });

  assert.deepEqual(violations, []);
});

test('클라이언트 테마는 dark 클래스 대신 data-theme 속성을 사용한다', () => {
  const forbiddenPatterns = [
    /\.dark\b/,
    /classList\.(?:add|toggle)\(["']dark["']/,
  ];

  const violations = checkedFiles.flatMap((path) => {
    const text = fileText(path);
    return forbiddenPatterns.some((pattern) => pattern.test(text)) ? [label(path)] : [];
  });

  assert.deepEqual(violations, []);
});

test('클라이언트 페이지는 인증 게이트와 채팅 작업 영역으로 분리한다', () => {
  const expectedFiles = [
    join(clientRoot, 'src/components/AuthGate.tsx'),
    join(clientRoot, 'src/components/ChatWorkspace.tsx'),
  ];

  assert.deepEqual(
    expectedFiles.filter((path) => !existsSync(path)).map(label),
    [],
  );

  const chatPage = fileText(join(clientRoot, 'src/pages/ChatPage.tsx'));
  assert.match(chatPage, /<AuthGate\b/);
  assert.match(chatPage, /<ChatWorkspace\b/);
});

test('클라이언트는 @ 절대 import alias를 설정하고 사용한다', () => {
  const packageJson = JSON.parse(fileText(join(clientRoot, 'package.json')));
  const rootTsconfig = fileText(join(clientRoot, 'tsconfig.json'));
  const tsconfig = fileText(join(clientRoot, 'tsconfig.app.json'));
  const viteConfig = fileText(join(clientRoot, 'vite.config.ts'));
  const legacyAlias = ['#', 'src/'].join('');
  const forbiddenNodeTestImport = ['node', ':test'].join('');

  assert.equal(packageJson.imports?.[`${legacyAlias}*`], undefined);
  assert.equal(packageJson.devDependencies?.tsx, undefined);
  assert.ok(packageJson.devDependencies?.vitest);
  assert.match(packageJson.scripts?.['test:unit'], /^vitest run\b/);
  assert.match(rootTsconfig, /"baseUrl":\s*"\."/);
  assert.match(rootTsconfig, /"@\/\*":\s*\[\s*"src\/\*"\s*\]/);
  assert.match(tsconfig, /"baseUrl":\s*"\."/);
  assert.match(tsconfig, /"@\/\*":\s*\[\s*"src\/\*"\s*\]/);
  assert.match(viteConfig, /alias:\s*\{/);
  assert.match(viteConfig, /['"]@['"]:\s*['"]\/src['"]/);

  const relativeImportPattern =
    /\bimport(?:\s+type)?(?:\s+['"]\.{1,2}\/|[\s\S]*?\s+from\s+['"]\.{1,2}\/)/;
  const violations = listScriptFiles(join(clientRoot, 'src')).flatMap((path) => {
    const text = fileText(path);
    return relativeImportPattern.test(text) || text.includes(legacyAlias) || text.includes(forbiddenNodeTestImport)
      ? [label(path)]
      : [];
  });

  assert.deepEqual(violations, []);
});

test('클라이언트는 Zustand와 TanStack Query 경계를 사용한다', () => {
  const packageJson = JSON.parse(fileText(join(clientRoot, 'package.json')));
  const expectedFiles = [
    join(clientRoot, 'src/lib/queryClient.ts'),
    join(clientRoot, 'src/providers/AppProviders.tsx'),
    join(clientRoot, 'src/stores/chatStore.ts'),
    join(clientRoot, 'src/hooks/useServerHealth.ts'),
  ];

  assert.ok(packageJson.dependencies?.zustand);
  assert.ok(packageJson.dependencies?.['@tanstack/react-query']);
  assert.deepEqual(
    expectedFiles.filter((path) => !existsSync(path)).map(label),
    [],
  );

  const app = fileText(join(clientRoot, 'src/App.tsx'));
  const chatPage = fileText(join(clientRoot, 'src/pages/ChatPage.tsx'));
  assert.match(app, /<AppProviders>/);
  assert.match(chatPage, /useChatStore/);
  assert.match(chatPage, /useServerHealth/);
});

test('채팅 페이지는 API 토큰 동기화 전 인증 화면을 노출하지 않는다', () => {
  const chatPage = fileText(join(clientRoot, 'src/pages/ChatPage.tsx'));

  assert.match(chatPage, /isApiSessionReady/);
  assert.match(chatPage, /setSyncedSessionToken/);
  assert.match(chatPage, /!apiSessionReady/);
  assert.match(chatPage, /<LoadingScreen\s*\/>/);
});

test('채팅 창은 stale query 스냅샷으로 실시간 메시지를 덮어쓰지 않는다', () => {
  const chatWindow = fileText(join(clientRoot, 'src/components/ChatWindow.tsx'));

  assert.match(chatWindow, /useQueryClient/);
  assert.match(chatWindow, /staleTime:\s*Infinity/);
  assert.match(chatWindow, /setQueryData/);
  assert.match(chatWindow, /mergeMessages/);
  assert.doesNotMatch(chatWindow, /setMessages\(messagesQuery\.data\)/);
});

test('서버 헬스 훅은 재조회 실패 횟수까지 상태 계산에 반영한다', () => {
  const useServerHealth = fileText(join(clientRoot, 'src/hooks/useServerHealth.ts'));

  assert.match(useServerHealth, /deriveServerHealthState/);
  assert.match(useServerHealth, /failureCount/);
  assert.match(useServerHealth, /isRefetchError/);
});
