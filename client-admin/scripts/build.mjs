import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = resolve(root, 'dist');

await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });
await cp(resolve(root, 'index.html'), resolve(dist, 'index.html'));
await cp(resolve(root, 'src'), resolve(dist, 'src'), { recursive: true });
await cp(resolve(root, 'fonts'), resolve(dist, 'fonts'), { recursive: true });

// base-url 기본값 주입: ADMIN_DEFAULT_BASE_URL이 있으면 index.html 토큰을 치환한다.
// 미지정 시 빈 문자열 → 클라이언트는 상대경로 '/api'로 폴백한다.
const defaultBaseUrl = process.env.ADMIN_DEFAULT_BASE_URL ?? '';
const indexPath = resolve(dist, 'index.html');
const html = await readFile(indexPath, 'utf8');
await writeFile(indexPath, html.replaceAll('%%ADMIN_DEFAULT_BASE_URL%%', defaultBaseUrl));

console.log(`Built client-admin to ${dist} (default base-url: ${defaultBaseUrl || '/api (relative)'})`);
