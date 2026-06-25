import axios from 'axios';
import type {
  AdminExportJob,
  AdminFilters,
  AdminMessagePage,
  AdminRoomStatus,
} from '../types/index';
import { appConfig } from '../config/appConfig.ts';

const DEFAULT_MAX_LIMIT = 100;

export function createAdminHeaders(adminToken: string): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'X-Admin-Token': adminToken,
  };
}

export function buildAdminHistoryUrl(baseUrl: string, roomId: number, filters: AdminFilters = {}): string {
  const params = new URLSearchParams();
  appendOptional(params, 'from', filters.from);
  appendOptional(params, 'to', filters.to);
  appendOptional(params, 'cursor', filters.cursor);
  params.set('limit', String(boundedLimit(filters.limit)));
  return `${normalizeBaseUrl(baseUrl)}/admin/chat-rooms/${encodeURIComponent(String(roomId))}/messages?${params}`;
}

export function buildAdminSearchUrl(baseUrl: string, filters: AdminFilters = {}): string {
  const params = new URLSearchParams();
  params.set('q', filters.query ?? '');
  appendOptional(params, 'mode', filters.mode);
  appendOptional(params, 'roomId', filters.roomId);
  appendOptional(params, 'from', filters.from);
  appendOptional(params, 'to', filters.to);
  appendOptional(params, 'senderId', filters.senderId);
  appendOptional(params, 'cursor', filters.cursor);
  params.set('limit', String(boundedLimit(filters.limit)));
  return `${normalizeBaseUrl(baseUrl)}/admin/messages/search?${params}`;
}

export function buildAdminRoomStatusUrl(baseUrl: string, roomId: number): string {
  return `${normalizeBaseUrl(baseUrl)}/admin/rooms/${encodeURIComponent(String(roomId))}/status`;
}

export function buildAdminExportUrl(baseUrl: string): string {
  return `${normalizeBaseUrl(baseUrl)}/admin/exports/messages`;
}

export function buildAdminExportStatusUrl(baseUrl: string, jobId: string): string {
  return `${normalizeBaseUrl(baseUrl)}/admin/exports/${encodeURIComponent(jobId)}`;
}

export async function fetchAdminHistory(
  baseUrl: string,
  adminToken: string,
  roomId: number,
  filters: AdminFilters,
): Promise<AdminMessagePage> {
  return requestJson(buildAdminHistoryUrl(baseUrl, roomId, filters), adminToken);
}

export async function searchAdminMessages(
  baseUrl: string,
  adminToken: string,
  filters: AdminFilters,
): Promise<AdminMessagePage> {
  return requestJson(buildAdminSearchUrl(baseUrl, filters), adminToken);
}

export async function fetchAdminRoomStatus(
  baseUrl: string,
  adminToken: string,
  roomId: number,
): Promise<AdminRoomStatus> {
  return requestJson(buildAdminRoomStatusUrl(baseUrl, roomId), adminToken);
}

export async function createAdminExport(
  baseUrl: string,
  adminToken: string,
  payload: AdminFilters,
): Promise<AdminExportJob> {
  return requestJson(buildAdminExportUrl(baseUrl), adminToken, payload);
}

export async function fetchAdminExportStatus(
  baseUrl: string,
  adminToken: string,
  jobId: string,
): Promise<AdminExportJob> {
  return requestJson(buildAdminExportStatusUrl(baseUrl, jobId), adminToken);
}

async function requestJson<T>(url: string, adminToken: string, body?: unknown): Promise<T> {
  try {
    const response = await axios.request<T>({
      url,
      method: body === undefined ? 'GET' : 'POST',
      headers: createAdminHeaders(adminToken),
      timeout: appConfig.api.timeoutMs,
      data: body,
    });
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response) {
      throw new Error(`Admin request failed: ${error.response.status} ${extractErrorDetail(error.response.data)}`);
    }
    throw error instanceof Error ? error : new Error(String(error));
  }
}

// Spring 등 백엔드의 에러 응답({ message, error, ... })에서 사람이 읽을 메시지를 우선 추출한다.
function extractErrorDetail(data: unknown): string {
  if (typeof data === 'string') {
    return data;
  }
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>;
    const message = record.message ?? record.error;
    if (typeof message === 'string' && message !== '') {
      return message;
    }
    return JSON.stringify(data);
  }
  return String(data ?? '');
}

function appendOptional(
  params: URLSearchParams,
  key: string,
  value: string | number | null | undefined,
): void {
  if (value !== undefined && value !== null && value !== '') {
    params.set(key, String(value));
  }
}

function boundedLimit(value: number | undefined, maxLimit = DEFAULT_MAX_LIMIT): number {
  const parsed = Number.parseInt(String(value ?? 50), 10);
  if (!Number.isFinite(parsed)) {
    return 50;
  }
  return Math.min(Math.max(parsed, 1), maxLimit);
}

function normalizeBaseUrl(baseUrl: string): string {
  return (baseUrl || '/api').replace(/\/+$/, '');
}
