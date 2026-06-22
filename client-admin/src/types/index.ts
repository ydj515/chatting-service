// 어드민 검색 방식
export type SearchMode = 'FTS' | 'CONTAINS';

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
}
