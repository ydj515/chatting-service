import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'vitest';

const adminRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

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
  join(adminRoot, 'index.html'),
  ...listSourceFiles(join(adminRoot, 'src')),
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
  return relative(adminRoot, path);
}

test('관리자 소스는 외부 폰트 CDN과 특정 디자인 시스템 워딩을 노출하지 않는다', () => {
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

test('관리자 소스는 인라인 스타일 대신 CSS 클래스와 토큰을 사용한다', () => {
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

test('관리자 테마는 dark 클래스 대신 data-theme 속성을 사용한다', () => {
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

test('관리자 페이지는 컨트롤, 상세 필터, export 상태 영역으로 분리한다', () => {
  const expectedFiles = [
    join(adminRoot, 'src/components/AdminControls.tsx'),
    join(adminRoot, 'src/components/AdvancedFilters.tsx'),
    join(adminRoot, 'src/components/ExportStatus.tsx'),
  ];

  assert.deepEqual(
    expectedFiles.filter((path) => !existsSync(path)).map(label),
    [],
  );

  const adminPage = fileText(join(adminRoot, 'src/pages/AdminPage.tsx'));
  assert.match(adminPage, /<AdminControls\b/);
  assert.doesNotMatch(adminPage, /className="advanced-filters"/);
  assert.doesNotMatch(adminPage, /className="export-status"/);
});

test('관리자는 @ 절대 import alias를 설정하고 사용한다', () => {
  const packageJson = JSON.parse(fileText(join(adminRoot, 'package.json')));
  const rootTsconfig = fileText(join(adminRoot, 'tsconfig.json'));
  const tsconfig = fileText(join(adminRoot, 'tsconfig.app.json'));
  const viteConfig = fileText(join(adminRoot, 'vite.config.ts'));
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
  const violations = listScriptFiles(join(adminRoot, 'src')).flatMap((path) => {
    const text = fileText(path);
    return relativeImportPattern.test(text) || text.includes(legacyAlias) || text.includes(forbiddenNodeTestImport)
      ? [label(path)]
      : [];
  });

  assert.deepEqual(violations, []);
});

test('관리자는 Zustand와 TanStack Query 경계를 사용한다', () => {
  const packageJson = JSON.parse(fileText(join(adminRoot, 'package.json')));
  const expectedFiles = [
    join(adminRoot, 'src/lib/queryClient.ts'),
    join(adminRoot, 'src/providers/AppProviders.tsx'),
    join(adminRoot, 'src/stores/adminStore.ts'),
  ];

  assert.ok(packageJson.dependencies?.zustand);
  assert.ok(packageJson.dependencies?.['@tanstack/react-query']);
  assert.deepEqual(
    expectedFiles.filter((path) => !existsSync(path)).map(label),
    [],
  );

  const app = fileText(join(adminRoot, 'src/App.tsx'));
  const adminPage = fileText(join(adminRoot, 'src/pages/AdminPage.tsx'));
  assert.match(app, /<AppProviders>/);
  assert.match(adminPage, /useAdminStore/);
  assert.match(adminPage, /useMutation/);
});

test('관리자 히스토리 다음 페이지 요청도 roomId 숫자 검증을 적용한다', () => {
  const adminPage = fileText(join(adminRoot, 'src/pages/AdminPage.tsx'));

  assert.match(adminPage, /isValidRoomId/);
  assert.match(adminPage, /handleHistoryNext[\s\S]*Room ID는 숫자여야 합니다\./);
  assert.match(adminPage, /handleHistoryNext[\s\S]*historyNextMutation\.mutate/);
});

test('관리자 store는 상태 영속화 예외를 persistState 경계에서 방어한다', () => {
  const adminStore = fileText(join(adminRoot, 'src/stores/adminStore.ts'));

  assert.match(adminStore, /try\s*\{[\s\S]*saveAdminState/);
  assert.match(adminStore, /catch\s*\{/);
});
