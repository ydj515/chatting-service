import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { buildPsqlCommand } from './psqlCommand.mjs';

const DEFAULT_SLOW_QUERY_PLAN_OUTPUT_DIR = 'docs/performance/admin-search-slow-query-plans';

export function slowQueryPlanCaptureModeValue(value) {
  const normalized = value === undefined ? 'off' : String(value).trim();
  if (normalized === 'off' || normalized === 'on-cold-failure') {
    return normalized;
  }
  throw new Error('--slow-query-plan must be one of off, on-cold-failure.');
}

export function buildSlowQueryCaptureRequests(results, { mode }) {
  if (mode !== 'on-cold-failure') {
    return [];
  }

  return results.flatMap((result) =>
    (result.gateResults ?? [])
      .filter((gateResult) => gateResult.gate === 'cold' && gateResult.summary?.passedTarget === false)
      .map((gateResult) => ({
        endpointName: result.name,
        url: result.url,
        gate: gateResult.gate,
        targetMetric: gateResult.summary?.targetMetric ?? 'unknown',
        measuredMs: gateResult.summary?.measuredMs ?? null,
        targetMs: gateResult.summary?.targetMs ?? null,
      })),
  );
}

export function buildAdminSearchExplainSql({
  name,
  query,
  searchMode,
  roomId,
  from,
  to,
  limit,
}) {
  const where = [];

  if (name === 'history') {
    where.push(`cm.room_id = ${sqlPositiveInteger(roomId, 'roomId')}`);
  } else if (name === 'search') {
    const normalizedQuery = String(query ?? '').trim();
    if (normalizedQuery !== '') {
      if (searchMode === 'CONTAINS') {
        where.push(`cm.content ILIKE ${sqlString(`%${normalizedQuery}%`)}`);
      } else {
        where.push(`cm.content_tsv @@ plainto_tsquery('simple', ${sqlString(normalizedQuery)})`);
      }
    }
    if (roomId !== undefined && roomId !== null) {
      where.push(`cm.room_id = ${sqlPositiveInteger(roomId, 'roomId')}`);
    }
  } else {
    throw new Error(`Unsupported admin search endpoint for EXPLAIN: ${name}`);
  }

  if (from) {
    where.push(`cm.created_at >= TIMESTAMPTZ ${sqlString(from)}`);
  }
  if (to) {
    where.push(`cm.created_at < TIMESTAMPTZ ${sqlString(to)}`);
  }
  if (where.length === 0) {
    throw new Error('Admin search EXPLAIN requires at least one bounded filter.');
  }

  const orderBy = name === 'history'
    ? 'cm.room_seq DESC, cm.created_at DESC, cm.message_id DESC'
    : 'cm.created_at DESC, cm.room_seq DESC, cm.message_id DESC';

  return `
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
${baseSelectSql()}
WHERE ${where.join(' AND ')}
ORDER BY ${orderBy}
LIMIT ${sqlPositiveInteger(limit, 'limit')};
`.trim();
}

export function buildSlowQueryPlanArtifactPath({
  outputDir = DEFAULT_SLOW_QUERY_PLAN_OUTPUT_DIR,
  measuredAt = new Date().toISOString(),
  endpointName,
  gate,
  targetMetric,
}) {
  const stamp = String(measuredAt)
    .replace(/\.\d{3}Z$/, 'Z')
    .replace(/[^0-9A-Za-z]+/g, '');
  return join(
    outputDir,
    `${stamp}_${slug(endpointName)}_${slug(gate)}_${slug(String(targetMetric).replace(/Ms$/, ''))}_explain.json`,
  );
}

export async function captureSlowQueryPlans(
  captureRequests,
  {
    db,
    psqlMode,
    psqlService,
    outputDir = DEFAULT_SLOW_QUERY_PLAN_OUTPUT_DIR,
    measuredAt,
    slowQueryPlanTimeoutMs = 30_000,
    planOptions,
    executeSql = runPsqlExplain,
  },
) {
  const captures = [];
  for (const request of captureRequests) {
    const artifactPath = buildSlowQueryPlanArtifactPath({
      outputDir,
      measuredAt,
      endpointName: request.endpointName,
      gate: request.gate,
      targetMetric: request.targetMetric,
    });
    try {
      const sql = buildAdminSearchExplainSql({
        ...planOptions,
        name: request.endpointName,
      });
      const rawExplain = await executeSql(
        db,
        {
          mode: psqlMode,
          service: psqlService,
          timeoutMs: slowQueryPlanTimeoutMs,
        },
        sql,
      );
      await mkdir(dirname(artifactPath), { recursive: true });
      await writeFile(
        artifactPath,
        `${JSON.stringify({
          measuredAt,
          ...request,
          sql,
          explain: parseJsonOrNull(rawExplain),
          rawExplain: rawExplain.trim(),
        }, null, 2)}\n`,
      );
      captures.push({
        ...request,
        status: 'captured',
        artifactPath,
      });
    } catch (error) {
      captures.push({
        ...request,
        status: 'failed',
        artifactPath,
        error: error.message,
      });
    }
  }
  return captures;
}

function runPsqlExplain(db, options, sql) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const command = buildPsqlCommand(db, {
      mode: options.mode,
      service: options.service,
    });
    const child = spawn(
      command.bin,
      [...command.args, '-X', '-q', '-t', '-A'],
      {
        env: { ...process.env, PGPASSWORD: db.password },
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    );
    const timeoutMs = Number.isSafeInteger(options.timeoutMs) && options.timeoutMs > 0
      ? options.timeoutMs
      : 30_000;
    const timer = setTimeout(() => {
      child.kill('SIGTERM');
      settle(reject, new Error(`psql explain timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    let stdout = '';
    let stderr = '';
    child.stdin.on('error', () => {});
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      settle(reject, error);
    });
    child.on('close', (code) => {
      if (code === 0) {
        settle(resolve, stdout);
      } else {
        settle(reject, new Error(`psql explain exited with code ${code}: ${stderr.trim()}`));
      }
    });
    try {
      child.stdin.end(`${sql}\n`);
    } catch (error) {
      settle(reject, error);
    }

    function settle(callback, value) {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      callback(value);
    }
  });
}

function baseSelectSql() {
  return `
SELECT
    cm.message_id,
    cm.client_message_id,
    cm.room_id,
    cm.room_seq,
    cm.write_shard,
    cm.sender_id,
    u.username AS sender_username,
    u.display_name AS sender_display_name,
    cm.message_type,
    cm.content,
    cm.is_deleted,
    cm.created_at
FROM chat_messages cm
JOIN app_users u ON u.id = cm.sender_id
`.trim();
}

function sqlPositiveInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || String(value).match(/[^0-9]/)) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return parsed;
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function parseJsonOrNull(value) {
  const trimmed = String(value ?? '').trim();
  if (trimmed === '') {
    return null;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

function slug(value) {
  return String(value ?? 'unknown')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'unknown';
}
