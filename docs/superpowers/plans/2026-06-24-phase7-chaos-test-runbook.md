# Phase 7 Chaos Test Runbook 구현 계획

설계 문서: [2026-06-24-phase7-chaos-test-runbook-design.md](../specs/2026-06-24-phase7-chaos-test-runbook-design.md)

## 1. 구현 순서 (TDD)

1. `scripts/lib/phase7ChaosPlan.test.mjs`에 순수 로직 테스트를 먼저 작성한다.
2. `scripts/lib/phase7ChaosPlan.mjs`에 테스트를 통과하는 최소 구현을 작성한다.
3. `node --test scripts/lib/phase7ChaosPlan.test.mjs`로 green을 확인한다.
4. `scripts/phase7-chaos-runner.mjs` 얇은 runner를 작성한다(docker/HTTP I/O, 폴링).
5. `node scripts/phase7-chaos-runner.mjs --scenario worker-kill`(dry-run)로 계획 출력을 확인한다.
6. `docs/phase7_chaos_test_runbook.md` 운영 문서를 작성한다.
7. `docs/phase7_slices.md` 인덱스를 갱신한다.

## 2. lib 공개 함수 (테스트 단위)

| 함수 | 책임 | 핵심 테스트 케이스 |
| --- | --- | --- |
| `parseChaosArgs(argv, env)` | CLI/env → options | 기본 dry-run, `--execute` 토글, `--no-restore`, `--checks` 파싱, unknown arg 에러 |
| `buildChaosScenario(options)` | scenario 정의(target, inject action, SLO, required checks) | 4개 시나리오 매핑, `--target` 오버라이드, 알 수 없는 scenario 에러 |
| `evaluateRecoveryCheck(check, observations)` | 체크 1개 독립 판정 | recovered true/false, recoveryMs 기록, lag threshold 비교 |
| `summarizeChaosRun(input)` | per-check + aggregate gate | 전체 복구, 부분 복구(health pass·lag fail), SLO 초과, dry-run |
| `exitCodeForChaosSummary(summary)` | exit code 도출 | 0/1/2, dry-run=0 |
| `buildInjectCommand(scenario)` | docker 인자 배열 | kill vs restart, restore 명령 |

## 3. runner 책임 (테스트하지 않음)

- `docker compose ps -q <service>`로 컨테이너 id 조회.
- dry-run이면 inject 계획만 출력하고 종료(exit 0).
- `--execute`면 inject 실행 → (restore) → 복구 폴링(health/functional/lag) → summarize → exit code.
- HTTP 폴링은 `AbortController` timeout을 쓰고 기존 runner의 request 헬퍼 패턴을 따른다.

## 4. 검증

- `node --test scripts/lib/phase7ChaosPlan.test.mjs` green.
- `node --test scripts/lib/` 전체 회귀 green.
- `node scripts/phase7-chaos-runner.mjs --scenario worker-kill --json` dry-run 출력 확인.
- 각 시나리오 dry-run JSON에 injectedContainer, checks, sloMs가 포함되는지 확인.

## 5. 남은 리스크

- 실제 `--execute` 복구 검증은 Compose 스택이 떠 있어야 하므로 이 슬라이스에서는 dry-run까지만 자동 검증한다.
- 시나리오 SLO 기본값은 baseline 측정 전 잠정값이며, 후속 질문으로 남긴다.
