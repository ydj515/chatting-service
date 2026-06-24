# Phase 7 Reconnect Chaos Recovery SLO Gate

이 문서는 WebSocket reconnect chaos orchestrator에 장애 주입 후 storm 완료까지의 wall-clock SLO gate를 추가한 기준을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- `scripts/phase7-reconnect-chaos.mjs`는 gateway fault를 주입하고 `scripts/phase7-reconnect-load.mjs` child의 reconnect gate 결과를 병합한다.
- 기존 gate는 ticket 발급, WebSocket handshake, rate limit, cohort 실패율만 release-blocking으로 본다.
- Phase 7 다음 후보는 주입 시점부터 storm 완료까지의 wall-clock 기준선을 release gate에 포함하는 것이다.
- dry-run은 실제 storm을 실행하지 않으므로 복구 시간 gate를 평가하지 않는다.

### 목표

- `--max-recovery-slo-ms` 옵션으로 reconnect chaos recovery SLO를 설정한다.
- 기본 기준선은 `30000ms`로 시작한다.
- 실행 summary에 `recoveryElapsedMs`, `maxRecoverySloMs`, `recoverySloMet`을 남긴다.
- SLO 초과 시 top-level `failedGates`에 `recovery_slo_ms`를 추가하고 release-blocking으로 판정한다.

## 2. 실행 방법

```bash
# 기본 30초 SLO로 rolling restart chaos gate 실행
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart --execute

# 기준선을 12초로 좁혀 회귀 감지
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart --execute \
  --max-recovery-slo-ms 12000

# hard kill fault도 같은 SLO gate를 적용
node scripts/phase7-reconnect-chaos.mjs --fault gateway-hard-kill --execute \
  --max-recovery-slo-ms 30000
```

## 3. Summary Contract

```json
{
  "faultMode": "gateway-rolling-restart",
  "dryRun": false,
  "injectedContainers": ["chat-websocket-app-1", "chat-websocket-app-2"],
  "injectionOffsetMs": 120,
  "recoveryElapsedMs": 8200,
  "maxRecoverySloMs": 30000,
  "recoverySloMet": true,
  "reconnect": {
    "ok": true,
    "scenario": "gateway-rolling-restart",
    "failedGates": []
  },
  "failedGates": [],
  "releaseBlocking": false
}
```

SLO 초과 예:

```json
{
  "recoveryElapsedMs": 32000,
  "maxRecoverySloMs": 30000,
  "recoverySloMet": false,
  "failedGates": ["recovery_slo_ms"],
  "releaseBlocking": true
}
```

## 4. Gate 판정

| 항목 | 기준 |
| --- | --- |
| reconnect storm gate | child summary의 `ok === true` |
| recovery SLO gate | `recoveryElapsedMs <= maxRecoverySloMs` |
| dry-run | 항상 non-blocking, `recoveryElapsedMs: null` |

release-blocking 판정:

- reconnect storm gate 실패 → release-blocking.
- recovery SLO 초과 → release-blocking.
- 둘 다 실패하면 `failedGates`는 child gate 이름과 `recovery_slo_ms`를 함께 포함한다.

## 5. 복잡도

- SLO 판정 시간 복잡도: `O(1)`
- summary 병합 시간 복잡도: `O(F)`
- 공간 복잡도: `O(F)`

여기서 `F`는 failed gate 수다.

## 6. 주의사항

> - `recoveryElapsedMs`는 첫 fault 주입 시각부터 reconnect-load child 종료 시각까지다. gateway health 복구 폴링 시간이 아니라 storm 완료 시간이다.
> - `30000ms`는 staging 출발 기준선이다. 정상 부하와 CI 성능 편차를 관측한 뒤 더 좁히는 것이 안전하다.
> - child reconnect gate 실패와 SLO 초과는 독립 원인으로 기록된다. 실패 원인을 하나로 합쳐 해석하지 않는다.
> - `--execute`는 실제 gateway를 restart/kill하므로 Compose 로컬 또는 CI 전용으로 사용한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| orchestrator summary에 wall-clock SLO 추가 | 기존 chaos runner와 같은 release gate 모델을 유지한다 | 실제 gateway health 복구 시각은 별도 측정하지 않는다 | 선택 |
| reconnect-load 내부 duration만 gate로 사용 | child 측정값을 재사용한다 | fault 주입 전 등록 시간이 섞이거나 주입 시점 기준을 잃는다 | 제외 |
| health polling recovery SLO 추가 | 실제 gateway 복구 상태를 직접 본다 | reconnect storm gate와 범위가 커지고 별도 polling runner가 필요하다 | 후속 |

## 8. 후속 질문

- staging baseline 후 기본 `30000ms`를 fault mode별로 더 좁힐 것인가?
- recovery SLO 실패 시 Prometheus snapshot이나 docker logs를 자동 capture할 것인가?
- nightly CI에서 rolling restart와 hard kill을 모두 release-blocking으로 실행할 것인가?
