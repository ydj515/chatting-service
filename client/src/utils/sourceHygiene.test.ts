import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

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
