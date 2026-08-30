# PRD-0007: BATON–BRIEF 연속성 신호 생산 계약

- 상태: 채택
- 결정일: 2026-08-22
- 수정일: 2026-08-27
- 구현 상태: BRIEF 이벤트 v2·RC 계약 팩·직렬화, 신호 스트림·outbox, 설정형 시간 재조정·원본 변경 자동 연결, 커밋 뒤 HTTP 송신과 BATON 프로덕션 설정 주입 구현, 로컬 원본 API·초기 정합화→BRIEF 종단 간 검증 완료
- 범위: BATON의 권위 있는 연속성 신호를 BRIEF에 내구성 있게 전달하기 위한 의미·정체성·리비전·재조정 경계

## 1. 목적

BATON은 조직·시즌·역할·루틴·역할 바통 원본과 조직 연속성 신호의 권위 있는 판정을
소유한다. BRIEF는 이 원본을 직접 읽거나 다시 판정하지 않고, BATON이 커밋 뒤 전달한
상태 이벤트를 멱등하게 수신해 관심 항목과 불변 에디션을 만든다.

현재 워크스페이스 응답의 연속성 신호는 조회 시점의 `Clock`과 시즌 시간대로 계산한다.
조회 결과를 곧바로 외부 이벤트로 보내면 안정적인 식별자·해소 전이·리비전과 날짜 경계의
변화를 보존할 수 없다. 이 문서는 생산자 구현 전에 권위 있는 신호 의미와 내구성 경계를
채택한다.

## 2. 현재 호환성 판단

BATON의 현재 신호와 BRIEF 이벤트 v1은 다음과 같이 다르다.

| 관심사 | BATON | BRIEF 이벤트 v1 | 결정 |
|---|---|---|---|
| 신호 종류 | `ROLE_UNASSIGNED`, `ROLE_SUCCESSOR_MISSING`, `ROLE_PREPARATION_INCOMPLETE`, `ROUTINE_REPEATEDLY_OVERDUE`, `HANDOFF_INCOMPLETE` | `HANDOFF_BLOCKED`, `ROUTINE_MISSED`, `DECISION_FOLLOW_UP_OVERDUE` | 이름 변환으로 연결하지 않고 이벤트 v2를 먼저 채택한다. |
| 심각도 | BATON이 `CRITICAL`, `WARNING`을 판정 | BRIEF 규칙 v1이 `HIGH`, `MEDIUM`을 판정 | v2는 BATON 심각도를 원본 사실로 전달한다. 소비자 표시 변환은 별도 계약으로 둔다. |
| 생명주기 | 조회 시 계산한 현재 목록 | 안정적인 `ACTIVE`, `RESOLVED` 상태 이벤트 | 내구성 있는 신호 스트림과 재조정을 추가하기 전에는 전송하지 않는다. |
| 리비전 | JPA `@Version`은 내부 충돌 제어 | 신호 정체성별 양의 단조 증가 값 | 외부 신호 리비전을 별도로 소유한다. |

현재 `Decision`에는 후속 기한과 후속 완료 상태가 없다. 따라서
`DECISION_FOLLOW_UP_OVERDUE`를 추측해 생산하지 않는다.

## 3. 권위 있는 신호 계약

BATON의 현재 다섯 `ContinuitySignalType`을 생산자 의미의 기준으로 채택한다.

| 신호 | 권위 있는 정체성 | 발생·유지 근거 | 해소 근거 |
|---|---|---|---|
| `ROLE_UNASSIGNED` | 시즌·역할 | 활동 중 현재 담당자가 없고 역할 바통 신호가 이를 대신하지 않음 | 활동 중 담당자를 지정하거나 역할 바통 신호가 해당 공백을 대신하거나 시즌이 종료됨 |
| `ROLE_SUCCESSOR_MISSING` | 시즌·역할 | 현재 담당자는 활동 중이고 담당 종료가 경고 구간 안이며 적격 후임과 역할 바통 신호가 없음 | 적격 후임 지정, 경고 구간 이탈, 역할 바통 신호로 전환 또는 시즌 종료 |
| `ROLE_PREPARATION_INCOMPLETE` | 시즌·역할 | 역할 위험이 있고 책임·활성 바통 항목·자료 준비가 부족하며 역할 바통 신호가 없음 | 필요한 준비 충족, 위험 제거, 역할 바통 신호로 전환 또는 시즌 종료 |
| `ROUTINE_REPEATEDLY_OVERDUE` | 시즌·역할·루틴 | 보관하지 않은 루틴이 서로 다른 두 회차 이상에서 지연됨 | 지연 회차 감소, 루틴 보관 또는 시즌 종료 |
| `HANDOFF_INCOMPLETE` | 시즌·역할 | 열린 역할 바통의 참여자·준비·전달·담당 기간 공백 또는 임박한 바통 미시작 문제 | 문제 해소, 역할 바통 수락·취소 또는 시즌 종료 |

같은 신호가 유지되는 동안 심각도나 BRIEF가 소비하는 근거가 바뀌면 같은 정체성의 다음
리비전을 만든다. 제목, 이유와 권장 행동은 BATON 화면 설명이며 외부 신호 정체성으로
사용하지 않는다.

## 4. 이벤트 v2 경계

BRIEF는 다음 의미의 이벤트 v2를 구현했고, BATON은 `2.0.0-rc.1` 계약 팩을 저장소에
고정했다. BATON 송신기는 기본 비활성 상태이며 전용 outbox·커밋 뒤 전달 생명주기와
BRIEF origin을 명시한 환경에서만 켠다.

- `workspaceId`는 BATON `teamId`, `seasonId`는 같은 BATON 시즌 UUID다.
- `eventType`은 이 문서의 다섯 `ContinuitySignalType` 중 하나다.
- 심각도는 BATON이 판정한 `CRITICAL` 또는 `WARNING`이다.
- `state`는 `ACTIVE` 또는 `RESOLVED`다.
- `sourceReference`는 `baton-continuity:<signalId>` 형식의 안정적인 불투명 참조다.
- `eventId`는 한 리비전의 불변 이벤트 UUID이며 재시도 때 바꾸지 않는다.
- `aggregateRevision`은 같은 `signalId` 안에서 `1`부터 연속 증가하는 양수다.
- `occurredAt`은 해당 상태·심각도 변화를 확정한 주입 `Clock`의 UTC 시각이다.

전역 outbox 자동 증가 번호는 다른 신호 때문에 건너뛰므로 `aggregateRevision`으로 사용하지
않는다. JPA `@Version`, HTTP 도착 순서와 현재 시각도 외부 리비전으로 사용하지 않는다.

`BriefContinuityEvent` record가 위 필드를 소유한다. 별도 JSON 생성기나 전송 DTO 변환기는
만들지 않고 Jackson 3의 표준 record 직렬화를 사용한다. 직렬화 결과는 고정한 계약 팩의
일곱 예시와 JSON Schema를 모두 대조한다. 이는 생산자 JSON 형식만 검증하며, 신호 계산·
영속 스트림·outbox·전달과 종단 간 수신 성공을 증명하지 않는다.

## 5. 내구성 있는 신호 스트림

생산자 구현은 자연 정체성과 영속 `signalId`, 마지막 상태·심각도, 신호별 최신 리비전을
보존한다. 자연 정체성은 신호 종류와 시즌·역할, 루틴 신호에서는 루틴을 포함하며 데이터베이스
유일 제약으로 중복 스트림을 막는다.

V24의 `brief_continuity_signal`은 신호 종류와 역할 또는 루틴 `subjectId`로 자연 정체성을
고정하고, 영속 `signalId`와 마지막 상태·심각도·리비전만 갱신한다.
`brief_continuity_outbox`는 각 리비전의 이벤트 v2 필드를 불변 행으로 저장하며 원본 엔티티
FK를 두지 않는다. V25는 기존 이벤트 필드를 바꾸지 않고 `PENDING`·`PROCESSING`·
`DELIVERED`·`FAILED` 전달 상태, 시도 횟수, 실행 가능 시각, lease와 완료·결과 코드를
추가한다. V24의 기존 행은 원래 `occurredAt`부터 전달 가능한 `PENDING`으로 이관한다.

`ReconcileBriefContinuitySignalsUseCase`는 후보를 조회하고 시즌별 작업자에게 맡긴다.
신호에 영향을 주는 원본 변경과 시간 재조정은 원본을 읽기 전에 `Team` 공유 잠금과
`Season` 배타 잠금을 순서대로 얻고 커밋까지 유지한다. 시즌 종료·전환은 기존의
`Team` 배타 잠금과 `Season` 배타 잠금을 유지한다. 이후 `brief_continuity_scope` 잠금,
현재 신호 계산, 기존 상태 비교, 현재 상태 갱신과 불변 outbox 삽입을 같은 트랜잭션에서
수행한다. 원본 변경 뒤에만 신호 행을 잠그면 MySQL `REPEATABLE READ`의 이전 원본
스냅샷과 최신 신호 상태가 섞일 수 있으므로, 첫 원본 조회 전에 시즌 잠금을 얻어야 한다.

신호에 영향을 주는 시즌·구성원·역할·역할 바통·루틴 보관·회차·
실행·바통 항목·역할 자료 변경은 이 경계를 같은 원본 변경 트랜잭션에서 호출한다. 자동
회차 생성도 시즌 저장 뒤 같은 트랜잭션에서 재조정한다. 신호 의미를 바꾸지 않는 접근 키,
구성원 이름, 결정과 루틴 설명 변경에는 불필요한 재조정을 붙이지 않는다.

같은 원본 변경 또는 재조정 트랜잭션에서 다음을 원자적으로 수행한다.

1. 시즌 범위의 권위 있는 현재 신호를 계산한다.
2. 영속 스트림의 마지막 스냅샷과 비교한다.
3. 새 발생·해소·심각도 변경만 신호별 다음 리비전으로 기록한다.
4. 같은 `eventId`와 이벤트 본문을 가진 전용 BRIEF outbox 행을 함께 기록한다.

동일 상태를 반복 계산하면 새 리비전과 outbox 행을 만들지 않는다. 해소된 같은 자연
정체성이 다시 발생하면 기존 `signalId`에서 더 큰 리비전의 `ACTIVE`를 기록한다. 생산자와
재생 계약을 유지하는 동안 스트림의 정체성과 리비전 근거를 임의로 삭제하거나 재기준화하지
않는다.

## 6. 시간 경계 재조정

사용자 쓰기 없이도 담당 종료일·경고 구간·회차 마감이 지나면 신호가 발생하거나 심각도가
바뀔 수 있다. 따라서 다음 두 트리거가 같은 계산과 저장 경계를 사용한다.

- 신호 원본을 바꾸는 BATON 트랜잭션 뒤의 정합성 확인
- 날짜·마감 경계를 발견하는 주기적 재조정과 첫 활성화 시 초기 정합화

재조정은 주입 `Clock`과 시즌 IANA `timeZone`을 사용한다. 같은 고정 시각과 같은 원본
스냅샷에서는 결과와 outbox가 멱등해야 한다. 구체적인 폴링 주기, 페이지 크기와 실행 시간
SLO는 운영 근거 없이 이 문서에서 정하지 않는다.

`baton.brief.reconciliation-interval`을 명시한 환경에서만 전용 단일 스레드 스케줄러를
조립한다. 기본 주기는 두지 않는다. 후보는 현재 열린 모든 시즌과 아직 `ACTIVE` 신호가
남은 종료 시즌의 합집합이며, 종료 재조정으로 마지막 `RESOLVED`를 기록한 뒤에는 후보에서
빠진다. 후보 조회는 읽기 전용이고 각 시즌은 독립 트랜잭션으로 처리해 한 시즌의 실패가
다음 시즌을 막지 않는다. 스케줄 실행은 실패한 시즌 수를 보고하지만 UUID를 메트릭
레이블로 만들지 않는다.

## 7. 커밋 뒤 전달

- BRIEF 전용 outbox는 WATCH·이메일 outbox의 테이블이나 상태를 재사용하지 않는다.
- 원본 트랜잭션은 BRIEF HTTP 호출을 기다리지 않는다.
- 작업자는 커밋 뒤 최소 한 번 전달하며 재시도마다 같은 `eventId`와 의미상 같은 본문을
  보낸다.
- 한 번에 한 건을 1분 lease로 claim하고, 같은 `signalId`의 후속 리비전은 앞선 리비전이
  `PENDING`·`PROCESSING`인 동안 열지 않는다. 만료 lease는 새 token과 증가한 시도 횟수로
  회수한다.
- BRIEF의 `200`·`202`는 완료, `400`·`409`·`422`는 계약 또는 생산자 데이터 실패,
  `429`·`5xx`·네트워크 실패는 재시도 가능 결과로 분류한다.
- 그 밖의 HTTP 상태도 영구 실패로 기록한다. 재시도 가능 결과는 다음 설정형 scheduler
  실행에서 다시 claim하며 별도 최대 시도 횟수와 backoff는 정하지 않는다.
- 공통 Prometheus 지표는 `integration="brief"` 범위의 상태별 행 수, 조치 대상 영구 실패,
  가장 오래된 대기 시간, 마지막 성공 시각과 만료 임대를 노출한다.
  `./ops/check-integration-delivery.sh`는 BRIEF 조치 대상 실패와 만료 임대도 확정 장애로 판정한다.
- 전달은 기본 비활성이다. 로컬에서는 loopback HTTP origin을, 그 밖의 환경에서는 HTTPS
  origin만 허용하고 redirect를 따르지 않는다. 경로·사용자 정보·query·fragment는
  허용하지 않으며 연결·읽기 시간 제한의 합은 45초 이하다. 직접 실행에서
  `BATON_BRIEF_BEARER_TOKEN`이 있으면 Spring `RestClient`의 표준 Bearer 헤더로 전송한다.
  프로덕션 Compose는 원문 대신 `BATON_BRIEF_BEARER_TOKEN_FILE` 경로를 검증하고 해당 값을
  Spring config tree의 `baton.brief.bearer-token`으로 마운트한다. 두 방식 모두 BRIEF
  PRD-0020의 전용 이벤트 수신 인증과 같은 값을 사용한다. 실패 복구 UI는 별도 계약에서
  정한다.

BRIEF 장애는 BATON 원본 변경을 롤백하지 않는다. 외부 호출 동안 MySQL 트랜잭션과 제품
행 잠금을 유지하지 않는다.

## 8. 구현 순서

1. 완료: BRIEF 저장소에서 이벤트 v2의 다섯 타입·심각도·호환성·기존 v1 재생 의미를 채택했다.
2. 완료: BRIEF `2.0.0-rc.1` 계약 팩을 고정하고 실제 BATON record 직렬화 결과를 검증했다.
3. 완료: 신호 스트림·불변 outbox, 시즌별 트랜잭션 재조정, 설정형 시간 트리거와 신호에
   영향을 주는 원본 변경·자동 회차 생성의 같은 트랜잭션 연결을 구현했다.
4. 완료: V25 전달 상태·lease·신호별 순서와 기본 비활성 HTTP 작업자·결과 분류를 구현했다.
5. 완료: 실제 BATON·BRIEF 실행 JAR과 MySQL·PostgreSQL에서 원본 API 변경,
   초기 정합화, BRIEF 장애 재시도, 같은 본문 재전달, 심각도 변경과
   `ACTIVE → RESOLVED` 수렴을 검증했다. 역순 리비전 차단은 outbox 영속성 검증이 담당한다.
6. 완료: 실제 두 프로세스 흐름에서 전용 Bearer 인증과 새·직전 token 중첩 교체를
   검증했다.
7. 완료: 기존 BATON 프로덕션 Compose에 BRIEF HTTPS origin, 명시적 재조정 주기와 소유자
   전용 Bearer 파일의 config tree 주입 경계를 연결했다.
8. 실제 공개 HTTPS 스테이징에서 전달·재시도·token 교체를 검증한다.

이 순서를 충족하기 전에는 README·HANDOFF·배포 문서에서 BATON→BRIEF 생산자 연동을
완료로 표시하지 않는다.

## 9. 수용 기준

- 다섯 신호의 발생·해소·심각도 의미가 현재 BATON 판정과 일치한다.
- 재계산과 재시작 뒤에도 같은 자연 정체성이 같은 `signalId`를 사용한다.
- 리비전은 신호별로 연속 증가하고 동일 상태 재조정은 새 행을 만들지 않는다.
- 시간만 지난 발생·해소·심각도 변경을 고정 `Clock`과 시즌 시간대로 재현한다.
- 원본 변경과 같은 트랜잭션에 불변 outbox를 기록하고 커밋 뒤 같은 이벤트를 재전달한다.
- BATON 실제 직렬화기와 BRIEF 실제 소비자가 v2 계약 아티팩트와 종단 간 시나리오를
  통과한다.

## 10. 명시적 비목표

- 현재 BRIEF 이벤트 v1 타입으로의 임시 이름 매핑
- `DECISION_FOLLOW_UP_OVERDUE`를 위한 기한·상태 추측
- WATCH·이메일 outbox 테이블과 전달 상태 재사용
- 원본 변경 트랜잭션 안의 BRIEF 동기 호출
- 브로커, BATON 사용자 계정 기반 BRIEF 관리 인증·인가, 최대 재시도 횟수·별도 backoff의 임의 채택
- 신호 목록 API, BRIEF 관리 UI와 실패 재처리 UI 구현

## 11. 검증 상태와 남은 작업

BRIEF 커밋 `df89f82`의 `2.0.0-rc.1` `VERSION`·JSON Schema·일곱 예시를 BATON 저장소에
고정했다. 다음 대상 계약 테스트와 전체 빌드가 성공했다. 계약 테스트는 BATON
`BriefContinuityEvent`의 Jackson 3 직렬화 결과가 모든 예시와 같고 Schema를 통과하는지
확인한다.

```bash
./gradlew --no-daemon :adapter-out-external:test --tests 'com.personal.baton.adapter.out.external.brief.BriefContinuityEventContractTest'
./gradlew --no-daemon build
```

V24와 명시적 재조정 경계에는 다음 검증을 추가했다.

```bash
./gradlew --no-daemon :application:useCaseTest \
  --tests 'com.personal.baton.application.brief.BriefContinuitySignalPersistenceTest' \
  --tests 'com.personal.baton.application.workspace.RoundAutomationApplicationTest'
./gradlew --no-daemon :application:policyTest :application:useCaseTest
./gradlew --no-daemon :bootstrap:test \
  --tests 'com.personal.baton.bootstrap.scheduling.SchedulingConfigTest'
./gradlew --no-daemon build
```

MySQL 8.4에서 신호에 영향을 주는 원본 변경이 수동 재조정 호출 없이 같은 자연 정체성의
최초 `ACTIVE`, 동일 계산 무변경, `RESOLVED → ACTIVE`, 심각도 변경의 `1..4` 연속 리비전과
안정적인 `signalId`·`sourceReference`를 만드는지 확인했다. 원본 변경과 자동 재조정을 같은
트랜잭션에 두고 롤백했을 때 원본과 outbox가 함께 복원되고, 시즌 종료가 다음 리비전의
`RESOLVED`를 기록하는 것도 확인했다. 자동 회차 생성은 저장 직후 같은 시즌 재조정을
한 번 호출한다. 두 역할의 담당자를 동시에 해제해도 두 신호가 모두 최초 `ACTIVE`로
남고 잘못된 `RESOLVED` 이벤트가 생기지 않는지 확인한다. 시간 재조정도 진행 중인 원본
변경의 커밋을 기다린 뒤 같은 신호 상태를 확인하며 불필요한 리비전을 만들지 않는다.
열린 시즌의 초기 정합화, 아직 활성 신호가 남은 종료 시즌의 마지막
`RESOLVED`, 이후 후보 제거와 한 시즌 실패 뒤 다음 시즌 계속 처리도 유지한다.

시간 스케줄러는 `BATON_BRIEF_RECONCILIATION_INTERVAL`을 명시한 환경에서만 활성화한다.
선택 실행 교차 서비스 테스트에서 재기동 뒤 스케줄러가 기존 열린 시즌 원본을
초기 정합화하는 경로를 확인했다.

V25 전달 생명주기에는 다음 대상 검증을 추가했고 전체 빌드와 실행 JAR 생성도 성공했다.

```bash
./gradlew --no-daemon :application:test \
  --tests 'com.personal.baton.application.brief.BriefContinuityOutboxDeliveryMigrationTest' \
  --tests 'com.personal.baton.application.brief.BriefContinuityOutboxPersistenceTest'
./gradlew --no-daemon :adapter-out-external:test \
  --tests 'com.personal.baton.adapter.out.external.brief.RestClientBriefContinuityClientTest'
./gradlew --no-daemon :bootstrap:test \
  --tests 'com.personal.baton.bootstrap.config.BriefIntegrationConfigTest' \
  --tests 'com.personal.baton.bootstrap.scheduling.SchedulingConfigTest'
./gradlew --no-daemon build
```

MySQL 8.4에서 기존 V24 이벤트가 V25의 전달 대기 행으로 보존되는지, 만료 lease 회수와
오래된 token 거부, 같은 신호의 후속 리비전 차단·해제를 확인했다. 실제 event record의
요청 JSON과 HTTP·네트워크 결과 분류, 기본 비활성 구성과 전용 scheduler 격리도 확인했다.
공통 운영 지표 테스트와 `ops/tests/integration-delivery-check-test.sh`는 BRIEF의 상태별 전달 수,
조치 대상 실패, 가장 오래된 대기 시간, 마지막 성공 시각과 만료 임대 판정을 함께 검증한다.
`d30be0d`의 선택 실행 테스트는 다음 명령으로 실제 BATON·BRIEF 실행 JAR과 MySQL 8.4·
PostgreSQL 18.4를 함께 기동했다.

```bash
./gradlew --no-daemon :application:briefCrossServiceTest \
  -PbriefBootJar=/absolute/path/to/baton-brief.jar
```

BATON 워크스페이스·역할 API로 담당자가 없는 미래 시작 시즌을 만들어 원본 변경과
outbox가 같이 커밋되는지 확인했다. BRIEF 파생 신호·outbox만 비운 뒤 BATON을 재기동하여
스케줄러가 존재하는 원본에서 `WARNING` `ROLE_UNASSIGNED` 리비전 1을 초기 정합화하는지
검증했다. BRIEF가 꺼진 동안 네트워크 실패가 `PENDING` 재시도로 남고, BRIEF 기동 뒤
최초 `202`로 완료됐다. BRIEF 수신 뒤 BATON 전달 행을 재시도 상태로 되돌려 응답 유실을
재현했을 때 같은 이벤트가 `200`으로 완료되고 최초 수신 증거가 바뀌지 않았다. 이어 시즌
기간과 역할 담당자를 실제 API로 변경했을 때 `WARNING → CRITICAL`, `ACTIVE → RESOLVED`가
BRIEF 현재 관심 항목의 리비전 2·3으로 수렴했다.

응답 유실은 실제 TCP 응답 절단이 아니라 BRIEF 수신 뒤 BATON의 전달 지속 상태를 같은
재시도 상태로 되돌려 재현했다. 이후 BRIEF 이벤트 수신 인증을 필수화하고 BATON
`RestClient`에 전용 Bearer를 설정한 같은 시나리오도 성공했다. BRIEF의 새 token과 직전
token 중첩 구간에서 BATON이 직전 token을 계속 보내는 순차 배포 상태도 수렴했다.
기존 BATON 프로덕션 Compose와 사전점검은 BRIEF delivery gate·HTTPS origin·명시적 재조정
주기, 소유자 전용 Bearer 파일과 `/run/baton-config/baton.brief.bearer-token` 마운트를
검증한다. 이 정적·조립 검증은 BRIEF 서비스를 같은 Compose에 배포하거나 실제 공개
HTTPS 통신을 수행하지 않는다. 공개 스테이징 활성화와 계약 팩 안정 버전 승격은 남아 있다.

```bash
bash ops/tests/pilot-readiness-test.sh
```

## 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [제품 개발 우선순위](../0003_product-roadmap/spec.md)
- [WATCH 트랜잭셔널 아웃박스](../../ADR/0015_watch-transactional-outbox/adr.md)
- [시즌 시간대와 수렴형 회차·마감 자동화](../../ADR/0012_round_schedule_and_deadline_automation/adr.md)
- [고정한 BRIEF 이벤트 계약 팩](../../../contracts/brief/README.md)
- [BATON 경유 BRIEF 에디션 조회와 생성](../0008_brief-edition-query-and-generation/spec.md)
- BRIEF `PRD-0018: BATON 생산자 호환성 선행조건`
