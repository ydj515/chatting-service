import {
  createAdminExport,
  fetchAdminHistory,
  fetchAdminRoomStatus,
  searchAdminMessages,
} from './services/adminApi.mjs';
import { loadAdminState, saveAdminState } from './services/adminState.mjs';
import { appendMetric } from './services/rendering.mjs';

const state = loadAdminState(localStorage);

const form = document.querySelector('#admin-form');
const historyBody = document.querySelector('#history-body');
const searchBody = document.querySelector('#search-body');
const statusGrid = document.querySelector('#status-grid');
const notice = document.querySelector('#notice');

const THEME_STORAGE_KEY = 'admin_theme';
const themeToggle = document.querySelector('#theme-toggle');
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)');

function hasExplicitTheme() {
  try {
    return Boolean(localStorage.getItem(THEME_STORAGE_KEY));
  } catch (error) {
    return false;
  }
}

if (themeToggle) {
  themeToggle.addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch (error) {
      // localStorage 비활성 환경은 무시
    }
  });
}

// OS 테마 변경 실시간 반영 — 사용자가 직접 토글한 적이 없을 때만 OS를 따른다.
const handlePrefersChange = (event) => {
  if (hasExplicitTheme()) {
    return;
  }
  document.documentElement.setAttribute('data-theme', event.matches ? 'dark' : 'light');
};
if (typeof prefersDark.addEventListener === 'function') {
  prefersDark.addEventListener('change', handlePrefersChange);
} else if (typeof prefersDark.addListener === 'function') {
  prefersDark.addListener(handlePrefersChange); // 구형 Safari 폴백
}

// 도움말 아이콘: 'i' 표기 + 가장자리 감지로 툴팁 위치 자동 보정
const helpEls = document.querySelectorAll('.help');
function positionTip(el) {
  const rect = el.getBoundingClientRect();
  el.classList.remove('tip-start', 'tip-end', 'tip-below');
  if (rect.left < 150) {
    el.classList.add('tip-start');
  } else if (window.innerWidth - rect.right < 150) {
    el.classList.add('tip-end');
  }
  if (rect.top < 120) {
    el.classList.add('tip-below');
  }
}
helpEls.forEach((el) => {
  el.textContent = 'i';
  el.addEventListener('mouseenter', () => positionTip(el));
  el.addEventListener('focus', () => positionTip(el));
});

// 상세 필터 — 접힌 상태에서도 적용 중인 항목을 개수 배지 + 개별 칩(X로 제거)으로 표시
const advancedBadge = document.querySelector('#advanced-badge');
const activeFilters = document.querySelector('#active-filters');

// 상세 필터 정의: id, 표시 라벨, 기본값(이 값이면 비활성으로 간주), 칩 표시 변환
const advancedDefs = [
  { id: 'sender-id', label: 'Sender ID', def: '' },
  { id: 'from', label: 'From', def: '', display: (v) => v.replace('T', ' ') },
  { id: 'to', label: 'To', def: '', display: (v) => v.replace('T', ' ') },
  { id: 'limit', label: 'Limit', def: '50' },
];

function activeAdvanced() {
  return advancedDefs
    .map((def) => {
      const el = document.querySelector('#' + def.id);
      return { ...def, el, value: el.value.trim() };
    })
    .filter((def) => def.value !== '' && def.value !== def.def);
}

function clearAdvanced(def) {
  def.el.value = def.def;
  updateAdvancedFilters();
}

function updateAdvancedFilters() {
  const active = activeAdvanced();

  // 토글 옆 개수 배지
  if (active.length > 0) {
    advancedBadge.textContent = String(active.length);
    advancedBadge.hidden = false;
  } else {
    advancedBadge.hidden = true;
  }

  // 개별 칩
  activeFilters.innerHTML = '';
  if (active.length === 0) {
    activeFilters.hidden = true;
    return;
  }
  activeFilters.hidden = false;

  active.forEach((def) => {
    const shown = def.display ? def.display(def.value) : def.value;
    const chip = document.createElement('span');
    chip.className = 'filter-chip';

    const text = document.createElement('span');
    text.append(`${def.label} · `);
    const strong = document.createElement('b');
    strong.textContent = shown; // 사용자 입력 — textContent로 안전하게 삽입
    text.appendChild(strong);

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'chip-remove';
    remove.setAttribute('aria-label', `${def.label} 필터 제거`);
    remove.innerHTML =
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';
    remove.addEventListener('click', () => clearAdvanced(def));

    chip.appendChild(text);
    chip.appendChild(remove);
    activeFilters.appendChild(chip);
  });

  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'chips-reset';
  reset.textContent = '전체 해제';
  reset.addEventListener('click', () => {
    advancedDefs.forEach((def) => {
      document.querySelector('#' + def.id).value = def.def;
    });
    updateAdvancedFilters();
  });
  activeFilters.appendChild(reset);
}

['sender-id', 'from', 'to', 'limit'].forEach((id) => {
  document.querySelector('#' + id).addEventListener('input', updateAdvancedFilters);
});
updateAdvancedFilters();

document.querySelector('#base-url').value = state.baseUrl;
document.querySelector('#admin-token').value = state.token;
document.querySelector('#room-id').value = state.roomId;
document.querySelector('#search-mode').value = state.searchMode;

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  saveState();
  await refreshAll();
});

document.querySelector('#history-next').addEventListener('click', async () => {
  if (state.historyCursor) {
    await loadHistory(state.historyCursor);
  }
});

document.querySelector('#search-next').addEventListener('click', async () => {
  if (state.searchCursor) {
    await loadSearch(state.searchCursor);
  }
});

document.querySelector('#export-button').addEventListener('click', async () => {
  saveState();
  await run('Export job created', async () => {
    const payload = filters();
    const job = await createAdminExport(state.baseUrl, state.token, payload);
    showNotice(`Export ${job.jobId} ${job.status}`);
  });
});

async function refreshAll() {
  await run('Admin data loaded', async () => {
    await Promise.all([
      loadStatus(),
      loadHistory(null),
      loadSearch(null),
    ]);
  });
}

async function loadStatus() {
  const status = await fetchAdminRoomStatus(state.baseUrl, state.token, Number(state.roomId));
  statusGrid.innerHTML = '';
  [
    ['Heat', status.heatLevel],
    ['Live Feed', `${status.liveFeedMaxMessages} / ${status.liveFeedMaxAgeSeconds}s`],
    ['Rate Limit', status.rateLimitPerSecond ?? 'off'],
    ['Slow Mode', status.slowModeSeconds ?? 'off'],
    ['Replica Lag', status.replicaLagMs == null ? 'n/a' : `${status.replicaLagMs}ms`],
    ['Search p95', status.searchP95LatencyMs == null ? 'n/a' : `${status.searchP95LatencyMs}ms`],
  ].forEach(([label, value]) => {
    appendMetric(statusGrid, document, label, value);
  });
}

async function loadHistory(cursor) {
  const response = await fetchAdminHistory(
    state.baseUrl,
    state.token,
    Number(state.roomId),
    { ...filters(), cursor },
  );
  state.historyCursor = response.nextCursor;
  renderRows(historyBody, response.messages);
  document.querySelector('#history-latency').textContent = `${response.latencyMs}ms`;
  document.querySelector('#history-next').disabled = !response.hasNext;
}

async function loadSearch(cursor) {
  const response = await searchAdminMessages(
    state.baseUrl,
    state.token,
    { ...filters(), cursor },
  );
  state.searchCursor = response.nextCursor;
  renderRows(searchBody, response.messages);
  document.querySelector('#search-latency').textContent = `${response.latencyMs}ms`;
  document.querySelector('#search-next').disabled = !response.hasNext;
}

function renderRows(target, messages) {
  target.innerHTML = '';
  messages.forEach((message) => {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td>${message.roomSeq}</td>
      <td>${escapeHtml(message.messageId)}</td>
      <td>${escapeHtml(message.senderDisplayName)} <span>${message.senderId}</span></td>
      <td>${escapeHtml(message.messageType)}</td>
      <td>${escapeHtml(message.content ?? '')}</td>
      <td>${escapeHtml(message.createdAt)}</td>
    `;
    target.appendChild(row);
  });
}

function filters() {
  return {
    query: document.querySelector('#query').value.trim(),
    mode: document.querySelector('#search-mode').value,
    roomId: numberOrNull(document.querySelector('#room-id').value),
    senderId: numberOrNull(document.querySelector('#sender-id').value),
    from: document.querySelector('#from').value,
    to: document.querySelector('#to').value,
    limit: Number(document.querySelector('#limit').value || 50),
  };
}

function saveState() {
  state.baseUrl = document.querySelector('#base-url').value.trim() || '/api';
  state.token = document.querySelector('#admin-token').value.trim();
  state.roomId = document.querySelector('#room-id').value.trim() || '1';
  state.searchMode = document.querySelector('#search-mode').value || 'FTS';
  saveAdminState(localStorage, state);
}

async function run(successMessage, operation) {
  try {
    setBusy(true);
    await operation();
    showNotice(successMessage);
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    setBusy(false);
  }
}

function setBusy(isBusy) {
  document.querySelectorAll('button').forEach((button) => {
    button.disabled = isBusy && button.type === 'submit';
  });
}

function showNotice(message, isError = false) {
  notice.textContent = message;
  notice.dataset.type = isError ? 'error' : 'ok';
}

function numberOrNull(value) {
  if (value === '') {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}
