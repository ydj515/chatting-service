# Alertmanager On-call Wiring

이 문서는 Prometheus alert를 Alertmanager로 전달하고, severity 기준으로 Slack과 PagerDuty에 라우팅하는 운영 기준을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- Prometheus는 `infra/prometheus/rules/*.rules.yml` alert rule을 로드한다.
- Alertmanager는 Docker Compose `cluster` profile에서 `alertmanager` 서비스로 실행된다.
- Prometheus는 `alertmanager:9093/metrics`를 scrape해 Alertmanager notification 실패 metric을 수집한다.
- warning alert는 Slack-compatible webhook으로 보낸다.
- critical alert 또는 `release_blocking="true"` alert는 PagerDuty로 보낸다.
- PagerDuty notification 실패 self-monitoring alert는 `severity="warning"`으로 정의해 Slack으로 보낸다.
- Slack webhook URL과 PagerDuty Events API v2 Integration Key는 repo에 저장하지 않는다.

### 목표

- Prometheus `alerting.alertmanagers`가 `alertmanager:9093`으로 알림을 전달한다.
- Alertmanager route가 `severity`와 `release_blocking` label로 receiver를 분리한다.
- alert rule은 `owner`, `runbook`, `release_gate` 메타데이터를 포함한다.
- critical Redis Streams lag/pending alert는 `release_blocking="true"`로 표시한다.
- PagerDuty delivery 실패는 `AlertmanagerPagerDutyNotificationFailures` alert로 감지하고 PagerDuty가 아닌 Slack으로 전달한다.

## 2. 구성 파일

| 파일 | 역할 |
| --- | --- |
| `infra/alertmanager/alertmanager.yml` | Alertmanager route와 receiver 설정 |
| `infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample` | Slack webhook URL sample 파일 |
| `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample` | PagerDuty routing key sample 파일 |
| `infra/prometheus/prometheus.yml` | Alertmanager target과 scrape/rule 설정 |
| `infra/prometheus/alert-smoke-prometheus.yml` | synthetic alert smoke 전용 Prometheus 설정 |
| `infra/prometheus/rules/*.rules.yml` | Prometheus alert rule |
| `infra/prometheus/rules/alertmanager-self-monitoring.rules.yml` | PagerDuty notification 실패를 Slack warning으로 보내는 self-monitoring rule |
| `infra/prometheus/smoke-rules/alertmanager-smoke.rules.yml` | `alert-smoke` profile에서만 평가하는 synthetic alert rule |
| `scripts/lib/alertmanagerConfig.mjs` | Alertmanager config renderer |
| `scripts/lib/alertmanagerConfig.test.mjs` | Alertmanager/Compose/Prometheus contract test |
| `scripts/lib/alertmanagerSelfMonitoringRules.mjs` | Alertmanager self-monitoring rule renderer |
| `scripts/lib/alertmanagerSelfMonitoringRules.test.mjs` | PagerDuty 실패 Slack fallback rule contract test |
| `scripts/lib/alertmanagerSmokeRules.mjs` | synthetic smoke rule renderer |
| `scripts/lib/alertmanagerSmokeRules.test.mjs` | smoke profile isolation contract test |

## 3. Secret 주입

`*_sample` 파일은 민감하지 않은 예시다. 실제 Compose 기본값은 sample이 아니라 `.gitignore` 처리된 실제 secret 파일을 읽는다.

```bash
cp infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample \
  infra/alertmanager/secrets/alertmanager_slack_webhook_url
cp infra/alertmanager/secrets/alertmanager_pagerduty_routing_key_sample \
  infra/alertmanager/secrets/alertmanager_pagerduty_routing_key
```

설정해야 하는 값:

| 파일 | 설정 값 |
| --- | --- |
| `infra/alertmanager/secrets/alertmanager_slack_webhook_url` | Slack-compatible webhook URL |
| `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key` | PagerDuty Events API v2 Integration Key |

현재 Alertmanager 설정은 `pagerduty_configs.routing_key_file`을 사용한다. 따라서 `alertmanager_pagerduty_routing_key`에는 PagerDuty REST API token이나 Prometheus URL이 아니라 **PagerDuty Events API v2 Integration Key**를 넣어야 한다. 발급 절차는 [PagerDuty Events API v2 Integration Key 발급 절차](./pagerduty_events_api_v2_integration_key.md)를 따른다.

repo 밖의 secret 파일을 쓰려면 Compose 실행 시 파일 경로를 넘긴다.

```bash
mkdir -p .secrets
printf '%s' 'https://hooks.slack.com/services/XXX/YYY/ZZZ' > .secrets/alertmanager_slack_webhook_url
printf '%s' 'pagerduty-routing-key' > .secrets/alertmanager_pagerduty_routing_key

CHAT_ADMIN_TOKEN='change-me' \
ALERTMANAGER_SLACK_WEBHOOK_URL_FILE=.secrets/alertmanager_slack_webhook_url \
ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE=.secrets/alertmanager_pagerduty_routing_key \
docker compose --profile cluster up -d alertmanager prometheus
```

## 4. Routing Matrix

| 조건 | Receiver | 대상 | 운영 의미 |
| --- | --- | --- | --- |
| `release_blocking="true"` | `pagerduty-critical` | PagerDuty | release 중단과 즉시 온콜 대응 |
| `severity="critical"` | `pagerduty-critical` | PagerDuty | 즉시 온콜 대응 |
| `severity="warning"` | `slack-warning` | Slack | 근무 시간 또는 비동기 triage |
| 기타 | `slack-warning` | Slack | 누락 label이 있어도 조용히 유실하지 않음 |

## 5. PagerDuty Delivery Self-monitoring

`AlertmanagerPagerDutyNotificationFailures` rule은 Alertmanager 자체 metric을 보고 PagerDuty notification request 실패를 감지한다.

```promql
increase(alertmanager_notification_requests_failed_total{integration="pagerduty"}[5m]) > 0
```

이 rule은 다음 이유로 `severity="warning"`만 사용한다.

- PagerDuty delivery가 깨졌을 때 같은 PagerDuty receiver로 알리면 장애를 놓칠 수 있다.
- warning route는 Slack으로 가므로 fallback 채널 역할을 한다.
- `release_blocking="true"`를 붙이지 않아 PagerDuty critical route와 섞이지 않는다.

Prometheus가 이 metric을 볼 수 있도록 기본 `prometheus`와 `prometheus-alert-smoke`는 모두 `alertmanager:9093/metrics`를 scrape한다.

실행 중인 stack에서는 다음 query로 실패 누적을 볼 수 있다.

```promql
alertmanager_notification_requests_failed_total{integration="pagerduty"}
```

실패가 발생하면 Alertmanager 로그에는 `Notify for alerts failed` 또는 retry 관련 로그가 남을 수 있다. 이 self-monitoring alert 자체는 Slack receiver로 전달되어야 한다.

## 6. 검증 절차

```bash
node --test scripts/lib/alertmanagerConfig.test.mjs \
  scripts/lib/alertmanagerSelfMonitoringRules.test.mjs \
  scripts/lib/phase7RedisStreamsAlertRules.test.mjs \
  scripts/lib/phase8RoomSeqGapAlertRules.test.mjs

CHAT_ADMIN_TOKEN=test docker compose --profile cluster --profile dev config --quiet

docker run --rm \
  -v "$PWD/infra/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  -v "$PWD/.secrets:/run/secrets:ro" \
  --entrypoint /bin/amtool \
  prom/alertmanager:v0.27.0 check-config /etc/alertmanager/alertmanager.yml

docker run --rm \
  -v "$PWD/infra/prometheus:/etc/prometheus:ro" \
  --entrypoint /bin/promtool \
  prom/prometheus:v2.53.0 check config /etc/prometheus/prometheus.yml
```

실행 중인 stack에서는 다음 endpoint로 연결 상태를 확인한다.

```bash
curl -s http://127.0.0.1:9093/-/ready
curl -s http://127.0.0.1:9090/api/v1/alertmanagers
curl -s http://127.0.0.1:9090/api/v1/targets
```

## 7. Firing Smoke 절차

`alert-smoke` profile은 기본 Prometheus와 분리된 `prometheus-alert-smoke` 서비스를 띄운다. 이 서비스만 `infra/prometheus/smoke-rules/alertmanager-smoke.rules.yml`을 읽으므로, 일반 `cluster` profile에서는 synthetic alert가 firing되지 않는다.

```bash
CHAT_ADMIN_TOKEN='change-me' \
ALERTMANAGER_SLACK_WEBHOOK_URL_FILE=.secrets/alertmanager_slack_webhook_url \
ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE=.secrets/alertmanager_pagerduty_routing_key \
docker compose --profile alert-smoke up -d alertmanager prometheus-alert-smoke
```

다음 alert 두 개가 firing되어야 한다.

| Alert | Severity | Receiver | 목적 |
| --- | --- | --- | --- |
| `AlertmanagerSmokeWarning` | `warning` | Slack | Slack-compatible webhook delivery 확인 |
| `AlertmanagerSmokeCritical` | `critical` | PagerDuty | PagerDuty incident/escalation 확인 |

실제 delivery smoke 결과 증거:

- [Slack warning 수신 결과](./images/alert-slack.png)
- [PagerDuty critical incident 수신 결과](./images/alert-pagerduty.png)

상태 확인:

```bash
curl -s http://127.0.0.1:${PROMETHEUS_ALERT_SMOKE_PORT:-9094}/api/v1/alerts
curl -s http://127.0.0.1:9093/api/v2/alerts
```

확인 후에는 반복 발송을 막기 위해 smoke Prometheus를 내린다.

```bash
docker compose --profile alert-smoke stop prometheus-alert-smoke
docker compose --profile alert-smoke rm -f prometheus-alert-smoke
```

## 8. Silence 절차

작업 중 의도된 alert는 Alertmanager silence로 묶는다. silence는 반드시 종료 시간을 둔다.

```bash
docker compose exec -T alertmanager \
  amtool --alertmanager.url=http://127.0.0.1:9093 \
  silence add 'alertname=RedisStreamsGroupLagCritical' \
  --duration=30m \
  --comment='planned worker restart'
```

synthetic smoke를 반복 실행할 때는 필요하면 `smoke="true"` matcher로 짧은 silence를 만든 뒤 receiver 설정 변경만 확인할 수 있다. 단, 실제 delivery smoke를 할 때는 silence를 걸지 않는다.

## 9. 복잡도

- Alertmanager route 평가 시간 복잡도: `O(R)`
- firing alert group 유지 공간 복잡도: `O(A)`
- PagerDuty failure self-monitoring rule 평가 시간 복잡도: `O(S)`
- PagerDuty failure self-monitoring rule 공간 복잡도: `O(S)`
- smoke rule 평가 시간 복잡도: `O(1)`
- config renderer 시간 복잡도: `O(N)`
- config renderer 공간 복잡도: `O(N)`

여기서 `R`은 route 개수, `A`는 firing alert 수, `S`는 Alertmanager notification 실패 시계열 수, `N`은 설정 항목 수다.

## 10. 주의사항

> - `infra/alertmanager/secrets/*_sample`은 로컬 예시이며 실제 온콜 연결 값이 아니다.
> - `infra/alertmanager/secrets/alertmanager_slack_webhook_url`과 `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key`는 `.gitignore` 대상이다.
> - 운영에서는 secret 파일 권한을 제한하고, K8s 전환 시 Secret 또는 ExternalSecret으로 대체한다.
> - warning까지 PagerDuty로 보내면 alert fatigue가 커질 수 있으므로 현재 warning은 Slack으로만 보낸다.
> - PagerDuty notification 실패 self-monitoring alert는 PagerDuty로 보내면 안 된다. 현재처럼 Slack warning route에 남겨야 한다.
> - `release_blocking="true"` label은 배포 중단 신호다. 단순 warning rule에 붙이지 않는다.
> - `prometheus-alert-smoke`는 `vector(1)` rule을 평가하므로 서비스가 켜져 있는 동안 synthetic alert가 계속 firing된다.
> - local Compose는 routing wiring을 검증한다. 실제 Slack/PagerDuty delivery와 escalation policy는 staging secret으로 `alert-smoke` profile을 켜서 확인한다.

## 11. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| PagerDuty failure self-monitoring을 Slack warning으로 전송 | PagerDuty 경로 장애를 다른 채널에서 감지한다 | Slack까지 동시에 장애면 놓칠 수 있다 | 선택 |
| PagerDuty failure self-monitoring도 PagerDuty로 전송 | 설정이 단순하다 | PagerDuty 경로가 깨진 상황을 같은 경로로 알려 놓칠 수 있다 | 제외 |
| Prometheus synthetic rule 별도 profile | Prometheus에서 Alertmanager까지 전체 경로를 검증한다 | 켜져 있는 동안 계속 firing된다 | 선택 |
| Alertmanager API 직접 POST | 빠르고 일회성이다 | Prometheus alerting 설정은 검증하지 못한다 | 보조 |
| 실제 Redis lag 유발 | 실제 장애 rule 품질까지 확인한다 | 부하와 데이터 리스크가 크다 | 제외 |

## 12. 후속 질문

- K8s 전환 시 Alertmanager를 kube-prometheus-stack으로 흡수할 것인가, 별도 chart로 유지할 것인가?
- Slack webhook을 환경별 채널에서 따로 발급할 것인가?
- alert-smoke profile을 CI staging job으로 자동화할 것인가?
