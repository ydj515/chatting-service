import React, { useCallback, useRef, useState } from 'react';
import { Info } from 'lucide-react';

interface InfoTooltipProps {
  // 표시할 설명 문구
  tip: string;
  // 접근성 라벨
  label?: string;
}

type HAlign = 'start' | 'center' | 'end';
type VAlign = 'above' | 'below';

// 어려운 항목 옆에 두는 도움말 아이콘 + 툴팁.
// admin의 .help 컴포넌트와 동일하게 화살표 + 가장자리 자동 보정을 제공한다.
// 색상은 @theme 토큰(CSS 변수)을 참조하므로 라이트/다크에서 자동으로 반전된다.
const InfoTooltip: React.FC<InfoTooltipProps> = ({ tip, label = '도움말' }) => {
  const ref = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [h, setH] = useState<HAlign>('center');
  const [v, setV] = useState<VAlign>('above');

  // 아이콘 위치를 측정해 화면 가장자리에서 잘리지 않도록 방향 결정
  const place = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    if (rect.left < 150) setH('start');
    else if (window.innerWidth - rect.right < 150) setH('end');
    else setH('center');
    setV(rect.top < 140 ? 'below' : 'above');
  }, []);

  const show = useCallback(() => {
    place();
    setOpen(true);
  }, [place]);
  const hide = useCallback(() => setOpen(false), []);

  const bubbleStyle: React.CSSProperties = {
    position: 'absolute',
    zIndex: 50,
    width: 'max-content',
    maxWidth: 260,
    padding: '9px 11px',
    fontSize: 12,
    fontWeight: 500,
    lineHeight: 1.5,
    letterSpacing: '-0.01em',
    textAlign: 'left',
    whiteSpace: 'normal',
    background: 'var(--color-gray-900)',
    color: 'var(--color-gray-50)',
    borderRadius: 'var(--radius-sm)',
    boxShadow: 'var(--shadow-lg)',
    ...(v === 'above' ? { bottom: 'calc(100% + 9px)' } : { top: 'calc(100% + 9px)' }),
    ...(h === 'center'
      ? { left: '50%', transform: 'translateX(-50%)' }
      : h === 'start'
        ? { left: -6 }
        : { right: -6 }),
  };

  const arrowStyle: React.CSSProperties = {
    position: 'absolute',
    zIndex: 51,
    width: 0,
    height: 0,
    left: '50%',
    transform: 'translateX(-50%)',
    border: '6px solid transparent',
    ...(v === 'above'
      ? { bottom: 'calc(100% + 3px)', borderTopColor: 'var(--color-gray-900)' }
      : { top: 'calc(100% + 3px)', borderBottomColor: 'var(--color-gray-900)' }),
  };

  return (
    <span
      ref={ref}
      tabIndex={0}
      role="img"
      aria-label={label}
      className="relative inline-flex items-center justify-center shrink-0 align-middle text-text-tertiary cursor-help rounded-full outline-none transition-colors duration-150 hover:text-primary focus-visible:text-primary"
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      <Info size={15} strokeWidth={2} />
      {open && (
        <>
          <span style={arrowStyle} aria-hidden="true" />
          <span style={bubbleStyle} role="tooltip">
            {tip}
          </span>
        </>
      )}
    </span>
  );
};

export default InfoTooltip;
