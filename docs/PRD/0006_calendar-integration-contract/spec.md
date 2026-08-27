# PRD-0006: BATON–CAL 일정 스냅샷 생산 계약

- 상태: 생산자 직렬화·트랜잭셔널 아웃박스·HTTP 전달·기존 데이터 보정·실제 CAL 검증 구현
- 기준일: 2026-08-27

## 1. 목적

BATON이 확정한 운영 회차와 루틴 실행 마감을 BATON CAL의 읽기 전용 iCalendar 피드에 손실 없이
반영한다. BATON은 원본 일정과 취소 의도를 소유하고, CAL은 전달받은 전체 스냅샷을 시즌별
`.ics` 표현으로 투영한다.

## 2. 고정한 외부 계약

BATON은 공개 불변 사전 릴리스
[`contracts-v1.0.0-rc.2`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0-rc.2)를
생산자 기준으로 고정한다.

| 항목 | 값 |
| --- | --- |
| 태그 커밋 | `730ae49a8b8eccf10e8f84f93b8a6a9d0fd24549` |
| 자산 | `baton-cal-contracts-1.0.0-rc.2.zip` |
| 자산 SHA-256 | `75120a7d21b6ea78c1e8bdab60829899525c1607262119053ea5904b57bd1eaf` |
| 일정 스키마 SHA-256 | `eec43ba76727cab8b5c1daa3af9ed014b8a8590e65b0a66b42e8f9ed9ba41309` |

저장소의 `contracts/baton-cal`은 이 버전 정보와 실제 생산자 테스트에 사용하는 일정 스키마를
보존한다. CAL의 Kotlin DTO나 내부 클래스를 공유 JAR로 가져오지 않는다.

## 3. 원본과 시간 형태

| BATON 원본 | CAL 시간 형태 | 규칙 |
| --- | --- | --- |
| 수동 `SeasonRound` | `ALL_DAY` | `meetingDate`부터 다음 날짜 전까지의 배타적 날짜 구간 |
| 자동 `SeasonRound` | `ZONED_LOCAL_POINT` | `scheduledAt`을 시즌 IANA 시간대로 되돌린 단일 현지 시점 |
| 마감이 있는 `RoutineExecution` | `UTC_POINT` | 저장된 `deadlineAt` 단일 UTC 시점 |
| 마감이 없는 `RoutineExecution` | 생산하지 않음 | 사람이 읽는 `dueLabel`에서 시각을 추측하지 않음 |

단일 시점에 임의 지속 시간을 붙이지 않고, 수동 회차에 자정 시각을 만들지 않는다. 자동 회차는
시즌 시간대 식별자를 보존하며 루틴 마감은 이미 BATON이 확정한 `Instant`를 그대로 사용한다.

## 4. 식별자와 상태

- `sourceItemId`는 원본 `SeasonRound.id` 또는 `RoutineExecution.id`다. 생성 뒤 다른 원본에
  재사용하지 않는다.
- `seasonId`는 원본 회차의 시즌 식별자다.
- 회차 이름은 회차 `summary`, 실행 제목은 마감 `summary`, 실행 상세는 마감 `description`이다.
- 회차 보관은 해당 회차와 마감이 있는 실행의 `CANCELLED` 스냅샷을 만든다. 복원은 더 높은
  개정 번호의 `ACTIVE` 스냅샷을 만든다.
- 실행 완료는 마감 취소가 아니므로 CAL 상태를 바꾸지 않는다.
- 시즌 종료는 과거 일정 취소가 아니므로 기존 항목을 일괄 `CANCELLED`로 바꾸지 않는다.

## 5. 개정 번호와 전달 경계

원본 변경과 같은 MySQL 트랜잭션에 불변 아웃박스 스냅샷을 기록하고, 아웃박스의 양수
`INT AUTO_INCREMENT` 식별자를 CAL `revision`으로 사용한다. 수동 회차 생성·정정·보관·복원과
자동 회차 생성은 회차 및 실제 마감이 있는 실행을 함께 기록한다. 실행 완료 변경은 일정 취소가
아니므로 기록하지 않는다.

- 이벤트 ID는 아웃박스 행마다 새 UUID를 발급한다.
- 같은 아웃박스 행의 재전송은 같은 이벤트 ID와 같은 전체 페이로드를 사용한다.
- HTTP 호출은 원본 트랜잭션 커밋 뒤 별도 작업자가 수행한다.
- CAL 장애는 이미 커밋한 BATON 원본 변경을 롤백하지 않는다.
- 같은 원본의 시각이 직전 시각보다 늦지 않으면 원본 행 잠금 안에서 1마이크로초 전진시킨다.
- 같은 `sourceItemId`의 이전 미종결 행보다 후속 행을 먼저 보내지 않는다.
- `APPLIED`, `DUPLICATE`, `STALE`는 전달 완료로 처리하고 계약 충돌과 잘못된 요청은 자동으로
  새 개정 번호를 만들어 덮지 않는다.

V22는 외래 키 없이 전체 스냅샷과 전달 대기 상태를 보존한다. 원본을 보관하거나 생명주기가 바뀌어도
취소 전달 근거가 남아야 하기 때문이다. V23은 처리 상태, 1분 임대와 fencing token을 추가한다.
작업자는 한 번에 한 행을 짧은 트랜잭션으로 임대한 뒤 MySQL 트랜잭션 밖에서 CAL을 호출하고,
개정 번호와 임대 토큰이 모두 맞을 때만 결과를 기록한다. 임대가 만료되면 같은 불변 행을 다시
전송하며 늦게 끝난 이전 작업자는 새 임대의 결과를 덮지 못한다.

네트워크 오류, 인증 자격 증명 교체로 회복할 수 있는 `401`·`403`, `429`와 `5xx`는 10초부터 최대
1시간까지 지수 backoff로 재시도한다. 정확한 `200`의 `APPLIED`, `DUPLICATE`, `STALE`만 완료로
처리한다. 잘못된 성공 본문과 나머지 `4xx` 계약 오류는 영구 실패로 기록하고 응답 원문은 저장하지
않는다.

`BATON_CAL_CAPTURE_ENABLED`, `BATON_CAL_BACKFILL_ENABLED`, `BATON_CAL_DELIVERY_ENABLED`의 기본값은
`false`다. 전달을 켤 때는
`BATON_CAL_BASE_URL`에 경로가 없는 절대 HTTPS 출처, `BATON_CAL_BEARER_TOKEN`에 32~200자의 URL 안전
ASCII 자격 증명을 넣는다. 연결·읽기 시간 제한의 합은 45초 이하이고 리디렉션은 따르지 않는다.

기존 일정 보정은 실제 날짜·시각이 있는 회차를 UUID 키셋 기반 100개 페이지로 읽고 회차마다 짧은
새 트랜잭션을 사용한다.
현재 회차·마감의 의미가 마지막 아웃박스 스냅샷과 다를 때만 새 행을 추가하므로 중단 뒤 전체 작업을
다시 실행해도 중복 행이 생기지 않는다. 이미 스냅샷을 보낸 회차는 이후 보관되더라도 보정 대상에
남아 취소 스냅샷을 추가한다.

## 6. 현재 검증

- 고정한 일정 JSON Schema의 SHA-256을 테스트 시작 시 확인한다.
- 수동 회차, 자동 회차와 루틴 마감의 실제 BATON 도메인 객체를 외부 요청으로 직렬화한다.
- 세 요청을 Draft 2020-12 스키마에 직접 대조한다.
- 회차 보관이 회차와 실행 스냅샷을 모두 `CANCELLED`로 만드는지 확인한다.
- 원본 저장 경계가 회차·실행 변경을 기록하고 자동 회차 생성도 같은 기록기를 호출하는지 확인한다.
- 세 시간 형태가 MySQL 불변 행으로 저장되고 아웃박스 번호가 개정 번호로 증가하는지 확인한다.
- 원본 트랜잭션 롤백 시 아웃박스도 롤백되고 같은 원본의 수정 시각이 단조 증가하는지 확인한다.
- V22 대기 행을 V23으로 이관하고 임대 없는 처리 상태를 DB 제약이 거부하는지 확인한다.
- 같은 원본의 이전 행만 선점하고 만료 임대를 다시 선점할 때 이전 fencing token의 결과를 거부한다.
- 인증된 전체 스냅샷 요청과 CAL 성공·중복·충돌·네트워크 응답 분류를 검증한다.
- 응답 유실 뒤 같은 행을 다시 보내 `DUPLICATE`로 완료하는 애플리케이션 흐름을 검증한다.
- 기존 활성 회차와 마감을 보정하고 재실행에서는 새 행이 없으며 보관 뒤에는 회차와 마감의
  `CANCELLED` 행만 추가하는지 실제 MySQL에서 검증한다.
- `./ops/tests/calendar-consumer-contract.sh`가 CAL rc.2 소스의 실제 PostgreSQL 컨테이너를 띄우고
  BATON 운영 클라이언트로 생성·변경·취소, 응답 유실 재전달과 역순 전달을 검증한다.

## 7. 운영 활성화 순서

1. CAL과 전용 Bearer를 준비하고 기존 BATON 문자열의 NFC·제어 문자 적합성을 점검한다.
2. 전달은 끈 채 `BATON_CAL_CAPTURE_ENABLED=true`, `BATON_CAL_BACKFILL_ENABLED=true`로 한 번
   기동해 기존 회차·마감 보정 완료 로그를 확인한다.
3. `BATON_CAL_BACKFILL_ENABLED=false`로 되돌리고 캡처는 유지한다.
4. 아웃박스 실패 행이 없음을 확인한 뒤 `BATON_CAL_DELIVERY_ENABLED=true`로 전환한다.
5. CAL에서 전달 적체와 시즌 피드의 대표 회차·마감을 확인한다.

## 8. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [CAL 생산자 경계 결정](../../ADR/0019_calendar_snapshot_producer/adr.md)
- [시즌 시간대와 회차·마감 자동화](../../ADR/0012_round_schedule_and_deadline_automation/adr.md)
