import React from 'react';

// 어드민 검색 방식
export type SearchMode = 'FTS' | 'CONTAINS';

// 공통 컴포넌트 Props
export interface BaseComponentProps {
  className?: string;
  children?: React.ReactNode;
}

// 버튼 Props (client UI 컴포넌트 체계와 동일)
export interface ButtonProps extends BaseComponentProps {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  loading?: boolean;
  onClick?: () => void;
  type?: 'button' | 'submit' | 'reset';
}

// 입력 Props (datetime-local 등 어드민 전용 타입 포함)
export interface InputProps extends BaseComponentProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  error?: string;
  type?: 'text' | 'password' | 'search' | 'number' | 'datetime-local';
  inputMode?: 'text' | 'numeric';
  autoComplete?: string;
  maxLength?: number;
  autoFocus?: boolean;
}

// 셀렉트 Props
export interface SelectOption {
  value: string;
  label: string;
}

export interface SelectProps extends BaseComponentProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  disabled?: boolean;
}

// 어드민 화면에서 관리하는 입력 상태(폼 + localStorage 동기화 대상)
export interface AdminState {
  baseUrl: string;
  token: string;
  roomId: string;
  searchMode: SearchMode;
  historyCursor: string | null;
  searchCursor: string | null;
}

// REST 호출에 사용하는 필터 묶음
export interface AdminFilters {
  query?: string;
  mode?: SearchMode;
  roomId?: number | null;
  senderId?: number | null;
  from?: string;
  to?: string;
  limit?: number;
  cursor?: string | null;
}

// 메시지 테이블 한 행
export interface AdminMessage {
  roomSeq: number;
  messageId: string;
  senderId: number;
  senderDisplayName: string;
  messageType: string;
  content?: string | null;
  createdAt: string;
}

// 히스토리/검색 공통 응답
export interface AdminMessagePage {
  messages: AdminMessage[];
  nextCursor: string | null;
  hasNext: boolean;
  latencyMs: number;
}

// 방 상태 지표
export interface AdminRoomStatus {
  heatLevel: string | number;
  liveFeedMaxMessages: number;
  liveFeedMaxAgeSeconds: number;
  rateLimitPerSecond?: number | null;
  slowModeSeconds?: number | null;
  replicaLagMs?: number | null;
  searchP95LatencyMs?: number | null;
}

// Export 작업 생성 응답
export interface AdminExportJob {
  jobId: string;
  status: string;
  createdAt?: string;
  startedAt?: string | null;
  completedAt?: string | null;
  exportedRows?: number;
  outputUri?: string | null;
  downloadUrl?: string | null;
  downloadUrlExpiresAt?: string | null;
  errorMessage?: string | null;
}
