# PRD-0006: BATON–CAL 일정 스냅샷 생산 계약

- 상태: 1차 생산자 직렬화 구현, 트랜잭셔널 아웃박스·전달 미구현
- 기준일: 2026-08-25

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

이번 단계의 생산자 모델은 외부에서 확정한 `eventId`, `revision`, `occurredAt`을 받아 계약 JSON을
만든다. 실제 연결 단계에서는 원본 변경과 같은 MySQL 트랜잭션에 불변 아웃박스 스냅샷을 기록하고,
아웃박스의 양수 `INT AUTO_INCREMENT` 식별자를 CAL `revision`으로 사용한다.

- 이벤트 ID는 아웃박스 행마다 새 UUID를 발급한다.
- 같은 아웃박스 행의 재전송은 같은 이벤트 ID와 같은 전체 페이로드를 사용한다.
- HTTP 호출은 원본 트랜잭션 커밋 뒤 별도 작업자가 수행한다.
- CAL 장애는 이미 커밋한 BATON 원본 변경을 롤백하지 않는다.
- 같은 `sourceItemId`의 이전 미종결 행보다 후속 행을 먼저 보내지 않는다.
- `APPLIED`, `DUPLICATE`, `STALE`는 전달 완료로 처리하고 계약 충돌과 잘못된 요청은 자동으로
  새 개정 번호를 만들어 덮지 않는다.

아웃박스, 인증, 재시도와 기존 데이터 조정은 아직 구현하지 않았다. 따라서 현재 코드는 CAL에
네트워크 요청을 보내지 않는다.

## 6. 현재 검증

- 고정한 일정 JSON Schema의 SHA-256을 테스트 시작 시 확인한다.
- 수동 회차, 자동 회차와 루틴 마감의 실제 BATON 도메인 객체를 외부 요청으로 직렬화한다.
- 세 요청을 Draft 2020-12 스키마에 직접 대조한다.
- 회차 보관이 회차와 실행 스냅샷을 모두 `CANCELLED`로 만드는지 확인한다.

## 7. 다음 완료 기준

- 원본 생성·정정·보관·복원과 자동 회차 생성 트랜잭션이 불변 CAL 아웃박스를 함께 기록한다.
- 회차 정정은 회차와 마감이 있는 실행을 각각 더 높은 개정 번호로 기록한다.
- 응답 유실 뒤 같은 행 재전송이 CAL에서 `DUPLICATE`로 수렴한다.
- 기본 비활성 설정, HTTPS 기준 URL과 별도 Bearer 토큰을 적용한다.
- 기존 활성 회차와 마감을 고정 크기 페이지로 조정하고 누락된 스냅샷을 채운다.
- 실제 CAL rc.2 컨테이너를 대상으로 생성·변경·취소와 순서 뒤바뀜을 검증한다.

## 8. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [CAL 생산자 경계 결정](../../ADR/0019_calendar_snapshot_producer/adr.md)
- [시즌 시간대와 회차·마감 자동화](../../ADR/0012_round_schedule_and_deadline_automation/adr.md)
