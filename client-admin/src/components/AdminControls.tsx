import React from 'react';
import type { AdminExportJob, SearchMode } from '@/types/index.ts';
import AdvancedFilters, {
  type AdvancedSetters,
  type AdvancedValues,
} from '@/components/AdvancedFilters.tsx';
import ExportStatus from '@/components/ExportStatus.tsx';
import Button from '@/components/ui/Button.tsx';
import Field from '@/components/ui/Field.tsx';
import Input from '@/components/ui/Input.tsx';
import Select from '@/components/ui/Select.tsx';

interface AdminControlsProps {
  baseUrl: string;
  token: string;
  roomId: string;
  query: string;
  searchMode: SearchMode;
  advancedOpen: boolean;
  advancedValues: AdvancedValues;
  advancedSetters: AdvancedSetters;
  busy: boolean;
  lastExportJob: AdminExportJob | null;
  onBaseUrlChange: (value: string) => void;
  onTokenChange: (value: string) => void;
  onRoomIdChange: (value: string) => void;
  onQueryChange: (value: string) => void;
  onSearchModeChange: (value: SearchMode) => void;
  onAdvancedOpenChange: (open: boolean) => void;
  onRefresh: (event: React.FormEvent) => void;
  onExport: () => void;
  onExportStatusRefresh: () => void;
}

// 입력 필드가 반응형으로 채워지는 공통 그리드
const CONTROL_GRID = 'grid grid-cols-[repeat(auto-fit,minmax(190px,1fr))] gap-x-4 gap-y-3 items-end';

const SEARCH_MODE_OPTIONS = [
  { value: 'FTS', label: 'FTS' },
  { value: 'CONTAINS', label: 'CONTAINS' },
];

const AdminControls: React.FC<AdminControlsProps> = ({
  baseUrl,
  token,
  roomId,
  query,
  searchMode,
  advancedOpen,
  advancedValues,
  advancedSetters,
  busy,
  lastExportJob,
  onBaseUrlChange,
  onTokenChange,
  onRoomIdChange,
  onQueryChange,
  onSearchModeChange,
  onAdvancedOpenChange,
  onRefresh,
  onExport,
  onExportStatusRefresh,
}) => {
  return (
    <form
      className="flex flex-col gap-4 px-5 py-[18px] bg-background border border-border rounded-lg shadow-md"
      onSubmit={onRefresh}
    >
      <div className={CONTROL_GRID}>
        <Field label="API Base URL" tip="백엔드 API의 기본 경로입니다. 기본값은 /api 이며, 다른 호스트를 보려면 전체 URL을 입력하세요.">
          <Input value={baseUrl} onChange={onBaseUrlChange} autoComplete="off" />
        </Field>
        <Field label="Admin Token" tip="관리자 인증 토큰입니다. 어드민 API 호출 시 인증 헤더로 전송되며, 없으면 401이 발생합니다.">
          <Input type="password" value={token} onChange={onTokenChange} autoComplete="off" />
        </Field>
        <Field label="Room ID" tip="조회할 채팅방의 숫자 ID입니다. Room Status와 Room History가 이 방을 대상으로 합니다.">
          <Input inputMode="numeric" value={roomId} onChange={onRoomIdChange} />
        </Field>
        <Field label="Query" tip="검색어입니다. Message Search에서 메시지 본문을 검색합니다. 비우면 검색 결과가 없습니다.">
          <Input value={query} onChange={onQueryChange} autoComplete="off" />
        </Field>
        <Field label="Search Mode" tip="검색 방식입니다. FTS는 전문(Full-Text) 검색 인덱스를 사용해 빠르고, CONTAINS는 단순 부분 문자열 매칭입니다.">
          <Select
            value={searchMode}
            onChange={(value) => onSearchModeChange(value as SearchMode)}
            options={SEARCH_MODE_OPTIONS}
          />
        </Field>
      </div>

      <AdvancedFilters
        advancedOpen={advancedOpen}
        values={advancedValues}
        setters={advancedSetters}
        onOpenChange={onAdvancedOpenChange}
      />

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onExport} disabled={busy}>
          Create Export
        </Button>
        <Button type="submit" disabled={busy}>
          Refresh
        </Button>
      </div>

      <ExportStatus
        job={lastExportJob}
        busy={busy}
        onRefresh={onExportStatusRefresh}
      />
    </form>
  );
};

export default AdminControls;
