# PagerDuty Events API v2 Integration Key 발급 절차

이 문서는 Alertmanager의 `pagerduty_configs.routing_key_file`에 넣을 PagerDuty Events API v2 Integration Key 발급 절차를 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- 현재 Alertmanager receiver는 `routing_key_file`을 사용한다.
- `routing_key_file`에는 PagerDuty Events API v2 Integration Key가 필요하다.
- PagerDuty REST API token, Prometheus URL, Email integration 주소는 이 값이 아니다.
- 발급받은 값은 `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key` 또는 `ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE`로 지정한 파일에 저장한다.
- `ALERTMANAGER_PAGERDUTY_ENABLED=false`이면 key 파일이 있어도 critical alert는 PagerDuty로 가지 않고 Slack receiver로 fallback 전송된다.

### 목표

- PagerDuty Service에 Events API v2 integration을 추가한다.
- 생성된 Integration Key를 Alertmanager secret 파일에 저장한다.
- `alert-smoke` profile로 critical alert delivery를 확인할 수 있게 한다.

## 2. 발급 절차

1. [PagerDuty Integrations](https://www.pagerduty.com/integrations/)에 접속한다. 
2. `Services` > `Service Directory`로 이동한다. [이미지](./images/1.service-menu.png)
3. 기존 서비스를 선택하거나 새 Service를 생성한다. 예: `chatting-service` [이미지](./images/2.create-service.png)
4. escalation policy는 새로 만들거나 기존 policy를 선택한다. [이미지](./images/3.assign-policy.png)
5. ai ops를 선택한다. [이미지](./images/4-1.ai-ops.png), [이미지](./images/4-2.ai-ops.png)
6. `Add integration`을 클릭한다. [이미지](./images/5.integration.png)
7. `Integration Type`에서 `Events API v2`를 선택한다.
8. 저장하면 `Integration Key`가 생성된다.
9. 그 값을 `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key`에 넣는다.

```bash
printf '%s' '발급받은-Events-API-v2-Integration-Key' \
  > infra/alertmanager/secrets/alertmanager_pagerduty_routing_key
```

repo 밖의 secret 파일을 쓰는 경우:

```bash
mkdir -p .secrets
printf '%s' '발급받은-Events-API-v2-Integration-Key' \
  > .secrets/alertmanager_pagerduty_routing_key

ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE=.secrets/alertmanager_pagerduty_routing_key \
docker compose --profile cluster up -d alertmanager prometheus
```

PagerDuty delivery를 검증하거나 운영 호출을 켜려면 `ALERTMANAGER_PAGERDUTY_ENABLED=true`를 사용한다. 임시로 PagerDuty 호출만 끄고 critical alert를 Slack으로 받으려면 `ALERTMANAGER_PAGERDUTY_ENABLED=false`를 지정한다.

## 3. Integration Type 선택 기준

| PagerDuty Integration Type | Alertmanager 설정         | 이 repo와 일치 |
|----------------------------|-------------------------|------------|
| `Events API v2`            | `routing_key_file`      | 예          |
| `Prometheus`               | 별도 receiver 설정 필요       | 아니오        |
| `Email`                    | 별도 email receiver/주소 필요 | 아니오        |

현재 Compose 설정은 `Events API v2` 기준이다. PagerDuty UI에서 `Prometheus` 또는 `Email` integration type을 선택하면 현재 `routing_key_file` 설정과 맞지 않는다.

## 4. 검증

설정 파일 검증:

```bash
tmpdir=$(mktemp -d)
cp infra/alertmanager/secrets/alertmanager_slack_webhook_url_sample "$tmpdir/alertmanager_slack_webhook_url"
cp infra/alertmanager/secrets/alertmanager_pagerduty_routing_key "$tmpdir/alertmanager_pagerduty_routing_key"
ALERTMANAGER_PAGERDUTY_RECEIVER=pagerduty-critical
sed "s|\${ALERTMANAGER_PAGERDUTY_RECEIVER}|${ALERTMANAGER_PAGERDUTY_RECEIVER}|g" \
  infra/alertmanager/alertmanager.yml > "$tmpdir/alertmanager.yml"

docker run --rm \
  -v "$tmpdir/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  -v "$tmpdir:/run/secrets:ro" \
  --entrypoint /bin/amtool \
  prom/alertmanager:v0.27.0 check-config /etc/alertmanager/alertmanager.yml

rm -rf "$tmpdir"
```

실제 delivery smoke:

```bash
CHAT_ADMIN_TOKEN='change-me' \
ALERTMANAGER_SLACK_WEBHOOK_URL_FILE=infra/alertmanager/secrets/alertmanager_slack_webhook_url \
ALERTMANAGER_PAGERDUTY_ROUTING_KEY_FILE=infra/alertmanager/secrets/alertmanager_pagerduty_routing_key \
docker compose --profile alert-smoke up -d alertmanager prometheus-alert-smoke
```

`AlertmanagerSmokeCritical` alert가 PagerDuty incident로 생성되는지 확인한 뒤 반복 발송을 막기 위해 smoke Prometheus를 내린다.

```bash
docker compose --profile alert-smoke stop prometheus-alert-smoke
docker compose --profile alert-smoke rm -f prometheus-alert-smoke
```

## 5. 복잡도

- key 파일 로딩 시간 복잡도: `O(1)`
- PagerDuty receiver routing 시간 복잡도: `O(R)`
- 문서 절차 실행 공간 복잡도: `O(1)`

여기서 `R`은 Alertmanager route 개수다.

## 6. 주의사항

> - Integration Key는 secret이다. Git에 커밋하지 않는다.
> - `infra/alertmanager/secrets/alertmanager_pagerduty_routing_key`는 `.gitignore` 대상이다.
> - `alert-smoke` profile은 켜져 있는 동안 synthetic critical alert를 계속 firing한다.
> - staging 또는 test service의 integration key로 먼저 검증한 뒤 production service에 적용한다.

## 7. 대안

| 대안                               | 장점                                               | 단점                                              | 판단 |
|----------------------------------|--------------------------------------------------|-------------------------------------------------|----|
| Events API v2 Integration Key    | 현재 `routing_key_file` 설정과 바로 맞는다                 | PagerDuty에서 Events API v2를 선택해야 한다              | 선택 |
| PagerDuty Prometheus integration | PagerDuty UI에서 Prometheus 전용 integration으로 관리 가능 | Alertmanager 설정 변경이 필요하다                        | 보류 |
| Email integration                | 가장 범용적이다                                         | Alertmanager email receiver와 메일 전달 경로가 추가로 필요하다 | 제외 |
