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
