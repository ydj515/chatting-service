import React from 'react';
import type { AdminRoomStatus } from '@/types/index.ts';

interface RoomStatusProps {
  status: AdminRoomStatus | null;
}

// 방 상태 지표 그리드. status가 없으면 빈 패널만 보여준다.
const RoomStatus: React.FC<RoomStatusProps> = ({ status }) => {
  const metrics: Array<[string, string | number]> = status
    ? [
        ['Heat', status.heatLevel],
        ['Live Feed', `${status.liveFeedMaxMessages} / ${status.liveFeedMaxAgeSeconds}s`],
        ['Rate Limit', status.rateLimitPerSecond ?? 'off'],
        ['Slow Mode', status.slowModeSeconds ?? 'off'],
        ['Replica Lag', status.replicaLagMs == null ? 'n/a' : `${status.replicaLagMs}ms`],
        ['Search p95', status.searchP95LatencyMs == null ? 'n/a' : `${status.searchP95LatencyMs}ms`],
      ]
    : [];

  return (
    <section className="bg-background border border-border rounded-lg shadow-md overflow-hidden">
      <div className="flex items-center justify-between gap-3 px-4 py-3.5 border-b border-border bg-gray-100">
        <h2 className="m-0 text-base font-semibold text-text-primary">Room Status</h2>
      </div>
      <div className="grid grid-cols-2 min-[720px]:grid-cols-6 gap-px bg-border-light">
        {metrics.map(([label, value]) => (
          <div className="grid gap-1 p-3.5 bg-background" key={label}>
            <span className="text-text-secondary text-xs tracking-wide">{label}</span>
            <strong className="text-lg font-bold tabular-nums text-text-primary">{value}</strong>
          </div>
        ))}
      </div>
    </section>
  );
};

export default RoomStatus;
