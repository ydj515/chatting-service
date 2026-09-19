# 모더레이션과 캐시

## 정책과 권한

관리자 API는 `X-Admin-Token`과 audit log를 사용한다. 사용자별 관리자 RBAC나 SSO 구현으로 간주하지 않는다.

현재 금칙어 정책은 GLOBAL/ROOM 범위의 대소문자 무시 substring `CONTAINS`와 `REJECT`다. 비어 있지 않은 message content에 적용하며 SYSTEM 타입도 우회하지 않는다. Unicode 정규화, 반복 도배 탐지, 신규 계정별 판정, AI 분류는 현재 범위 밖이다.

MUTE와 BAN은 발신을 막는다. BAN이 방 멤버십 자동 탈퇴를 뜻하지는 않는다. GLOBAL SUSPEND는 로그인·발신 제한과 [세션 철회](authentication.md)를 함께 처리한다. Subscriber-only 정책은 구독 도메인 검증 구현과 분리해 [후속 과제](../operations/backlog.md)로 관리한다.

## 캐시와 무효화

`moderationRules`는 GLOBAL과 room 규칙을 함께 사용하며 정책 변경 시 캐시를 무효화한다. `userSanctions`는 global/user와 room/user 범위를 구분하고 TTL 10초로 제한한다.

제재 변경과 `sanction_cache_invalidations` 생성은 같은 DB transaction에서 실행한다. Commit 후 무효화를 시도하고 실패한 작업은 worker가 lease와 backoff로 재시도한다. Redis I/O 동안 DB transaction/lock을 길게 유지하지 않는다. 장애나 프로세스 종료 이후 lease 만료로 재처리한다.

캐시 무효화 재시도와 세션 철회 재시도는 목적과 테이블이 다르다. Cache eviction 성공을 token revocation 성공으로 해석하지 않는다. 운영 시 두 job의 잔여 작업·실패·다음 시도 시각을 각각 확인한다.

## 방 조회와 전달

방 상세·멤버 목록·history는 인증된 현재 멤버만 조회한다. 상세·멤버 목록 응답은 캐시하지 않는다. 방 검색은 metadata를 제공하며 `lastMessage`를 노출하지 않는다.

Gateway는 전달 batch마다 primary DB에서 현재 멤버십을 다시 확인한다. Pub/Sub LEAVE 유실에만 권한 차단을 의존하지 않는다. 상세 흐름과 한계는 [메시지 전달](messaging.md)에 있다.
