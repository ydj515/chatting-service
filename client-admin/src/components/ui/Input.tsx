import React, { forwardRef } from 'react';
import { InputProps } from '@/types/index.ts';

const Input = forwardRef<HTMLInputElement, InputProps>(({
  value,
  onChange,
  placeholder,
  disabled = false,
  error,
  type = 'text',
  inputMode,
  autoComplete,
  maxLength,
  autoFocus = false,
  className = '',
  ...props
}, ref) => {
  // 값 변경 핸들러
  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange(e.target.value);
  };

  // 기본 클래스 + error 상태 조건부 클래스 결합
  const inputClassName = [
    'w-full min-w-0 px-3 min-h-10 text-base font-sans bg-background border border-border rounded-md outline-none transition-all duration-200 text-text-primary',
    'focus:border-primary focus:ring-2 focus:ring-primary-light',
    'disabled:bg-gray-100 disabled:text-gray-500',
    error ? 'border-error focus:border-error focus:ring-error-light' : '',
    className,
  ].filter(Boolean).join(' ');

  return (
    <div className="w-full">
      <input
        ref={ref}
        type={type}
        value={value}
        onChange={handleChange}
        placeholder={placeholder}
        disabled={disabled}
        inputMode={inputMode}
        autoComplete={autoComplete}
        maxLength={maxLength}
        autoFocus={autoFocus}
        className={inputClassName}
        {...props}
      />
      {error && (
        <div className="mt-1 text-sm text-error">
          {error}
        </div>
      )}
    </div>
  );
});

Input.displayName = 'Input';

export default Input;
