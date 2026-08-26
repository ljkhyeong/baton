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

BATON은 불변 `contracts-v1.0.0-rc.2` 자산과 SHA-256을 고정한다. 애플리케이션 계층은 외부
라이브러리를 모르는 `CalendarSnapshot` 합 타입과 `CalendarSnapshotFactory`를 소유하고, 외부
어댑터가 이를 CAL JSON 요청으로 바꾼다. 생산자 테스트는 고정한 실제 JSON Schema로 결과를
검증한다.

```text
SeasonRound / RoutineExecution
  └─ CalendarSnapshotFactory
       └─ CalendarSnapshot
            └─ 외부 어댑터 요청 DTO
                 └─ BATON CAL rc.2 JSON Schema
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
네트워크·`429`·`5xx`는 재시도하고 잘못된 성공 본문과 계약 `4xx`는 영구 실패로 남긴다.

## 결과

### 장점

- BATON 도메인과 CAL의 Kotlin 구현이 분리된다.
- 원본의 날짜·현지 시각·UTC 마감을 손실 없이 표현한다.
- 불변 계약 자산과 실제 생산자 JSON 사이의 드리프트를 빌드에서 발견한다.
- 원본 저장과 아웃박스 적재가 함께 커밋되거나 함께 롤백된다.
- 개정 번호와 원본 수정 시각을 CAL의 단조 증가 계약에 맞춘다.
- 응답 유실과 작업자 중단 뒤에도 같은 불변 스냅샷을 안전하게 다시 보낸다.

### 비용과 한계

- 계약 스키마 사본과 핀 정보를 BATON 저장소에서 갱신해야 한다.
- 기존 활성 회차·마감의 조정과 실제 CAL rc.2 컨테이너 검증은 아직 없다. 완료 전에는 운영에서
  캡처와 전달을 계속 비활성으로 둔다.
- BATON에 저장된 기존 문자열이 CAL의 NFC·제어 문자 규칙을 모두 만족하는지는 운영 활성화 전
  별도 데이터 점검이 필요하다. 런타임마다 JSON Schema를 중복 실행하지 않는다.

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
```

## 관련 문서

- [BATON–CAL 일정 스냅샷 생산 계약](../../PRD/0006_calendar-integration-contract/spec.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [시즌 시간대와 회차·마감 자동화](../0012_round_schedule_and_deadline_automation/adr.md)
