import React from 'react';
import InfoTooltip from '@/components/ui/InfoTooltip.tsx';

interface FieldProps {
  // 필드 라벨 텍스트
  label: string;
  // 라벨 옆 도움말 툴팁(선택)
  tip?: string;
  // 그리드 span 등 추가 클래스
  className?: string;
  children: React.ReactNode;
}

// 라벨 + 도움말 툴팁 + 입력 컨트롤을 묶는 폼 필드 래퍼.
// 기존 전역 label/.label-row 스타일을 유틸리티로 대체한다.
const Field: React.FC<FieldProps> = ({ label, tip, className = '', children }) => {
  const labelClassName = ['grid gap-1.5 min-w-0', className].filter(Boolean).join(' ');

  return (
    <label className={labelClassName}>
      <span className="inline-flex items-center gap-1.5 text-xs font-semibold tracking-wide text-text-secondary">
        {label}
        {tip && <InfoTooltip tip={tip} />}
      </span>
      {children}
    </label>
  );
};

export default Field;
