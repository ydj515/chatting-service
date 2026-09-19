# 인증과 세션 철회

## 세션

세션은 HMAC 서명 토큰이며 기본 TTL은 12시간이다. Password는 bcrypt를 사용하고, 유효한 legacy SHA-256 비밀번호는 72 UTF-8 byte 제한 안에서 로그인 시 업그레이드한다. 운영 signing key와 admin token은 외부 설정으로 제공한다.

개별 토큰 철회는 SHA-256 digest key, 사용자 단위 철회는 issuedAt cutoff를 Redis에 기록한다. Cutoff는 단조 증가 Lua로 갱신해 오래된 재시도가 최신 철회 시각을 되돌리지 않는다. Legacy 3-field token은 사용자 철회 marker가 없는 경우에만 호환한다.

## WebSocket 연결 티켓

인증된 세션으로 단기 티켓을 발급받아 WebSocket handshake에 사용한다. 기본 TTL은 30초이며 Redis `GETDEL`로 한 번만 소비한다. Key에는 티켓 원문의 SHA-256 digest를 사용한다.

티켓 payload는 부모 세션의 digest·원래 issuedAt·만료 시각에 연결한다. TTL은 부모 세션 만료를 넘지 않고, 소비 시 개별 세션 철회와 사용자 cutoff를 다시 확인한다. 원문 bearer token은 티켓 payload에 저장하지 않는다. 부모 연결 정보가 없는 과거 티켓은 재발급해야 한다.

재사용 가능한 session token query fallback은 설정으로 제어한다. 속성 기본값과 배포 profile의 값이 다를 수 있으므로 [설정 문서](../configuration.md)와 실제 배포 값을 확인한다. 티켓·세션 query 값을 access log에 남기지 않는다.

## 발급 제한

기본값은 사용자당 분당 10회, IP당 분당 60회다. 사용자와 IP 각각 단일 키 Lua counter/TTL repair를 실행하고 Redis 오류에는 fail-closed로 실패한다. 두 제한을 합친 원자 연산은 아니다. User 통과 후 IP 거부 시 user counter가 소모될 수 있어 NAT 환경에서는 보수적으로 작동한다.

다음은 관측 결과가 아닌 운영 판단 기준이다. Rolling 15분 정상 reconnect 발급 성공률 99.9% 이상, rate-limit 실패율 0.1% 이하, NAT/proxy/mobile cohort 실패율 p95 0.3% 이하를 목표로 한다. 명백한 abuse·invalid session·malformed ticket·의도적 chaos는 정상 reconnect 분모에서 제외한다.

전체 실패율 0.1~0.5% 또는 cohort 0.3~1% 구간에서는 limit/window/burst를 먼저 튜닝한다. 튜닝 후 전체 0.5% 초과 또는 cohort p95 1% 초과가 15분 창 두 번 이상 반복되면 결합 원자 처리나 별도 rate-limit 서비스를 검토한다. IP 거부 이후 user counter 소모로 추정되는 정상 reconnect 실패가 0.2%를 초과해 두 창 이상 반복되는 경우도 재검토한다. 이를 판별할 cohort/overcount 지표는 [관측 과제](../operations/observability.md)에 구분한다.

## 정지 처리와 내구성 있는 재시도

GLOBAL SUSPEND는 로그인·발신을 막고 같은 DB transaction에서 `session_revocation_jobs`를 기록한다. Commit 후 즉시 철회를 시도하며 실패하면 worker가 lease와 backoff로 재시도한다. 캐시 무효화와 같은 `chat.cache.sanction-retry.*` 설정을 재사용한다.

Redis 철회 기록과 force-logout publish가 성공해야 job을 완료한다. Redis 장애·worker crash·lease 만료 이후에도 재시도할 수 있다. 재시도는 sanction의 원래 발생 시각을 사용한다.

Force logout은 at-least-once이며 현재 연결된 사용자 세션 전체를 닫는다. 지연된 재시도가 더 최근 연결까지 닫을 수 있다. Pub/Sub를 놓친 Gateway의 기존 연결 종료까지 내구성 있게 보장하지는 않는다. Redis cutoff는 새 인증과 티켓 소비를 차단한다.

필수 DB 준비와 티켓 전환 순서는 [마이그레이션](../operations/migrations.md)에 있다. Key rotation·refresh 정책과 관리자 SSO/RBAC는 [남은 과제](../operations/backlog.md)다.
