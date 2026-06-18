import React from 'react';
import { ButtonProps } from '../../types/index';

// variant별 Tailwind 클래스 매핑
const variantClasses: Record<string, string> = {
  primary: 'bg-primary text-white border-transparent hover:bg-primary-hover disabled:bg-gray-300',
  secondary: 'bg-secondary text-white border-transparent hover:bg-secondary-hover disabled:bg-gray-100',
  ghost: 'bg-transparent text-primary border-primary hover:bg-primary-light disabled:text-gray-400 disabled:border-gray-300',
  danger: 'bg-error text-white border-transparent hover:bg-error-hover disabled:bg-gray-300',
};

// size별 Tailwind 클래스 매핑
const sizeClasses: Record<string, string> = {
  sm: 'px-2 py-1 text-sm min-h-8',
  md: 'px-4 py-2 text-base min-h-10',
  lg: 'px-4 py-4 text-lg min-h-12',
};

// 버튼 공통 기본 클래스
const baseClasses =
  'inline-flex items-center justify-center gap-1 rounded-md font-medium font-sans outline-none select-none whitespace-nowrap transition-all duration-200 border';

const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  size = 'md',
  disabled = false,
  loading = false,
  onClick,
  type = 'button',
  className = '',
  ...props
}) => {
  // disabled 또는 loading 상태에 따른 커서/투명도 클래스
  const stateClasses = disabled || loading
    ? 'cursor-not-allowed opacity-60'
    : 'cursor-pointer';

  // 최종 className 조합
  const combinedClassName = [
    baseClasses,
    variantClasses[variant] ?? '',
    sizeClasses[size] ?? '',
    stateClasses,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const handleClick = () => {
    if (!disabled && !loading && onClick) {
      onClick();
    }
  };

  return (
    <button
      type={type}
      className={combinedClassName}
      onClick={handleClick}
      disabled={disabled || loading}
      {...props}
    >
      {/* 로딩 스피너 */}
      {loading && (
        <div className="w-4 h-4 border-2 border-transparent border-t-current rounded-full animate-spin" />
      )}
      {children}
    </button>
  );
};

export default Button;
