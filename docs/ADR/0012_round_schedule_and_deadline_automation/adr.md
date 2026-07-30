# ADR-0012: 시즌 시간대와 수렴형 회차·마감 자동화

- 상태: 채택
- 날짜: 2026-07-30

## 맥락

BATON의 기존 회차는 운영자가 이름과 모임 날짜를 직접 입력하는 수동 기록이었다. 루틴의 `dueLabel`도 사람이 읽는 기한 문구일 뿐 계산 가능한 시각이 아니어서, 매주 또는 격주로 모이는 그룹 스터디는 회차를 반복 생성하고 지연 여부를 직접 판단해야 했다.

회차 생성과 마감 판정에는 조직이 사용하는 달력 날짜와 시간대가 필요하다. 서버의 UTC `Clock`, 브라우저 위치나 scheduler가 실행된 시스템 시간대를 그대로 사용하면 같은 스터디도 실행 위치에 따라 생성일과 지연 경계가 달라진다. 일광 절약 시간 전환이 있는 시간대에서는 존재하지 않거나 두 번 나타나는 로컬 시각의 해석도 고정해야 한다.

자동 생성은 한 프로세스의 cron 성공만 믿을 수 없다. 애플리케이션 재시작, 긴 중단, 중복 실행과 여러 인스턴스가 있어도 이미 만든 예정일을 중복 생성하지 않고 누락된 회차를 다시 따라잡아야 한다. 동시에 한 번의 실행이 밀린 전체 이력을 무제한 처리해 transaction과 서버 자원을 독점해서도 안 된다.

## 결정

### 시즌 시간대와 단일 반복 일정

- `Season`은 최대 64자의 유효한 IANA 시간대 식별자 `timeZone`을 소유한다. 기존 시즌과 최초 온보딩의 기본값은 `Asia/Seoul`이다.
- 다음 시즌은 원본 시즌의 시간대를 이어 받지만 회차 일정과 발생 커서는 복사하지 않는다.
- 한 시즌은 nullable한 `RoundSchedule` 하나만 가진다. 일정은 첫 모임 날짜, 시즌 시간대 기준 모임 시각, `WEEKLY` 또는 `BIWEEKLY` 반복 주기, `0..30`일의 선행 생성 기간, 활성 여부와 다음 발생일 커서로 구성한다.
- 첫 모임 날짜는 시즌 기간 안에 있어야 한다. 일정 변경은 이미 전진한 커서를 되감지 않고 새 반복 규칙에서 기존 커서 이후의 첫 발생일로 정렬한다.
- 일정 비활성화는 커서를 보존한 일시 중지다. 다시 활성화하면 보존한 커서부터 수렴한다.
- 회차가 하나라도 생성된 뒤에는 시즌 시간대를 바꿀 수 없다. 이미 저장한 모임 시각과 마감 `Instant`의 의미를 과거로 소급해 바꾸지 않기 위한 경계다.
- 종료 시즌은 일정을 변경하거나 자동 생성하지 않는다.

일정 활성화 시 시즌의 모든 루틴에 계산 가능한 마감 규칙이 있어야 한다. 활성 일정이 있는 동안 새 루틴을 만들거나 기존 루틴을 수정할 때도 마감 규칙을 제거할 수 없다. 일정이 비활성화된 시즌과 기존 데이터는 실제 마감이 없는 루틴을 계속 허용한다.

### 실제 마감 스냅샷과 파생 상태

- `Routine`은 사람이 읽는 `dueLabel`을 유지하면서 모임 날짜 기준 `-30..30`일의 `deadlineDayOffset`과 시즌 시간대 기준 `deadlineTime`을 선택적으로 가진다. 두 필드는 함께 설정하거나 함께 비워야 한다.
- 수동 또는 자동 회차를 만들 때 루틴의 마감 규칙을 `RoutineExecution`에 복사하고, `meetingDate + deadlineDayOffset + deadlineTime + season.timeZone`을 UTC `deadlineAt`으로 계산해 저장한다.
- 루틴 정의를 나중에 바꿔도 기존 실행의 마감 규칙과 `deadlineAt`은 바뀌지 않는다. 다만 운영자가 수동 회차의 모임 날짜를 정정하면 그 회차에 이미 복사된 규칙과 시즌 시간대로 실행들의 `deadlineAt`을 다시 계산한다.
- 자동 회차의 이름과 날짜는 발생 identity이므로 직접 수정할 수 없다. 수동 회차와 마찬가지로 보관·복원할 수 있으며, 보관해도 발생 identity와 중복 방지 기록은 유지한다.

실행 상태 `WAITING`·`DONE`은 사용자의 완료 의도를 저장하고 시간 상태는 조회 시 서버 `Clock`과 시즌 시간대로 파생한다.

| 루틴 시간 상태 | 규칙 |
| --- | --- |
| `COMPLETED` | 저장 상태가 `DONE`이다. |
| `UNSCHEDULED` | 완료되지 않았고 `deadlineAt`이 없다. |
| `PLANNED` | 시즌 현지 날짜가 마감 현지 날짜보다 이르다. |
| `IN_PROGRESS` | 마감 현지 날짜이며 아직 `deadlineAt` 전이다. |
| `OVERDUE` | 완료되지 않았고 현재 시각이 `deadlineAt`과 같거나 지났다. |

회차 시간 상태는 모든 실행이 완료되면 `COMPLETED`, 하나라도 지연이면 `OVERDUE`, 진행 중이거나 일부 완료된 실행이 있으면 `IN_PROGRESS`, 그 밖에는 `PLANNED`다. 실행이 없는 자동 회차는 예정 모임 시각에 도달하면 `IN_PROGRESS`가 된다. 시간 상태는 별도 저장하지 않아 조회 시각과 완료 변경에 맞춰 자연스럽게 갱신한다.

### 데이터베이스 수렴형 자동 생성

Spring scheduler는 기본 `PT1M`의 fixed delay로 자동 생성 use case를 호출한다. `BATON_ROUND_AUTOMATION_POLL_INTERVAL`로 이 운영상 poll 간격을 바꿀 수 있지만, 이 값은 모임 주기나 업무 마감 자체를 표현하지 않는다.

한 tick은 활성 일정 후보 전체를 식별자 순으로 읽고, 각 시즌에서 최대 8개의 밀린 발생을 처리한다. 시즌 수에 고정된 앞쪽 cap을 두지 않아 뒤쪽 일정이 영구적으로 굶지 않게 한다. 시즌별 실패는 로그에 남기고 다음 시즌 처리를 계속하며, 시즌별 처리 한도에 남은 발생은 다음 tick에서 이어 간다.

각 발생은 별도 transaction에서 다음 순서로 처리한다.

1. `Team` 공유 잠금 뒤 `Season` 배타 잠금을 얻는다.
2. 시즌의 종료 여부, 일정 활성 상태, 다음 발생일과 선행 생성 경계를 다시 확인한다.
3. 시즌 시간대에서 trigger `Instant`의 달력 날짜가 `nextOccurrenceDate - generationLeadDays`에 도달했으면 해당 발생을 처리한다.
4. `(season_id, scheduled_occurrence_date)`의 기존 회차가 없을 때만 자동 회차와 루틴 실행 스냅샷을 만든다.
5. 회차가 이미 있어도 다음 발생일 커서를 한 주 또는 두 주 전진한다.

시즌 배타 잠금은 같은 시즌의 커서 전진을 직렬화하고, 데이터베이스의 `(season_id, scheduled_occurrence_date)` 유일 제약은 최종 중복 방지선이다. 따라서 scheduler가 중단됐다가 재실행되거나 이미 생성한 발생을 다시 만나도 같은 예정일의 회차를 늘리지 않고 저장 상태에 수렴한다.

자동 회차는 `origin = AUTOMATIC`, 원래 발생일 `scheduledOccurrenceDate`와 시즌 시간대의 모임 시각을 변환한 UTC `scheduledAt`을 보존한다. 수동 회차는 `origin = MANUAL`이고 두 일정 메타데이터가 없다.

### DST 해석

모임 예정 시각과 실제 마감은 Java `LocalDateTime.atZone(ZoneId)` 정책으로 해석한다.

- DST 전환으로 존재하지 않는 로컬 시각은 gap 길이만큼 앞으로 이동한다.
- 두 번 나타나는 로컬 시각은 앞선 오프셋을 사용한다.

이 정책은 별도 보정 로직을 만들지 않고 Java 시간 API와 `Clock` 기반 정책 테스트로 고정한다. API에는 최종 UTC `scheduledAt`과 `deadlineAt`을 반환하므로 클라이언트가 다시 시간대 규칙을 추론하지 않는다.

### API와 호환성

다음 설정 API를 추가한다.

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule
```

요청은 `timeZone`, `firstMeetingDate`, `meetingTime`, `recurrence`, `generationLeadDays`, `enabled`를 사용하고, 응답은 갱신된 시즌 전체 표현과 `nextOccurrenceDate`를 포함한 일정을 반환한다. workspace projection의 시즌·루틴·회차·실행에도 시간대, 일정, 마감과 파생 상태를 함께 노출한다.

Flyway V12는 기존 시즌 시간대를 `Asia/Seoul`로 채우고 일정은 비워 둔다. 기존 루틴과 실행의 구조화된 마감은 원래 데이터에 없으므로 `NULL`로 유지하며, 기존 회차는 `MANUAL`로 이관한다. 과거의 `dueLabel`을 해석해 실제 시각을 만들어 내지 않는다.

## 결과

### 장점

- 운영자는 주간·격주 스터디 회차를 매번 만들지 않고 일정을 일시 중지·재개할 수 있다.
- 실제 마감과 예정·진행·지연·완료 상태가 서버 `Clock`과 시즌 시간대로 일관되게 계산된다.
- scheduler 중단과 재실행에도 누락된 발생을 제한된 양으로 따라잡고 중복 회차를 만들지 않는다.
- 마감 `Instant`를 실행에 스냅샷하므로 루틴 정의 변경이 이미 시작한 회차의 약속을 소급 변경하지 않는다.

### 비용과 한계

- 시즌, 루틴, 회차와 실행에 일정·마감 메타데이터가 추가되고 workspace projection이 커진다.
- 일정 변경은 커서를 되감지 않으므로 과거 발생을 새 규칙으로 자동 재작성하지 않는다.
- 한 시즌의 tick당 발생 수를 제한하므로 장기간 중단 뒤의 복구는 여러 tick에 걸릴 수 있다. 활성 시즌 후보는 모두 조회하므로 시즌 수가 매우 커지면 keyset pagination이나 공정한 round-robin cursor를 추가해야 한다.
- 현재 반복 규칙은 주간과 격주 하나뿐이며 월간, 예외일, 휴일과 여러 병렬 일정은 지원하지 않는다.
- 외부 알림, 캘린더·메시징·메일 연동과 전달 결과 추적은 구현하지 않았다. 이는 계정·신원과 실제 채널 요구를 확인한 뒤 P3에서 결정한다.
- 완료한 사용자의 신원과 감사 이력은 아직 제공하지 않는다.

## 대안

### cron 표현식을 시즌에 저장

표현력은 높지만 파일럿 사용자가 이해하기 어렵고, 시즌 기간·선행 생성·발생 identity와 시간대 해석을 별도로 설계해야 한다. 현재 필요한 주간·격주 규칙에는 과도해 채택하지 않았다.

### 요청 시점에 누락 회차 생성

별도 scheduler는 줄지만 사용자가 워크스페이스를 열지 않으면 회차가 생기지 않고, 조회 요청이 예기치 않은 쓰기와 잠금 실패를 일으킨다. 서버 상태를 능동적으로 수렴시키기 위해 채택하지 않았다.

### scheduler 성공 이력만으로 중복 방지

프로세스 재시작과 여러 인스턴스에서 신뢰할 수 없고 데이터베이스 기록과 어긋날 수 있다. 발생일 identity와 데이터베이스 유일 제약을 권위로 삼는다.

### 조회할 때마다 루틴 정의로 deadline 계산

저장 공간은 줄지만 루틴이나 시즌 시간대 변경이 과거 회차의 마감 의미까지 바꾼다. 실행 시점의 약속을 보존하기 위해 `deadlineAt` 스냅샷을 채택했다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
cd frontend
npm run typecheck
npm run e2e:operations
```

시간대·주간·격주·선행 생성 경계, 일정 일시 중지와 커서 보존, 실제 마감 스냅샷, 정확한 마감 instant의 지연 전환, DST gap·overlap, 기존 발생의 재실행, 시즌별 실패 격리와 tick 처리 한도를 확인한다. MySQL에서는 V11 데이터가 V12의 nullable 일정·마감과 자동 회차 유일 제약으로 안전하게 이관되는지 확인한다.

## 관련 결정

- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [테스트 전략](../0002_test-strategy/adr.md)
- [테스트에서 생성하는 API 계약](../0004_test-derived-api-contract/adr.md)
- [공유 콘텐츠 낙관적 잠금](../0005_optimistic-content-updates/adr.md)
- [루틴 정의와 회차 실행 분리](../0006_routine-definition-and-round-execution/adr.md)
- [운영 회차 정정과 가역 보관](../0008_revisable-round-lifecycle/adr.md)
- [서버 요청 시간 예산](../0009_server-request-time-budget/adr.md)
- [시즌 종료와 다음 시즌 전환](../0011_season_lifecycle/adr.md)
