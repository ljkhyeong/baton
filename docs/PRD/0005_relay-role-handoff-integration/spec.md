# PRD-0005: BATON–RELAY 역할 바통 전달 이벤트 연동 계약

- 상태: 1차 발행 계약 채택, 단일 노드 wire 검증 완료·RELAY E2E 미검증
- 기준일: 2026-08-08

## 1. 목적

BATON의 역할 바통 전달 transaction과 BATON RELAY의 영속 수신·중복 제거·후속 전달을
분리한다. BATON은 바통이 `PREPARING`에서 `TRANSFERRED`로 전이했다는 domain
사건을 원본 transaction에서 잃지 않게 캡처하고, commit 후에 비동기 RabbitMQ 메시지로
RELAY에 발행한다.

BATON은 조직, 시즌, 역할, 역할 바통과 그 상태의 source of truth다. RELAY는 이벤트
inbox, `eventId` 중복 제거, 구독·provider binding, 전달 job과 결과를 소유하며 역할 바통
상태를 결정하지 않는다.

## 2. 서비스 경계

- BATON은 유효한 역할 바통 전이, 전달 시각, 안정적인 event ID와 transactional outbox를
  소유한다.
- RELAY는 수신한 envelope의 inbox, exact replay·conflict 판정, exact-match 구독 적격성,
  전달 job·attempt·result 생명주기를 소유한다.
- BATON의 역할 바통 mutation transaction은 RELAY, RabbitMQ 또는 실제 채널 provider의 응답을
  기다리지 않는다.
- RELAY·broker·provider 장애는 이미 commit된 BATON 전달 기록을 rollback하거나 숨기지
  않는다.
- 이벤트는 전달 사건을 알리는 신호이며 역할·바통 스냅샷, 사용자 신원, 권한 증거나
  전자 서명이 아니다.
- BATON은 채널별 수신자 해석, 목적지 해결, template 선택과 provider 호출을 소유하지
  않는다.

## 3. 이벤트 발생 의미

`ROLE_HANDOFF_TRANSFERRED` version 1은 다음 경계만 표현한다.

- 기존 `RoleHandoff`가 처음 `PREPARING → TRANSFERRED`로 전이한 성공 transaction에서 한 건을
  append한다.
- 상태 전이와 outbox append 중 하나라도 실패하면 transaction 전체를 rollback하므로 두
  기록은 함께 있거나 함께 없다.
- 이미 `TRANSFERRED`, `ACCEPTED` 또는 `CANCELLED`인 이력의 동일 전달 요청을 멱등 replay해
  현재 표현을 반환할 때는 새 outbox event를 만들지 않는다.
- 거절되거나 rollback된 전달 요청은 event를 만들지 않는다.
- 수락과 취소는 version 1의 새 event type이 아니다. 이후 상태가 바뀌어도 이미 기록한
  전달 event를 수정·취소하지 않는다.
- `occurredAt`은 해당 전이에 저장한 `RoleHandoff.transferredAt`이다. publisher 시각이나 재시도
  시각으로 대체하지 않는다.
- `eventId`는 outbox event를 처음 append할 때 한 번 부여하고 모든 재시도에서 보존한다.

Flyway나 시작 시 reconciliation으로 기존 `TRANSFERRED`·`ACCEPTED`·`CANCELLED` 이력의 과거 event를
합성하지 않는다. 과거의 정확한 발생 transaction과 event ID를 복원할 근거가 없기 때문이다.

## 4. RELAY wire envelope

UTF-8 JSON body는 빈 문서가 아니고 2,048 bytes 이하이며 다음 여섯 필드만 가진다.

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

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `contractVersion` | integer | 필수, 정확히 `1` |
| `eventId` | UUID | 필수, BATON outbox event ID이자 RELAY의 유일 수신 멱등키 |
| `eventType` | string | 필수, 정확히 `ROLE_HANDOFF_TRANSFERRED` |
| `eventVersion` | integer | 필수, 정확히 `1` |
| `subjectReference` | string | 필수, 정확히 `role:{roleId}`; `roleId`는 해당 BATON `Role` UUID |
| `occurredAt` | string | 필수, UTC ISO-8601 instant이며 `Z`로 끝남 |

누락, `null`, 중복 JSON key, 추가 필드, 잘못된 타입, 지원하지 않는 version은 계약 밖이다.
body에 payload, 구성원·수신자·연락처, 역할·바통 내용, URL, credential, 목적지, template,
추적 정보를 추가하지 않는다. `subjectReference`는 이름이나 연락처가 아닌 안정적인 opaque
reference다.

## 5. RabbitMQ publication 계약

BATON publisher가 설정하는 AMQP 속성은 다음과 같다.

| 속성 | 규칙 |
| --- | --- |
| `contentType` | 정확히 `application/json` |
| delivery mode | `PERSISTENT` |
| `messageId` | body의 `eventId`와 같은 UUID 문자열 |
| mandatory | 항상 `true` |

publisher는 application header를 하나도 설정하지 않는다. content encoding, correlation ID, reply-to,
type, expiration, priority, timestamp, user ID, app ID, cluster ID를 포함한 선택 속성도 설정하지
않는다. publisher는 `RabbitTemplate.execute`의 한 channel scope에서 publication을 정확히 하나만
실행한다. 임시 `ReturnCallback`/`ReturnListener`를 등록하고 header table이 없는 raw
`basicPublish(mandatory=true)` 후 `waitForConfirms`를 수행해 같은 scope의 return과 confirm을
해당 publication에 연결한다. 끝나면 `finally`에서 listener를 제거한다. AMQP
`correlationId`나 application header를 상관 수단으로 전송하지 않는다.

다음 두 증거가 모두 있을 때만 해당 outbox event를 발행 완료로 표시한다.

1. 해당 publication의 publisher confirm이 positive ack이다.
2. 같은 publication에 대한 mandatory return이 없다.

라우팅되지 않아 return된 메시지도 broker ack를 받을 수 있으므로 positive confirm만으로는
완료하지 않는다. return, negative confirm, confirm timeout, connection 오류, listener 처리
실패나 결과를 확정할 수 없는 경우는 outbox를 미완료로 남기고 같은 `eventId`와 같은
envelope로만 다시 시도한다.

기본 exchange는 `baton.events.v1`, routing key는 `relay.events.v1`이다. 두 값과 RabbitMQ workload
credential, TLS, topology 사전 구성과 운영 용량은 환경별 설정이며 body나 AMQP
metadata에 넣지 않는다. confirm timeout의 기본값은 5초고 설정 허용 범위는 1ms부터
45초까지다.

## 6. outbox, transaction과 at-least-once

- 역할 바통 전달 mutation과 같은 MySQL transaction에서 immutable outbox event를 append한다.
- envelope 값은 append 후 수정하지 않고, 발행 완료 여부와 후속 운영 metadata만 분리해
  변경할 수 있다.
- outbox는 `PENDING`, `PUBLISHING`, `PUBLISHED` 상태를 명시적으로 저장한다.
- publisher는 10초 기본 dispatch 간격으로 due row를 한 건씩 claim한다. claim은 짧은
  transaction에서 `FOR UPDATE SKIP LOCKED`를 사용해 `PENDING` 또는 lease가 만료된
  `PUBLISHING`을 1분 lease와 새 lease token의 `PUBLISHING`으로 전이하고 시도 횟수를
  올린다.
- publisher는 commit된 outbox만 읽는다. broker I/O는 claim transaction을 commit한 뒤 BATON DB
  transaction, row lock과 제품 transaction 시간 예산 밖에서 수행한다.
- `PUBLISHED` 완료와 retry 예약은 `rowId + PUBLISHING + current leaseToken`이 모두 일치할
  때만 반영한다. lease 시각이 지났더라도 아직 re-claim되지 않아 token이 그대로라면
  현 worker의 finalize를 허용한다. 다른 worker가 만료 row를 re-claim해 token을 회전한
  뒤에는 이전 worker의 완료·retry 갱신을 무시한다.
- publish 성공 후 completion update가 실패하거나 confirm 결과가 유실되면 같은 event가 중복
  발행될 수 있다. BATON–RabbitMQ–RELAY 사이에 exactly-once나 분산 transaction을 가정하지
  않는다.
- 전달은 at-least-once이며 RELAY inbox는 `eventId`로 중복을 제거한다. 같은 ID와 같은 envelope의
  exact replay는 새 inbox나 job을 만들지 않고, 같은 ID와 다른 envelope는 producer 계약 충돌이다.
- publisher 활성 여부와 outbox capture를 분리한다. publisher가 기본 비활성이거나 임시
  중지된 동안에도 새 `ROLE_HANDOFF_TRANSFERRED` event를 항상 원본 transaction에 capture한다.
  후에 publisher를 활성화하면 그 뒤의 domain 상태를 재계산하지 않고 이미 캡처한 backlog를
  발행한다.
- 비활성 상태에서는 Rabbit health contributor도 꺼서 RabbitMQ가 없는 기존 BATON 배포의
  aggregate health를 바꾸지 않는다. 활성화할 때는 host, port, username, password와 virtual
  host를 모두 명시하지 않으면 시작을 거부한다.
- 현재 production env validator와 Compose는 RELAY broker 입력을 허용·전달하지 않는다.
  provisioning, workload 인증·TLS와 운영 topology를 검증하는 후속 운영 변경 전까지 표준
  파일럿 production 배포에서는 publisher를 활성화하지 않는다.

## 7. RELAY provisioning 선행 조건

RELAY는 이벤트를 inbox에 저장할 때 활성 provider binding과 활성 exact-match subscription이
이미 있어야 전달 job을 만든다. 구독은 최소한 다음 세 match field를 같아야 한다.

```text
subjectReference = role:{roleId}
eventType = ROLE_HANDOFF_TRANSFERRED
eventVersion = 1
```

구독과 binding 상태 변경은 이미 수신한 inbox event를 backfill하지 않고 exact event replay에
새 job도 추가하지 않는다. 따라서 대상 역할의 provider binding과 구독을 생성·활성화하고
유효함을 확인하기 전에 BATON publisher로 그 역할의 backlog를 발행하면 안 된다. 선행 조건을
지키지 않으면 RELAY는 event를 정상 수락하고도 job `0`건을 만들고, BATON은 broker
발행을 완료로 표시할 수 있다.

채널, provider, credential reference, 수신자, destination reference, template과 역할 생명주기에 따른
구독 provisioning 절차는 아직 결정하지 않는다. 이 문서는 특정 메시지·메일·큐, 연락처,
수신자 해석 규칙이나 template 내용을 채택하지 않는다. 이를 결정하고 RELAY provisioning을
완료하기 전까지 운영 publisher는 비활성으로 둔다.

## 8. retry, concurrency, retention과 관측 경계

### 8.1 현재 1차 범위

- 완료 조건을 만족하지 못한 event는 미완료로 남아 같은 `eventId`로 다시 시도할 수
  있다.
- 재실행과 재시도가 envelope를 재생성하거나 새 event ID를 발급하지 않는다.
- retry delay는 첫 시도 실패 후 10초에서 시작해 시도 횟수에 따라 `10초 × 2^(attempt-1)`로
  증가하고 1시간에서 상한한다. 현재 jitter, 최대 횟수와 terminal failure 상태는 없다.
- 각 dispatch는 due row를 최대 한 건만 claim한다. 1분 lease, `SKIP LOCKED`와 lease-token
  fencing으로 만료 복구와 stale worker 갱신 차단을 구분한다. 경쟁 worker는 이미
  잠긴 머리 row에서 기다리지 않고 다음 적격 row를 claim할 수 있다.
- `relay_event_outbox`는 publication status, 시도 횟수, 다음 시도 시각, lease token·만료
  시각, 발행 완료 시각과 최대 64자의 안정적인 마지막 오류 code만 저장한다. raw
  exception, broker response와 message body를 오류 필드에 저장하지 않는다.
- scheduler는 미확정 결과가 있는 tick에 `claimed`, `published`, `retry` 건수만 경고로 남긴다.
  event ID, subject reference, body, broker reply text를 로그하지 않는다.
- 테스트는 캡처 원자성, wire body·AMQP 속성, confirm·return 완료 판정과 동일 event 재시도를
  포함해 lease 만료 re-claim, token 회전 fencing과 exponential retry 상한을 결정적으로 검증한다.

### 8.2 아직 채택하지 않은 운영 정책

- jitter, 최대 시도 횟수, terminal failure 분류와 수동 재처리 절차
- lease 갱신, 단건을 넘는 batch 크기와 throughput·backpressure 정책
- 완료·미완료 outbox의 보존 기간, 정리 batch, archive·백업·복구 정책
- 운영자용 backlog·실패 조회·재처리 API·UI, metric·export, alert, dashboard, SLO와 운영 runbook
- 운영 RabbitMQ의 인증·TLS, quorum 노드, capacity, retention, failover·partition·upgrade·복구 목표

현재 claim·lease·fencing과 retry scheduler는 구현 범위이지만 운영 가시성, 수동 회복,
자동 정리와 production 다중 instance·broker 가용성을 완료했다는 증거가 아니다. event ID,
subject reference와 JSON body를 로그·metric label에 넣는 방식은 관측 해법으로 사용하지 않는다.

## 9. 알려진 한계

- `ROLE_HANDOFF_TRANSFERRED` version 1에는 상세 payload가 없어 연락처나 알림 문구를 만들 정보를
  제공하지 않는다.
- 수락·취소 이벤트, 다른 역할을 포함하는 구독, wildcard 매칭과 과거 이벤트 backfill은
  이 계약에 없다.
- RELAY의 소비자·inbox 계약이 구현되어 있더라도 이 BATON 저장소의 결정적 테스트는
  BATON publisher의 wire 계약만 검증하며 두 서비스의 운영 연결을 증명하지 않는다.
- client test double을 사용한 결정적 계약 테스트와 RabbitMQ 4.3.4 폐기 가능한 단일
  노드 통합 테스트가 통과했다. 통합 테스트는 routed persistent message의 positive
  confirm과 수신 header `0`건, positive confirm을 받은 unroutable mandatory publication의
  `MANDATORY_RETURN` 분류를 확인한다.
- 위 단일 노드 테스트는 BATON publisher–RELAY consumer end-to-end, 운영 topology 사전 구성,
  quorum 가용성과 failover를 증명하지 않는다.
- 현재는 모든 publish failure를 retry 가능한 결과로 남긴다. 장기적 설정 오류나
  unroutable 메시지를 terminal로 닫거나 운영자가 선택해 재처리하는 계약은 없다.

## 10. 구현 완료 기준

- 처음 `PREPARING → TRANSFERRED`로 전이하는 transaction이 정확히 하나의 immutable outbox event를
  함께 저장하고 replay는 추가 event를 만들지 않는다.
- publisher 비활성 상태에서도 전달 event를 캡처하고, broker I/O는 원본·결과 DB
  transaction 밖에서만 수행한다.
- body가 정확한 여섯 필드와 고정 event type·version·subject reference를 사용하고 2,048-byte
  제한을 지킨다.
- persistent, `application/json`, `messageId=eventId`, header·선택 속성 없음, mandatory와
  channel 단위 simple publisher confirm을 검증한다.
- positive confirm과 return 없음이 모두 있을 때만 완료하고 나머지 결과는 같은 event
  재시도가 가능하게 남긴다.
- 단건 claim, `PENDING → PUBLISHING → PUBLISHED`, 1분 lease, token fencing, 10초부터
  1시간까지의 exponential retry와 10초 dispatch 기본값을 검증한다.
- confirm timeout 기본 5초와 1ms~45초 설정 범위, 기본 exchange·routing key를 시작 설정에서
  고정한다.
- publisher 비활성 시 Rabbit health가 기존 aggregate health에 참여하지 않고, 활성 시
  명시적인 broker 연결값이 없으면 fail-closed하는지 검증한다.
- RELAY provisioning을 선행하지 않으면 job이 생성되지 않는 경계를 운영 절차와 검증에
  반영한다.

## 11. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [역할 바통 전달 생명주기](../../ADR/0013_role_handoff_lifecycle/adr.md)
- [RELAY transactional outbox 결정](../../ADR/0016_relay-transactional-outbox/adr.md)
- [BATON–WATCH 역할 자료 감시 계약](../0004_watch-integration-contract/spec.md)
- [WATCH transactional outbox 결정](../../ADR/0015_watch-transactional-outbox/adr.md)
