import React from 'react';
import type { AdminMessage } from '@/types/index.ts';
import Button from '@/components/ui/Button.tsx';

interface MessageTableProps {
  title: string;
  latencyMs: number;
  rows: AdminMessage[];
  hasNext: boolean;
  onNext: () => void;
}

// 셀 공통 스타일(테이블 헤더/본문)
const TH_CLASS =
  'border-b border-border-light px-3 py-2.5 text-left align-top text-xs font-semibold tracking-wide text-text-secondary bg-gray-100';
const TD_CLASS =
  'border-b border-border-light px-3 py-2.5 text-left align-top text-[13px] tabular-nums text-text-primary';

// Room History / Message Search 공통 결과 패널.
// 값은 모두 JSX 텍스트 노드로 렌더되므로 별도 escape 없이 XSS 안전하다.
const MessageTable: React.FC<MessageTableProps> = ({ title, latencyMs, rows, hasNext, onNext }) => {
  return (
    <section className="bg-background border border-border rounded-lg shadow-md overflow-hidden">
      <div className="flex items-center justify-between gap-3 px-4 py-3.5 border-b border-border bg-gray-100">
        <h2 className="m-0 text-base font-semibold text-text-primary">{title}</h2>
        <span className="text-text-secondary text-xs tabular-nums">{latencyMs}ms</span>
      </div>
      <div className="overflow-auto">
        <table className="w-full border-collapse min-w-[900px]">
          <thead>
            <tr>
              <th className={TH_CLASS}>Seq</th>
              <th className={TH_CLASS}>Message ID</th>
              <th className={TH_CLASS}>Sender</th>
              <th className={TH_CLASS}>Type</th>
              <th className={TH_CLASS}>Content</th>
              <th className={TH_CLASS}>Created</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td className="px-3 py-6 text-center text-text-secondary text-[13px]" colSpan={6}>
                  결과가 없습니다.
                </td>
              </tr>
            ) : (
              rows.map((message) => (
                <tr key={message.messageId}>
                  <td className={TD_CLASS}>{message.roomSeq}</td>
                  <td className={TD_CLASS}>{message.messageId}</td>
                  <td className={TD_CLASS}>
                    {message.senderDisplayName}{' '}
                    <span className="text-text-secondary text-xs">{message.senderId}</span>
                  </td>
                  <td className={TD_CLASS}>{message.messageType}</td>
                  <td className={TD_CLASS}>{message.content ?? ''}</td>
                  <td className={TD_CLASS}>{message.createdAt}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <div className="flex justify-end px-4 py-3">
        <Button variant="ghost" disabled={!hasNext} onClick={onNext}>
          Next
        </Button>
      </div>
    </section>
  );
};

export default MessageTable;
