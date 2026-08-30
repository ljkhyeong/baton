# PRD-0006: BATON–CAL 일정 스냅샷 생산 계약

- 상태: 생산자 직렬화·트랜잭셔널 아웃박스·HTTP 전달·기존 데이터 보정·실제 CAL 검증 구현
- 기준일: 2026-08-30

## 1. 목적

BATON이 확정한 운영 회차와 루틴 실행 마감을 BATON CAL의 읽기 전용 iCalendar 피드에 손실 없이
반영한다. BATON은 원본 일정과 취소 의도를 소유하고, CAL은 전달받은 전체 스냅샷을 시즌별
`.ics` 표현으로 투영한다.

## 2. 고정한 외부 계약

BATON은 공개 불변 안정 릴리스
[`contracts-v1.0.0`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0)를
생산자 기준으로 고정한다.

| 항목 | 값 |
| --- | --- |
| 태그 커밋 | `fd081a742b7c09a7ace53bb445ce1380c533c19e` |
| 자산 | `baton-cal-contracts-1.0.0.zip` |
| 자산 SHA-256 | `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7` |
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
자동 회차 생성에서는 회차와 실제 마감이 있는 실행을 각각 최신 아웃박스 스냅샷과 비교한다.
이전 스냅샷이 없거나 시즌·상태·제목·설명·장소·시간이 달라진 원본만 새 행으로 기록한다.
이벤트 ID와 수정 시각만 달라진 경우에는 새 행을 만들지 않는다.

같은 보관·복원 요청을 반복해도 이미 기록한 상태는 다시 추가하지 않는다. 회차 이름만 바뀌면
회차 스냅샷만 추가하고 변경 없는 실행 마감은 건너뛴다. 실제 보관·복원은 회차와 실행 마감의
상태가 달라지므로 각각 새 스냅샷을 기록한다. 실행 완료 변경은 일정 취소가 아니므로 기록하지 않는다.

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
않는다. `200` 응답 본문을 읽는 중 발생한 시간 초과나 연결 끊김은 본문 계약 오류와 구분해
같은 아웃박스 행을 재시도한다.

BATON은 공통 Prometheus 지표에 CAL 아웃박스의 `PENDING`·`PROCESSING`·`FAILED` 수, 조치 대상
영구 실패 수, 만료된 `PROCESSING` 임대 수, 가장 오래된 대기 시간과 마지막 전달 성공 시각을
노출한다. 운영 명령은 애플리케이션 컨테이너의 비공개 Prometheus 응답에서
CAL·WATCH·BRIEF·이메일 지표를 읽으며 실패 행을 자동 재처리하거나 삭제하지 않는다. 마지막 전달·정상 갱신 시각의
`0`은 해당 기록이 아직 없다는 뜻이다. 대기 시간의 `0`은 대기 행이 없거나 가장 오래된 행도 생성된
지 1초가 지나지 않은 상태이므로 대기 항목 수와 함께 판단한다. 지표 조회 실패는 제품 요청과 전달
작업을 막지 않고 마지막 정상 스냅샷과 갱신 실패 지표로 구분한다. 호스트의 읽기 전용 주기 점검은
지표 갱신 실패·정체, 영구 실패와 만료 임대를 실패 종료로 기록하되 정상 `PENDING` 재시도만으로는
실패시키지 않는다. 외부 알림과 실패 행 자동 재처리는 하지 않으며 운영자는 README의 순서대로 실패
코드와 재시도 가능 시각을 함께 확인한다.

`BATON_CAL_CAPTURE_ENABLED`, `BATON_CAL_BACKFILL_ENABLED`, `BATON_CAL_DELIVERY_ENABLED`의 기본값은
`false`다. 전달을 켤 때는
`BATON_CAL_BASE_URL`에 경로가 없는 절대 HTTPS 출처를 넣는다. 32~200자의 URL 안전 ASCII 자격
증명은 소유자 전용 파일에 저장하고 `BATON_CAL_BEARER_TOKEN_FILE`에는 그 절대 경로만 넣는다.
프로덕션 Compose는 원문을 환경 변수로 전달하지 않고 Compose secret과 Spring 설정 트리를 사용한다.
연결·읽기 시간 제한의 합은 45초 이하이고 리디렉션은 따르지 않는다.

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
- 실시간 캡처를 반복해도 같은 내용은 추가하지 않고, 이름만 바꾸면 회차만 기록하며 실제 보관·복원은
  회차와 실행 마감의 상태 변경을 모두 기록하는지 실제 MySQL에서 확인한다.
- 세 시간 형태가 MySQL 불변 행으로 저장되고 아웃박스 번호가 개정 번호로 증가하는지 확인한다.
- 원본 트랜잭션 롤백 시 아웃박스도 롤백되고 같은 원본의 수정 시각이 단조 증가하는지 확인한다.
- V22 대기 행을 V23으로 이관하고 임대 없는 처리 상태를 DB 제약이 거부하는지 확인한다.
- 같은 원본의 이전 행만 선점하고 만료 임대를 다시 선점할 때 이전 fencing token의 결과를 거부한다.
- 인증된 전체 스냅샷 요청과 CAL 성공·중복·충돌·네트워크 응답 분류를 검증한다.
- 응답 유실 뒤 같은 행을 다시 보내 `DUPLICATE`로 완료하는 애플리케이션 흐름을 검증한다.
- 기존 활성 회차와 마감을 보정하고 재실행에서는 새 행이 없으며 보관 뒤에는 회차와 마감의
  `CANCELLED` 행만 추가하는지 실제 MySQL에서 검증한다.
- 보정 대상 전체의 제목과 설명을 먼저 읽기 전용으로 점검하고, NFC가 아니거나 LF·HTAB 외 제어
  문자가 있으면 원본 UUID와 필드만 알린 채 아웃박스를 하나도 추가하지 않는지 검증한다.
- Actuator Prometheus의 `baton_integration_delivery_items`와
  `baton_integration_delivery_actionable_failed_items`가 `integration="calendar"` 범위에서 MySQL
  아웃박스의 상태별 현재 행 수와 조치 대상 실패 수를 노출하는지 공통 운영 지표 테스트로 검증한다.
- `./ops/tests/calendar-consumer-contract.sh`가 CAL 안정 계약 `1.0.0` 소스의 실제 PostgreSQL 컨테이너를 띄우고
  BATON 운영 클라이언트로 생성·변경·취소, 응답 유실 재전달과 역순 전달을 검증한다.

## 7. 운영 활성화 순서

1. CAL과 전용 Bearer 파일을 준비하고 프로덕션 사전점검을 통과한다.
2. 전달은 끈 채 `BATON_CAL_CAPTURE_ENABLED=true`, `BATON_CAL_BACKFILL_ENABLED=true`로 한 번
   기동한다. 보정 전 자동 점검이 실패하면 로그의 원본 UUID와 필드를 바로잡은 뒤 다시 실행하며,
   완료 로그가 나오기 전에는 전달을 켜지 않는다.
3. `BATON_CAL_BACKFILL_ENABLED=false`로 되돌리고 캡처는 유지한다.
4. `./ops/check-integration-delivery.sh`가 성공하는지 확인하고, `./ops/show-integration-metrics.sh`와
   DB 상태에서 `baton_integration_delivery_actionable_failed_items{integration="calendar"}`가
   `0`인지 확인한 뒤
   `BATON_CAL_DELIVERY_ENABLED=true`로 전환한다.
5. 두 점검 명령을 다시 실행해 `pending`, `processing`, `failed`가 모두 `0`으로 수렴했는지 확인하고
   CAL 시즌 피드의 대표 회차·마감을 확인한다.

## 8. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [CAL 생산자 경계 결정](../../ADR/0019_calendar_snapshot_producer/adr.md)
- [시즌 시간대와 회차·마감 자동화](../../ADR/0012_round_schedule_and_deadline_automation/adr.md)
