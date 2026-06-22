import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ChevronDown, Moon, Sun, X } from 'lucide-react';
import {
  createAdminExport,
  fetchAdminHistory,
  fetchAdminRoomStatus,
  searchAdminMessages,
} from './services/adminApi.ts';
import { loadAdminState, saveAdminState } from './services/adminState.ts';
import { appConfig } from './config/appConfig.ts';
import type {
  AdminFilters,
  AdminMessage,
  AdminRoomStatus,
  SearchMode,
} from './types/index';
import InfoTooltip from './components/ui/InfoTooltip.tsx';
import MessageTable from './components/MessageTable.tsx';
import RoomStatus from './components/RoomStatus.tsx';

const THEME_STORAGE_KEY = 'admin_theme';

const numberOrNull = (value: string): number | null => {
  if (value.trim() === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

// 상세 필터 정의: id, 표시 라벨, 기본값(이 값이면 비활성으로 간주), 칩 표시 변환
type AdvancedKey = 'senderId' | 'from' | 'to' | 'limit';
interface AdvancedDef {
  key: AdvancedKey;
  label: string;
  def: string;
  display?: (value: string) => string;
}
const ADVANCED_DEFS: AdvancedDef[] = [
  { key: 'senderId', label: 'Sender ID', def: '' },
  { key: 'from', label: 'From', def: '', display: (v) => v.replace('T', ' ') },
  { key: 'to', label: 'To', def: '', display: (v) => v.replace('T', ' ') },
  { key: 'limit', label: 'Limit', def: '50' },
];

function App() {
  const initialState = useMemo(
    () => loadAdminState(localStorage, appConfig.api.defaultBaseUrl),
    [],
  );

  // ----- 핵심 입력 -----
  const [baseUrl, setBaseUrl] = useState(initialState.baseUrl);
  const [token, setToken] = useState(initialState.token);
  const [roomId, setRoomId] = useState(initialState.roomId);
  const [query, setQuery] = useState('');
  const [searchMode, setSearchMode] = useState<SearchMode>(initialState.searchMode);

  // ----- 상세 필터 -----
  const [senderId, setSenderId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [limit, setLimit] = useState('50');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const advancedValues: Record<AdvancedKey, string> = { senderId, from, to, limit };
  const advancedSetters: Record<AdvancedKey, (value: string) => void> = {
    senderId: setSenderId,
    from: setFrom,
    to: setTo,
    limit: setLimit,
  };

  // ----- 결과 / 상태 -----
  const [status, setStatus] = useState<AdminRoomStatus | null>(null);
  const [historyRows, setHistoryRows] = useState<AdminMessage[]>([]);
  const [historyLatency, setHistoryLatency] = useState(0);
  const [historyHasNext, setHistoryHasNext] = useState(false);
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [searchRows, setSearchRows] = useState<AdminMessage[]>([]);
  const [searchLatency, setSearchLatency] = useState(0);
  const [searchHasNext, setSearchHasNext] = useState(false);
  const [searchCursor, setSearchCursor] = useState<string | null>(null);

  const [notice, setNotice] = useState('Ready');
  const [noticeError, setNoticeError] = useState(false);
  const [busy, setBusy] = useState(false);

  // ----- 테마 -----
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    try {
      return (
        (localStorage.getItem(THEME_STORAGE_KEY) as 'light' | 'dark') ||
        (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
      );
    } catch {
      // localStorage 비활성 환경은 기본 테마로 폴백
      return 'light';
    }
  });

  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }, [theme]);

  // OS 테마 변경 실시간 반영 — 사용자가 직접 토글한 적이 없을 때만 OS를 따른다.
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (event: MediaQueryListEvent) => {
      if (!localStorage.getItem(THEME_STORAGE_KEY)) {
        setTheme(event.matches ? 'dark' : 'light');
      }
    };
    mq.addEventListener?.('change', handler);
    return () => mq.removeEventListener?.('change', handler);
  }, []);

  const toggleTheme = () => {
    setTheme((prev) => {
      const next = prev === 'light' ? 'dark' : 'light';
      try {
        localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {
        // localStorage 비활성 환경은 무시
      }
      return next;
    });
  };

  // ----- 적용 중인 상세 필터 -----
  const activeAdvanced = ADVANCED_DEFS.filter((def) => {
    const value = advancedValues[def.key].trim();
    return value !== '' && value !== def.def;
  });

  const clearAdvanced = (key: AdvancedKey, def: string) => {
    advancedSetters[key](def);
  };

  const resetAdvanced = () => {
    ADVANCED_DEFS.forEach((def) => advancedSetters[def.key](def.def));
  };

  // ----- 필터/저장 -----
  const buildFilters = useCallback((): AdminFilters => {
    return {
      query: query.trim(),
      mode: searchMode,
      roomId: numberOrNull(roomId),
      senderId: numberOrNull(senderId),
      from,
      to,
      limit: Number(limit || 50),
    };
  }, [query, searchMode, roomId, senderId, from, to, limit]);

  const persistState = useCallback(() => {
    const normalizedBaseUrl = baseUrl.trim() || '/api';
    const normalizedRoomId = roomId.trim() || '1';
    setBaseUrl(normalizedBaseUrl);
    setRoomId(normalizedRoomId);
    saveAdminState(localStorage, {
      baseUrl: normalizedBaseUrl,
      token,
      roomId: normalizedRoomId,
      searchMode,
      historyCursor: null,
      searchCursor: null,
    });
    return { baseUrl: normalizedBaseUrl, roomId: normalizedRoomId };
  }, [baseUrl, roomId, token, searchMode]);

  const showNotice = (message: string, isError = false) => {
    setNotice(message);
    setNoticeError(isError);
  };

  // successMessage는 함수로도 받아 작업 결과(예: export job id)를 성공 메시지에 반영할 수 있다.
  const run = async (
    successMessage: string | (() => string),
    operation: () => Promise<void>,
  ) => {
    try {
      setBusy(true);
      await operation();
      showNotice(typeof successMessage === 'function' ? successMessage() : successMessage);
    } catch (error) {
      showNotice(error instanceof Error ? error.message : String(error), true);
    } finally {
      setBusy(false);
    }
  };

  // ----- 데이터 로드 -----
  const loadStatus = useCallback(
    async (effectiveBaseUrl: string, effectiveRoomId: string) => {
      const result = await fetchAdminRoomStatus(effectiveBaseUrl, token, Number(effectiveRoomId));
      setStatus(result);
    },
    [token],
  );

  const loadHistory = useCallback(
    async (effectiveBaseUrl: string, effectiveRoomId: string, cursor: string | null) => {
      const response = await fetchAdminHistory(effectiveBaseUrl, token, Number(effectiveRoomId), {
        ...buildFilters(),
        cursor,
      });
      setHistoryCursor(response.nextCursor);
      setHistoryRows(response.messages);
      setHistoryLatency(response.latencyMs);
      setHistoryHasNext(response.hasNext);
    },
    [token, buildFilters],
  );

  const loadSearch = useCallback(
    async (effectiveBaseUrl: string, cursor: string | null) => {
      const response = await searchAdminMessages(effectiveBaseUrl, token, {
        ...buildFilters(),
        cursor,
      });
      setSearchCursor(response.nextCursor);
      setSearchRows(response.messages);
      setSearchLatency(response.latencyMs);
      setSearchHasNext(response.hasNext);
    },
    [token, buildFilters],
  );

  const handleRefresh = async (event: React.FormEvent) => {
    event.preventDefault();
    const { baseUrl: effectiveBaseUrl, roomId: effectiveRoomId } = persistState();
    // roomId가 숫자가 아니면 /admin/rooms/NaN/... 같은 잘못된 요청이 나가므로 사전 검증한다.
    if (numberOrNull(effectiveRoomId) === null) {
      showNotice('Room ID는 숫자여야 합니다.', true);
      return;
    }
    await run('Admin data loaded', async () => {
      await Promise.all([
        loadStatus(effectiveBaseUrl, effectiveRoomId),
        loadHistory(effectiveBaseUrl, effectiveRoomId, null),
        loadSearch(effectiveBaseUrl, null),
      ]);
    });
  };

  const handleExport = async () => {
    const { baseUrl: effectiveBaseUrl } = persistState();
    let successMessage = 'Export job created';
    await run(
      () => successMessage,
      async () => {
        const job = await createAdminExport(effectiveBaseUrl, token, buildFilters());
        successMessage = `Export ${job.jobId} ${job.status}`;
      },
    );
  };

  const handleHistoryNext = async () => {
    if (!historyCursor) return;
    await run('Admin data loaded', async () => {
      await loadHistory(baseUrl, roomId, historyCursor);
    });
  };

  const handleSearchNext = async () => {
    if (!searchCursor) return;
    await run('Admin data loaded', async () => {
      await loadSearch(baseUrl, searchCursor);
    });
  };

  return (
    <div className="shell">
      <header className="topbar">
        <h1>Chat Admin</h1>
        <div className="topbar-actions">
          <div className="notice" data-type={noticeError ? 'error' : 'ok'}>
            {notice}
          </div>
          <button
            className="icon-toggle"
            type="button"
            onClick={toggleTheme}
            aria-label="테마 변경"
            title={theme === 'light' ? '다크 모드로 변경' : '라이트 모드로 변경'}
          >
            {theme === 'light' ? <Moon size={18} /> : <Sun size={18} />}
          </button>
        </div>
      </header>

      <main className="layout">
        <form className="controls" onSubmit={handleRefresh}>
          {/* 핵심 필드 — 검색/조회에 바로 필요한 항목 */}
          <div className="control-grid">
            <label>
              <span className="label-row">
                API Base URL
                <InfoTooltip tip="백엔드 API의 기본 경로입니다. 기본값은 /api 이며, 다른 호스트를 보려면 전체 URL을 입력하세요." />
              </span>
              <input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                autoComplete="off"
              />
            </label>
            <label>
              <span className="label-row">
                Admin Token
                <InfoTooltip tip="관리자 인증 토큰입니다. 어드민 API 호출 시 인증 헤더로 전송되며, 없으면 401이 발생합니다." />
              </span>
              <input
                type="password"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                autoComplete="off"
              />
            </label>
            <label>
              <span className="label-row">
                Room ID
                <InfoTooltip tip="조회할 채팅방의 숫자 ID입니다. Room Status와 Room History가 이 방을 대상으로 합니다." />
              </span>
              <input
                inputMode="numeric"
                value={roomId}
                onChange={(e) => setRoomId(e.target.value)}
              />
            </label>
            <label>
              <span className="label-row">
                Query
                <InfoTooltip tip="검색어입니다. Message Search에서 메시지 본문을 검색합니다. 비우면 검색 결과가 없습니다." />
              </span>
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                autoComplete="off"
              />
            </label>
            <label>
              <span className="label-row">
                Search Mode
                <InfoTooltip tip="검색 방식입니다. FTS는 전문(Full-Text) 검색 인덱스를 사용해 빠르고, CONTAINS는 단순 부분 문자열 매칭입니다." />
              </span>
              <select value={searchMode} onChange={(e) => setSearchMode(e.target.value as SearchMode)}>
                <option value="FTS">FTS</option>
                <option value="CONTAINS">CONTAINS</option>
              </select>
            </label>
          </div>

          {/* 상세 필터 — 기본 접힘 */}
          <div className="advanced-filters">
            <button
              type="button"
              className="advanced-summary"
              aria-expanded={advancedOpen}
              onClick={() => setAdvancedOpen((open) => !open)}
            >
              <span className="advanced-chevron" data-open={advancedOpen} aria-hidden="true">
                <ChevronDown size={16} />
              </span>
              상세 필터
              {activeAdvanced.length > 0 && (
                <span className="advanced-badge" aria-label="적용된 상세 필터 개수">
                  {activeAdvanced.length}
                </span>
              )}
            </button>
            {advancedOpen && (
              <div className="control-grid advanced-grid">
                <label>
                  <span className="label-row">
                    Sender ID
                    <InfoTooltip tip="특정 보낸 사람(사용자 ID)으로 결과를 좁힙니다. 비우면 모든 발신자를 포함합니다." />
                  </span>
                  <input
                    inputMode="numeric"
                    value={senderId}
                    onChange={(e) => setSenderId(e.target.value)}
                  />
                </label>
                <label>
                  <span className="label-row">
                    From
                    <InfoTooltip tip="조회 시작 시각입니다. 이 시각 이후에 생성된 메시지만 포함합니다." />
                  </span>
                  <input
                    type="datetime-local"
                    value={from}
                    onChange={(e) => setFrom(e.target.value)}
                  />
                </label>
                <label>
                  <span className="label-row">
                    To
                    <InfoTooltip tip="조회 종료 시각입니다. 이 시각 이전에 생성된 메시지만 포함합니다." />
                  </span>
                  <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
                </label>
                <label>
                  <span className="label-row">
                    Limit
                    <InfoTooltip tip="한 번에 가져올 최대 행 수입니다. 기본값은 50입니다." />
                  </span>
                  <input
                    inputMode="numeric"
                    value={limit}
                    onChange={(e) => setLimit(e.target.value)}
                  />
                </label>
              </div>
            )}
          </div>

          {/* 적용 중인 상세 필터 — 개별 칩으로 표시, X로 제거 */}
          {activeAdvanced.length > 0 && (
            <div className="chips-row">
              {activeAdvanced.map((def) => {
                const value = advancedValues[def.key].trim();
                const shown = def.display ? def.display(value) : value;
                return (
                  <span className="filter-chip" key={def.key}>
                    <span>
                      {def.label} · <b>{shown}</b>
                    </span>
                    <button
                      type="button"
                      className="chip-remove"
                      aria-label={`${def.label} 필터 제거`}
                      onClick={() => clearAdvanced(def.key, def.def)}
                    >
                      <X size={14} />
                    </button>
                  </span>
                );
              })}
              <button type="button" className="chips-reset" onClick={resetAdvanced}>
                전체 해제
              </button>
            </div>
          )}

          <div className="control-actions">
            <button className="secondary" type="button" onClick={handleExport} disabled={busy}>
              Create Export
            </button>
            <button type="submit" disabled={busy}>
              Refresh
            </button>
          </div>
        </form>

        <section className="content">
          <RoomStatus status={status} />
          <MessageTable
            title="Room History"
            latencyMs={historyLatency}
            rows={historyRows}
            hasNext={historyHasNext}
            onNext={handleHistoryNext}
          />
          <MessageTable
            title="Message Search"
            latencyMs={searchLatency}
            rows={searchRows}
            hasNext={searchHasNext}
            onNext={handleSearchNext}
          />
        </section>
      </main>
    </div>
  );
}

export default App;
