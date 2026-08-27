# ADR-0019: BATON CAL 일정 스냅샷 생산자 경계

- 상태: 채택
- 결정일: 2026-08-25

## 배경

BATON의 회차와 루틴 마감은 MySQL 트랜잭션에서 확정되고 BATON CAL은 별도 PostgreSQL과 배포
단위를 가진다. 원본 저장 중 CAL을 동기 호출하면 외부 장애가 사용자 변경을 막고, 커밋 뒤 응답만
유실되면 두 서비스 상태가 갈라진다. 또한 BATON의 JPA 엔티티나 Java DTO를 CAL과 공유하면 두
서비스의 배포와 언어 선택이 결합된다.

## 결정

### 계약 고정과 계층

BATON은 불변 안정 릴리스 `contracts-v1.0.0` 자산과 SHA-256을 고정한다. 애플리케이션 계층은 외부
라이브러리를 모르는 `CalendarSnapshot` 합 타입과 `CalendarSnapshotFactory`를 소유하고, 외부
어댑터가 이를 CAL JSON 요청으로 바꾼다. 생산자 테스트는 고정한 실제 JSON Schema로 결과를
검증한다.

```text
SeasonRound / RoutineExecution
  └─ CalendarSnapshotFactory
       └─ CalendarSnapshot
            └─ 외부 어댑터 요청 DTO
                 └─ BATON CAL 안정 계약 `1.0.0` JSON Schema
```

공유 DTO JAR, CAL 내부 Kotlin 타입 복사와 런타임 JSON Schema 검증기는 도입하지 않는다. 스키마
검증은 빌드의 생산자 계약 테스트가 소유하고, 런타임은 고정된 요청 DTO와 CAL 응답 분류를 사용한다.

### 시간 의미

- 수동 회차는 `meetingDate`의 `ALL_DAY` 한 날짜다.
- 자동 회차는 `scheduledAt`을 시즌 시간대로 표현한 `ZONED_LOCAL_POINT`다.
- 마감이 있는 실행은 저장된 `deadlineAt`의 `UTC_POINT`다.
- 마감이 없는 실행은 발행하지 않는다.

점 일정에 임의 `DTEND`를 만들지 않고 날짜 일정에 자정 시각을 만들지 않는다. JDK 시간 API로 이미
확정한 `Instant`와 시즌 `ZoneId`를 사용하며 별도 시간대 변환 규칙을 구현하지 않는다.

### 상태와 트랜잭셔널 아웃박스

회차 보관은 회차와 그 실행 마감의 취소이며 복원은 다시 활성화다. 실행 완료와 시즌 종료는 일정
취소로 해석하지 않는다.

원본 변경과 같은 트랜잭션에서 불변 CAL 아웃박스를 기록한다. 아웃박스의
`INT AUTO_INCREMENT` 키를 CAL 개정 번호로 사용해 JPA `@Version`과 외부 순서를 분리한다.
아웃박스에는 JSON이 아니라 계약 스냅샷 필드를 저장하고 외부 어댑터만 JSON 직렬화를 소유한다.
각 행은 전체 스냅샷을 보존하고 같은 행 재시도에서 이벤트 ID와 페이로드를 바꾸지 않는다.

같은 원본의 직전 `sourceUpdatedAt`보다 새 시각이 늦지 않으면 원본 변경이 이미 획득한 잠금 안에서
1마이크로초 전진시킨다. 캡처와 전달은 각각 기본 비활성이다. 전달 작업자는 한 건을 1분간 임대한 뒤
MySQL 트랜잭션 밖에서 인증된 HTTPS 요청을 보내고, 개정 번호와 임대 토큰이 모두 일치할 때만 결과를
기록한다. 후속 전달 작업자는 이전 미종결 행이 있는 같은 원본의 다음 행을 먼저 임대하지 않는다.
임대가 만료되면 같은 행을 다시 보내며 `APPLIED`, `DUPLICATE`, `STALE`만 전달 완료로 처리한다.
네트워크·`401`·`403`·`429`·`5xx`는 재시도하고 잘못된 성공 본문과 나머지 계약 `4xx`는 영구
실패로 남긴다. 인증 오류는 운영 자격 증명 교체로 회복할 수 있으므로 불변 아웃박스 행을 보존한다.
프로덕션 Bearer 원문은 `.env.production`이나 컨테이너 환경 변수에 두지 않는다. 소유자 전용 호스트
파일을 기존 비밀 검증 경계에서 확인한 뒤 Compose secret과 Spring 설정 트리로 전달한다.
Actuator Prometheus는 MySQL 아웃박스를 기준으로 운영 조치가 필요한 `PENDING`, `PROCESSING`,
`FAILED` 현재 행 수를 하나의 게이지와 고정된 `status` 태그로 노출한다. 계속 누적되는 완료 이력은
매번 세지 않으며 이벤트 ID, 시즌 ID와 오류 코드도 메트릭 태그로 사용하지 않는다.

기존 회차·마감은 UUID 키셋 기반 100개 페이지와 회차별 `REQUIRES_NEW` 트랜잭션으로 보정한다.
쓰기 전에 같은 후보 전체의 CAL 출력 문자열을 읽기 전용으로 점검한다. NFC가 아니거나 LF·HTAB 외
제어 문자가 있으면 값은 노출하지 않고 원본 UUID와 필드만 알린 뒤 어떤 아웃박스도 추가하지 않는다.
회차를 배타 잠금한 뒤 최신 아웃박스와 현재 의미가 다를 때만 새 스냅샷을 추가한다. 작업 완료 표시나
별도 해시를 만들지 않고 불변 아웃박스 자체를 비교 기준으로 사용한다. 따라서 중단 뒤 처음부터 다시
실행할 수 있고, 과거에 발행한 회차가 보관된 경우에도 취소 상태로 수렴한다.

## 결과

### 장점

- BATON 도메인과 CAL의 Kotlin 구현이 분리된다.
- 원본의 날짜·현지 시각·UTC 마감을 손실 없이 표현한다.
- 불변 계약 자산과 실제 생산자 JSON 사이의 드리프트를 빌드에서 발견한다.
- 원본 저장과 아웃박스 적재가 함께 커밋되거나 함께 롤백된다.
- 개정 번호와 원본 수정 시각을 CAL의 단조 증가 계약에 맞춘다.
- 응답 유실과 작업자 중단 뒤에도 같은 불변 스냅샷을 안전하게 다시 보낸다.
- 기존 데이터 보정은 중단 뒤 재실행할 수 있고 변경이 없으면 새 아웃박스 행을 만들지 않는다.
- 배포 운영자는 별도 관리 API나 원본 식별자 노출 없이 전달 적체와 영구 실패를 판정할 수 있다.

### 비용과 한계

- 계약 스키마 사본과 핀 정보를 BATON 저장소에서 갱신해야 한다.
- 실제 CAL 안정 계약 `1.0.0` 컨테이너와 BATON 운영 클라이언트의 교차 서비스 검증은 로컬 Docker 경계까지
  완료했다. 공인 HTTPS 운영 환경의 첫 전달과 실제 시즌 피드 확인은 별도로 수행해야 한다.
- 보정 전 점검은 CAL로 실제 출력하는 제목·설명만 확인하며 런타임마다 JSON Schema를 중복 실행하지
  않는다. 실제 운영 데이터에 점검과 보정을 실행하는 일은 운영 활성화 단계에 남아 있다.

## 대안

### CAL DTO 공유 JAR

두 서비스의 언어와 배포 버전을 결합하므로 채택하지 않는다.

### 원본 저장 트랜잭션에서 동기 HTTP 호출

CAL 장애와 응답 유실이 BATON 원본 가용성과 정합성을 해치므로 채택하지 않는다.

### 회차와 마감에 임의 지속 시간 추가

BATON 원본에 없는 의미를 만들고 날짜 단위 회차를 시각 일정으로 바꾸므로 채택하지 않는다.

## 검증

```bash
./gradlew --no-daemon :adapter-out-external:test \
  --tests com.personal.baton.adapter.out.external.calendar.CalendarSnapshotContractTest
./gradlew --no-daemon :application:test \
  --tests 'com.personal.baton.application.calendar.*'
./ops/tests/calendar-consumer-contract.sh
```

## 관련 문서

- [BATON–CAL 일정 스냅샷 생산 계약](../../PRD/0006_calendar-integration-contract/spec.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [시즌 시간대와 회차·마감 자동화](../0012_round_schedule_and_deadline_automation/adr.md)
