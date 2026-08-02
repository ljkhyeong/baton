# ADR-0016: WATCH health-change event transactional inbox

- 상태: 채택
- 결정일: 2026-08-02

## 배경

WATCH는 역할 자료 URL의 점검 결과에서 health가 바뀌면 `RESOURCE_HEALTH_CHANGED` event를 BATON callback으로 직접 전달한다. 전달은 at-least-once이며 응답 유실, worker lease 만료와 재시작 때문에 같은 event가 다시 올 수 있고 서로 다른 event의 도착 순서도 보장하지 않는다.

BATON이 receipt를 먼저 반환하고 나중에 메모리나 별도 transaction으로 저장하면 응답 직후 프로세스가 종료될 때 event를 잃는다. 반대로 수신하면서 `RoleResource`를 조회하거나 workspace health를 즉시 바꾸면 늦게 도착한 event, BATON 단독 복구와 향후 자료 생명주기에 수신 가용성이 결합된다.

WATCH payload의 `sourceRevision`은 BATON이 보낸 monitor desired snapshot revision이다. 동일한 revision에서 health가 여러 번 바뀔 수 있고 이전 revision의 점검이 나중에 끝날 수도 있으므로 health event sequence가 아니다.

## 결정

BATON은 다음 내부 API에서 WATCH health-change event를 받아 MySQL transactional inbox에 원자적으로 저장한다.

```http
POST /api/v1/internal/resource-health-events
Authorization: Bearer <WATCH event receiver token>
Idempotency-Key: <본문 eventId와 같은 UUID>
```

```text
WATCH HTTPS request
  └─ 별도 Bearer 인증과 envelope 검증
       └─ watch_health_event_inbox INSERT 또는 기존 row 잠금
            ├─ 신규·정확 replay: transaction commit
            │    └─ 202 receipt(eventId, 최초 acceptedAt)
            └─ 같은 ID의 다른 envelope: 기존 row 불변 유지 + 409
```

### 인증과 식별자

- receiver는 기본 비활성화하며 활성화할 때 32~200자의 URL-safe ASCII Bearer token과 고정 source namespace를 요구한다.
- receiver token은 outbound WATCH API token, 워크스페이스 키와 다른 값이어야 한다. 인증값은 저장소, 오류 응답과 access log에 남기지 않는다.
- `Idempotency-Key`와 본문 `eventId`가 다르면 저장 전에 `400 IDEMPOTENCY_KEY_MISMATCH`로 거부한다.
- `resourceReference`는 설정된 namespace의 `baton-manager:<namespace>:role-resource:<canonical UUID>`만 허용한다.
- reference 검증은 namespace와 canonical UUID 형식만 확인한다. `RoleResource` 존재 조회를 하지 않고 inbox에 원본 FK도 두지 않는다.

### Immutable envelope와 멱등성

V17의 `watch_health_event_inbox`는 event UUID를 primary key로 사용하고 event type, resource reference와 파생한 resource UUID, source revision, nullable attempt UUID, 이전·현재 health, 변경 시각, envelope SHA-256 fingerprint와 최초 접수 시각을 저장한다.

신규 event는 한 transaction에서 insert한다. 같은 event ID가 이미 있으면 row를 잠그고 typed envelope 전체와 fingerprint를 비교한다.

- 같은 event ID와 같은 envelope: 새 row를 만들지 않고 최초 `acceptedAt`을 반환한다.
- 같은 event ID와 다른 envelope: `409 WATCH_EVENT_ID_CONFLICT`로 거부한다.
- 서로 다른 event ID: source revision, 변경 시각이나 도착 순서와 관계없이 모두 저장한다.

fingerprint는 빠른 동일성 판정을 돕지만 저장한 typed envelope도 함께 비교한다. hash 일치만으로 replay를 인정하지 않는다.

### 시간 정밀도

MySQL `DATETIME(6)`만 사용하면 WATCH가 보낸 `Instant`의 나노초 일부가 사라져 정확한 replay가 충돌로 오판될 수 있다. `changedAt`은 다음 두 열로 분리한다.

- `changed_at DATETIME(6)`: 마이크로초까지의 UTC 시각
- `changed_at_nano_remainder SMALLINT`: 나머지 `0..999` 나노초

두 값을 다시 합쳐 원래 `Instant`를 비교한다. BATON이 생성하는 `acceptedAt`은 DB가 보존할 수 있는 마이크로초 정밀도로 한 번 기록하고 replay에서도 그 값을 유지한다.

Java `Instant`가 MySQL보다 넓은 연도 범위를 허용하므로 UTC 기준 1000년 이상 10000년 미만만 수신한다. 범위 밖 `changedAt`은 DB 오류와 무한 재시도로 넘기지 않고 저장 전에 `400 INVALID_INPUT`으로 분류한다.

### Receipt와 처리 경계

`202 Accepted` receipt는 inbox transaction이 commit된 뒤에만 반환한다. 이는 event를 잃지 않고 후속 처리할 수 있다는 접수 확인이며 다음을 뜻하지 않는다.

- workspace health projection 갱신 완료
- WATCH event의 전역 순서 확정
- 사용자 UI 또는 알림 전달 완료

첫 구현은 inbox 저장과 deduplication까지만 수행한다. projection consumer, 처리 완료 상태와 retention 삭제는 별도 요구사항과 순서 정책을 채택하기 전에는 추가하지 않는다.

## 대안

### 수신 transaction에서 `RoleResource` 존재 확인 또는 FK 추가

늦게 도착한 event, 원본 수명주기와 BATON 단독 복구가 수신을 실패시키고 producer가 발급한 사실 보존을 방해하므로 채택하지 않는다.

### `sourceRevision`이 낮은 event 폐기

source revision은 monitor 설정 순서이지 health 변경 순서가 아니므로 유효한 서로 다른 event를 잃을 수 있어 채택하지 않는다.

### 도착한 마지막 event를 즉시 현재 health로 적용

WATCH가 전달 순서를 보장하지 않아 오래된 event가 최신 상태를 덮을 수 있다. 현재 health projection에는 별도의 단조 health sequence, WATCH 조회 reconciliation 또는 그에 준하는 순서 계약이 필요하므로 채택하지 않는다.

### receipt 반환 뒤 비동기 저장

응답과 durable 저장 사이의 프로세스 종료에서 event를 잃으므로 채택하지 않는다.

### 동일 event ID면 payload를 비교하지 않고 성공

producer 결함이나 event ID 충돌을 숨기므로 채택하지 않는다.

## 결과

### 장점

- WATCH의 중복 전달과 BATON 응답 유실에 같은 receipt로 안전하게 대응한다.
- event 도착 순서와 원본 자료 생명주기에서 사실 수신을 분리한다.
- 서로 다른 모든 event를 보존해 이후 projection 순서 정책과 장애 분석의 근거를 남긴다.
- 나노초 단위 `changedAt`까지 동일 envelope replay를 정확히 판정한다.

### 비용과 한계

- inbox가 계속 증가하므로 처리 상태와 retention을 별도로 설계해야 한다.
- 현재 inbox만으로 workspace의 최신 health를 계산하거나 UI에 표시할 수 없다.
- static Bearer token은 rotation grace, OAuth와 mTLS를 제공하지 않는다.
- 저장소 테스트는 실제 public HTTPS staging의 WATCH→BATON callback, 응답 유실 replay와 운영 token 배포 순서를 대신하지 않는다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
./gradlew --no-daemon test
```

통합 테스트는 V16→V17 migration의 기존 데이터 보존, 신규·정확 replay·충돌의 원자성, concurrent duplicate와 `changedAt` 나노초 정밀도를 검증한다. HTTP 계약 테스트는 전용 Bearer 경계, header/body ID 일치, canonical reference와 `202` receipt·안정적인 오류 코드를 검증한다.

## 관련 문서

- [BATON–WATCH 역할 자료 감시 계약](../../PRD/0004_watch-integration-contract/spec.md)
- [API 계약 기준선](../../PRD/0002_api-contract/spec.md)
- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [WATCH transactional outbox와 수렴형 동기화](../0015_watch-transactional-outbox/adr.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
