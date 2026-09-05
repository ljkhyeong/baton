# ADR-0016: WATCH 상태 변경 이벤트 트랜잭셔널 인박스

- 상태: 채택
- 결정일: 2026-08-02

## 배경

WATCH는 역할 자료 URL의 점검 결과에서 상태가 바뀌면 `RESOURCE_HEALTH_CHANGED` 이벤트를 BATON 콜백으로 직접 전달한다. 최소 한 번 전달 방식이므로 응답 유실, 작업자 임대 만료와 재시작 때문에 같은 이벤트가 다시 올 수 있고 서로 다른 이벤트의 도착 순서도 보장하지 않는다.

BATON이 접수증을 먼저 반환하고 나중에 메모리나 별도 트랜잭션으로 저장하면 응답 직후 프로세스가 종료될 때 이벤트를 잃는다. 반대로 수신하면서 `RoleResource`를 조회하거나 워크스페이스 상태를 즉시 바꾸면 늦게 도착한 이벤트, BATON 단독 복구와 향후 자료 생명주기에 수신 가용성이 결합된다.

WATCH 페이로드의 `sourceRevision`은 BATON이 보낸 모니터 목표 스냅샷 리비전이다. 동일한 리비전에서 상태가 여러 번 바뀔 수 있고 이전 리비전의 점검이 나중에 끝날 수도 있으므로 상태 이벤트의 순번이 아니다.

## 결정

BATON은 다음 내부 API에서 WATCH 상태 변경 이벤트를 받아 MySQL 트랜잭셔널 인박스에 원자적으로 저장한다.

```http
POST /api/v1/internal/resource-health-events
Authorization: Bearer <WATCH 이벤트 수신기 토큰>
Idempotency-Key: <본문 eventId와 같은 UUID>
```

```text
WATCH HTTPS 요청
  └─ 별도 Bearer 인증과 이벤트 전체 내용 검증
       └─ watch_health_event_inbox 삽입 또는 기존 행 잠금
            ├─ 신규·동일 재전송: 트랜잭션 커밋
            │    └─ 202 접수증(eventId, 최초 acceptedAt)
            └─ 같은 ID의 다른 이벤트 전체 내용: 기존 행 불변 유지 + 409
```

### 인증과 식별자

- 수신기는 기본 비활성화하며 활성화할 때 32~200자의 URL 안전 ASCII Bearer 토큰과 고정 소스 이름공간을 요구한다.
- 수신기 토큰은 외부 전송용 WATCH API 토큰, 워크스페이스 키와 다른 값이어야 한다. 인증값은 저장소, 오류 응답과 접근 로그에 남기지 않는다.
- `Idempotency-Key`와 본문 `eventId`가 다르면 저장 전에 `400 IDEMPOTENCY_KEY_MISMATCH`로 거부한다.
- `resourceReference`는 설정된 이름공간의 `baton-manager:<namespace>:role-resource:<정규 형식 UUID>`만 허용한다.
- 참조 검증은 이름공간과 정규 형식 UUID만 확인한다. `RoleResource` 존재 조회를 하지 않고 인박스에 원본 FK도 두지 않는다.

### 불변 이벤트 전체 내용과 멱등성

V17의 `watch_health_event_inbox`는 이벤트 UUID를 기본 키로 사용하고 이벤트 유형, 자료 참조와 파생한 자료 UUID, 소스 리비전, null을 허용하는 시도 UUID, 이전·현재 상태, 변경 시각, 이벤트 전체 내용 SHA-256 지문과 최초 접수 시각을 저장한다.

신규 이벤트는 한 트랜잭션에서 삽입한다. 같은 이벤트 ID가 이미 있으면 행을 잠그고 타입이 지정된 이벤트 전체 내용의 필드 전체와 지문을 비교한다.

- 같은 이벤트 ID와 같은 이벤트 전체 내용: 동일 재전송으로 처리해 새 행을 만들지 않고 최초 `acceptedAt`을 반환한다.
- 같은 이벤트 ID와 다른 이벤트 전체 내용: `409 WATCH_EVENT_ID_CONFLICT`로 거부한다.
- 서로 다른 이벤트 ID: 소스 리비전, 변경 시각이나 도착 순서와 관계없이 모두 저장한다.

지문은 빠른 동일성 판정을 돕지만 저장한 타입 지정 이벤트 전체 내용도 함께 비교한다. 해시 일치만으로 동일 재전송을 인정하지 않는다.

### 시간 정밀도

MySQL `DATETIME(6)`만 사용하면 WATCH가 보낸 `Instant`의 나노초 일부가 사라져 동일 재전송을 충돌로 오판할 수 있다. `changedAt`은 다음 두 열로 분리한다.

- `changed_at DATETIME(6)`: 마이크로초까지의 UTC 시각
- `changed_at_nano_remainder SMALLINT`: 나머지 `0..999` 나노초

두 값을 다시 합쳐 원래 `Instant`를 비교한다. BATON이 생성하는 `acceptedAt`은 DB가 보존할 수 있는 마이크로초 정밀도로 한 번 기록하고 동일 재전송에서도 그 값을 유지한다.

Java `Instant`가 MySQL보다 넓은 연도 범위를 허용하므로 UTC 기준 1000년 이상 10000년 미만만 수신한다. 범위 밖 `changedAt`은 DB 오류와 무한 재시도로 넘기지 않고 저장 전에 `400 INVALID_INPUT`으로 분류한다.

### 접수증과 처리 경계

`202 Accepted` 접수증은 인박스 트랜잭션이 커밋된 뒤에만 반환한다. 이는 이벤트를 잃지 않고 후속 처리할 수 있다는 접수 확인이며 다음을 뜻하지 않는다.

- 워크스페이스 상태 프로젝션 갱신 완료
- WATCH 이벤트의 전역 순서 확정
- 사용자 UI 또는 알림 전달 완료

첫 구현은 인박스 저장과 중복 제거까지만 수행한다. 프로젝션 소비자, 처리 완료 상태와 보존 기한에 따른 삭제는 별도 요구사항과 순서 정책을 채택하기 전에는 추가하지 않는다.

## 대안

### 수신 트랜잭션에서 `RoleResource` 존재 확인 또는 FK 추가

늦게 도착한 이벤트, 원본 수명주기와 BATON 단독 복구가 수신을 실패시키고 생산자가 발급한 사실 보존을 방해하므로 채택하지 않는다.

### `sourceRevision`이 낮은 이벤트 폐기

소스 리비전은 모니터 설정 순서이지 상태 변경 순서가 아니므로 유효한 서로 다른 이벤트를 잃을 수 있어 채택하지 않는다.

### 도착한 마지막 이벤트를 즉시 현재 상태로 적용

WATCH가 전달 순서를 보장하지 않아 오래된 이벤트가 최신 상태를 덮을 수 있다. 현재 상태 프로젝션에는 별도의 단조 상태 순번, WATCH 조회 정합성 조정 또는 그에 준하는 순서 계약이 필요하므로 채택하지 않는다.

### 접수증 반환 뒤 비동기 저장

응답과 영속 저장 사이의 프로세스 종료에서 이벤트를 잃으므로 채택하지 않는다.

### 동일 이벤트 ID면 페이로드를 비교하지 않고 성공

생산자 결함이나 이벤트 ID 충돌을 숨기므로 채택하지 않는다.

## 결과

### 장점

- WATCH의 중복 전달과 BATON 응답 유실에 같은 접수증으로 안전하게 대응한다.
- 이벤트 도착 순서와 원본 자료 생명주기에서 사실 수신을 분리한다.
- 서로 다른 모든 이벤트를 보존해 이후 프로젝션 순서 정책과 장애 분석의 근거를 남긴다.
- 나노초 단위 `changedAt`까지 동일 이벤트 전체 내용 재전송을 정확히 판정한다.

### 비용과 한계

- 인박스가 계속 증가하므로 처리 상태와 보존 기한을 별도로 설계해야 한다.
- 현재 인박스만으로 워크스페이스의 최신 상태를 계산하거나 UI에 표시할 수 없다.
- 정적 Bearer 토큰은 회전 유예, OAuth와 mTLS를 제공하지 않는다.
- 저장소 테스트는 실제 공개 HTTPS 스테이징의 WATCH→BATON 콜백, 응답 유실 뒤 동일 재전송과 운영 토큰 배포 순서를 대신하지 않는다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
./gradlew --no-daemon test
```

통합 테스트는 V16→V17 마이그레이션의 기존 데이터 보존, 신규·동일 재전송·충돌의 원자성, 동시 중복 요청과 `changedAt` 나노초 정밀도를 검증한다. HTTP 계약 테스트는 전용 Bearer 경계, 헤더·본문 ID 일치, 정규 참조와 `202` 접수증·안정적인 오류 코드를 검증한다.

## 관련 문서

- [BATON–WATCH 역할 자료 감시 계약](../../PRD/0004_watch-integration-contract/spec.md)
- [API 명세](../../PRD/0002_api-contract/spec.md)
- [제품 명세](../../PRD/0001_product-baseline/spec.md)
- [WATCH 트랜잭셔널 아웃박스와 현재 상태 재동기화](../0015_watch-transactional-outbox/adr.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
