# ADR-0016: RELAY 역할 바통 이벤트 transactional outbox와 confirmed publish

- 상태: 채택
- 결정일: 2026-08-08

## 배경

BATON의 역할 바통 전달은 MySQL transaction 안에서 `RoleHandoff`를 `PREPARING`에서
`TRANSFERRED`로 전이시킨다. RELAY는 별도 PostgreSQL, RabbitMQ consumer, runtime과 배포 단위를
가지므로 BATON domain transaction에 참여할 수 없다.

원본 transaction 안에서 broker를 동기 호출하면 RabbitMQ 장애가 바통 전달을 막고 DB commit과
broker 수락 중 하나만 성공한 결과를 원자적으로 취소할 수 없다. commit 후 in-memory
event만 발행하면 프로세스 종료·재배포 창에서 이벤트를 잃는다.

RabbitMQ의 publisher confirm도 라우팅 성공을 혼자 증명하지 못한다. mandatory publication이
라우팅되지 않아 publisher에 반환되면서도 broker ack를 받을 수 있다. 따라서 BATON은
confirm과 return을 같은 publication으로 상관시킬 완료 규칙이 필요하다.

## 결정

BATON은 역할 바통이 처음 `PREPARING → TRANSFERRED`로 전이하는 transaction에 immutable
RELAY event outbox를 함께 append한다. 별도 publisher는 commit된 미완료 row만 읽어 DB
transaction 밖에서 RabbitMQ에 발행하고, 상관된 positive confirm과 mandatory return 없음을
모두 확인한 후에만 짧은 DB transaction으로 완료 처리한다.

```text
RoleHandoff transfer transaction
  ├─ RoleHandoff: PREPARING → TRANSFERRED
  └─ immutable RELAY event outbox INSERT
       └─ commit
            └─ short single-row claim: PENDING/expired PUBLISHING → PUBLISHING
                 └─ 1-minute lease and new fencing token
                      └─ persistent mandatory RabbitMQ publish outside DB transaction
                           └─ correlate publisher confirm and mandatory return
                                └─ fenced short completion or retry transaction
```

### 발생 경계와 멱등 replay

전달 event는 실제 상태 전이에서만 한 건 생성한다. 이미 종료된 전달 요청의 멱등 replay가
현재 표현을 돌려줄 때는 새 event를 append하지 않는다. 이를 위해 outbox append는 controller나
commit listener가 아니라 실제 전이를 조정하는 application transaction 경계에 둔다.

version 1은 `ACCEPTED`와 `CANCELLED`를 따로 발행하지 않는다. 전달 뒤 수락되거나 취소되어도
이미 생성한 event는 그 시점의 불변 사실로 남는다.

### Immutable envelope

outbox는 다음 여섯 값을 발생 시점에 고정한다.

```json
{
  "contractVersion": 1,
  "eventId": "018f5b4a-91c2-7d60-b3c9-2f6f3c69b903",
  "eventType": "ROLE_HANDOFF_TRANSFERRED",
  "eventVersion": 1,
  "subjectReference": "role:018f5b4a-91c2-7d60-b3c9-2f6f3c69b904",
  "occurredAt": "2026-08-08T12:30:00Z"
}
```

- `contractVersion`은 `1`이다.
- `eventId`는 outbox와 RELAY inbox에서 같은 UUID를 사용하는 유일 수신 멱등키다.
- `eventType`은 `ROLE_HANDOFF_TRANSFERRED`, `eventVersion`은 `1`이다.
- `subjectReference`는 `role:{roleId}`이다. 역할·구성원·바통의 이름이나 연락처를 넣지
  않는다.
- `occurredAt`은 publisher 시각이 아니라 영속화된 `RoleHandoff.transferredAt`이다.

전달할 때 현재 `RoleHandoff`나 `Role`을 다시 조회해 envelope를 재구성하지 않는다. 모든
재시도는 같은 event ID와 같은 여섯 값을 사용해야 한다. 부가 payload나 수신자·목적지·template,
credential, URL과 추적 metadata는 outbox envelope에 저장하지 않는다.

### AMQP wire와 완료 판정

body는 정확히 여섯 필드만 가진 2,048-byte 이하의 UTF-8 JSON이다. publisher는
`contentType=application/json`, `PERSISTENT`, `messageId=eventId`, `mandatory=true`를 사용한다.
application header와 기타 선택 AMQP 속성은 설정하지 않는다.

publisher는 Spring callback channel이 wire 상관 header를 추가하지 않도록 publisher returns를
비활성화하고 simple publisher confirm을 사용한다. `RabbitTemplate.execute`의 한 channel scope에서
publication을 정확히 하나만 실행한다.
임시 `ReturnCallback`/`ReturnListener`를 등록한 뒤 header table이 없는 raw
`basicPublish(mandatory=true)`를 호출하고 같은 scope에서 `waitForConfirms`로 결과를
기다린다. 단일 publication scope를 유지해 return과 confirm을 해당 publication에 연결하고,
`finally`에서 listener를 제거한다. AMQP `correlationId`나 application header를 상관 수단으로
전송하지 않는다.

해당 publication의 positive publisher confirm을 받고 같은 publication에 mandatory return이 없음을
확인했을 때만 outbox를 완료한다. return, negative confirm, timeout, connection failure와
불명확한 결과는 미완료로 남긴다.

기본 exchange는 `baton.events.v1`, routing key는 `relay.events.v1`이다. confirm timeout은 기본
5초며 1ms부터 45초까지만 설정할 수 있다. exchange·routing key·broker endpoint는 환경별
설정이고 wire body나 AMQP metadata에 넣지 않는다.

broker 수락 후 BATON completion update 전에 프로세스가 멈추거나 completion transaction이
실패하면 중복 발행할 수 있다. RELAY는 같은 `eventId`와 같은 envelope를 exact replay로
수락하고 중복 inbox·job을 만들지 않는다. 같은 ID의 envelope를 변경하거나 재시도에
새 ID를 발급하는 것은 금지한다.

### Publisher 비활성과 capture

publisher는 기본 비활성으로 운영할 수 있지만 outbox capture는 설정으로 끄지 않는다. publisher
비활성·broker 미구성 상태에서도 새 전달 event를 원본 transaction에 저장한다. 활성화
후에는 현재 domain 상태로 event를 재구성하거나 reconciliation row를 만들지 않고 저장된
backlog를 그대로 발행한다.

publisher 비활성 상태에서는 Rabbit health contributor도 비활성화해 RabbitMQ가 없는 기존
배포의 aggregate health를 바꾸지 않는다. publisher를 활성화할 때는 RabbitMQ host, port,
username, password와 virtual host를 모두 명시해야 시작한다. 현재 production env validator와
Compose는 이 입력을 허용·전달하지 않으며, provisioning·workload 인증·TLS와 topology를
검증하는 별도 운영 변경 전에는 표준 production 배포에서 publisher를 활성화하지 않는다.

이 결정은 WATCH의 desired-state 연동과 의도적으로 다르다. WATCH는 활성화 후 현재 상태를
reconciliation하지만, RELAY event는 특정 시점의 사건이므로 활성화 여부와 무관하게 발생 당시
캡처한다.

### Provisioning 순서

RELAY는 event 수락 시점에 활성 exact-match subscription과 provider binding이 있는 경우에만 job을
만든다. 나중의 provisioning은 기존 inbox를 backfill하지 않고 exact replay에도 job을 추가하지
않는다. 따라서 `role:{roleId}`·`ROLE_HANDOFF_TRANSFERRED`·version `1`의 구독과 참조 binding을
먼저 생성·활성화하고 나서 해당 backlog를 발행한다.

이 ADR은 채널, 수신자, provider, credential reference, destination reference와 template를 선택하지
않는다. 이를 결정하고 provisioning하기 전에는 운영 publisher를 활성화하지 않는다.

### Claim, retry와 fencing

`relay_event_outbox`는 `PENDING`, `PUBLISHING`, `PUBLISHED` 상태를 사용한다. 기본 10초 간격
dispatcher의 한 tick은 `FOR UPDATE SKIP LOCKED`로 due `PENDING` 또는 lease가 만료된
`PUBLISHING` row를 최대 한 건만 짧은 transaction에서 claim한다. claim은 시도 횟수를
올리고 새 lease token과 1분 만료 시각을 고정한 뒤 바로 commit한다. RabbitMQ I/O 동안
MySQL connection이나 row lock을 점유하지 않는다. 경쟁 worker는 잠긴 머리 row에서
기다리지 않고 다음 적격 row를 claim할 수 있다.

```text
PENDING ──claim──> PUBLISHING ──confirmed──> PUBLISHED
   ^                     │
   └──── retry 예약 ─────────┘

expired PUBLISHING ──re-claim──> PUBLISHING with a new lease token
```

발행 성공 finalize와 실패 retry 예약은 `rowId`, `PUBLISHING`, current lease token이 모두
일치할 때만 반영한다. lease 시각이 만료되어도 아직 다른 worker가 re-claim하지 않아
token이 그대로라면 현 worker의 finalize를 허용한다. re-claim으로 token이 회전한 뒤에는 이전
worker의 finalize와 retry 예약을 `false`로 무시해 stale worker가 새 소유자의 결과를 덮지
못하게 한다.

실패는 시도 완료 시각부터 `10초 × 2^(attempt-1)` 후로 다음 시도를 예약하고 1시간에서
상한한다. 현재 jitter, 최대 시도 횟수와 terminal failure 상태는 없다. DB에는 최대
64자의 안정적인 오류 code만 남기고 raw exception, broker response와 body를 저장하지 않는다.
scheduler는 미확정 결과가 있는 tick에 `claimed`, `published`, `retry` 건수만 경고로 남기고
event ID, subject reference, body와 broker reply text를 로그하지 않는다.

### 아직 채택하지 않은 운영 경계

- retry jitter, 최대 시도 횟수, terminal failure 분류와 수동 재처리 절차
- lease 갱신, batch 확대, throughput·backpressure 정책
- 완료·미완료 row 보존 기간, archive·purge·백업·복원 절차
- backlog·실패·재처리 운영 API·UI, metric·export, alert, dashboard·SLO와 runbook
- production RabbitMQ 인증·TLS·용량·retention·quorum HA·failover·partition·upgrade·복구 목표

현재 claim·lease·fencing과 retry scheduler는 이 저장소의 결정적 검증 범위이지만 production
다중 instance·broker 가용성, 운영 가시성, 수동 회복과 보존 정책을 완료했다는 증거가
아니다.

## 대안

### 원본 transaction에서 RabbitMQ 동기 발행

broker 장애가 역할 바통 전달을 막고 MySQL과 RabbitMQ 결과를 원자적으로 일치시킬 수 없어
채택하지 않는다.

### commit 후 in-memory event만 발행

commit과 callback 사이의 프로세스 종료, 재배포와 일시적 broker 장애에서 event를 잃어 채택하지
않는다.

### publisher가 활성인 동안에만 outbox capture

수신자·채널 provisioning을 나중에 완료하는 동안 사건을 잃고, 과거 event를 현재 상태에서
정확히 재구성할 수 없어 채택하지 않는다.

### positive publisher confirm만으로 완료

mandatory로 반환된 라우팅 실패도 positive confirm을 받을 수 있어 유실을 완료로 오판할 수
있으므로 채택하지 않는다.

### 재시도할 때 새 event ID 발급

RELAY의 유일한 수신 멱등키를 바꿔 불명확한 이전 시도와 중복 job을 만들 수 있어 채택하지
않는다.

### envelope에 바통 payload·수신자·template 포함

개인정보와 목적지가 source outbox·broker·RELAY inbox에 복제되고 provider 결정이 BATON 이벤트
계약에 결합되므로 채택하지 않는다.

## 결과

### 장점

- 역할 바통 전달과 event capture가 함께 commit되어 프로세스 중단에도 발행 근거를 남긴다.
- broker·RELAY 장애를 BATON 원본 transaction과 분리한다.
- confirm·return 조합으로 unroutable 메시지를 성공으로 잘못 처리하지 않는다.
- 응답 유실·중복 발행을 RELAY inbox의 `eventId` 멱등성으로 흡수한다.
- publisher를 안전하게 끄고도 다음 시작에서 발행할 사건 backlog를 유지한다.

### 비용과 한계

- outbox schema, serializer, RabbitMQ publisher와 confirm·return correlation이 필요하다.
- at-least-once는 중복 발행을 허용하므로 RELAY exact-envelope 멱등성이 연동 정합성의 필수
  경계다.
- RELAY provisioning 실패는 broker publication 실패가 아니므로 publisher가 감지하지 못한다. 발행 전
  별도 rollout gate가 필요하다.
- retry의 terminal·수동 회복, retention, 관측·export와 운영 RabbitMQ 토폴로지는 후속
  결정과 검증이 필요하다.
- 현재 envelope는 통지 내용을 제공하지 않고 전달·수락·취소 전체 상태를 동기화하지
  않는다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-out-external:test
./gradlew --no-daemon test
```

구현 테스트는 전달 상태와 outbox의 원자적 commit, 멱등 replay, publisher 비활성 캡처,
정확한 여섯 필드·2,048-byte body·AMQP 속성, positive confirm·mandatory return 조합과 같은
event 재시도, 단건 claim, 1분 lease 만료 복구, lease-token fencing, 10초부터 1시간까지의
exponential retry, 10초 dispatch와 confirm timeout·exchange·routing 기본값을 검증해야 한다.

client test double을 사용한 결정적 계약 테스트와 RabbitMQ 4.3.4 폐기 가능한 단일
노드 통합 테스트가 통과했다. 통합 테스트는 routed persistent message의 positive confirm과
수신 header `0`건, positive confirm을 받은 unroutable mandatory publication의 `MANDATORY_RETURN`
분류를 확인한다.

이 테스트는 BATON publisher–RELAY consumer end-to-end, production RabbitMQ 사전 구성, workload
인증·TLS, 다중 노드 quorum 가용성, failover, partition, retention, alert이나 실제 채널 전달을
증명하지 않는다. RELAY consumer의 별도 검증도 BATON publisher 운영 연동을 자동으로 증명하지
않는다.

BATON의 공개 `/api/v1` HTTP 계약은 바뀌지 않으므로 REST Docs와 생성 OpenAPI에 새 operation을
추가하지 않는다.

## 관련 문서

- [BATON–RELAY 역할 바통 전달 이벤트 계약](../../PRD/0005_relay-role-handoff-integration/spec.md)
- [역할 바통 전달 생명주기](../0013_role_handoff_lifecycle/adr.md)
- [제품 개발 우선순위](../../PRD/0003_product-roadmap/spec.md)
- [BATON–WATCH 역할 자료 감시 계약](../../PRD/0004_watch-integration-contract/spec.md)
- [WATCH transactional outbox 결정](../0015_watch-transactional-outbox/adr.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [서버 요청 시간 예산](../0009_server-request-time-budget/adr.md)
