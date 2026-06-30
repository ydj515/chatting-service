import React from 'react';
import { ChevronDown, X } from 'lucide-react';
import Field from '@/components/ui/Field.tsx';
import Input from '@/components/ui/Input.tsx';

export type AdvancedKey = 'senderId' | 'from' | 'to' | 'limit';
export type AdvancedValues = Record<AdvancedKey, string>;
export type AdvancedSetters = Record<AdvancedKey, (value: string) => void>;

interface AdvancedDef {
  key: AdvancedKey;
  label: string;
  def: string;
  display?: (value: string) => string;
}

const ADVANCED_DEFS: AdvancedDef[] = [
  { key: 'senderId', label: 'Sender ID', def: '' },
  { key: 'from', label: 'From', def: '', display: (value) => value.replace('T', ' ') },
  { key: 'to', label: 'To', def: '', display: (value) => value.replace('T', ' ') },
  { key: 'limit', label: 'Limit', def: '50' },
];

interface AdvancedFiltersProps {
  advancedOpen: boolean;
  values: AdvancedValues;
  setters: AdvancedSetters;
  onOpenChange: (open: boolean) => void;
}

// 상세 필터 영역의 반응형 그리드 (480px·720px 분기 보존)
const ADVANCED_GRID =
  'mt-3 grid grid-cols-1 min-[480px]:grid-cols-2 min-[720px]:grid-cols-4 gap-x-4 gap-y-3 items-end';

const AdvancedFilters: React.FC<AdvancedFiltersProps> = ({
  advancedOpen,
  values,
  setters,
  onOpenChange,
}) => {
  const activeAdvanced = ADVANCED_DEFS.filter((def) => {
    const value = values[def.key].trim();
    return value !== '' && value !== def.def;
  });

  const clearAdvanced = (key: AdvancedKey, def: string) => {
    setters[key](def);
  };

  const resetAdvanced = () => {
    ADVANCED_DEFS.forEach((def) => setters[def.key](def.def));
  };

  return (
    <>
      <div className="border-t border-border-light pt-1">
        <button
          type="button"
          className="inline-flex items-center gap-1.5 w-max px-1 py-2 bg-transparent border-none cursor-pointer text-[13px] font-semibold text-primary outline-none select-none hover:text-primary-hover"
          aria-expanded={advancedOpen}
          onClick={() => onOpenChange(!advancedOpen)}
        >
          <span
            className={`inline-flex transition-transform duration-200 ${advancedOpen ? 'rotate-180' : ''}`}
            aria-hidden="true"
          >
            <ChevronDown size={16} />
          </span>
          상세 필터
          {activeAdvanced.length > 0 && (
            <span
              className="inline-flex items-center justify-center min-w-[18px] h-[18px] px-1.5 rounded-full bg-primary text-white text-[11px] font-bold tabular-nums leading-none"
              aria-label="적용된 상세 필터 개수"
            >
              {activeAdvanced.length}
            </span>
          )}
        </button>
        {advancedOpen && (
          <div className={ADVANCED_GRID}>
            <Field label="Sender ID" tip="특정 보낸 사람(사용자 ID)으로 결과를 좁힙니다. 비우면 모든 발신자를 포함합니다.">
              <Input
                inputMode="numeric"
                value={values.senderId}
                onChange={setters.senderId}
              />
            </Field>
            <Field label="From" tip="조회 시작 시각입니다. 이 시각 이후에 생성된 메시지만 포함합니다.">
              <Input
                type="datetime-local"
                value={values.from}
                onChange={setters.from}
              />
            </Field>
            <Field label="To" tip="조회 종료 시각입니다. 이 시각 이전에 생성된 메시지만 포함합니다.">
              <Input
                type="datetime-local"
                value={values.to}
                onChange={setters.to}
              />
            </Field>
            <Field label="Limit" tip="한 번에 가져올 최대 행 수입니다. 기본값은 50입니다.">
              <Input
                inputMode="numeric"
                value={values.limit}
                onChange={setters.limit}
              />
            </Field>
          </div>
        )}
      </div>

      {activeAdvanced.length > 0 && (
        <div className="flex items-center gap-2 flex-wrap min-h-8">
          {activeAdvanced.map((def) => {
            const value = values[def.key].trim();
            const shown = def.display ? def.display(value) : value;
            return (
              <span
                className="inline-flex items-center gap-1.5 h-8 pl-3 pr-1.5 rounded-full bg-primary-light border border-primary/30 text-primary text-[13px] font-semibold whitespace-nowrap"
                key={def.key}
              >
                <span>
                  {def.label} · <b className="font-bold tabular-nums">{shown}</b>
                </span>
                <button
                  type="button"
                  className="w-[18px] h-[18px] inline-flex items-center justify-center border-0 rounded-full bg-transparent text-primary cursor-pointer hover:bg-primary/15"
                  aria-label={`${def.label} 필터 제거`}
                  onClick={() => clearAdvanced(def.key, def.def)}
                >
                  <X size={14} />
                </button>
              </span>
            );
          })}
          <button
            type="button"
            className="h-8 px-2 border-0 bg-transparent text-text-secondary text-[13px] font-semibold cursor-pointer hover:text-text-primary"
            onClick={resetAdvanced}
          >
            전체 해제
          </button>
        </div>
      )}
    </>
  );
};

export default AdvancedFilters;
