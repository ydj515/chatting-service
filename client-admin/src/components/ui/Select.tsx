import React from 'react';
import { ChevronDown } from 'lucide-react';
import { SelectProps } from '@/types/index.ts';

// 네이티브 select를 디자인 토큰에 맞춰 감싼 컴포넌트.
// 화살표 아이콘은 직접 렌더하고 기본 화살표는 appearance-none으로 숨긴다.
const Select: React.FC<SelectProps> = ({
  value,
  onChange,
  options,
  disabled = false,
  className = '',
}) => {
  const selectClassName = [
    'w-full min-w-0 appearance-none pl-3 pr-9 min-h-10 text-base font-sans bg-background border border-border rounded-md outline-none transition-all duration-200 text-text-primary cursor-pointer',
    'focus:border-primary focus:ring-2 focus:ring-primary-light',
    'disabled:bg-gray-100 disabled:text-gray-500 disabled:cursor-not-allowed',
    className,
  ].filter(Boolean).join(' ');

  return (
    <div className="relative w-full">
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        className={selectClassName}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ChevronDown
        size={16}
        className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-text-tertiary"
        aria-hidden="true"
      />
    </div>
  );
};

export default Select;
