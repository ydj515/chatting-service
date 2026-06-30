import React from 'react';
import InfoTooltip from '@/components/ui/InfoTooltip.tsx';

const EmptyChatState: React.FC = () => {
  return (
    <div className="flex-1 flex flex-col justify-center items-center bg-bg-tertiary text-text-secondary p-8 animate-fade-in">
      <div className="text-[64px] mb-6">💬</div>
      <h2 className="text-2xl font-bold text-text-primary mb-2">채팅을 시작해보세요!</h2>
      <p className="text-base text-text-secondary text-center leading-relaxed max-w-[400px]">
        왼쪽에서 채팅방을 선택하거나 새로운 채팅방을 만들어서
        실시간 대화를 시작할 수 있습니다.
      </p>

      <div className="mt-8 p-6 bg-bg-secondary rounded-lg border border-border max-w-[350px] text-left">
        <h4 className="m-0 mb-4 text-text-primary text-base font-semibold">
          주요 기능
        </h4>
        <ul className="m-0 flex flex-col gap-1.5 text-sm text-text-secondary leading-relaxed list-none p-0">
          <li className="flex items-center gap-1.5">실시간 메시지 전송</li>
          <li className="flex items-center gap-1.5">
            타이핑 인디케이터
            <InfoTooltip tip="상대방이 메시지를 입력 중일 때 '입력 중...' 표시를 실시간으로 보여주는 기능입니다." />
          </li>
          <li className="flex items-center gap-1.5">채팅방 생성 및 관리</li>
          <li className="flex items-center gap-1.5">
            분산 서버 지원
            <InfoTooltip tip="여러 대의 채팅 서버에 연결이 나뉘어도 메시지가 모든 서버의 사용자에게 전달됩니다. 트래픽이 늘어나면 서버를 늘려 수평 확장할 수 있습니다." />
          </li>
          <li className="flex items-center gap-1.5">
            커서 기반 페이징
            <InfoTooltip tip="페이지 번호 대신 마지막으로 읽은 메시지 위치(커서)를 기준으로 다음 묶음을 불러오는 방식입니다. 메시지가 계속 쌓여도 누락이나 중복 없이 안정적으로 페이징됩니다." />
          </li>
        </ul>
      </div>
    </div>
  );
};

export default EmptyChatState;
