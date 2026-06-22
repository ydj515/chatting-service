import React from 'react';
import type { AdminRoomStatus } from '../types/index';

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
    <section className="panel">
      <div className="panel-header">
        <h2>Room Status</h2>
      </div>
      <div className="metrics">
        {metrics.map(([label, value]) => (
          <div className="metric" key={label}>
            <span>{label}</span>
            <strong>{value}</strong>
          </div>
        ))}
      </div>
    </section>
  );
};

export default RoomStatus;
