# Phase 8.1 Observability Pipeline + Gateway/Fan-out Metrics 설계서

- 작성일: 2026-06-25
- 슬라이스: Phase 8.1 관측 파이프라인 + Gateway/Fan-out 실측 metric
- 상태: 구현 완료
- 구현 브랜치: `feat/phase8-1-observability`
- 계획 문서: `docs/superpowers/plans/2026-06-25-phase8-1-observability-pipeline-gateway-metrics.md`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 7에서 Redis Streams lag/pending, fanout owner, reconnect chaos, admin search gate 같은 측정 수단을 만들었다.
- 그러나 Docker Compose 전체 stack에는 Prometheus scrape pipeline과 Grafana dashboard가 없어서 release gate를 상시 관측하기 어렵다.
- Gateway/Fan-out 경로의 핵심 운영 신호(active connections, send queue depth, outbound bytes, local delivery, write latency, slow client disconnect)가 비어 있거나 placeholder였다.
- `RoomPolicyWorker`의 `gatewaySendQueueDepth` 입력은 기본 provider가 `0`을 반환해 실제 Gateway backpressure를 `OVERLOAD` 판정에 반영하지 못했다.
- production readiness 평가의 Blocker B-3을 닫기 위해, Compose 기준 관측 pipeline과 실측 metric을 먼저 완성해야 한다.

### 목표

- `micrometer-registry-prometheus`와 actuator `prometheus` endpoint를 활성화한다.
- Prometheus와 Grafana 컨테이너를 Compose에 추가하고 role별 scrape target을 구성한다.
- 기존 Redis Streams alert rule을 Prometheus rule path에 연결한다.
- bounded-cardinality 원칙을 지키는 `WebSocketGatewayMetrics`를 추가한다.
- `WebSocketSessionManager` lifecycle/send path에 Gateway metric을 배선한다.
- Gateway send queue depth를 `RoomPolicySignalProvider`로 연결해 `OVERLOAD` 판정에 실측값을 제공한다.
- PostgreSQL read replica lag을 Prometheus gauge로 노출한다.
- release gate dashboard에 Redis Streams, Gateway, replica lag 패널을 제공한다.

### 비범위

- Alertmanager routing, notification receiver, paging policy는 이 슬라이스에서 다루지 않는다.
- 전체 stack e2e 기동 후 Prometheus target up, Grafana dashboard 렌더 확인은 무거운 운영 검증으로 남긴다.
- admin 응답 DTO의 `searchP95LatencyMs` 직접 주입은 별도 admin metric 슬라이스로 분리한다.
- reconnect rate metric은 WebSocket ticket/reconnect gate 소관으로 후속 슬라이스에서 다룬다.

## 2. 해결 접근

### 선택한 접근

Spring Boot/Micrometer의 표준 Prometheus registry를 추가하고, Compose 내부의 Prometheus가 각 role 앱의 `/actuator/prometheus`를 scrape한다. Gateway metric은 `ObjectProvider<MeterRegistry>` 기반의 optional registry 패턴을 사용해 테스트와 non-observability runtime에서 안전하게 동작한다.

RoomPolicy signal은 Gateway queue depth만 우선 실측으로 연결한다. writer/fanout lag는 이미 `chat.redis.stream.group.lag` gauge로 존재하므로, RoomPolicy 합성은 후속 작업에서 다룬다.

### 이유

- Prometheus/Grafana는 Phase 9 Kubernetes 전환 때도 그대로 재사용 가능한 운영 표준 구성이다.
- Micrometer registry를 사용하면 기존 Spring Boot actuator와 자연스럽게 통합된다.
- metric tag cardinality를 `gatewayGroup`, `outcome`, `reason` 같은 bounded 값으로 제한해 Prometheus 비용 폭증을 피한다.
- Gateway send queue depth는 slow client/backpressure를 가장 빠르게 반영하므로 `OVERLOAD` 자동 판정의 첫 실측 입력으로 적합하다.

## 3. 설계 상세

### Prometheus scrape endpoint

- `gradle/libs.versions.toml`에 `micrometer-registry-prometheus`를 추가한다.
- `chat-persistence/build.gradle.kts`에 Prometheus registry 의존성을 추가한다.
- `application-docker.yml`의 actuator exposure에 `prometheus`를 포함한다.
- nginx 외부 경로에서는 actuator 노출을 차단해 운영 표면을 줄인다.

### Compose observability stack

- `prometheus` 서비스는 app role별 target을 scrape한다.
- `grafana` 서비스는 Prometheus datasource와 dashboard provider를 provisioning으로 로딩한다.
- Prometheus/Grafana host port는 `127.0.0.1`에 bind해 로컬 운영 도구로 제한한다.
- 기존 Redis Streams lag alert rule directory를 Prometheus rule path로 mount한다.

### Gateway/Fan-out metric

`WebSocketGatewayMetrics`는 다음 계열 metric을 제공한다.

- active WebSocket connections gauge
- room subscription count gauge
- send queue depth gauge
- local delivery counter
- outbound payload bytes counter
- batch frame counter
- WebSocket write latency timer
- slow client disconnect counter

모든 tag는 bounded 값만 사용한다. `roomId`, `userId`, `sessionId`, message id, raw IP는 tag로 사용하지 않는다.

### RoomPolicy signal 연결

`GatewaySendQueueRoomPolicySignalProvider`는 `WebSocketSessionManager`의 pending queue depth를 읽어 `RoomPolicySignals.gatewaySendQueueDepth`에 제공한다. worker/websocket module bean 충돌을 피하기 위해 주입은 `ObjectProvider` 기반으로 안전하게 처리한다.

### Replica lag gauge

`ReplicaLagGaugePublisher`는 기존 `ReadReplicaLagPolicy`의 replica lag 조회 로직을 이용해 `chat.postgres.replica.lag` gauge를 등록한다. scrape 시점 supplier gauge로 동작하므로 특정 scheduler role에 묶이지 않는다.

## 4. 구현 결과

구현 브랜치 `feat/phase8-1-observability` 기준 완료 항목:

- Prometheus scrape endpoint 활성화
- Prometheus/Grafana Compose 서비스와 role별 scrape config 추가
- `WebSocketGatewayMetrics` 및 단위 테스트 추가
- `WebSocketSessionManager` metric 배선
- send queue depth 기반 RoomPolicy signal provider 추가
- replica lag gauge publisher 추가
- Grafana release gate dashboard 6패널 추가
- nginx actuator 외부 차단
- Prometheus/Grafana host port `127.0.0.1` bind

검증된 항목:

- `bootJar` 빌드
- `docker compose config`
- Gateway/RoomPolicy/Replica lag 단위 테스트
- dashboard JSON 검증
- 전체 Gradle test 회귀

남은 운영 검증:

- 전체 stack 기동 후 Prometheus targets up 확인
- Grafana dashboard 실제 렌더 확인
- 실제 부하에서 Gateway queue depth, write latency, slow client disconnect 패널 값 검증

## 5. 복잡도

- scrape endpoint 생성 시간 복잡도: `O(M)` (`M`은 등록 metric 수)
- Prometheus scrape 비용: `O(T * M)` (`T`는 scrape target 수)
- Gateway metric record 시간 복잡도: `O(1)`
- send queue depth gauge 조회 시간 복잡도: 구현상 전체 pending 합산 기준 `O(S)` (`S`는 active session 수)
- dashboard 렌더 비용: `O(P * Q)` (`P`는 panel 수, `Q`는 PromQL query 수)
- 애플리케이션 추가 공간 복잡도: `O(M + S)` 수준의 meter/gauge 참조

## 6. 주의사항

> - Prometheus label에 `roomId`, `userId`, `sessionId`, message id, raw IP를 넣으면 cardinality 폭증이 발생한다.
> - actuator endpoint는 내부 scrape용이다. nginx 외부 경로에서 그대로 열면 운영 정보 노출 위험이 있다.
> - scrape target이 up이어도 dashboard query가 release gate 의미를 보장하지 않는다. 실제 부하와 failure scenario에서 패널 값을 확인해야 한다.
> - replica lag gauge는 조회 시점의 snapshot이다. 장기 추세와 alert 기준은 Prometheus recording/alert rule로 별도 조정해야 한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Prometheus/Grafana Compose 내장 | Phase 9 K8s 전환 시 재사용 가능하고 운영 표준에 가깝다 | Compose 구성과 provisioning 파일이 늘어난다 | 선택 |
| actuator `/metrics`만 사용 | 추가 인프라가 작다 | scrape/alert/dashboard pipeline이 없어 release gate가 수동화된다 | 제외 |
| StatsD/OpenTelemetry collector 우선 | vendor 중립성이 높다 | 현재 Spring/Micrometer + Prometheus 문맥보다 도입 비용이 크다 | 후순위 |

## 8. 후속 질문

- Phase 8.1 e2e로 Prometheus target up과 Grafana dashboard screenshot 검증을 별도 slice로 만들 것인가?
- writer/fanout lag gauge를 RoomPolicy signal에 합성하는 작업을 Phase 8.1 후속으로 둘 것인가?
- Alertmanager route와 notification policy를 Phase 8 관측 범위에 포함할 것인가?
