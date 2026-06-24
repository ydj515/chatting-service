# Phase 7 Reconnect Chaos Recovery SLO Gate 설계서

- 작성일: 2026-06-24
- 슬라이스: Reconnect chaos recovery SLO gate
- 상태: 구현 완료

## 1. 문제 이해 / 요구사항 정리

### 조건

- `phase7-reconnect-chaos`는 gateway fault 주입과 reconnect storm을 겹쳐 실행한다.
- 기존 release gate는 child reconnect summary의 `ok`만 본다.
- Phase 7 인덱스의 후보 E는 주입→storm 완료 wall-clock SLO 기준선을 추가하는 것이다.
- 기존 `phase7-chaos-runner`는 `sloMs`/`recoverySloMet`으로 wall-clock SLO를 summary에 남기는 선례가 있다.

### 목표

- reconnect chaos orchestrator에 `--max-recovery-slo-ms` 옵션을 추가한다.
- 기본 SLO는 rolling restart와 hard kill 모두 `30000ms`로 둔다.
- 실행 summary에 `recoveryElapsedMs`, `maxRecoverySloMs`, `recoverySloMet`을 추가한다.
- SLO 초과를 `recovery_slo_ms` failed gate로 노출하고 exit code `1`로 연결한다.

### 비범위

- gateway health polling 추가.
- Prometheus snapshot 또는 docker log 자동 수집.
- fault mode별 production 최종 SLO 확정.

## 2. 해결 접근

### 선택한 접근

`scripts/lib/phase7ReconnectChaosPlan.mjs`에 순수 summary 판정을 추가하고, runner는 첫 fault 주입 시각부터 reconnect-load child 종료 시각까지를 `recoveryElapsedMs`로 측정한다.

### 이유

- fault 주입 시점을 기준으로 하므로 storm 등록 시간이나 dry-run 계획 시간이 섞이지 않는다.
- 기존 reconnect rate gate를 유지하면서 wall-clock 회귀만 top-level gate로 추가할 수 있다.
- 단위 테스트에서 SLO 초과, child gate 실패, dry-run을 독립적으로 검증할 수 있다.

## 3. Contract

| 필드 | 의미 |
| --- | --- |
| `maxRecoverySloMs` | CLI 또는 fault mode 기본값으로 정한 SLO 기준선 |
| `recoveryElapsedMs` | 첫 fault 주입부터 child storm 종료까지의 ms |
| `recoverySloMet` | dry-run이거나 `recoveryElapsedMs <= maxRecoverySloMs` |
| `failedGates[]` | child failed gates + SLO 초과 시 `recovery_slo_ms` |
| `releaseBlocking` | child gate 실패 또는 SLO 초과 |

## 4. 복잡도

- CLI 파싱 시간 복잡도: `O(A)`
- summary 병합 시간 복잡도: `O(F)`
- SLO 판정 시간 복잡도: `O(1)`
- 공간 복잡도: `O(F)`

여기서 `A`는 CLI 인자 수, `F`는 failed gate 수다.

## 5. 주의사항

> - `recoveryElapsedMs`는 health recovery 시간이 아니라 reconnect storm completion 시간이다.
> - dry-run은 실제 fault와 child storm이 없으므로 `recoveryElapsedMs`를 `null`로 둔다.
> - `30000ms`는 conservative baseline이며, staging 결과로 조정해야 한다.
> - reconnect child gate와 recovery SLO gate는 원인이 다르므로 `failedGates`에 둘 다 남긴다.

## 6. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| orchestrator summary에서 wall-clock 측정 | 주입 시점 기준이 명확하고 구현 범위가 작다 | health 복구 자체는 직접 측정하지 않는다 | 선택 |
| reconnect-load `durationSeconds`를 gate로 사용 | 기존 child summary만 보면 된다 | fault 주입 시점 기준이 아니며 등록 시간이 섞인다 | 제외 |
| 별도 health polling recovery gate 추가 | 인프라 복구 시간을 직접 검증한다 | 슬라이스가 커지고 기존 chaos runner와 중복된다 | 후속 |

## 7. 후속 질문

- fault mode별 SLO를 rolling restart `15000ms`, hard kill `30000ms`처럼 분리할 것인가?
- SLO 초과 시 plan capture처럼 artifact를 자동 남길 것인가?
- nightly CI에서 이 gate를 어떤 빈도로 실행할 것인가?
