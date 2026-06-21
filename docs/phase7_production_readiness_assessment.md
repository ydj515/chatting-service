# Phase 7까지 구현 가정 시 Production Readiness 평가

- 작성일: 2026-06-21
- 평가 대상: `docs/superpowers/specs/2026-06-11-high-traffic-chat-service-design.md` Phase 0 ~ Phase 7
- 목적: (1) Phase 6 구현 완료 여부 검증, (2) Phase 7까지 모두 구현했다고 가정했을 때 실서비스(production)에서 남는 공백 평가

---

## 1. Phase 6 구현 완료 검증

### 1.1 결론

Phase 6의 외부 계약과 완료 기준은 **기능적으로 모두 구현되어 있다.** 설계서가 나열한 파일명(`RateLimitService.kt`, `LiveFeedPolicyService.kt`, `RoomPolicyDto.kt`)은 실제로는 다른 이름의 클래스로 구현되었으나, 책임은 동일하게 존재한다.

| Phase 6 요구 | 설계서 예상 파일 | 실제 구현 | 상태 |
| --- | --- | --- | --- |
| Fanout owner lease | `FanoutOwnerLeaseService.kt` | `FanoutOwnerLeaseService.kt` (313줄, `SET NX PX` + renew/release Lua + fencing token) | 완료 |
| Owner gate fanout | `HotRoomFanoutWorker.kt` | `HotRoomFanoutWorker.kt` (owner일 때만 `XREADGROUP`/publish/`XACK`, publish/ack 직전 token 재검증) | 완료 |
| Room heat 분류 | `RoomHeatClassifier.kt` | `RoomHeatClassifier.kt` (`NORMAL/HOT/VERY_HOT/OVERLOAD`) | 완료 |
| Rate limit / slow mode | `RateLimitService.kt` | `MessageAdmissionPolicyService.kt` + `RedisMessageAdmissionPolicyService` (room 단위 `{roomId}` hash tag Lua 1회, fail-closed, 429/`MESSAGE_ADMISSION_REJECTED`) | 완료 |
| 자동 downgrade | (room-policy worker) | `RoomPolicyWorker.kt` + `RoomPolicyAutoDowngradeService.kt` (60초 snapshot + `RoomPolicySignalProvider` lag 합성, `auto_policy_enabled` 보호) | 완료 |
| Live feed 정책 | `LiveFeedPolicyService.kt` | `room_storage_configs` 정책 컬럼 + 클라이언트 bounded window + `BoundedOutboundSessionQueue` (overflow 시 `1013` close) | 완료 |
| Admin override | `AdminChatController.kt` | admin policy override + audit log + cache evict + `clearRateLimit/clearUserRateLimit/clearSlowMode` flag | 완료 |
| moderator priority | - | `moderatorPriority=true`일 때 `OWNER/ADMIN` admission 우회 | 완료 |
| Smoke 검증 | `scripts/phase6-fanout-takeover-smoke.mjs` | 존재. `sent=600`, `receivedPerViewer=[600,600,600]`, `assertedRoomSeqOrder=true` 통과 기록 | 완료 |

`docs/production_hardening_tasks.md`의 항목 4(Fanout Owner Lease), 항목 5(Message Admission Lua Policy)도 모두 **"적용 완료"**로 기록되어 있어 검증 결과와 일치한다.

### 1.2 실제 등록된 Phase 6 metric (코드 확인)

```
chat.fanout.owner.lease.acquire / renew / lost
chat.fanout.owner.rooms / takeovers / token_mismatch
chat.message.admission.rejected
chat.websocket.ticket.events / issue.latency / rate_limit.script.failures
```

### 1.3 Phase 6 단계에서 의도적으로 남긴 항목

설계서에 명시된 의도적 보류이며 Phase 6 미완료가 아니다.

- `subscriber_only`는 스키마 컬럼만 준비, 강제 로직 없음(`MemberRole`에 subscriber 도메인 부재).
- server-side backpressure는 drop-oldest가 아니라 `1013 Outbound queue full` close + gap fill 경로.
- `chat.fanout.owner.takeovers`의 production 계측 semantics 정합성 확정은 Phase 7 task로 분리.

> **Phase 6 결론:** 완료. 이후 평가는 "Phase 7까지 모두 끝냈다"는 가정 위에서 진행한다.

---

## 2. Phase 7 범위 요약

Phase 7은 **새 기능이 아니라 운영 검증/계측/릴리즈 게이트**다. 핵심 작업:

1. Nginx stale upstream 대응(2026-06-20 사전 점검에서 실제 재현됨) — 최우선
2. Fanout owner kill takeover flake 처리 및 raw delivery vs client-visible 분리 검증
3. k6/Gatling/Node 부하 테스트, WebSocket fan-out p95/p99, Redis Streams lag/pending, writer batch latency, replica lag, admin search warm/cold latency 계측
4. chaos test(Gateway/Worker kill, Redis 재시작, replica 지연)
5. `chat.fanout.owner.takeovers` metric semantics 정합성 확정
6. 장애별 runbook, rollback 기준

즉 **Phase 7를 "완료"하려면 현재 코드에 없는 대량의 계측 코드를 새로 추가해야 한다.** 이 점이 평가의 핵심 전제다.

---

## 3. Production 공백 평가 (Phase 7까지 했다고 가정)

각 항목을 심각도로 분류한다.

- **Blocker**: 이 상태로는 실서비스 트래픽/SLA를 받을 수 없음
- **High**: 공개 직후 장애/데이터/보안 리스크가 큼
- **Medium**: 운영 품질/확장성 저하, 단기 운영은 가능
- **Low**: 알려진 trade-off 또는 장기 대안

### 3.1 Blocker

#### B-1. Redis 단일 인스턴스 (SPOF)

- 현재: `docker-compose.yml`은 `redis:7.2-alpine` 단일 노드. `redis.conf`는 `appendonly yes`, `appendfsync everysec`, RDB save.
- 문제: Redis는 sequence 발급, Streams ingest, admission, presence, fanout owner lease, pub/sub의 **단일 의존점**이다. 단일 노드 장애 = 전체 메시지 수락/실시간 경로 중단.
- 코드는 Redis Cluster hash tag(`{roomId}`)를 준비했지만 **실제 Cluster/Sentinel/replica 배포는 없다.**
- `appendfsync everysec`는 장애 시 최대 약 1초 분량 ingest 유실 가능(설계서 13장도 인정).
- Phase 7에도 Redis Cluster 도입 task는 없음.

> Redis Cluster(또는 최소 Sentinel + replica) 미도입 시, 10,000 msg/sec hot room을 단일 Redis가 sequence INCR + Streams append + admission Lua로 직렬 처리하게 되어 처리량/가용성 모두 한계다.

#### B-2. Object Storage 부재 + Cold Archive 미구현

- 현재: `AdminMessageExportWorker`는 `java.nio.file.Files`로 **로컬 파일시스템에 CSV** 작성(`exportDirectory`). S3 client 코드, MinIO/S3 compose 서비스 모두 없음.
- archive worker는 100일 초과 partition **detach/drop**만 수행, Object Storage로의 cold archive는 없음 → retention 초과분은 보존이 아니라 사실상 삭제.
- 설계서 6.7/8.3은 S3 cold archive + 만료 URL export를 전제로 함.
- 영향: 멀티 인스턴스 환경에서 export 파일이 특정 worker 노드 로컬 디스크에만 존재 → 다운로드 URL 제공/감사/법적 보관 불가.

#### B-3. 핵심 observability metric 대량 미구현

코드에 실제 등록된 metric은 fanout owner / admission / ticket 계열뿐이다. `observability_metrics.md`가 release gate로 요구하는 다음 metric은 **코드에 없다**(일부는 문서에서 명시적 TODO):

- Gateway/Fan-out: active connections, send queue depth, outbound bytes/sec, batch frames/sec, WebSocket write latency p95/p99, slow client disconnect, reconnect rate
- Ingest/Worker: messages accepted/rejected per sec, Redis Streams lag/pending gauge, writer success/failure, writer batch latency, fanout worker lag, fanout batch size
- PostgreSQL: partition write latency, replica lag, admin search warm/cold latency, search projection lag, archive run duration
- Reconnect: `ticket.issue.outcomes`, `reconnect.attempts`, `sequential_overcount.suspected` (문서상 TODO)

> Phase 7 release gate("writer lag 3초 이하", "fan-out p95 500ms 이하", "admin search warm p95 1초 이하" 등)는 **측정 수단 자체가 없으면 판정 불가**다. 이 계측 코드를 채우는 것이 Phase 7의 가장 큰 실작업이며, 미구현 시 release gate는 형식적으로만 통과한다.

#### B-4. hot room shard 실제 분산 미적용 (10k msg/sec 목표 충돌)

- `streamShard` 기본 count = 1 (`ChatRedisProperties.shardCount = 1`, `RoomStorageConfigJdbcRepository.DEFAULT_SHARD_COUNT = 1`).
- stream shard가 1이면 fanout owner도 방당 1개 owner가 전 메시지를 직렬 `XREADGROUP`/publish → owner lease는 순서를 지키지만 **단일 owner가 10,000 msg/sec를 혼자 처리**해야 한다.
- 설계서는 Phase 3에서 shard count 1로 두고 hot room 분산은 "이후"로 미룸. Phase 6/7 어디에도 shard count 동적 확장의 구현/검증 task가 명시적으로 없다.
- 결과: Phase 7 release gate "10,000 msg/sec 60초 유지"를 통과하려면 shard 확장 + 방별 owner 분산이 선행돼야 하는데 그 구현 공백이 남는다.

### 3.2 High

#### H-1. 전송 구간 암호화/시크릿 관리 부재

- `infra/`에 TLS/SSL 설정 없음. WebSocket은 `ws://`(wss 아님), nginx TLS termination 미설정.
- DB 연결, Redis 연결 암호화/`requirepass`(AUTH) 미설정.
- `.env` 평문 시크릿. Vault/KMS/Secret manager 미연동.
- HMAC session token 서명 키 회전 전략 없음.

> 공개 인터넷 서비스라면 wss + nginx TLS는 사실상 필수. 내부망 한정이라도 시크릿 평문 보관은 운영 보안 기준에 미달.

#### H-2. 인증 토큰 폐기/회전(revocation) 부재

- `HmacSessionTokenService`는 만료(TTL)만 검증. 로그아웃/제재/유출 시 **즉시 무효화(revoke) 경로 없음**.
- refresh token, 강제 로그아웃, 키 롤오버 미구현.
- WebSocket one-time ticket(Phase 2.5)은 잘 되어 있으나, 그 ticket을 발급받는 기반 session token의 수명 관리가 약하다.

#### H-3. 배포/오케스트레이션 (Kubernetes) 부재

- 설계서 10장은 K8s + HPA(connection/lag/queue 기반) 운영을 전제하나, **K8s manifest/Helm 전무**. Docker Compose만 존재.
- nginx static upstream stale 문제(Phase 7 최우선 task)는 근본적으로 K8s Service/Ingress의 stable endpoint로 해결되는 문제다. Compose 운영을 고수하면 resolver 기반 동적 upstream 또는 배포 시 nginx restart 절차에 계속 의존.
- HPA 없이는 hot room 급증 시 Gateway/Worker 자동 확장 불가 → 수동 스케일.

#### H-4. moderation / 콘텐츠 필터 미구현

- admission은 rate limit + slow mode만 처리. 설계서 6.2/9.5의 **금칙어, 스팸, 도배(중복 메시지) 필터, 신규/비인증 계정 차등**은 미구현.
- 삭제는 `is_deleted` tombstone 존재(부분 구현), 사용자 제재(ban/mute) 도메인은 빈약.

### 3.3 Medium

#### M-1. hot room 전용 Gateway pool / room-aware routing 부재

- 설계서 9.3은 hot room 전용 Gateway group + room-aware routing 권장. 현재 nginx는 단순 분산, room 기반 라우팅 없음.
- 일반 방과 hot room이 같은 Gateway pool을 공유 → hot room이 일반 방 사용자 경험까지 저하시킬 수 있음.

#### M-2. 분산 부하 발생기 부재

- 부하 검증이 단일 노드 Node 스크립트(`load-chat.mjs`) 기반. 10,000 viewers / 10,000 msg/sec를 단일 발생기로 신뢰성 있게 생성하기 어렵다.
- Phase 7가 k6/Gatling 도입을 명시하나, 분산 부하(여러 부하 노드) 환경 구성은 별도 인프라 작업.

#### M-3. Admin export atomic manifest 미적용

- `production_hardening_tasks.md` 항목 3에 기록됨. 현재 chunk append + cursor checkpoint resume까지만 구현 → crash 시 마지막 chunk 중복 가능(데이터 유실 아님).
- export 결과가 감사/법무/정산 자료가 되는 시점에 hardening 필요. 이미 알려진 trade-off.

#### M-4. takeover flake 미해결 (Phase 7 진행 중 항목)

- 2026-06-20 사전 점검에서 `roomSeq order violated: 77 came after 600` flake 1회 관측, 재실행 통과.
- Phase 7가 raw delivery(diagnostic) vs client-visible(release blocking) 분리 검증으로 다루기로 했으나, **release gate 확정 전까지는 미해결 리스크**.

### 3.4 Low (알려진 trade-off / 장기 대안)

- **OpenSearch 미도입**: 의도적. PostgreSQL FTS/trigram first. 검색 SLA 초과 시 도입(설계 6.8 기준 명확).
- **ScyllaDB/Cassandra 미도입**: 의도적 장기 대안.
- **ws-ticket user/IP rate limit 완전 원자성 미적용**: 단일 key Lua + fail-closed로 충분한지 Phase 7 지표로 판단하기로 명문화됨. 기준 초과 시에만 검토.
- **`subscriber_only` 강제 로직 부재**: 구독 도메인 부재로 컬럼만 준비.

---

## 4. DR / 백업 (전 단계 누락)

Phase 0~7 어디에도 명시적 task가 없는 운영 필수 영역:

- PostgreSQL 정기 백업 / PITR(Point-in-Time Recovery) 절차
- Redis 백업(AOF/RDB) 보관 및 복구 리허설
- multi-AZ / 리전 장애 대응
- 데이터 복구 RTO/RPO 정의

> chaos test(Phase 7)는 "장애 시 서비스가 견디는가"를 보지만, "데이터를 어떻게 복구하는가(백업 복원)"는 별도다. 둘을 혼동하면 안 된다.

---

## 5. 종합 판정

| 구분 | 항목 | Phase 7가 다루는가 |
| --- | --- | --- |
| Blocker | B-1 Redis SPOF | 아니오 (신규 인프라 필요) |
| Blocker | B-2 Object Storage / cold archive | 아니오 |
| Blocker | B-3 핵심 metric 대량 미구현 | 부분 (Phase 7 핵심 실작업이나 구현량 큼) |
| Blocker | B-4 shard 분산 / 10k msg/sec | 게이트로만 존재, 구현 task 불명확 |
| High | H-1 TLS/시크릿 | 아니오 |
| High | H-2 토큰 revocation | 아니오 |
| High | H-3 K8s/HPA | 부분 (nginx 라우팅만) |
| High | H-4 moderation | 아니오 |
| Medium | M-1 hot room Gateway 분리 | 아니오 |
| Medium | M-2 분산 부하 발생기 | 부분 |
| Medium | M-3 export atomic manifest | 아니오 (hardening 후보) |
| Medium | M-4 takeover flake | 예 (진행 중) |
| - | DR/백업 | 아니오 (전 단계 누락) |

**결론:** Phase 7까지 문서 정의대로 완료하더라도, Phase 7은 본질적으로 "검증/계측/릴리즈 게이트"이므로 **신규 인프라성 공백(Redis Cluster, Object Storage, TLS, K8s, DR)은 그대로 남는다.** 특히 B-1~B-4는 Phase 7 release gate를 실제로 통과하기 위한 선행 조건인데도 명시적 구현 단계가 없어, "Phase 7 완료 = production ready"가 성립하지 않는다.

---

## 6. 배포 타깃별 달라지는 포인트

전제: **현재는 Docker Compose(단일 VM 가정)로 운영하고, 추후 Kubernetes 전환을 계획한다.** 이를 위해 `chat-api`, `chat-websocket`, `chat-worker`, `chat-admin`을 개별 실행 모듈/이미지로 분리해 두었다. 이 분리는 Compose에서는 `--scale`로, K8s에서는 모듈별 독립 Deployment + 개별 HPA로 자연스럽게 이어진다. 즉 모듈 분리 구성 자체는 두 타깃 모두에 유효하고, **재작성이 아니라 배포 계층(라우팅/스케일/HA/시크릿)만 교체**하면 된다.

아래는 같은 공백이라도 타깃에 따라 해법이 갈리는 지점이다. 애플리케이션 코드는 대부분 공통이고, 갈리는 것은 인프라/배포 계층이다.

| 항목 | Docker Compose (현재) | Kubernetes (추후) | 코드 영향 |
| --- | --- | --- | --- |
| B-1 Redis HA | Sentinel + replica 컨테이너, 또는 단일 + AOF로 한시 운영 | Redis Operator / Redis Cluster, PV 기반 | 없음 — hash tag(`{roomId}`)가 이미 Cluster 전제로 준비됨. client 연결 설정만 분기 |
| B-2 Object Storage | MinIO 컨테이너 | 동일 MinIO(인클러스터) 또는 매니지드 S3 | 동일 S3 호환 client 추상화 1벌이면 양쪽 공용. endpoint/credential만 설정 |
| H-1 TLS/wss | nginx에서 TLS termination + 인증서 마운트 | Ingress(또는 Gateway API) + cert-manager 자동 갱신 | 없음 — TLS는 엣지에서 종단 |
| H-3 라우팅/오라우팅 | nginx **static upstream** → 컨테이너 재생성 시 stale IP(=Phase 7 최우선 task의 원인). resolver 기반 동적 upstream 또는 배포 후 nginx restart 절차 필요 | Service/Ingress의 **stable virtual endpoint** → stale upstream 문제 자체가 소멸 | 없음 — 배포 계층 문제 |
| 스케일링 | `docker compose up -d --scale chat-websocket-app=N` 수동 | 모듈별 HPA(connection/lag/queue metric 기반) 자동 | 없음 — 단, HPA는 B-3 metric을 외부(Prometheus Adapter 등)로 노출해야 동작 |
| M-1 hot room Gateway 분리 | hot room 전용 websocket service group + nginx upstream 분리, room-aware 라우팅은 앱/엣지에서 수동 구성 | 전용 Deployment + label/affinity + Service 분리로 깔끔 | 라우팅 정책(어떤 방을 어느 pool로) 코드/설정은 양쪽 공통으로 설계 필요 |
| DR/백업 | 호스트 볼륨 스냅샷/덤프 스크립트, 단일 VM 한계(노드 장애 = 전체 정지) | PV snapshot, operator backup, multi-AZ/노드 분산 | 없음 — 운영 절차 |
| nginx stale upstream (Phase 7 1순위 task) | **존재**. Compose 운영 기간 동안 resolver/restart 절차로 막아야 함 | **소멸**. 전환과 동시에 해당 task와 synthetic check는 회귀 방지용으로만 축소 | 없음 |

설계 시사점:

- **지금 들이는 인프라 작업이 K8s에서 버려지지 않도록**, Object Storage는 S3 호환 추상화로, Redis는 Cluster hash slot 전제(이미 충족)로, TLS는 엣지 종단으로 둔다. 이러면 Compose→K8s 전환 시 애플리케이션 코드 변경이 최소화된다.
- **버려지는 작업을 최소화한다.** nginx static upstream stale 대응은 Compose 기간 한정 비용이다. 여기에 과투자(복잡한 resolver 동적 라우팅)하기보다, "배포 후 nginx restart + role routing synthetic check" 수준으로 막고, 근본 해결은 K8s 전환에 맡기는 것이 비용 대비 합리적이다.
- **B-3 metric은 두 타깃 모두에서 가치가 있다.** Compose에서는 release gate 판정용, K8s에서는 거기에 더해 HPA 입력값이 된다. 그래서 metric은 어느 타깃이든 Phase 7에서 먼저 해야 하는 공통 토대다.

## 7. 권장 실행 전략 (하이브리드)

방향: **토대 인프라(측정 대상)는 Phase 7-pre로 선행하고, 계측·게이트 판정(측정 수단/행위)은 Phase 7에 흡수한다.** "측정 대상이 작업 중간에 바뀌지 않게" 하면서 "측정 도구 없이 게이트를 형식 통과시키지 않게" 하는 절충이다.

### 7.1 Phase 7-pre — 토대 선행 (측정 대상 고정)

Compose 기준으로 먼저 안정화하되, K8s에서 재사용 가능한 형태로 만든다.

1. **B-1 Redis HA**: Compose는 Sentinel + replica(또는 한시적으로 단일 + AOF 운영 기준 명문화). hash tag는 이미 준비됨.
2. **B-2 Object Storage + cold archive**: MinIO 컨테이너 + S3 호환 client 추상화. archive worker의 detach/drop 전에 cold archive 경로 추가.
3. **B-4 일부**: stream/fanout shard count를 방별로 늘릴 수 있는 구조 정비(현재 default=1). 실제 10k 분산 검증은 Phase 7 본체로 넘김.
4. **H-1 TLS/wss**: nginx TLS termination(엣지 종단). K8s 전환 시 Ingress+cert-manager로 자리만 이동.

### 7.2 Phase 7 본체 — 계측·검증·게이트 흡수

1. **B-3 metric 전면 계측을 가장 먼저** 붙인다(Gateway/Ingest/PG/reconnect). 이게 release gate 판정 수단이자, 토대 구축 후 부하 신호를 보는 도구다.
2. Nginx stale upstream 대응(Compose 한정) + role routing synthetic check.
3. 부하 테스트(k6/Gatling), fan-out p95/p99, Streams lag/pending, writer/replica lag, admin search warm/cold.
4. chaos test(Gateway/Worker kill, Redis 재시작, replica 지연).
5. **B-4 10k msg/sec 분산 부하 검증** + shard 분산 효과 확인.
6. fanout takeover metric semantics 정합성 확정(M-4), takeover flake 분리 검증.
7. 장애별 runbook, rollback 기준.

### 7.3 Phase 8 이후 — K8s 전환 및 보안/운영

1. **K8s 전환**: 모듈별 Deployment + 개별 HPA, Service/Ingress(=H-3, nginx stale 문제 소멸), Redis Operator, PV 기반 저장소. Phase 7-pre에서 만든 토대(S3 추상화, Cluster 전제, 엣지 TLS)를 그대로 이식.
2. **M-1 hot room 전용 Gateway pool** + room-aware 라우팅.
3. **보안/운영**: 토큰 revocation/회전, 시크릿 관리(Vault/KMS), moderation 파이프라인.
4. **DR/백업**: PV snapshot, 백업 보관·복구 리허설, RTO/RPO 정의.
5. **Hardening 후보 승격**: export atomic manifest(M-3), ws-ticket 완전 원자성(지표 초과 시).

### 7.4 한 줄 요약

> 토대는 앞으로(Phase 7-pre), 계측은 Phase 7 맨 앞으로, 게이트 판정은 맨 뒤로. 배포는 Compose로 시작하되 모든 토대는 K8s에서 그대로 재사용되도록 S3 추상화·Redis Cluster 전제·엣지 TLS로 둔다.

---

## 8. 주의사항

> - 이 평가는 "Phase 7 정의대로 완료"를 가정한 것이며, 현재 코드 기준으로는 Phase 7 metric 대부분이 아직 미구현 상태다. 따라서 실제 잔여 작업량은 본 문서의 공백 + Phase 7 자체 구현량의 합이다.
> - Redis 단일 노드, 로컬 파일 export는 로컬/단일 노드 데모에서는 정상 동작하므로, 기능 테스트 통과와 production 준비 완료를 혼동하면 안 된다.
> - "Blocker"는 트래픽/SLA를 받는 공개 서비스 기준이다. 내부 PoC/제한 사용자 베타라면 일부 Blocker는 High로 완화될 수 있다.
> - shard count는 외부 계약이 아니라 운영 설정값이지만, 과거 bucket의 shard count는 유지되므로(설계 7.2) 확장 시점·전략을 미리 정해야 조회 복잡도가 통제된다.
> - **배포 타깃은 Compose→K8s로 결정**되었으므로, Phase 7-pre 토대는 반드시 K8s 재사용 가능한 형태(S3 추상화, Redis Cluster 전제, 엣지 TLS)로 만든다. Compose 한정 작업(nginx static upstream stale 대응)에는 과투자하지 않는다.
> - 단일 VM Compose 운영 기간에는 노드 장애가 곧 전체 정지이므로, 이 기간의 가용성 기대치를 운영 합의로 낮춰 두어야 한다(B-1 한시 운영 기준).

## 9. 후속 질문 (Next Questions)

- Compose 운영 기간의 Redis 가용성을 Sentinel + replica로 갖출 것인가, 단일 + AOF로 한시 운영하며 가용성 기대치를 낮출 것인가?
- B-4 shard 분산을 Phase 7-pre에서 "구조만" 정비하고 10k 검증은 Phase 7 본체로 미루는 본 안에 동의하는가?
- 10,000 msg/sec 목표를 1차 공개부터 release gate로 강제할 것인가, 더 낮은 목표로 시작하고 K8s 전환 후 상향할 것인가? (낮추면 B-4가 Blocker에서 빠진다)
- Phase 7-pre / Phase 7 본체 / Phase 8을 각각 별도 실행 플랜 문서로 분리해 드릴까?
