# Detekt baseline 검토 기록

2026-09-20 기준으로 기존 72개 항목을 모두 검토했다. 코드 수정으로 24개를 제거하고 48개를 유지한다.
새 규칙 예외나 suppression은 추가하지 않았다. `ChatServiceImpl`의 통계 오류 격리 1개는
`MessageStreamPublisher`로 책임과 함께 이동했다. 줄 길이·강제 줄바꿈 정책은 그대로다.

## 해결한 항목

- `ChatServiceImpl`: 사용하지 않는 의존성 제거, 메시지 정책/스트림 발행/멤버십 알림 분리.
- 일반·관리자 API의 쿼리 인자를 immutable 요청 객체로 묶고 HTTP 파라미터 이름·기본값 유지.
- 관리자 검색 조건과 테스트 시나리오 설정을 명시적 값 객체로 묶음.
- 감사 기록을 `AdminAuditRecorder`로 분리하고 제재 서비스의 생성자 의존성 축소.
- 엔티티 DTO 변환을 재사용 가능한 함수로 분리.
- shard 설정·export job·sequence 감사의 0행 처리를 nullable 결과로 변경. 중복 행·DB 오류는 계속 전파.
- 토큰 envelope/claims 파싱 분리, 커서의 구체적 파싱 예외 처리, 테스트 예외 타입 구체화.
- CSV 출력 옵션을 명시적으로 선택하고 export loop의 종료 조건 정리.
- room heat 정책 생성과 shard 값 보정 분리, 중복된 테스트 helper 인자 제거.

## 유지한 항목

48개는 Java 연동·JPA 초기화·오류 격리 계약에 대한 기존 예외다.
Export 업로드·설정, worker 역할별 스케줄러, WebSocket 방 구독·전송 책임을 분리해
남아 있던 구조 부채 4개를 제거했다. WebSocket 오류 격리 예외 2개는 전송 책임과 함께 이동했다.
Gateway 모듈 분리 시 큐·전송 클래스의 기존 예외 ID 3개를 persistence baseline에서
websocket baseline으로 이동했다. 예외 범위와 수는 늘리지 않았다.
Baseline은 자동 재생성하지 않으며 제거된 항목을 다시 추가하지 않는다.

| 규칙 | 위치 | 유지 사유와 재검토 조건 |
| --- | --- | --- |
| `SpreadOperator` | [chat-admin-application/ChatAdminApplication.kt](../../chat-admin-application/src/main/kotlin/com/chat/admin/application/ChatAdminApplication.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-admin/WebConfig.kt](../../chat-admin/src/main/kotlin/com/chat/admin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-admin/WebConfig.kt](../../chat-admin/src/main/kotlin/com/chat/admin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-admin/WebConfig.kt](../../chat-admin/src/main/kotlin/com/chat/admin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-api-application/ChatApiApplication.kt](../../chat-api-application/src/main/kotlin/com/chat/api/application/ChatApiApplication.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-api/WebConfig.kt](../../chat-api/src/main/kotlin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-api/WebConfig.kt](../../chat-api/src/main/kotlin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-api/WebConfig.kt](../../chat-api/src/main/kotlin/config/WebConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-application/ChatApplication.kt](../../chat-application/src/main/kotlin/ChatApplication.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `LongParameterList` | [chat-domain/ChatRoom.kt](../../chat-domain/src/main/kotlin/model/ChatRoom.kt) | JPA 매핑 필드를 생성자에서 명시적으로 초기화한다. 인자 수만 줄이기 위해 setter나 임의의 속성 묶음으로 바꾸지 않는다. 영속 모델 분리 시 재검토한다. |
| `LongParameterList` | [chat-domain/ChatRoomMember.kt](../../chat-domain/src/main/kotlin/model/ChatRoomMember.kt) | JPA 매핑 필드를 생성자에서 명시적으로 초기화한다. 인자 수만 줄이기 위해 setter나 임의의 속성 묶음으로 바꾸지 않는다. 영속 모델 분리 시 재검토한다. |
| `LongParameterList` | [chat-domain/Message.kt](../../chat-domain/src/main/kotlin/model/Message.kt) | JPA 매핑 필드를 생성자에서 명시적으로 초기화한다. 인자 수만 줄이기 위해 setter나 임의의 속성 묶음으로 바꾸지 않는다. 영속 모델 분리 시 재검토한다. |
| `LongParameterList` | [chat-domain/User.kt](../../chat-domain/src/main/kotlin/model/User.kt) | JPA 매핑 필드를 생성자에서 명시적으로 초기화한다. 인자 수만 줄이기 위해 setter나 임의의 속성 묶음으로 바꾸지 않는다. 영속 모델 분리 시 재검토한다. |
| `SpreadOperator` | [chat-persistence/AdminMessageRepository.kt](../../chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-persistence/AdminMessageRepository.kt](../../chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-persistence/ModerationRuleJdbcRepository.kt](../../chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-persistence/RedisMessageStreamConsumer.kt](../../chat-persistence/src/main/kotlin/redis/RedisMessageStreamConsumer.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-persistence/RedisMessageStreamConsumer.kt](../../chat-persistence/src/main/kotlin/redis/RedisMessageStreamConsumer.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-persistence/UserSanctionJdbcRepository.kt](../../chat-persistence/src/main/kotlin/repository/UserSanctionJdbcRepository.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SwallowedException` | [chat-persistence/AdminMessageRepository.kt](../../chat-persistence/src/main/kotlin/repository/AdminMessageRepository.kt) | 방 설정 미등록을 기본 상태로 변환한다. 빈 결과를 반환하는 조회 API로 전환할 때 예외 처리도 제거한다. |
| `SwallowedException` | [chat-persistence/FanoutOwnerLeaseService.kt](../../chat-persistence/src/main/kotlin/service/FanoutOwnerLeaseService.kt) | lease 갱신 실패 후 부가적인 원인 조회 실패를 흡수한다. 소유권은 이미 상실 처리하며 발행/ACK를 재개하지 않는다. |
| `SwallowedException` | [chat-persistence/JpaMessageWriteAdapter.kt](../../chat-persistence/src/main/kotlin/service/JpaMessageWriteAdapter.kt) | 동시 중복 삽입 후 기존 메시지를 재조회해 멱등 결과를 반환한다. 중복 처리 저장 계약을 바꿀 때 원인 예외 처리도 검토한다. |
| `SwallowedException` | [chat-persistence/ModerationRuleJdbcRepository.kt](../../chat-persistence/src/main/kotlin/repository/ModerationRuleJdbcRepository.kt) | UPDATE RETURNING의 0행을 명시적인 not-found 오류로 변환한다. nullable 조회 계약으로 전환할 때 제거한다. |
| `TooGenericExceptionCaught` | [chat-persistence/AdminMessageExportWorker.kt](../../chat-persistence/src/main/kotlin/service/AdminMessageExportWorker.kt) | 실패한 export를 FAILED로 기록하고 다음 스케줄 작업을 계속한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-websocket/BoundedOutboundSessionQueue.kt](../../chat-websocket/src/main/kotlin/service/BoundedOutboundSessionQueue.kt) | sender 실패 시 큐를 닫고 onFailure를 호출해 멈춘 큐와 세션 자원 누수를 방지한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/MessageStreamPublisher.kt](../../chat-persistence/src/main/kotlin/service/MessageStreamPublisher.kt) | 스트림 발행에 성공한 뒤 통계 갱신 실패로 응답이 실패하지 않게 한다. 기존 ChatServiceImpl의 경계를 이동했다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/FanoutOwnerLeaseService.kt](../../chat-persistence/src/main/kotlin/service/FanoutOwnerLeaseService.kt) | Redis 오류 시 소유권을 획득/유지한 것으로 판단하지 않는다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/HotRoomFanoutWorker.kt](../../chat-persistence/src/main/kotlin/service/HotRoomFanoutWorker.kt) | 레코드 실패를 pending/dead-letter 처리하며 소유권 상실 후 ACK하지 않는다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/MessageAdmissionPolicyService.kt](../../chat-persistence/src/main/kotlin/service/MessageAdmissionPolicyService.kt) | 정책 평가 실패를 발행 허용으로 바꾸지 않고 명시적인 거절로 변환한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/MessageWriterWorker.kt](../../chat-persistence/src/main/kotlin/service/MessageWriterWorker.kt) | 배치 실패 후 레코드별 재시도·중복 처리·dead-letter 분기를 보장한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/ReadReplicaLagPolicy.kt](../../chat-persistence/src/main/kotlin/service/ReadReplicaLagPolicy.kt) | lag 측정 실패 시 primary로 전환한다. 오류 타입만 좁히면 기존 fallback 보장이 달라진다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RedisMessageBroker.kt](../../chat-persistence/src/main/kotlin/redis/RedisMessageBroker.kt) | 초기화·수신 오류를 격리한다. fanout 발행 오류는 worker에 전파하여 실패한 메시지를 ACK하지 않는다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RedisMessageStreamConsumer.kt](../../chat-persistence/src/main/kotlin/redis/RedisMessageStreamConsumer.kt) | consumer group 생성의 BUSYGROUP 충돌만 허용하고 나머지는 다시 던진다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RedisMessageStreamProducer.kt](../../chat-persistence/src/main/kotlin/redis/RedisMessageStreamProducer.kt) | known-stream 등록 실패 시 로컬 캐시를 되돌린 후 원래 예외를 다시 던진다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RedisSessionControlBroker.kt](../../chat-persistence/src/main/kotlin/service/RedisSessionControlBroker.kt) | 손상된 제어 이벤트나 처리 실패가 Redis listener 수명주기로 전파되지 않게 한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RedisWebSocketTicketService.kt](../../chat-persistence/src/main/kotlin/service/RedisWebSocketTicketService.kt) | 발급·소비·rate-limit 실패를 인증 허용으로 처리하지 않는 fail-closed 경계다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/ReplicaLagGaugePublisher.kt](../../chat-persistence/src/main/kotlin/service/ReplicaLagGaugePublisher.kt) | 측정 실패 시 마지막 값을 유지하고 주기적 수집이 중단되지 않게 한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RoomPolicyWorker.kt](../../chat-persistence/src/main/kotlin/service/RoomPolicyWorker.kt) | 한 방의 정책 적용 실패를 격리해 다른 방 처리를 계속한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RoomSeqGapAuditWorker.kt](../../chat-persistence/src/main/kotlin/service/RoomSeqGapAuditWorker.kt) | 감사 조회 실패를 기록하고 스케줄러의 다음 실행을 유지한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-persistence/RoomTrafficStatsService.kt](../../chat-persistence/src/main/kotlin/service/RoomTrafficStatsService.kt) | 관측용 통계 실패가 메시지 처리 자체를 실패시키지 않도록 한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-websocket/WebSocketSessionTransport.kt](../../chat-websocket/src/main/kotlin/service/WebSocketSessionTransport.kt) | 한 세션의 전송·ping·종료 실패를 격리하고 등록된 세션 자원을 정리한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-websocket/WebSocketSessionTransport.kt](../../chat-websocket/src/main/kotlin/service/WebSocketSessionTransport.kt) | 한 세션의 전송·ping·종료 실패를 격리하고 등록된 세션 자원을 정리한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `SpreadOperator` | [chat-websocket-application/ChatWebSocketApplication.kt](../../chat-websocket-application/src/main/kotlin/com/chat/websocket/application/ChatWebSocketApplication.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SpreadOperator` | [chat-websocket/WebSocketConfig.kt](../../chat-websocket/src/main/kotlin/config/WebSocketConfig.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |
| `SwallowedException` | [chat-websocket/ChatWebSocketHandler.kt](../../chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt) | 알 수 없거나 손상된 JSON의 메시지 타입 추출을 null로 처리한다. 파서 오류 응답 계약을 분리할 때 좁힌다. |
| `TooGenericExceptionCaught` | [chat-websocket/ChatWebSocketHandler.kt](../../chat-websocket/src/main/kotlin/handler/ChatWebSocketHandler.kt) | 개별 메시지 처리·오류 응답·세션 종료 실패가 다른 세션 처리로 전파되지 않도록 격리한다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `TooGenericExceptionCaught` | [chat-websocket/WebSocketHandshakeInterceptor.kt](../../chat-websocket/src/main/kotlin/interceptor/WebSocketHandshakeInterceptor.kt) | 인증 의존성 또는 입력 처리 실패 시 handshake를 거부하는 fail-closed 경계다. 포트의 오류 타입 계약과 해당 장애 테스트가 고정될 때 catch 범위를 축소한다. |
| `SpreadOperator` | [chat-worker-application/ChatWorkerApplication.kt](../../chat-worker-application/src/main/kotlin/com/chat/worker/application/ChatWorkerApplication.kt) | 기존 Java vararg API에 동적 배열을 전달한다. 동일 동작의 컬렉션 API가 있거나 배열 복사가 측정된 병목일 때 변경한다. 숫자를 줄이기 위한 Java wrapper는 만들지 않는다. |

## 검증

- baseline을 비운 임시 Detekt 검사로 기본/타입 해석 결과를 대조했다. 유지한 예외 때문에 이 감사 검사는 의도적으로 실패한다.
- 저장소의 정상 검증 명령은 `./gradlew check`다. 규칙, coverage threshold, CI 실패 조건은 완화하지 않는다.
- HTTP 바인딩, 메시지 멱등성·정책 거절·발행 실패·shard routing, 커밋 이후 멤버십 알림, nullable 조회와 장애 전파를 검증한다.

## 변경 코드 검토

CodeRabbit의 persistence 변경 검토에서 다음 두 항목을 확인했다.

- 커서 타입 불일치: `AdminMessageSearchCursor`는 `AdminMessageCursor`의 typealias다. 별도 변환이 필요하지 않으며 export 검색 테스트도 통과한다.
- 커밋 이후 제재 캐시 무효화의 내구성: 제재 변경과 같은 트랜잭션에 무효화 작업을 저장한다. 커밋 직후 실행과 worker의 lease 기반 재시도를 통해 Redis 장애·프로세스 종료 후 작업을 복구한다.

최종 PostgreSQL 테스트 환경에서 `./gradlew check` 통과: 358개 테스트(ArchUnit 포함), Detekt, ktlint, ArchUnit, Kover.
커버리지: 라인 80.97%, 분기 62.63%. 기존 최소 기준 78%/60%를 유지한다.
캐시 장애는 fault injection으로 검증했으며 실제 Redis 장애나 프로세스 강제 종료는 실행하지 않았다.
콜백 미실행 상태의 DB 작업 복구, 서비스 재생성 후 재시도, 두 worker 경합, lease 만료,
이전 소유자의 완료 차단, JPA/JDBC 공동 롤백, 실제 제재·감사 기록의 롤백을 검증했다.
원격 CI 결과는 이번 로컬 검증에 포함하지 않는다.

전체 코드 리뷰 후 입력 검증·오류 응답, 메시지 발행 트랜잭션과 오류 전파, 순번 TTL,
방 정원 잠금, scheduler 분리, executor 종료, 세션 인덱스 경쟁 조건을 수정했다.
직접 선언한 repository Optional 반환을 nullable로 바꾸고 제재·lastSeen 시각에 Clock을 사용한다.
사용되지 않는 MessagePersistenceService와 순번 TTL 설정을 제거했다.

격리된 PostgreSQL에서 동시 가입의 정원 제한과 기존 캐시 outbox 검사를 실행했다.
격리된 Redis에서 순번 TTL 제거와 64-bit 정밀도를 확인했고, 기존 순번 보존 마이그레이션도
별도로 실행해 값 유지·TTL 제거·숫자 room ID 외 키 제외를 확인했다.
캐시/방 인덱스 장애는 fault injection으로 검증한다. 기존 순번 키가 이미 만료된 경우의 복원과
원격 CI 실행은 이번 검증에 포함하지 않는다.
