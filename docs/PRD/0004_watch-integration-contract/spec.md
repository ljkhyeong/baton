# PRD-0004: BATON–WATCH 역할 자료 감시 계약

- 상태: 양방향 1차 연동 계약 채택, health projection·표시 미구현
- 기준일: 2026-08-02

## 1. 목적

BATON의 역할 자료 링크를 저장하는 transaction과 BATON WATCH의 비동기 URL 점검을 분리하면서도, 자료 변경·시즌 종료·장애 복구 뒤 WATCH의 monitor가 BATON의 현재 상태로 수렴하고 WATCH의 health 변경 사실을 BATON이 유실 없이 받을 수 있게 한다.

BATON은 조직, 시즌, 역할 자료와 자료 접근 권한의 source of truth다. WATCH는 공개 URL의 제한된 reachability를 비동기로 점검하고 현재 건강 상태를 계산하지만, 자료의 존재·권한·신뢰성이나 콘텐츠 의미를 판정하지 않는다.

## 2. 서비스 경계

- BATON은 `RoleResource`와 시즌 활성 상태, WATCH에 보낼 desired monitoring snapshot을 소유한다.
- WATCH는 monitor schedule, 안전한 destination 검사, 시도·결과와 현재 health projection을 소유한다.
- BATON의 자료 생성·수정·시즌 transaction은 WATCH 응답을 기다리지 않는다.
- WATCH 장애나 계약 거절은 이미 commit된 BATON 자료를 rollback하거나 숨기지 않는다.
- WATCH health는 비권위 projection이다. 조회 실패·미생성·동기화 지연은 BATON 자료 자체의 오류가 아니라 `UNKNOWN`으로 해석한다.
- WATCH는 health 변경 event를 at-least-once로 직접 HTTPS 전달하고 BATON은 이를 immutable inbox에 원자적으로 수신한다. 이 수신만으로 workspace projection이나 UI를 갱신하지 않는다.

## 3. 식별자와 revision

### 3.1 resource reference

WATCH의 `resourceReference`는 다음 형식으로 만든다.

```text
baton-manager:<source-namespace>:role-resource:<role-resource-uuid>
```

- `source-namespace`는 1~63자의 영문자, 숫자, `.`, `_`, `-`만 사용한다.
- 전체 reference는 WATCH의 128자 제한과 `[A-Za-z0-9._:-]+` 규칙을 만족한다.
- namespace는 한 환경에서 최초 outbox를 만든 뒤 바꾸지 않는다.
- 운영, staging과 독립 복구 복제본이 같은 WATCH를 사용한다면 서로 다른 namespace를 쓴다.
- URL, 제목, 역할, 시즌 이름과 시즌 활성 상태가 바뀌어도 reference는 바꾸지 않는다.

### 3.2 source revision

- `watch_monitor_outbox.id`의 `BIGINT AUTO_INCREMENT` 값을 해당 immutable snapshot의 `sourceRevision`으로 사용한다.
- rollback으로 생긴 번호 공백은 허용하며 revision의 연속성은 요구하지 않는다.
- 같은 역할 자료의 변경은 기존 `Team → Season` 잠금과 `RoleResource` 낙관적 잠금으로 직렬화한다.
- `RoleResource.version`은 내부 동시성 토큰이므로 WATCH revision으로 재사용하지 않는다.
- 같은 revision과 같은 raw payload 재전송은 WATCH의 멱등 처리에 맡긴다. 같은 revision으로 payload를 다시 만들거나 수정하지 않는다.
- `sourceRevision`은 monitor desired snapshot의 revision일 뿐 health event sequence가 아니다. 같은 revision에서 여러 health 변경이 생길 수 있고 이전 revision의 점검 결과가 나중에 도착할 수도 있으므로 BATON inbox의 최신 health 선택 기준으로 사용하지 않는다.

BATON DB를 과거 시점으로 단독 복구하고 WATCH에는 더 높은 revision이 남은 경우 로컬 auto increment만으로 즉시 추월할 수 있다고 가정하지 않는다. 파일럿에서는 BATON·WATCH를 일관된 복구 지점으로 복구하거나, WATCH의 현재 revision을 확인해 더 높은 revision으로 재동기화하는 운영 절차를 마련한 뒤 독립 재해 복구를 수행한다.

## 4. 감시 적격 URL

BATON 자료 저장 규칙과 WATCH 감시 규칙은 분리한다. BATON은 사용자 정보가 없는 절대 `http`·`https` URL을 계속 저장할 수 있지만, 다음 조건을 모두 만족한 URL만 ACTIVE monitor로 보낸다.

- 길이 2,048자 이하의 절대 `http` 또는 `https` URL
- ASCII hostname이며 IP literal이나 모호한 숫자 주소가 아님
- user-info, fragment, control character와 backslash가 없음
- 포트가 생략됐거나 `http:80`, `https:443`인 기본 포트
- query string이 없음

WATCH 자체는 query string을 허용하지만 전체 URL을 monitor와 attempt에 저장한다. BATON은 서명 URL, 접근 token과 개인정보가 WATCH DB에 복제되는 일을 피하려고 첫 계약에서 query URL을 감시 대상에서 제외한다.

정적 적격 판정은 실제 접속 가능성이나 안전성을 보장하지 않는다. DNS가 loopback·사설망으로 해석되는지, redirect destination이 안전한지는 WATCH가 매 시도에서 판정한다.

## 5. desired state 전이

| BATON 변경 | WATCH desired snapshot |
| --- | --- |
| 적격 자료 신규 생성 | `ACTIVE`와 현재 URL |
| 생성 멱등 재생 | 새 snapshot 없음 |
| 적격 URL에서 다른 적격 URL로 수정 | `ACTIVE`와 새 URL |
| 적격 URL에서 비적격 URL로 수정 | `INACTIVE`, URL 없음 |
| 비적격 URL에서 적격 URL로 수정 | `ACTIVE`와 새 URL |
| 비적격 URL 사이 수정 | 새 snapshot 없음 |
| URL이 같고 제목·설명·역할만 수정 | 새 snapshot 없음 |
| 시즌 종료 | 시즌의 모든 자료를 `INACTIVE` |
| 종료 시즌 재개 | 적격 자료는 `ACTIVE`, 비적격 자료는 `INACTIVE` |
| 다음 시즌 시작 | 실제로 종료되는 원본 시즌의 모든 자료를 `INACTIVE` |
| 다음 시즌 멱등 재생 | 새 snapshot 없음 |

WATCH에는 DELETE API가 없으므로 자료가 더 이상 감시 대상이 아닐 때는 더 높은 revision의 `INACTIVE` PUT으로 기존 점검을 중단한다. ACTIVE 요청이 `422 INVALID_TARGET_URL`로 거절되면 이전 ACTIVE monitor가 남을 수 있으므로 BATON outbox는 해당 실패와 더 높은 revision의 INACTIVE 보상 snapshot을 원자적으로 기록한다. V18 이후 보상 snapshot은 거절된 ACTIVE revision을 가리키며, 같은 raw target URL은 메타데이터 수정이나 reconciliation만으로 다시 ACTIVE가 되지 않는다. 역할 자료 URL이 실제로 달라지면 새 ACTIVE snapshot을 만들 수 있다.

역할 자료는 다음 시즌에 복사되지 않으므로 새 시즌 monitor를 자동으로 만들지 않는다.

## 6. WATCH HTTP 계약

### 6.1 동기화

```http
PUT /api/v1/resource-monitors/{resourceReference}
Authorization: Bearer <WATCH API token>
Content-Type: application/json
```

ACTIVE 요청:

```json
{
  "sourceRevision": 42,
  "monitoringState": "ACTIVE",
  "targetUrl": "https://docs.example.com/study-guide"
}
```

INACTIVE 요청:

```json
{
  "sourceRevision": 43,
  "monitoringState": "INACTIVE",
  "targetUrl": null
}
```

- 성공은 정확히 `200 OK`다. `201`, `202`, `204`를 포함한 다른 `2xx`는 계약 위반인 영구 실패로 기록한다.
- 낮은 revision의 `409 STALE_SOURCE_REVISION`은 이미 더 최신 snapshot이 반영된 것으로 보고 해당 outbox 전달을 완료 처리한다.
- 같은 revision과 다른 payload의 `409 SOURCE_REVISION_CONFLICT`는 producer 결함 또는 복구 불일치로 보고 자동으로 revision을 올리지 않는다.
- `422 INVALID_TARGET_URL`은 ACTIVE 실패를 기록하고 INACTIVE 보상 snapshot을 만든다.
- timeout, 연결 오류, `429`와 `5xx`는 제한된 exponential backoff로 재시도한다.
- 그 밖의 `4xx`는 영구·운영 오류로 분류하고 짧은 간격의 무한 재시도를 하지 않는다.

오류 body, target URL과 bearer token 원문은 로그와 outbox 오류 필드에 저장하지 않는다. outbox에는 최대 64자의 안정적인 오류 code만 남긴다.

### 6.2 현재 health 조회

WATCH에는 다음 개별 조회만 있고 전체 monitor 열거 API는 없다.

```http
GET /api/v1/resource-monitors/{resourceReference}
Authorization: Bearer <WATCH API token>
```

BATON의 health 조회·표시는 아직 구현하지 않는다. 도입할 때에도 workspace projection 요청 안에서 WATCH를 동기 호출하지 않고, BATON이 별도로 갱신한 비권위 projection 또는 제한된 비동기 조회를 사용한다. `404`와 WATCH 장애는 자료 삭제나 접근 거부로 해석하지 않는다.

### 6.3 health 변경 event 수신

WATCH는 health가 실제로 바뀔 때 다음 endpoint로 불변 event envelope를 전달한다.

```http
POST /api/v1/internal/resource-health-events
Authorization: Bearer <WATCH event receiver token>
Idempotency-Key: <본문 eventId와 같은 UUID>
Content-Type: application/json
```

```json
{
  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
  "eventType": "RESOURCE_HEALTH_CHANGED",
  "resourceReference": "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "sourceRevision": 7,
  "attemptId": "81ccb9da-f9f9-4abc-87fe-cf6193ee5f79",
  "previousHealth": "DEGRADED",
  "currentHealth": "BROKEN",
  "changedAt": "2026-08-02T03:04:05.123456789Z"
}
```

`attemptId`는 점검 시도가 직접 만든 변경이 아닐 때 생략할 수 있다. health 값은 `UNKNOWN`, `HEALTHY`, `DEGRADED`, `BROKEN` 중 서로 달라야 하고 계약에 없는 필드는 허용하지 않는다. `resourceReference`는 현재 BATON에 설정한 namespace와 canonical UUID를 사용한 `baton-manager:<namespace>:role-resource:<uuid>` 형식이어야 한다.

신규 envelope를 durable inbox에 commit한 뒤 `202 Accepted`로 다음 receipt를 반환한다.

```json
{
  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
  "acceptedAt": "2026-08-02T03:04:06.123456Z"
}
```

같은 `eventId`와 정확히 같은 envelope의 replay는 최초 `acceptedAt`을 보존한 같은 receipt를 `202`로 반환한다. 같은 ID에 다른 envelope를 보내면 `409 WATCH_EVENT_ID_CONFLICT`, header와 body ID가 다르면 `400 IDEMPOTENCY_KEY_MISMATCH`, namespace 또는 canonical reference가 다르면 `400 WATCH_RESOURCE_REFERENCE_INVALID`다. 인증 누락·중복·불일치와 receiver 비활성 상태는 본문을 처리하기 전에 `401 UNAUTHORIZED`로 수렴한다.

`202`는 inbox 저장 완료만 뜻한다. BATON health projection과 UI 반영, event 처리 순서 또는 downstream 알림 완료를 보장하지 않는다.

## 7. outbox, inbox, 전달과 reconciliation

- 역할 자료·시즌 mutation과 같은 MySQL transaction에서 immutable outbox snapshot을 저장한다.
- HTTP worker는 짧은 transaction으로 due row를 lease한 뒤 DB transaction 밖에서 WATCH를 호출한다.
- 성공, retry, 영구 실패와 보상 snapshot은 각각 짧은 transaction으로 기록한다.
- 같은 역할 자료의 이전 미종결 row가 있으면 후속 row를 먼저 lease하지 않는다.
- lease가 만료되면 다른 worker가 같은 immutable payload를 다시 전달할 수 있다.
- dispatcher는 핵심 회차 자동화와 분리된 전용 scheduler에서 한 번에 한 row만 1분 lease한다. WATCH HTTP connect·read timeout 합은 45초 이하로 제한해 아직 호출하지 않은 batch row가 먼저 만료되는 일을 막는다.
- reconciliation은 BATON의 모든 역할 자료와 시즌 종료 상태에서 현재 desired snapshot을 다시 계산한다.
- 같은 reference·state·raw target URL의 최신 outbox가 있으면 중복 snapshot을 만들지 않는다. 최신 snapshot이 invalid-target 보상이면 해당 보상이 가리키는 거절 URL도 같은 desired ACTIVE로 간주한다.
- 후보 조회 뒤 원본이 바뀌었으면 resource row 잠금 아래 현재 URL·시즌 상태를 다시 확인하고 오래된 후보를 append하지 않는다.
- 기존 V15 자료는 Flyway에서 namespace를 추측해 backfill하지 않고 runtime reconciliation으로 채운다.
- V18은 보상 여부를 확정할 표식이 없는 기존 INACTIVE를 추측하지 않고, 새 invalid-target 보상부터 거절 revision에 연결한다.

첫 구현의 reconciliation은 파일럿 데이터 규모를 전제로 전체 후보를 읽는다. 데이터가 늘기 전에 page·cursor 기반 조회와 운영자용 실패 재처리 가시성을 추가한다. 인증·경로 같은 운영 설정의 `3xx`·`4xx` 실패는 재시작 시 다시 `PENDING`으로 전환하지만 revision conflict와 invalid target은 자동 재처리하지 않는다.

health event receiver는 V17의 `watch_health_event_inbox`에 event ID와 전체 envelope fingerprint를 한 transaction으로 저장하고 같은 row를 잠가 replay와 충돌을 판정한다. event ID가 다른 envelope는 `sourceRevision`, `changedAt`과 도착 순서에 관계없이 모두 보존한다. `changedAt`은 UTC 기준 1000년 이상 10000년 미만만 허용하고, MySQL `DATETIME(6)`과 `0..999` 나노초 remainder로 나눠 원래 `Instant`의 나노초 정밀도를 잃지 않는다. 저장 범위 밖 값은 inbox에 도달하기 전에 `400 INVALID_INPUT`으로 거부한다.

inbox는 `RoleResource` FK를 두지 않고 수신 transaction에서 원본 자료 존재도 조회하지 않는다. 이미 삭제됐거나 아직 복구되지 않은 원본과 늦게 도착한 event도 producer가 발급한 canonical reference 기준으로 보존해야 하기 때문이다. 현재 inbox는 append·deduplication 경계만 소유하며 health projection, 처리 완료 상태와 retention 삭제는 구현하지 않는다.

## 8. 인증과 설정

- WATCH 연동은 기본 비활성화한다.
- 비활성 상태에서는 기본 namespace로 미래 전달 row를 미리 쌓지 않는다. 활성화 직후 reconciliation이 설정된 고정 namespace로 기존 자료의 현재 desired snapshot을 backfill한다.
- 활성화할 때 HTTPS base URL, 32~200자의 URL-safe ASCII WATCH bearer token과 고정 source namespace를 환경 설정으로 주입한다.
- 활성 상태에서는 source namespace를 반드시 명시하며 기존 outbox의 namespace와 다르면 시작을 거부한다.
- token은 저장소, 로그, 오류 응답과 outbox에 기록하지 않는다.
- BATON의 WATCH API client는 bearer token이 다른 origin으로 전달되지 않도록 redirect를 따라가지 않는다.
- WATCH의 정적 token은 현재 rotation grace, OAuth와 mTLS를 제공하지 않는다. 운영 연동 전에 token 교체 절차와 두 서비스의 배포 순서를 확인한다.
- health event receiver도 기본 비활성화한다. 활성화할 때 같은 고정 source namespace와 별도의 32~200자 URL-safe ASCII Bearer token을 설정한다.
- outbound WATCH API token과 inbound event receiver token은 서로 달라야 하며 저장소, 로그와 오류 응답에 기록하지 않는다. receiver token은 워크스페이스 공유 키나 최종 사용자 인증을 대신하지 않는다.

`BATON_WATCH_ENABLED=false`는 전송 연결과 변경 캡처를 멈추지만 이미 WATCH에 전달된 ACTIVE monitor를 자동 삭제하지 않는다. 점검을 폐기할 때는 다음 순서를 지킨다.

1. 연결은 활성 상태로 유지하고 `BATON_WATCH_MONITORING_ENABLED=false`로 배포한다.
2. reconciliation과 dispatcher가 현재 모든 자료의 INACTIVE snapshot을 전달하도록 기다린다.
3. outbox 실패가 없고 WATCH monitor가 INACTIVE로 수렴했음을 확인한다.
4. 그 다음에만 `BATON_WATCH_ENABLED=false`로 전환한다.

## 9. 알려진 한계

- WATCH worker는 인증 header·cookie 없이 GET을 수행하므로 로그인 뒤 문서의 실제 접근성을 검증하지 않는다.
- 응답 body가 64KiB를 넘으면 실패로 분류될 수 있어 일반 문서 페이지가 정상이어도 `BROKEN`이 될 수 있다.
- WATCH의 system status는 현재 DB readiness를 증명하지 않는다.
- WATCH의 직접 HTTPS health-change event sender와 BATON의 durable receiver는 저장소에 구현했지만 실제 public staging의 WATCH→BATON 전달, 응답 유실 replay와 운영 활성화는 아직 검증하지 않았다. event broker가 있다고 가정하지 않는다.
- BATON UI의 health badge, 최근 점검 시각과 수동 재검사는 이 계약의 다음 기능이며 현재 완료 범위가 아니다.
- inbox retention과 projection 적용 완료 표시는 아직 없다. 고유 event를 임의로 삭제하거나 `sourceRevision`·도착 순서만으로 최신 health를 선택하지 않는다.
- outbox 실패 목록·수동 재처리 UI와 전체 INACTIVE 전달 완료를 한 번에 증명하는 운영 명령은 아직 없다. 첫 파일럿에서는 DB 상태와 WATCH 개별 조회로 중단 절차를 확인한다.

## 10. 완료 기준

- 자료 생성·URL 전이와 시즌 종료·재개가 같은 transaction에서 올바른 outbox snapshot을 만든다.
- 멱등 replay와 메타데이터-only 수정은 중복 snapshot을 만들지 않는다.
- source revision이 `RoleResource.version`과 분리되고 단조 증가한다.
- WATCH 장애가 BATON 원본 transaction을 rollback하지 않는다.
- lease 만료, transient retry, stale 완료, permanent failure와 invalid-target 보상을 검증한다.
- V15→V16 migration이 기존 데이터를 보존하고 빈 outbox schema를 추가한다.
- reconciliation이 기존 자료와 누락 snapshot을 현재 desired state로 수렴시킨다.
- V17 migration이 기존 데이터를 보존하고 FK 없는 빈 immutable health event inbox를 추가한다.
- V17→V18 migration이 기존 outbox를 보존하고 새 invalid-target 보상을 식별할 nullable self-reference와 무결성 제약을 추가한다.
- 신규·정확 replay의 같은 `202` receipt, header/body ID 불일치, canonical reference 거절과 같은 ID의 다른 envelope `409`를 검증한다.
- 서로 다른 event ID는 전달 순서와 source revision에 관계없이 모두 저장하고 `changedAt`의 나노초 정밀도를 보존한다.

## 11. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [API 계약 기준선](../0002_api-contract/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [Transactional outbox 결정](../../ADR/0015_watch-transactional-outbox/adr.md)
- [WATCH health-change event transactional inbox 결정](../../ADR/0016_watch-health-event-transactional-inbox/adr.md)
- [헥사고날 아키텍처](../../ADR/0001_hexagonal-architecture/adr.md)
