# ADR-0014: 반복 루틴 정의를 실행 기록과 분리해 가역 보관

- 상태: 채택
- 결정일: 2026-08-01
- 대체 관계: ADR-0006의 모든 루틴 정의를 새 회차에 포함하는 한계를 대체

## 배경

ADR-0006은 반복 정의인 `Routine`과 회차별 사실인 `RoutineExecution`을 분리해 과거 실행의 내용과 완료 상태를 보존했다. 그러나 더 이상 운영하지 않는 루틴 정의도 모든 수동·자동 회차와 다음 시즌 선택에 계속 나타난다. 정의를 삭제하면 과거 실행이 어떤 루틴에서 만들어졌는지 잃고, projection에서 제거하면 새로고침 뒤 복원할 별도 조회 계약이 필요하다.

루틴 보관과 회차 생성이 동시에 진행될 때 어느 정의를 새 실행으로 복사했는지도 일관돼야 한다. BATON의 일반 콘텐츠 변경과 수동 회차 생성은 `Team` 공유 잠금 뒤 `Season` 공유 잠금을 사용하고, 자동 회차 생성은 같은 순서의 `Season` 배타 잠금을 사용한다.

## 결정

### 정의 생명주기와 실행 기록

- `Routine`에 nullable `archived_at`을 둔다. 처음 보관할 때 서버 `Clock`의 UTC `Instant`를 기록하고 반복 보관은 최초 시각을 유지한다. 복원은 값을 `null`로 되돌린다.
- 보관·복원은 정의나 기존 `RoutineExecution`을 삭제·수정·재생성하지 않는다. 과거 회차의 실행 식별자, 스냅샷, 마감과 완료 상태를 그대로 보존한다.
- workspace projection의 `routines`에는 활성·보관 정의를 모두 포함하고 nullable `archivedAt`을 반환한다. 프런트엔드는 활성 목록과 보관함을 분리하되, 선택한 과거 회차는 실행 스냅샷을 기준으로 표시한다.
- 보관된 정의는 일반 수정과 실제 마감 변경을 할 수 없다. 없거나 다른 시즌 소속인 정의와 같은 `404 ROUTINE_NOT_FOUND`를 반환하며 먼저 복원해야 한다.

### 미래 회차와 시즌 전환

- 수동·자동 회차는 생성 transaction에서 활성 루틴만 `RoutineExecution`으로 스냅샷한다. 보관 이전에 만든 회차의 실행은 계속 표시하고 완료 상태를 바꿀 수 있다.
- 활성화된 자동 일정에서 활성 루틴이 0개인 발생일은 `nextOccurrenceDate` 커서만 다음 주기로 전진시키고, 실행이 없는 자동 회차는 만들지 않는다. 프런트엔드는 일정을 꺼진 것으로 오해하지 않도록 `활성 루틴 대기 중` 상태와 다음 발생일을 함께 설명한다.
- 자동 일정 활성화는 활성 루틴의 실제 마감 규칙만 검사한다. 실제 마감 규칙이 없는 보관 루틴을 자동 일정이 켜진 시즌으로 복원하려 하면 `400 INVALID_INPUT`으로 거절한다.
- 다음 시즌에는 활성 루틴만 선택해 복사할 수 있다. 보관 정의 식별자를 요청하면 `404 ROUTINE_NOT_FOUND`로 전체 전환을 rollback한다.
- 반복 지연 연속성 신호는 현재 활성 정의만 행동 대상으로 계산한다. 복원하면 보존된 과거 실행을 다시 근거로 사용할 수 있다.

### HTTP와 멱등 재생

다음 경로가 `{ "archived": true | false }`를 받고 현재 루틴 표현을 반환한다.

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive
```

루틴 생성 멱등 예약은 최초 생성 요청 fingerprint와 정의 식별자를 계속 소유한다. 같은 생성 요청을 재생하면 새 정의를 만들거나 보관 상태를 바꾸지 않고 같은 정의의 현재 `archivedAt`을 반환한다.

### 동시성 경계

- 보관·복원은 기존 `Team` 공유 잠금 뒤 `Season` 배타 잠금을 얻고 접근 키·종료 상태를 다시 확인한 같은 transaction에서 수행한다.
- 일반 루틴 변경과 수동 회차 생성은 `Season` 공유 잠금을 사용하므로 보관·복원과 직렬화된다. 자동 회차 생성도 `Season` 배타 잠금을 사용하므로 같은 시즌에서 순서가 하나로 정해진다.
- 루틴 하나의 드문 생명주기 변경이 같은 시즌의 다른 mutation을 잠시 기다리게 하는 비용을 받아들이고, 별도 루틴 잠금 API와 잠금 집합을 추가하지 않는다.
- `Routine.@Version`은 같은 정의의 겹친 저장을 계속 `409 WORKSPACE_CONTENT_CONFLICT`로 변환한다.

### 기존 데이터

Flyway V15는 `routines.archived_at`을 nullable로 추가한다. 기존 정의는 모두 활성 상태인 `NULL`로 보존하며 과거 실행과 계보 식별자를 바꾸지 않는다.

## 결과

### 장점

- 현재 운영에서 쓰지 않는 정의를 치우면서 과거 회차의 실행 사실을 잃지 않는다.
- 같은 projection에서 보관함과 복원을 제공하고 별도 archive 조회 cache를 만들지 않는다.
- 수동·자동 회차와 보관의 순서가 기존 시즌 잠금 경계에서 결정된다.
- 생성 재시도와 다음 시즌 복사가 보관 상태를 암묵적으로 되돌리지 않는다.

### 비용과 한계

- projection 크기는 줄지 않으며 모든 소비자는 현재 정의와 과거 실행 스냅샷을 구분해야 한다.
- 보관·복원 중에는 같은 시즌의 일반 mutation과 회차 생성이 순서대로 기다린다.
- 활성 루틴이 없어서 건너뛴 자동 일정 발생일은 정의를 복원해도 소급해 빈 회차나 실행을 만들지 않는다.
- 현재 보관 시각만 저장하며 보관·복원 이력이나 변경 주체 감사 로그는 제공하지 않는다.
- 루틴 보관 자체는 BATON 내부 생명주기다. BATON RELAY, WATCH, ROUND와 BATON GO에 이벤트를 보내지 않는다.

## 대안

### 루틴 정의 영구 삭제

목록은 단순해지지만 과거 실행의 원본 계보와 복구 경로를 잃으므로 채택하지 않았다.

### 보관 루틴을 projection에서 제외

일반 응답은 작아지지만 복원용 API·cache가 별도로 필요하고 생성 멱등 재생의 현재 표현과 조회 결과가 어긋나므로 채택하지 않았다.

### 루틴 행 단위 비관적 잠금과 회차 생성의 루틴 집합 잠금

동시성은 더 높지만 회차 생성마다 전체 루틴 잠금 순서를 관리해야 한다. 파일럿 규모와 보관 빈도에서는 기존 시즌 잠금 재사용이 더 단순하고 검증 가능해 채택하지 않았다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
cd frontend && npm run typecheck
cd frontend && npm run build
cd frontend && npm run e2e:operations
```

기존 정의의 V15 이관, 최초 보관 시각 유지와 복원, 보관 전 실행 보존, 보관 뒤 수동·자동 회차 제외, 활성 루틴이 없는 자동 발생의 커서 전진과 빈 회차 미생성, 활성 일정의 복원 검증, 다음 시즌 선택 거절과 생성 멱등 재생의 현재 표현을 확인한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [테스트 전략](../0002_test-strategy/adr.md)
- [루틴 정의와 회차 실행 분리](../0006_routine-definition-and-round-execution/adr.md)
- [시즌 시간대와 회차 자동화](../0012_round_schedule_and_deadline_automation/adr.md)
