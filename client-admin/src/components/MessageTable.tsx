import React from 'react';
import type { AdminMessage } from '../types/index';

interface MessageTableProps {
  title: string;
  latencyMs: number;
  rows: AdminMessage[];
  hasNext: boolean;
  onNext: () => void;
}

// Room History / Message Search 공통 결과 패널.
// 값은 모두 JSX 텍스트 노드로 렌더되므로 별도 escape 없이 XSS 안전하다.
const MessageTable: React.FC<MessageTableProps> = ({ title, latencyMs, rows, hasNext, onNext }) => {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>{title}</h2>
        <span className="latency">{latencyMs}ms</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Seq</th>
              <th>Message ID</th>
              <th>Sender</th>
              <th>Type</th>
              <th>Content</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td className="table-empty" colSpan={6}>
                  결과가 없습니다.
                </td>
              </tr>
            ) : (
              rows.map((message) => (
                <tr key={message.messageId}>
                  <td>{message.roomSeq}</td>
                  <td>{message.messageId}</td>
                  <td>
                    {message.senderDisplayName} <span className="cell-sub">{message.senderId}</span>
                  </td>
                  <td>{message.messageType}</td>
                  <td>{message.content ?? ''}</td>
                  <td>{message.createdAt}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <div className="panel-actions">
        <button className="secondary" type="button" disabled={!hasNext} onClick={onNext}>
          Next
        </button>
      </div>
    </section>
  );
};

export default MessageTable;
