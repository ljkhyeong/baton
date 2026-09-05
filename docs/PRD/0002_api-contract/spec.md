# PRD-0002: BATON API 계약 기준선

- 상태: 초기 기준선
- 작성일: 2026-07-20
- 적용 범위: 현재 구현된 HTTP API와 향후 `/api/v1` 계약의 공통 규칙

## 1. 목적

이 문서는 프런트엔드와 백엔드 사이에서 유지해야 할 HTTP 계약을 정의한다. 구현되지 않은 제품 API를 미리 발명하지 않고, 현재 실행 가능한 계약과 향후 API가 따라야 할 최소 규칙만 기록한다.

## 2. 공통 규칙

- 제품 API 기본 경로는 `/api/v1`이다.
- 요청과 응답 본문은 별도 표기가 없으면 JSON을 사용한다.
- 날짜와 시각은 의미가 불분명한 문자열 대신 ISO 8601 형식을 사용한다.
- 서버 시각은 UTC를 기준으로 생성하고 응답에는 오프셋 또는 `Z`를 포함한다.
- 서버가 생성해 저장하는 UTC 시각은 MySQL `DATETIME(6)`과 같은 마이크로초 정밀도를 사용해 최초 응답과 재조회·멱등 동일 재처리 응답을 일치시킨다.
- 시즌의 모임 날짜와 로컬 시각은 해당 시즌의 IANA `timeZone`으로 해석하고, 계산을 마친 예정·마감 시각은 UTC 시각으로 반환한다.
- 식별자는 클라이언트가 형식이나 정렬 의미를 추론하지 않는 불투명한 값으로 다룬다.
- HTTP DTO와 `application` 결과 타입을 분리한다.
- 컨트롤러는 요청 검증과 변환을 담당하고 업무 규칙은 `application` 또는 `domain`에 둔다.
- 기존 필드의 의미를 바꾸거나 제거하는 변경은 새 버전 또는 명시적 호환 전략 없이 진행하지 않는다.
- 모든 `/api/v1` 성공·오류 응답은 본문 유무와 관계없이 `X-Request-ID` 헤더를 포함한다.

페이지네이션 형식은 이를 필요로 하는 실제 API가 설계될 때 확정한다. 워크스페이스·콘텐츠 생성과 접근 키 변경의 멱등 계약 및 동시 충돌은 아래 파일럿 API 절에서 정의한다.

`X-Request-ID`는 서버가 요청마다 생성하는 UUID 형태의 진단 식별자다. 클라이언트는 값을 불투명하게 다루며 운영 문의와 서버 로그 상관관계에만 사용한다. 외부 요청의 같은 이름 헤더는 신뢰하거나 재사용하지 않고, 이 값으로 인증·권한·멱등성 판단 또는 메트릭 레이블을 만들지 않는다. Spring이 처리한 응답은 애플리케이션이 생성한 값을 유지하고, 요청 본문 제한이나 업스트림 장애처럼 Caddy가 직접 응답할 때만 Caddy가 누락된 헤더를 자체 UUID로 채운다.

## 3. 시스템 상태 API

### 시스템 상태 조회

```http
GET /api/v1/system/status
```

- 인증: 필요 없음
- 요청 본문: 없음
- 성공 상태: `200 OK`

응답 예시:

```json
{
  "service": "baton",
  "checkedAt": "2026-07-20T12:00:00Z"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `service` | string | 서비스 식별자. 현재 값은 `baton`이다. |
| `checkedAt` | string | 서버가 상태를 확인한 UTC 시각이다. |

이 계약은 `SystemStatusRestDocsTest`가 검증하고 REST Docs 스니펫을 생성한다.

## 4. 파일럿 워크스페이스 API

### 워크스페이스 생성

```http
POST /api/v1/workspaces
```

- 접근 키: 필요 없음
- `Idempotency-Key`: 필수. `[A-Za-z0-9._~-]` 문자로 된 32자 이상 200자 이하의 고엔트로피 값
- `X-Baton-Creation-Key`: 서버에 파일럿 생성 키가 설정된 경우 필수
- 성공 상태: `201 Created`
- `Location`: 생성한 팀·시즌의 워크스페이스 조회 경로
- `Cache-Control: no-store`

요청:

```json
{
  "teamName": "알고리즘 한 바퀴",
  "seasonName": "2026 여름 시즌",
  "startDate": "2026-07-20",
  "endDate": "2026-09-17",
  "memberNames": ["박민서", "김준호"]
}
```

`teamName`과 `seasonName`은 공백만으로 구성될 수 없고 각각 100자 이하다. `startDate`와 `endDate`는 시간대 없는 ISO 8601 달력 날짜이며 시작일은 종료일보다 늦을 수 없다. `memberNames`는 1명 이상 100명 이하이고 각 이름은 공백만으로 구성될 수 없으며 100자 이하다. 앞뒤 공백을 제거한 구성원 이름은 중복될 수 없으며, 이름이 같은 사람은 역할 선택에서 구분할 수 있는 별칭을 붙인다.

응답:

```json
{
  "teamId": "8a4ec48a-56fa-4bb0-a411-4230f627f3e6",
  "seasonId": "7ccf1568-ae38-48a0-b2bf-30dd366e9ce7",
  "accessKey": "최초 생성 또는 동일 멱등 재처리에서만 반환하는 공유 키"
}
```

클라이언트는 식별자를 불투명한 값으로 다룬다. `accessKey` 원문은 아래의 동일 멱등 요청 재처리 외에는 다시 조회할 수 없으며 서버는 접근 키와 멱등 키의 SHA-256 기반 해시만 저장한다.

같은 `Idempotency-Key`와 의미가 같은 정규화 요청을 다시 보내면 새 팀을 만들지 않고 최초의 `teamId`, `seasonId`, `accessKey`를 같은 `201 Created` 응답으로 반환한다. 문자열 앞뒤 공백과 구성원 입력 순서는 정규화한다. 같은 키를 다른 요청에 사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. 동일 키가 동시에 처리되어 DB 고유 제약에서 충돌하면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환하므로 클라이언트는 잠시 뒤 같은 키와 요청으로 재시도한다.

멱등 키는 응답 유실을 복구할 때까지 접근 자격과 같은 수준으로 다루고 다른 생성 요청에 재사용하지 않는다. 브라우저 클라이언트는 생성 요청 전에 멱등 키를 Web Storage에 기록해야 하며, `setItem`이 예외를 던지면 요청하지 않는다. 접근 키가 회전 또는 운영자 복구된 뒤 과거 생성 요청을 동일 재처리하면 더 이상 유효하지 않은 옛 키를 반환하는 대신 `409 IDEMPOTENCY_REPLAY_EXPIRED`를 반환한다.

`BATON_WORKSPACE_CREATION_KEY`가 비어 있는 로컬 환경에서는 생성 키 없이 만들 수 있다. 프로덕션 파일럿은 이 설정을 필수로 주입하며, 누락되거나 일치하지 않는 `X-Baton-Creation-Key`에는 `403 WORKSPACE_CREATION_DENIED`를 반환한다.

### 팀·시즌 범위와 접근 키

이후 파일럿 API는 다음 범위를 공유한다.

```text
/api/v1/teams/{teamId}/seasons/{seasonId}
```

접근 키 복구와 운영자 시즌 이름 정정을 제외한 워크스페이스 조회·변경 요청에는 다음 헤더가 필요하다.

```http
X-Baton-Access-Key: <워크스페이스 접근 키>
```

키가 없거나 올바르지 않으면 `403 Forbidden`과 `WORKSPACE_ACCESS_DENIED`를 반환한다. 팀에 속하지 않는 시즌·구성원 식별자를 다른 팀 경로에 사용할 수 없고, 역할과 그 역할을 참조하는 루틴·결정·바통 항목·역할 바통·자료는 다른 시즌 경로에 사용할 수 없다.

### 워크스페이스 조회

```http
GET /api/v1/teams/{teamId}/seasons/{seasonId}/workspace
```

- 성공 상태: `200 OK`
- 요청 본문: 없음
- `Cache-Control: no-store`

응답은 한 화면을 구성하는 다음 프로젝션을 반환한다.

| 필드 | 내용 |
| --- | --- |
| `team` | `id`, `name` |
| `season` | 요청한 시즌의 `id`, `name`, `startDate`, `endDate`, `null` 허용 `endedAt`, `null` 허용 `previousSeasonId`, IANA `timeZone`, `null` 허용 `roundSchedule` |
| `seasons` | 같은 팀의 서버 권위 시즌 목록. 각 항목은 `season`과 같은 필드를 가짐 |
| `members` | 팀 구성원의 `id`, `name`, `initials`, `tone`, `null` 허용 `deactivatedAt` 목록 |
| `roles` | 현재 시즌의 역할 스냅샷, 담당자·기간, 책임과 위험 신호 목록 |
| `routines` | 현재 시즌의 반복 루틴 정의, `null` 허용 실제 마감 규칙과 `null` 허용 `archivedAt` 목록. 완료 상태는 포함하지 않음 |
| `rounds` | 생성 출처·시간 상태·`null` 허용 `archivedAt`을 가진 시즌 회차와 회차 생성 시 복사된 실제 마감·루틴 실행 목록 |
| `decisions` | 결정, 서버 생성 시각, 작성자 식별자·이름, 관련 역할과 `null` 허용 `archivedAt` 목록 |
| `handoffItems` | 역할별 바통 항목, 완료 여부, `null` 허용 `createdAt`과 `null` 허용 `archivedAt` 목록 |
| `resources` | 역할별 자료의 제목, 외부 링크, `null` 허용 설명과 `null` 허용 `createdAt` 목록 |
| `roleHandoffs` | 역할별 바통 준비·전달·수락·취소 이력과 전달 시점 준비도 스냅샷 목록 |
| `continuitySignals` | 현재 기록에서 계산한 조직 연속성 위험의 유형·우선순위·이유와 다음 행동 목록 |

역할 응답 필드:

```json
{
  "id": "opaque-role-id",
  "name": "문제 큐레이터",
  "purpose": "이번 주 학습 목표에 맞는 문제를 고른다",
  "currentMemberId": "opaque-member-id",
  "nextMemberId": null,
  "assignmentStartDate": "2026-07-20",
  "assignmentEndDate": "2026-09-17",
  "responsibilities": ["문제 선정", "난이도 균형 확인"],
  "risk": "선정 기준이 개인 메모에만 있다"
}
```

구성원의 `deactivatedAt`은 활동 중이면 `null`, 활동 종료 상태이면 서버 `Clock`으로 생성한 UTC ISO 8601 시각이다. 워크스페이스 프로젝션은 기존 역할·결정 참조를 표시할 수 있도록 두 상태의 구성원을 모두 반환한다. 시즌의 `endedAt`은 운영 중이면 `null`, 운영자가 명시적으로 종료했으면 서버 `Clock`으로 생성한 UTC ISO 8601 시각이다. `endDate`가 지났다는 이유만으로 자동 종료하지 않는다. `previousSeasonId`는 최초 시즌이면 `null`, 다음 시즌이면 원본 시즌 UUID다. `timeZone`은 최대 64자의 유효한 IANA 식별자이고 기존·최초 시즌의 기본값은 `Asia/Seoul`이다. 시즌 목록은 시작일과 UUID 내림차순으로 정렬한다.

`roundSchedule`이 설정되지 않았으면 `null`이다. 설정된 일정은 `firstMeetingDate`, 시즌 시간대 기준 `meetingTime`, `WEEKLY` 또는 `BIWEEKLY`인 `recurrence`, `0..30`의 `generationLeadDays`, `enabled`, 서버가 다음에 처리할 `nextOccurrenceDate`를 가진다.

루틴 정의 응답의 `phase`는 `BEFORE`, `DURING`, `AFTER` 중 하나이고 완료 상태는 없다. `deadlineDayOffset`과 `deadlineTime`은 둘 다 `null`이거나 함께 값이 있으며, 날짜 오프셋은 모임 날짜 기준 `-30..30`일이다. `archivedAt`은 활성 정의이면 `null`, 보관 정의이면 최초 보관 UTC ISO 8601 시각이다. 워크스페이스 프로젝션은 두 상태를 모두 반환하고 프런트엔드는 활성 운영 목록과 복원 가능한 보관함으로 나눈다. `rounds[].routineExecutions[]`는 생성 당시 루틴의 `routineId`, `title`, `phase`, `dueLabel`, `ownerRoleId`, `detail`을 스냅샷으로 보존하고 `status`를 `WAITING` 또는 `DONE`으로 가진다. 실행의 `deadlineAt`은 실제 마감 규칙이 없으면 `null`, 있으면 모임 날짜·오프셋·시즌 시간대로 계산한 UTC ISO 8601 시각이다. `timingStatus`는 `UNSCHEDULED`, `PLANNED`, `IN_PROGRESS`, `OVERDUE`, `COMPLETED` 중 하나다.

회차의 `origin`은 `MANUAL` 또는 `AUTOMATIC`이고 자동 회차만 원래 발생일 `scheduledOccurrenceDate`와 시즌 시간대의 모임 시각을 UTC로 변환한 `scheduledAt`을 가진다. 회차 `timingStatus`는 `PLANNED`, `IN_PROGRESS`, `OVERDUE`, `COMPLETED` 중 하나다. 새로 생성하거나 수정하는 회차의 `meetingDate`는 필수지만, V5 이전의 루틴 상태를 이관한 `회차 도입 이전 기록`은 실제 날짜를 알 수 없어 운영자가 수정할 때까지 응답에서 `null`이다. 회차의 `archivedAt`은 활성 상태에서 `null`, 보관 상태에서 서버 `Clock`으로 생성한 UTC ISO 8601 시각이다. 워크스페이스 프로젝션은 활성·보관 회차를 모두 반환하며 프런트엔드는 일반 운영 선택과 완료 계산에서는 활성 회차만 사용하고 보관 회차는 복원 가능한 보관함으로 나눈다.

결정의 `createdAt`은 항상 서버 `Clock`으로 생성한 UTC ISO 8601 시각이다. 바통 항목과 역할 자료도 새로 생성할 때 서버 `Clock`의 UTC 시각을 기록하지만, V14 이전 기록에는 실제 생성 시각이 없어 `createdAt`이 `null`이다. 서버는 마이그레이션 시각 등으로 이를 추정해 채우지 않는다. 수정·완료·보관·복원과 동일 멱등 요청의 동일 재처리는 최초 `createdAt`을 변경하지 않는다. 결정, 바통 항목과 역할 자료의 `archivedAt`은 활성 상태에서 `null`, 보관 상태에서 최초 보관 UTC 시각인 같은 표현을 사용한다. 바통 항목의 `category`는 `RESPONSIBILITY`, `ROUTINE`, `RESOURCE`, `ADVICE` 중 하나다. `resources[]`는 `id`, `roleId`, `title`, `url`, `null` 허용 `description`, `null` 허용 `createdAt`, `null` 허용 `archivedAt`을 가진다.

현재 통합 탐색은 요청한 한 시즌의 워크스페이스 프로젝션을 프런트에서 필터링하며 별도 검색 엔드포인트나 페이지네이션 계약을 추가하지 않는다. 과거·종료 시즌은 해당 시즌 워크스페이스로 전환해 조회한다. 결정은 제목·이유·대안·작성자·관련 역할, 바통 항목은 내용·분류·역할, 자료는 제목·설명·역할을 검색 대상으로 사용한다. 자료 URL 문자열과 외부 문서 본문은 검색하지 않는다.

`roleHandoffs[]`는 `id`, `roleId`, 이전·다음 담당자 `fromMemberId`·`toMemberId`, 이전 담당 시작일 `outgoingAssignmentStartDate`·`null` 허용 종료일 `outgoingAssignmentEndDate`, 수락 뒤 적용할 `incomingAssignmentStartDate`·`null` 허용 `incomingAssignmentEndDate`, `status`, 상태별 시각과 확인자, 전달 시점 준비도 스냅샷을 가진다. 상태는 `PREPARING`, `TRANSFERRED`, `ACCEPTED`, `CANCELLED` 중 하나다. `preparedAt`은 항상 존재하고 `transferredAt`, `acceptedAt`, `cancelledAt`과 각 `transferredByMemberId`, `acceptedByMemberId`, `cancelledByMemberId`는 해당 전환 전까지 `null`이다. `activeItemCount`, `incompleteItemCount`, `resourceCount`도 전달 전에는 `null`이고 전달 뒤에는 당시 수치를 보존한다. `warningAcknowledged`는 전달 시 준비도 경고를 명시적으로 확인했는지 나타낸다. 완료·취소한 이력도 프로젝션에 남으며, 역할마다 `PREPARING` 또는 `TRANSFERRED` 상태의 열린 이력은 하나만 존재한다.

`continuitySignals[]`는 다음 필드를 가진다.

| 필드 | 내용 |
| --- | --- |
| `type` | `ROLE_UNASSIGNED`, `ROLE_SUCCESSOR_MISSING`, `ROLE_PREPARATION_INCOMPLETE`, `ROUTINE_REPEATEDLY_OVERDUE`, `HANDOFF_INCOMPLETE` 중 하나 |
| `severity` | 즉시 확인할 `CRITICAL` 또는 미리 준비할 `WARNING` |
| `roleId` | 신호가 가리키는 역할 UUID |
| `routineId` | 반복 지연 신호가 가리키는 루틴 UUID. 다른 유형은 `null` |
| `title` | 신호의 짧은 사용자용 제목 |
| `reason` | 현재 기록에서 이 신호가 발생한 이유 |
| `recommendedAction` | 사용자가 바로 취할 수 있는 다음 행동 |
| `relevantDate` | 담당 종료일 또는 새 담당 시작일. 날짜가 없는 유형은 `null` |

레이더는 별도 저장 상태가 아니라 워크스페이스 조회 시점의 서버 `Clock`과 시즌 `timeZone`으로 계산한 프로젝션이다.

- 현재 담당자가 없거나 활동을 종료한 역할은 신호를 만든다. 시즌 시작 전이면 `WARNING`, 시작일 이후면 `CRITICAL`이다.
- 현재 담당자가 활동 중이고 다음 담당자가 없거나 활동을 종료했거나 현재 담당자와 같으며 담당 종료일이 시즌 현지 오늘부터 14일 이내이거나 이미 지났으면 후임 공백 신호를 만든다. 종료일까지 시간이 남았으면 `WARNING`, 오늘이거나 지났으면 `CRITICAL`이다.
- 역할에 위험 신호가 있으면서 책임 목록이 없거나, 활성 바통 항목이 없거나 미완료이거나, 역할 자료가 없으면 사용자에게 기록한 위험과 부족한 준비 요소를 한 신호의 이유에 함께 설명한다.
- 같은 루틴의 미완료 실행이 서로 다른 활성 회차에서 실제 마감 뒤로 2회 이상 지연되면 반복 지연 신호를 만든다. 2회는 `WARNING`, 3회 이상은 `CRITICAL`이다.
- 현재·다음 담당자가 활동 중이고 담당 종료일이 14일 이내이거나 이미 지났지만 열린 역할 바통이 없으면 바통 미시작 신호를 만든다.
- 열린 역할 바통은 새 담당 시작일이 시즌 현지 오늘부터 7일 이내이거나 이미 지났으면 항목 준비도와 무관하게 남은 전달 또는 수락 행동을 알린다. `PREPARING`은 현재 활성 항목을, `TRANSFERRED`는 전달 시점 스냅샷을 이유에 사용한다. 시작일까지 시간이 남았으면 `WARNING`, 오늘이거나 지났으면 `CRITICAL`이다.
- 현재 담당 종료일이 14일 이내이거나 이미 지났고 새 담당 시작일이 그 다음 날보다 늦으면, 열린 역할 바통의 시작일이 7일 밖에 있어도 실제 담당 공백을 `WARNING` 또는 `CRITICAL`로 알린다.
- `PREPARING` 바통은 현재·다음 담당자가 모두 활동 중이어야 전달할 수 있다. `TRANSFERRED` 바통은 다음 담당자만 활동 중이면 수락할 수 있으므로 이전 담당자의 활동 종료를 참여자 오류로 오분류하지 않는다. 다만 수락 전 현재 역할의 담당 공백이므로 거리와 관계없이 `CRITICAL`로 즉시 수락 또는 취소를 안내한다. 현재 단계에 필요한 참여자가 활동을 종료했거나 기록을 찾을 수 없을 때도 `CRITICAL`로 알린다.
- 구체적인 바통 신호가 있는 역할에서는 같은 담당자·후임·항목 공백을 일반 역할 신호로 다시 만들지 않는다.
- 종료 시즌은 행동 가능한 신호를 반환하지 않는다. 보관 회차는 반복 지연에서, 보관 바통 항목은 준비도에서 제외한다.
- 응답 순서는 `CRITICAL`을 먼저 두고 관련 날짜가 이른 신호, 유형 우선순위와 제목 순으로 안정적으로 정렬한다.

현재 자료에는 마지막 확인 시각이 없으므로 오래 확인되지 않은 역할 자료를 추측해 신호로 만들지 않는다.

### 시즌 생명주기

시즌 정보 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "name": "2026 여름 시즌",
  "startDate": "2026-07-01",
  "endDate": "2026-09-30"
}
```

성공 상태는 `200 OK`이고 갱신된 시즌 전체 표현을 반환한다. 이름은 앞뒤 공백을 정리한 뒤 팀 안에서 유일하고 최대 100자다. 시작일은 종료일보다 늦을 수 없다. 종료되지 않은 시즌만 수정할 수 있으며 기존 회차 날짜, 역할 담당 기간이나 열린 역할 바통의 다음 담당 기간을 제외하도록 기간을 줄일 수 없다.

운영자 시즌 이름 정정:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/name
X-Baton-Recovery-Key: <파일럿 운영자 복구 키>
```

```json
{ "name": "2026 여름 시즌" }
```

`BATON_WORKSPACE_RECOVERY_KEY`로 보호하는 운영 경로다. 복구 키를 먼저 검증하고 팀 공유 잠금과
시즌 배타 잠금을 얻는다. 일반 공유 접근 키나 로그인 세션으로 대신 인증할 수 없다.
종료되었거나 후속 시즌이 있는 시즌도 이름만 정정할 수 있다. 기간·시간대·자동 회차 설정·종료 시각·
계보와 후속 시즌은 유지하며, 일반 시즌 수정 API의 종료 제한은 바꾸지 않는다.

이름은 기존 시즌과 같은 공백 정리·최대 100자·팀별 유일성 규칙을 따른다. CAL 캡처와 이름 연동이
켜져 있으면 원본 저장과 같은 트랜잭션에서 이름 아웃박스를 기록한다. 같은 이름을 다시 요청해도
새 이름 개정이 추가되지 않으며 별도 `Idempotency-Key`는 필요하지 않다. CAL의 NFC·TEXT 검증을
이 API에 복제하지 않으므로 CAL 보정 오류를 정정한 경우에는 보정을 다시 실행해 확인한다.

성공은 `200 OK`와 시즌 전체 표현이다. 입력 오류는 `400 INVALID_INPUT`, 복구 키 미설정·누락·
불일치는 `403 WORKSPACE_RECOVERY_DENIED`, 없는 팀이나 해당 팀에 속하지 않은 시즌은
`404 TEAM_NOT_FOUND` 또는 `404 SEASON_NOT_FOUND`, 이름 중복은 `409 SEASON_NAME_CONFLICT`,
겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다. 운영자 전용이며 일반 화면에는 추가하지 않는다.

회차 일정과 시즌 시간대 설정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "timeZone": "Asia/Seoul",
  "firstMeetingDate": "2026-08-06",
  "meetingTime": "20:00",
  "recurrence": "WEEKLY",
  "generationLeadDays": 7,
  "enabled": true
}
```

`timeZone`은 최대 64자의 JDK 시간대 공급자에 등록된 IANA 식별자다. `+09:00`, `Z`, `UTC+09:00` 같은 고정 오프셋 형식과 `UTC` 별칭은 받지 않으며 UTC 지역은 `Etc/UTC`를 사용한다. `firstMeetingDate`는 시즌 기간 안에 있어야 하고 `meetingTime`은 시즌 시간대 기준 ISO 8601 로컬 시각이다. `recurrence`는 `WEEKLY` 또는 `BIWEEKLY`, `generationLeadDays`는 `0..30`이다. `enabled: false`는 다음 발생일 커서를 보존한 채 자동 생성을 일시 중지한다. 설정을 바꿔도 이미 처리한 커서를 과거로 되감지 않고 새 반복 주기의 다음 가능한 날짜로 정렬한다.

일정을 활성화하려면 시즌의 모든 활성 루틴에 `deadlineDayOffset`과 `deadlineTime`이 있어야 한다. 활성 일정이 있는 동안에는 마감이 없는 루틴을 새로 만들거나 기존 활성 루틴의 마감 규칙을 제거할 수 없고, 마감 규칙이 없는 보관 루틴을 복원할 수 없다. 회차가 하나라도 생성된 뒤에는 시즌 시간대를 바꿀 수 없다. 종료 시즌에서는 일정을 바꿀 수 없다.

성공 상태는 `200 OK`이고 `timeZone`과 `roundSchedule`을 포함한 갱신된 시즌 전체 표현을 반환한다. `roundSchedule.nextOccurrenceDate`는 서버가 다음에 처리할 발생일이다. 입력·IANA 시간대·시즌 기간·마감 규칙 또는 기존 회차 뒤 시간대 변경 제한 위반은 `400 INVALID_INPUT`, 접근 실패는 `403 WORKSPACE_ACCESS_DENIED`, 팀·시즌이 없으면 해당 `404`, 종료 시즌이나 겹친 변경은 `409 SEASON_ENDED` 또는 `409 WORKSPACE_CONTENT_CONFLICT`다.

종료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/ending
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "ended": true }
```

`true`는 최초 종료 UTC 시각을 `endedAt`에 기록하고 같은 상태를 반복해도 그 시각을 유지한다. `false`는 `endedAt`을 `null`로 되돌린다. 후속 시즌이 있거나 같은 팀의 다른 활성 시즌이 있으면 다시 열 수 없다. 성공 상태는 `200 OK`이고 갱신된 시즌을 반환한다.

`PREPARING` 또는 `TRANSFERRED` 역할 바통이 하나라도 있으면 시즌을 종료할 수 없고 `409 ROLE_HANDOFF_STATE_CONFLICT`를 반환한다. 해당 바통을 수락하거나 취소한 뒤 다시 요청해야 한다.

다음 시즌 시작:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/successor
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "name": "2026 가을 시즌",
  "startDate": "2026-10-01",
  "endDate": "2026-12-31",
  "copyRoleIds": ["원본 역할 UUID"],
  "copyRoutineIds": ["원본 루틴 UUID"]
}
```

새 시즌 시작일은 원본 시즌 종료일보다 늦어야 한다. 두 선택 목록은 각각 최대 100개이고 중복이나 다른 시즌 식별자를 허용하지 않는다. 활성 루틴만 선택할 수 있고 선택한 루틴의 담당 역할도 `copyRoleIds`에 포함해야 한다. 보관 루틴 식별자는 `404 ROUTINE_NOT_FOUND`로 거절하고 전체 전환을 롤백한다.

서버는 한 트랜잭션에서 원본 시즌을 종료하고 후속 시즌과 선택한 정의를 만든다. 역할은 이름·목적·책임·위험 신호를 새 UUID로 복사하되 현재·다음 담당자와 담당 기간을 비운다. 루틴도 실제 마감 규칙과 함께 새 UUID로 복사하고 새 역할 UUID를 참조한다. 새 시즌은 원본 시즌의 `timeZone`을 이어 받지만 `roundSchedule`, 발생 커서, 회차·실행, 결정, 바통 항목, 역할 바통 이력과 역할 자료는 복사하지 않고 원본 시즌에 남긴다. 같은 원본 시즌에는 후속 시즌을 하나만 만들 수 있고 한 팀에는 종료되지 않은 시즌을 하나만 둔다. 열린 역할 바통이 있으면 원본 시즌을 종료하거나 후속 시즌을 만들지 않고 `409 ROLE_HANDOFF_STATE_CONFLICT`를 반환한다.

성공 상태는 `201 Created`이고 `Location`은 새 시즌 워크스페이스 경로다. 응답은 종료된 `sourceSeason`, 생성한 `season`, `sourceRoleId`와 새 `roleId`의 `copiedRoles`, `sourceRoutineId`와 새 `routineId`의 `copiedRoutines`를 반환한다. 같은 멱등 키와 정규화 요청을 동일 재처리하면 같은 시즌과 식별자 대응을 반환하며, 역할·루틴 선택 순서는 요청 지문에서 의미 없는 집합으로 정렬한다.

종료 시즌은 워크스페이스 조회, 시즌 전환, 접근 키 회전·복구와 다음 시즌 시작을 허용한다. 구성원·역할·자료·루틴·회차·실행·결정·바통 항목·역할 바통의 생성·수정·완료·보관·복원은 `409 SEASON_ENDED`로 거절한다. 프런트엔드도 같은 경계를 읽기 전용으로 표시하지만 서버 검증이 권위다.

### 접근 키 회전

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <현재 접근 키>
```

- 성공 상태: `200 OK`
- 요청 본문: 없음
- `Cache-Control: no-store`

응답:

```json
{
  "accessKey": "최초 변경 또는 동일 멱등 재처리에서만 반환하는 새 접근 키"
}
```

최초 요청은 현재 접근 키가 유효할 때만 회전할 수 있다. 성공하는 즉시 이전 키와 이전 공유 링크는 더 이상 사용할 수 없으며 새 원문 키는 서버에 저장하지 않는다.

`Idempotency-Key`의 형식은 워크스페이스 생성과 같다. 서버가 키 변경을 커밋한 뒤 응답만 유실된 경우, 클라이언트는 이전 접근 키와 같은 멱등 키로 재시도할 수 있다. 서버는 현재 접근 키 검증보다 동일 작업 재처리를 먼저 확인해 같은 새 키를 반환한다. 따라서 완료 전 멱등 키는 접근 자격과 같은 수준으로 보호해야 하며, 브라우저 클라이언트는 이를 Web Storage에 기록해야 한다. `setItem`이 예외를 던지면 회전 요청을 시작하지 않는다.

서버는 팀별로 이미 사용한 키 변경 멱등 해시를 보관한다. 신규 회전의 멱등 결과와 새 접근 키는 `teamId + Idempotency-Key`로 파생하므로 응답 유실 뒤 같은 팀의 다른 시즌 경로로 재시도해도 같은 결과를 반환한다. 이전 배포에서 시즌 범위로 시작한 변경도 저장된 원래 결과를 호환 동일 재처리할 수 있다. 같은 멱등 키로 만든 결과 뒤에 더 최신 키 변경이 완료됐다면 `409 IDEMPOTENCY_REPLAY_EXPIRED`를 반환하며, 폐기된 과거 접근 키를 다시 발급하지 않는다. 서로 다른 회전·복구 요청이 같은 팀에 동시에 반영되려 하면 하나는 `409 WORKSPACE_ACCESS_KEY_CONFLICT`를 받으며, 클라이언트는 최신 접근 상태를 확인한 뒤 새 멱등 키로 명시적으로 다시 시도한다.

### 접근 키 운영자 복구

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Recovery-Key: <파일럿 운영자 복구 키>
```

- 성공 상태: `200 OK`
- 요청 본문: 없음
- `Cache-Control: no-store`
- 응답: 접근 키 회전과 같은 `accessKey` 객체

모든 구성원이 접근 키를 잃었을 때만 사용하는 파일럿 운영 절차다. 현재 접근 키는 요구하지 않지만 서버에 생성 권한과 별도인 `BATON_WORKSPACE_RECOVERY_KEY`가 설정되어 있고 요청 헤더가 일치해야 한다. 설정이 비어 있거나 헤더가 일치하지 않으면 `403 WORKSPACE_RECOVERY_DENIED`이며, 성공하면 기존 키를 즉시 폐기한다.

`Idempotency-Key` 형식, 팀 범위와 응답 유실 동일 재처리 규칙은 회전과 같다. 복구 결과의 동일 재처리도 매번 올바른 `X-Baton-Recovery-Key`를 먼저 검증하며, 회전과 복구에 같은 원문 멱등 키를 사용해도 작업별로 분리된 결과를 만든다.

### 콘텐츠 생성 멱등성

구성원, 역할, 루틴, 회차, 결정, 바통 항목, 역할 자료와 역할 바통 준비를 만드는 여덟 `POST` 요청에는 워크스페이스 생성과 같은 형식의 `Idempotency-Key`가 필수다. 서버는 동일 재처리 요청에서도 현재 `X-Baton-Access-Key`를 먼저 검증하며, 팀·시즌·작업 종류별로 멱등 결과를 분리한다. 따라서 같은 원문 키를 다른 작업 종류나 다른 작업 공간에서 독립적으로 사용할 수 있지만, 클라이언트는 각 사용자 의도마다 새 키를 사용한다.

같은 키와 의미가 같은 정규화 요청을 다시 보내면 새 리소스를 만들지 않고 최초에 생성된 리소스의 같은 `id`와 현재 표현을 `201 Created`로 반환한다. 그 사이 구성원의 이름·활동 상태, 루틴·회차의 보관 상태, 회차의 이름·모임 날짜·루틴 실행 상태, 바통 항목의 완료 상태, 결정·바통 항목의 내용이나 보관 상태 또는 역할 바통의 전환 상태가 바뀌었다면 동일 재처리 응답에는 현재 상태가 보인다. 보관된 루틴·회차·결정·바통 항목도 `archivedAt`이 있는 현재 표현으로 반환되므로 동일 재처리 성공을 활성 기록의 재생성으로 해석하지 않는다. 역할 바통 준비 동일 재처리도 같은 `role`과 `handoff`의 현재 표현을 반환하며 완료·취소한 이력을 새로 열지 않는다. 회차 생성 뒤 루틴 정의를 추가·수정·보관해도 동일 재처리는 최초 회차의 실행 식별자, 구성과 스냅샷을 바꾸지 않는다. 동일 재처리 일치 여부는 현재 표현이 아니라 최초 생성 요청의 지문으로 판단하므로, 정정된 이름·날짜를 원래 생성 키와 함께 보내면 `409 IDEMPOTENCY_KEY_REUSED`다. 같은 범위·작업의 키를 그 밖의 의미가 다른 요청에 재사용해도 같은 오류를 반환하고, 동일 키 예약이 동시에 충돌하면 `409 IDEMPOTENCY_KEY_CONFLICT`다. 동시 충돌을 받은 클라이언트는 새 키를 만들지 않고 잠시 뒤 같은 키와 같은 요청으로 재시도한다.

현재 자동 일정에 따른 루틴 마감 필수 여부와 현재 시즌 기간에 따른 회차 날짜 검증은 신규 생성에만 적용한다. 생성 뒤 자동 일정이나 시즌 기간이 바뀌어도 같은 멱등 키와 최초 요청의 재시도를 이 조건으로 거부하지 않는다. 현재 접근 키와 시즌 종료 여부, 최초 요청 지문과의 일치 여부는 재시도에서도 확인한다.

요청 지문은 도메인 입력과 같이 문자열 앞뒤 공백과 도메인이 같은 값으로 취급하는 선택적 빈 문자열을 정규화한다. 책임과 관련 역할처럼 순서가 응답에 보존되는 목록은 순서까지 요청 의미에 포함한다. 서버는 원문 멱등 키 대신 작업·팀·시즌으로 범위를 분리한 SHA-256 기반 해시만 저장하며, 멱등 예약과 리소스 생성은 한 트랜잭션에서 커밋하거나 함께 롤백한다.

브라우저 클라이언트는 요청 전에 정규화 요청과 멱등 키를 Web Lock 임계 구역에서 내구 저장해야 한다. `setItem`이 예외를 던지거나 브라우저 전체의 미완료 콘텐츠 생성 기록이 20개에 도달하면 새 생성을 전송하지 않는다. 성공 또는 같은 결과의 동일 재처리를 확인한 뒤에만 기록을 지우며, 네트워크 오류·서버 오류·동시 충돌·접근 키 오류에는 보존한다. 같은 키의 다른 요청으로 판정되면 해당 기록을 지우고 사용자의 명시적인 새 제출을 요구한다.

### 구성원

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/members
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청:

```json
{
  "name": "최유진"
}
```

`name`은 앞뒤 공백을 제거한 뒤 1자 이상 100자 이하이고 같은 팀 안에서 유일해야 한다. 중복은 공백을 정리한 문자열을 대소문자와 악센트를 구분해 정확히 비교한다. 이름이 같은 사람은 역할 선택에서 구분할 수 있는 별칭을 사용한다. 구성원은 팀 스코프에 속하므로 이 요청으로 추가한 구성원은 같은 팀의 다른 시즌에서도 같은 구성원으로 사용한다. 경로의 `seasonId`는 현재 접근 키로 변경할 수 있는 팀·시즌 조합인지 검증하고 구성원 생성 멱등 결과의 범위를 정하는 문맥이다.

성공 상태는 `201 Created`이며 응답은 생성된 구성원의 `id`, 정규화한 `name`, 표시용 `initials`, `tone`, `null` 허용 `deactivatedAt`을 반환한다. 새 구성원의 `deactivatedAt`은 `null`이다. 같은 `Idempotency-Key`와 같은 정규화 이름을 다시 보내면 구성원을 중복 생성하지 않고 최초 구성원의 같은 `id`와 현재 표현을 `201 Created`로 반환한다.

빈 이름이나 100자를 넘는 이름은 `400 INVALID_INPUT`, 접근 키 누락·불일치는 `403 WORKSPACE_ACCESS_DENIED`, 팀이나 시즌 범위가 없으면 해당 `404` 오류를 반환한다. 같은 팀에 정규화한 이름이 이미 있으면 `409 MEMBER_NAME_CONFLICT`다. 멱등 키 재사용과 동시 처리 충돌은 위 콘텐츠 생성 공통 규칙의 `409 IDEMPOTENCY_KEY_REUSED`, `409 IDEMPOTENCY_KEY_CONFLICT`를 따른다.

이름 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}
X-Baton-Access-Key: <워크스페이스 접근 키>
Content-Type: application/json

{
  "name": "최유진(백엔드)"
}
```

표시 이름은 생성과 같은 정규화·길이·팀별 유일성 규칙을 따른다. 활동 종료 구성원의 이름도 계속 예약되며, 대상 구성원 자신의 현재 이름은 중복으로 보지 않는다. 성공 상태는 `200 OK`이고 같은 UUID, 정정된 이름·이니셜, 기존 활동 상태를 반환한다. 이름은 현재 표시 정보이므로 이를 참조한 기존 역할과 결정의 표시 이름도 새 이름으로 보이지만, 별도의 당시 이름 스냅샷을 만들지는 않는다.

활동 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation
X-Baton-Access-Key: <워크스페이스 접근 키>
Content-Type: application/json

{
  "deactivated": true
}
```

`deactivated: true`는 최초 활동 종료 시각을 `deactivatedAt`에 기록하고, `false`는 값을 `null`로 만들어 다시 활성화한다. 같은 상태를 다시 요청해도 최초 활동 종료 시각을 바꾸지 않고 `200 OK`로 현재 표현을 반환한다. 활동 종료는 기존 역할 담당자와 결정 작성자 참조를 바꾸지 않으며 워크스페이스 프로젝션에서도 구성원을 제거하지 않는다. 새 역할 담당자와 새 결정 작성자는 활동 중 구성원만 허용한다. 기존 역할·결정 수정은 같은 위치의 기존 활동 종료 구성원 참조를 그대로 유지할 수 있지만, 활동 종료 구성원을 다른 위치나 다른 기록에 새로 배정할 수는 없다.

두 수정 API는 대상이 없거나 다른 팀 소속이면 `404 MEMBER_NOT_FOUND`, 이름이 겹치면 `409 MEMBER_NAME_CONFLICT`, 같은 구성원의 이름·활동 상태 변경이 겹치거나 신규 배정의 구성원 잠금에 실패하면 `409 WORKSPACE_CONTENT_CONFLICT`를 반환한다. 상태 설정 요청은 같은 요청을 반복해도 추가 리소스를 만들지 않으므로 `Idempotency-Key`를 요구하지 않는다.

### 역할

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/roles
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

- 성공 상태: `201 Created`
- 같은 시즌의 역할 이름은 중복될 수 없다.
- `currentMemberId`와 `nextMemberId`는 해당 팀의 활동 중 구성원이어야 한다.
- 두 담당 날짜가 모두 있으면 시작일은 종료일보다 늦을 수 없다.

요청은 역할 응답에서 `id`를 제외한 `name`, `purpose`, 선택적 담당자·기간, `responsibilities`, 선택적 `risk`를 사용한다. `name`은 최대 100자, `purpose`는 최대 1000자다. `responsibilities`는 필수 목록이며 최대 100개까지 받고 각 책임은 공백만으로 구성될 수 없으며 최대 500자다. 선택적 `risk`는 최대 1000자다. 응답은 생성된 역할이다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 역할은 해당 팀·시즌 소속이어야 하고 시즌 안의 이름 중복, 구성원 소속·활동 상태와 담당 기간 규칙을 다시 검증한다. 기존 현재·다음 위치에 있던 활동 종료 구성원 ID를 같은 위치에 유지하는 것은 허용하지만 활동 종료 구성원을 새 위치에 배정할 수는 없다. 자기 자신의 현재 이름은 중복으로 보지 않는다. 응답은 수정된 역할이다. 같은 역할을 먼저 읽은 다른 수정과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 워크스페이스를 다시 확인해야 한다.

열린 역할 바통이 `PREPARING`이면 현재·다음 담당자와 담당 기간은 바통 준비 시점 값으로 고정하지만 역할의 이름·목적·책임·위험 신호는 수정할 수 있다. `TRANSFERRED`이면 수락 또는 취소 전까지 역할 전체를 수정할 수 없다. 위반은 `409 ROLE_HANDOFF_STATE_CONFLICT`다.

### 역할 바통 전달

역할 바통은 바통북의 항목과 별개인 역할 교대 이력이다. 한 역할에는 `PREPARING` 또는 `TRANSFERRED` 상태의 열린 바통을 하나만 둘 수 있고, `ACCEPTED`와 `CANCELLED` 이력은 삭제하지 않고 워크스페이스 프로젝션에 남긴다.

준비:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "toMemberId": "opaque-member-id",
  "incomingAssignmentStartDate": "2026-08-01",
  "incomingAssignmentEndDate": "2026-09-17"
}
```

역할에는 현재 담당자와 현재 담당 시작일이 있어야 한다. 현재 담당자와 다음 담당자는 서로 달라야 하고 둘 다 해당 팀의 활동 중 구성원이어야 한다. 역할에 다음 담당자가 이미 있으면 `toMemberId`와 같아야 한다. 다음 담당 시작일은 필수이고 선택적인 종료일과 함께 시즌 기간 안에 있어야 하며 시작일은 종료일보다 늦을 수 없다.

서버는 이전 담당자·기간과 요청한 다음 담당자·기간을 새 역할 바통에 고정하고 역할의 `nextMemberId`를 다음 담당자로 설정한다. 성공 상태는 `201 Created`, `Location`은 `/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}`다. 응답은 현재 역할과 준비한 바통을 함께 반환한다.

```json
{
  "role": {
    "id": "opaque-role-id",
    "name": "질문 큐레이터",
    "purpose": "막힌 지점을 모아 함께 푼다",
    "currentMemberId": "opaque-current-member-id",
    "nextMemberId": "opaque-next-member-id",
    "assignmentStartDate": "2026-07-20",
    "assignmentEndDate": "2026-09-17",
    "responsibilities": ["질문 수집", "공통 막힘 정리"],
    "risk": "질문이 개인 메모에만 남을 수 있다"
  },
  "handoff": {
    "id": "opaque-handoff-id",
    "roleId": "opaque-role-id",
    "fromMemberId": "opaque-current-member-id",
    "toMemberId": "opaque-next-member-id",
    "outgoingAssignmentStartDate": "2026-07-20",
    "outgoingAssignmentEndDate": "2026-09-17",
    "incomingAssignmentStartDate": "2026-08-01",
    "incomingAssignmentEndDate": "2026-09-17",
    "status": "PREPARING",
    "preparedAt": "2026-07-30T08:00:00Z",
    "transferredAt": null,
    "acceptedAt": null,
    "cancelledAt": null,
    "transferredByMemberId": null,
    "acceptedByMemberId": null,
    "cancelledByMemberId": null,
    "activeItemCount": null,
    "incompleteItemCount": null,
    "resourceCount": null,
    "warningAcknowledged": false
  }
}
```

같은 멱등 키와 같은 정규화 요청은 같은 바통의 현재 표현을 `201 Created`로 반환한다. 다른 열린 바통이 있거나 역할의 현재·다음 담당자 상태가 준비 조건과 맞지 않으면 `409 ROLE_HANDOFF_STATE_CONFLICT`다.

전달:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/transfer
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "confirmedByMemberId": "opaque-current-member-id",
  "warningAcknowledged": true
}
```

`PREPARING` 상태에서 준비 당시 이전 담당자 ID를 `confirmedByMemberId`로 선언해야 한다. 서버는 그 시점의 보관되지 않은 활성 바통 항목 수, 그중 미완료 수와 역할 자료 수를 각각 `activeItemCount`, `incompleteItemCount`, `resourceCount`로 스냅샷한다. 활성 바통 항목이 없거나 미완료 항목이 하나 이상이거나 자료가 없으면 `warningAcknowledged: true`가 필요하며, 그렇지 않으면 `409 ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED`다. 성공하면 `TRANSFERRED`가 되고 `200 OK`로 현재 역할과 바통을 반환한다.

수락:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/acceptance
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "confirmedByMemberId": "opaque-next-member-id" }
```

`TRANSFERRED` 상태에서 준비 당시 다음 담당자 ID를 선언해야 한다. 해당 구성원이 계속 활동 중이고 준비한 담당 기간이 여전히 시즌 안에 있으면, 한 트랜잭션에서 바통을 `ACCEPTED`로 바꾸고 역할의 현재 담당자를 다음 담당자로, `nextMemberId`를 `null`로, 담당 기간을 준비 때 고정한 다음 담당 기간으로 전환한다. 성공 상태는 `200 OK`이며 갱신한 역할과 바통을 함께 반환한다.

취소:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/cancellation
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "confirmedByMemberId": "opaque-current-member-id" }
```

현재 파일럿은 `PREPARING` 또는 `TRANSFERRED` 상태에서 준비 당시 이전 담당자 ID를 선언한 취소만 허용한다. 성공하면 바통을 `CANCELLED`로 만들고 역할의 `nextMemberId` 예약을 비우며 `200 OK`로 현재 역할과 바통을 반환한다. `ACCEPTED` 상태는 취소할 수 없다.

세 전환 요청은 별도 `Idempotency-Key`를 요구하지 않는다. 이미 같은 확인자 명의로 완료한 전달·수락·취소를 다시 요청하면 현재 표현을 반환하지만, 다른 확인자나 허용하지 않은 상태 전환은 `409 ROLE_HANDOFF_STATE_CONFLICT`다. 없는 바통이나 경로의 팀·시즌·역할과 소속이 다른 바통은 `404 ROLE_HANDOFF_NOT_FOUND`다. 응답의 `transferredByMemberId`, `acceptedByMemberId`, `cancelledByMemberId`는 요청의 `confirmedByMemberId`를 상태별로 기록한 값이다. 공유 키를 가진 요청자가 해당 구성원 명의로 확인했다고 선언한 값이며, 사용자 인증이 없으므로 실제 사람이 그 구성원인지 증명하는 서명이나 감사 기록으로 해석하지 않는다.

`PREPARING` 동안에는 담당자와 담당 기간만 고정되고 역할 내용, 바통 항목과 역할 자료는 계속 보완할 수 있다. `TRANSFERRED` 뒤에는 역할, 그 역할의 바통 항목과 자료를 수락 또는 취소 전까지 수정·완료·보관·복원하거나 다른 역할로 옮길 수 없다. 자료나 바통 항목을 다른 역할 사이에 옮길 때도 출발·도착 역할 중 하나가 `TRANSFERRED`이면 `409 ROLE_HANDOFF_STATE_CONFLICT`다.

### 역할 자료

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "roleId": "opaque-role-id",
  "title": "질문 정리 가이드",
  "url": "https://docs.example.com/question-guide",
  "description": "질문을 모으고 분류하는 기준"
}
```

`roleId`는 요청한 시즌의 역할이어야 한다. `title`은 필수이며 최대 200자, `url`은 사용자 정보가 없는 절대 `http` 또는 `https` 주소이며 최대 2048자다. `description`은 선택이고 최대 1000자다. 성공 상태는 `201 Created`이며 생성된 자료와 서버가 기록한 `null` 허용 `createdAt`을 반환한다. 새 자료에서는 `createdAt`이 항상 존재하고, `null` 허용은 V14 이전 자료를 같은 응답 형태로 조회하기 위한 호환 계약이다.

BATON 서버는 URL 대상을 요청하거나 내용·가용성·신뢰성을 확인하지 않는다. 프런트엔드는 링크를 새 탭에서 열고 `noopener noreferrer`를 적용한다. 링크 대상의 접근 권한과 안전성은 사용자가 확인해야 한다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `title`, `url`, `description` 전체 표현을 사용하고 성공 상태는 `200 OK`다. 응답은 최초 `null` 허용 `createdAt`과 현재 `archivedAt`을 그대로 유지한다. 대상 자료는 요청한 시즌의 역할에 연결되어 있어야 하며 `roleId`를 같은 시즌의 다른 역할로 바꿀 수 있다. 자료가 없거나 다른 시즌 소유이면 `404 ROLE_RESOURCE_NOT_FOUND`, 새 소유 역할이 해당 시즌에 없으면 `404 ROLE_NOT_FOUND`다. 같은 자료 수정 트랜잭션이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 워크스페이스를 다시 확인해야 한다. 생성 대상이나 수정 전·후 소유 역할에 `TRANSFERRED` 바통이 있으면 `409 ROLE_HANDOFF_STATE_CONFLICT`다. 보관한 자료는 먼저 복원해야 수정할 수 있다.

보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
Content-Type: application/json

{"archived": true|false}
```

`true`는 서버 `Clock`의 최초 보관 UTC 시각을 `archivedAt`에 기록하고, `false`는 이를 `null`로 되돌린다. 같은 상태 요청은 현재 표현을 그대로 반환한다. 보관 자료는 활성 역할 화면, 바통 전달의 `resourceCount`, 새 ROUND 방 매핑과 기존 방의 새 참여권 발급, WATCH 활성 감시 대상에서 제외하지만 워크스페이스 프로젝션과 탐색 기록에는 남는다. 자료를 복원하면 다시 활성 동작 대상이 되고 WATCH는 현재 URL을 활성 스냅샷으로 기록한다. 대상 역할이 `TRANSFERRED` 상태면 보관과 복원을 모두 `409 ROLE_HANDOFF_STATE_CONFLICT`로 거부한다.

### 운영 루틴

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/routines
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청 필드는 `title`, `phase`, `dueLabel`, `null` 허용 `deadlineDayOffset`, `null` 허용 `deadlineTime`, `ownerRoleId`, `detail`이다. `ownerRoleId`는 요청한 시즌의 역할이어야 한다. `deadlineDayOffset`은 모임 날짜 기준 `-30..30`일이고 `deadlineTime`은 시즌 시간대 기준 ISO 8601 로컬 시각이다. 두 필드는 함께 설정하거나 함께 `null`이어야 한다. `dueLabel`은 사람이 읽는 설명으로 계속 필수이며 서버가 이를 파싱해 마감을 추론하지 않는다. 이 리소스는 반복 정의이므로 완료 상태를 갖지 않으며 성공 상태는 `201 Created`다. 응답에는 활성 상태를 뜻하는 `archivedAt: null`이 포함된다.

정의 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 루틴은 해당 시즌 소속의 활성 정의이고 `ownerRoleId`도 같은 시즌 역할이어야 한다. 제목, 단계, 기한 문구, 실제 마감 규칙, 담당 역할과 상세를 바꾸며 이미 생성한 회차의 실행 스냅샷과 `deadlineAt`은 바꾸지 않는다. 자동 회차 일정이 활성화되어 있으면 마감 규칙을 비울 수 없다. 보관 정의는 `404 ROUTINE_NOT_FOUND`이며 먼저 복원해야 한다. 같은 정의를 수정하는 두 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받아 상대 변경을 덮어쓰지 않는다.

정의 보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "archived": true
}
```

`archived: true`는 서버 `Clock`의 UTC 시각을 `archivedAt`에 기록하고 `false`는 `null`로 되돌린다. 같은 상태를 반복 요청하면 최초 보관 시각 또는 활성 상태를 유지한다. 성공 상태는 `200 OK`이고 현재 루틴 표현을 반환한다. 보관·복원은 기존 회차 실행을 삭제하거나 바꾸지 않으며, 보관 이전 실행은 계속 조회·완료 처리할 수 있다. 보관 정의는 이후 수동·자동 회차와 다음 시즌 복사 대상에서 제외되고 복원하면 다음 회차부터 다시 포함된다. 활성 자동 일정에서 실제 마감 규칙이 없는 정의의 복원은 `400 INVALID_INPUT`이다. 대상이 없거나 다른 시즌 소속이면 `404 ROUTINE_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

### 시즌 회차와 루틴 실행

회차 생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/rounds
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "name": "3회차",
  "meetingDate": "2026-07-27"
}
```

`name`은 앞뒤 공백을 정규화한 뒤 같은 시즌에서 유일해야 하고 최대 100자다. `meetingDate`는 ISO 8601 날짜이며 시즌 시작일과 종료일을 포함한 기간 안에 있어야 한다. 성공 상태는 `201 Created`다.

서버는 회차 생성 트랜잭션에서 현재 시즌의 활성 루틴 정의를 각각 독립된 실행으로 복사하고 처음 상태를 `WAITING`으로 둔다. 실제 마감 규칙이 있는 실행은 모임 날짜와 시즌 시간대로 UTC `deadlineAt`을 계산해 함께 스냅샷한다. 응답은 회차 `id`, `name`, `meetingDate`, `null` 허용 `archivedAt`, `origin`, `null` 허용 `scheduledOccurrenceDate`, `null` 허용 `scheduledAt`, `timingStatus`와 `routineExecutions`를 반환한다. 이 API로 만든 회차는 `origin: MANUAL`이고 두 일정 메타데이터는 `null`이며 새 회차의 `archivedAt`은 `null`이다. 각 실행은 `id`, `roundId`, 원본 `routineId`, 스냅샷 필드, `status`, `null` 허용 `deadlineAt`과 `timingStatus`를 가진다. 회차 생성 뒤 루틴을 추가·수정·보관해도 기존 회차에는 반영되지 않고 다음에 만드는 회차부터 반영된다. 같은 멱등 요청을 동일 재처리하면 실행을 다시 만들지 않고 최초 회차 식별자와 현재 이름·날짜·보관·실행 상태를 반환한다.

활성화한 주간·격주 일정은 별도 사용자 요청 없이 선행 생성일에 자동 회차를 만든다. 자동 회차는 `origin: AUTOMATIC`, 반복 일정의 원래 발생일 `scheduledOccurrenceDate`와 모임 시각의 UTC `scheduledAt`을 보존한다. `(seasonId, scheduledOccurrenceDate)`는 유일하므로 스케줄러가 같은 발생을 다시 처리해도 회차를 중복 생성하지 않는다. 처리할 발생일에 활성 루틴이 없으면 `roundSchedule.nextOccurrenceDate`만 다음 주기로 전진하고 실행이 없는 자동 회차는 만들지 않는다. 나중에 정의를 복원해도 이미 건너뛴 발생일을 소급 생성하지 않는다.

실행 `timingStatus`는 다음 규칙으로 조회 시 계산한다.

- 저장 상태가 `DONE`이면 `COMPLETED`
- 완료되지 않았고 `deadlineAt`이 없으면 `UNSCHEDULED`
- 시즌 현지 날짜가 마감 현지 날짜보다 이르면 `PLANNED`
- 마감 현지 날짜이지만 현재 시각이 `deadlineAt` 전이면 `IN_PROGRESS`
- 완료되지 않았고 현재 시각이 `deadlineAt`과 같거나 지났으면 `OVERDUE`

회차 `timingStatus`는 모든 실행이 완료되면 `COMPLETED`, 하나라도 지연이면 `OVERDUE`, 진행 중이거나 일부 완료된 실행이 있으면 `IN_PROGRESS`, 그 밖에는 `PLANNED`다.

회차 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{
  "name": "세 번째 모임",
  "meetingDate": "2026-07-28"
}
```

요청은 이름과 모임 날짜의 전체 표현이다. `name`의 정규화·길이·시즌 안 유일성, `meetingDate`의 ISO 8601 형식과 시즌 기간 규칙은 생성과 같다. 성공 상태는 `200 OK`이고 수정된 회차 전체를 반환한다. 회차 `id`와 기존 루틴 실행의 `id`, 원본 루틴 식별자, 마감 규칙을 포함한 스냅샷 필드와 `status`는 바뀌지 않는다. 모임 날짜를 바꾸면 복사된 마감 규칙과 시즌 시간대로 실행의 `deadlineAt`만 다시 계산한다. V5 이관 회차의 `null` 날짜는 서버가 임의로 채우지 않으며, 운영자가 이 API로 정정할 때 실제 시즌 내 날짜를 반드시 제공한다.

보관된 회차는 수정할 수 없으며, 없거나 다른 시즌 소속인 회차와 같은 `404 SEASON_ROUND_NOT_FOUND`를 반환한다. 활성 자동 회차도 이름과 날짜를 수정할 수 있다. `scheduledOccurrenceDate`는 원래 발생일로 유지하며, 날짜 변경 때 기존 `scheduledAt`의 시즌 현지 모임 시각을 새 날짜에 적용해 `scheduledAt`을 갱신한다. 날짜가 같으면 기존 UTC 시각을 유지한다. 반복 설정과 발생 커서는 변경하지 않고, 다른 발생과 같은 날짜로 옮겨도 서로 다른 회차로 유지한다. 날짜 변경·보관·복원은 같은 CAL 원본 ID의 새 스냅샷으로 기록한다. 이름 유일성은 보관 여부와 무관하게 시즌 전체에 적용되므로 보관된 회차의 이름도 예약된다. 다른 회차와 이름이 겹치면 `409 ROUND_NAME_CONFLICT`, 같은 회차의 수정·보관이 겹쳐 늦은 저장이 발생하면 `409 WORKSPACE_CONTENT_CONFLICT`다.

회차 보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

`archived: true`는 서버 `Clock`의 UTC 시각을 `archivedAt`에 기록하고, `false`는 `archivedAt`을 `null`로 되돌려 복원한다. 이미 같은 상태라면 최초 보관 시각 또는 활성 상태를 유지한다. 수동·자동 회차 모두 보관·복원할 수 있다. 운영 화면에서 자동 회차의 보관은 `이번 회차 건너뛰기`로 표시하며, 이미 생성된 회차에만 적용하고 다음 반복 일정은 유지한다. 성공 상태는 `200 OK`이고 실행 목록을 포함한 회차 전체를 반환한다. 보관·복원은 회차나 루틴 실행을 삭제·재생성하지 않으므로 실행 식별자, 생성 출처·예정 발생일, 마감 스냅샷과 완료 상태를 그대로 보존한다. 보관된 회차도 워크스페이스 프로젝션의 `rounds`에 남고 프런트엔드는 활성 운영 목록과 보관함을 나눠 표시한다.

대상이 없거나 다른 시즌 소속이면 `404 SEASON_ROUND_NOT_FOUND`, 실제 버전·잠금 충돌은 `409 WORKSPACE_CONTENT_CONFLICT`다. 회차·실행 변경은 첫 원본 조회 전에 팀 공유 잠금과 시즌 배타 잠금을 얻어 원본 변경과 BRIEF 신호 기록을 함께 직렬화한다. 이후 보관·복원은 회차 행의 쓰기 잠금, 실행 완료는 같은 행의 공유 읽기 잠금을 유지한다. 같은 실행의 동일 완료 요청은 차례로 처리되어 각각 같은 완료 상태를 `200 OK`로 반환하며, 잠금 대기 실패나 실제 버전 충돌은 `409 WORKSPACE_CONTENT_CONFLICT`다. 보관이 먼저 반영되면 기다리던 완료 요청은 보관 상태를 확인한 뒤 `404 SEASON_ROUND_NOT_FOUND`로 끝나며, 완료가 먼저 반영되면 보관은 그 완료 상태를 보존한다.

회차별 완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/routine-executions/{executionId}/completion
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 `deadlineAt`과 갱신된 `timingStatus`를 포함한 루틴 실행을 반환한다. 회차는 요청한 시즌 소속의 활성 회차이고 실행은 해당 회차 소속이어야 한다. 보관된 회차는 완료 상태를 바꿀 수 없으며 `404 SEASON_ROUND_NOT_FOUND`다. 같은 실행을 바꾸는 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`다. 기존의 `PATCH /routines/{routineId}/completion`은 정의와 실행의 의미를 섞으므로 제거했다.

### 결정 기록

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/decisions
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청:

```json
{
  "title": "문제 수를 다섯 개로 줄인다",
  "reason": "풀이를 설명할 시간이 부족했다",
  "alternative": "모임 시간을 늘린다",
  "authorMemberId": "opaque-member-id",
  "roleIds": ["opaque-role-id"]
}
```

작성자는 해당 팀의 활동 중 구성원이어야 하고 관련 역할은 요청한 시즌 소속이어야 하며 한 개 이상이고 중복될 수 없다. 성공 상태는 `201 Created`다. 응답의 `id`, `createdAt`, `authorName`은 서버가 결정하고, `authorMemberId`는 요청한 작성자 식별자를 반환한다. 새 결정의 `archivedAt`은 `null`이다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `title`, `reason`, `alternative`, `authorMemberId`, `roleIds` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 `createdAt`은 최초 생성 시각을 유지한다. 대상 결정은 요청 시즌 소속이어야 하고 보관되지 않은 활성 기록이어야 한다. 작성자의 팀 소속과 활동 상태, 관련 역할의 같은 시즌 소속, 최소 개수와 중복 금지를 다시 검증한다. 기존 활동 종료 작성자 ID를 그대로 유지하는 것은 허용하지만 다른 활동 종료 구성원으로 바꿀 수는 없다. 대상이 없거나 다른 시즌 소속이거나 보관 상태이면 `404 DECISION_NOT_FOUND`다. 같은 결정을 먼저 읽은 다른 수정·보관 트랜잭션과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

관련 역할을 한 개 이상 유지하면서 일부 연결을 해제하거나 `roleIds` 배열 순서를 바꿀 수 있다.
수정 응답과 이후 워크스페이스 조회는 요청한 역할 순서를 그대로 반환한다.

보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

`archived: true`는 서버 `Clock`의 UTC 시각을 `archivedAt`에 기록하고, `false`는 `archivedAt`을 `null`로 되돌려 복원한다. 이미 같은 상태라면 기존 보관 시각을 바꾸지 않는다. 성공 상태는 `200 OK`이고 변경된 결정 전체를 반환한다. 보관은 영구 삭제가 아니며 보관된 결정도 워크스페이스 프로젝션의 `decisions`에 남는다. 프런트엔드는 활성 기록과 보관함을 나눠 표시하고, 보관 상태에서는 내용 수정보다 복원만 제공한다. 대상이 없거나 다른 시즌 소속이면 `404 DECISION_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

### 바통 항목

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청 필드는 `roleId`, `label`, `category`다. `roleId`는 요청한 시즌의 역할이어야 한다. 새 항목은 서버에서 항상 미완료로 시작하고 `archivedAt`은 `null`이다. 성공 상태는 `201 Created`이며 서버가 기록한 `null` 허용 `createdAt`을 함께 반환한다. 새 항목에서는 `createdAt`이 항상 존재하고, `null` 허용은 V14 이전 항목을 같은 응답 형태로 조회하기 위한 호환 계약이다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `label`, `category` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 기존 `completed` 값과 최초 `null` 허용 `createdAt`을 유지한다. 대상 항목은 요청한 시즌의 역할에 연결된 활성 기록이어야 하고 새 `roleId`도 같은 시즌 역할이어야 한다. 대상이 없거나 다른 시즌 소유이거나 보관 상태이면 `404 HANDOFF_ITEM_NOT_FOUND`, 새 소유 역할이 없으면 `404 ROLE_NOT_FOUND`다. 같은 항목을 먼저 읽은 수정·완료·보관 트랜잭션과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 최초 `null` 허용 `createdAt`을 유지한 갱신 항목을 반환한다. 보관된 항목은 완료 상태를 바꿀 수 없으며 `404 HANDOFF_ITEM_NOT_FOUND`다. 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

결정과 같은 규칙으로 `true`는 최초 보관 UTC 시각을 `archivedAt`에 기록하고 `false`는 `null`로 되돌린다. 성공 상태는 `200 OK`이고 기존 완료 여부와 최초 `null` 허용 `createdAt`을 포함한 항목 전체를 반환한다. 보관된 항목도 워크스페이스 프로젝션의 `handoffItems`에 남으며 프런트가 활성 바통과 보관함으로 나눈다. 대상이 없거나 다른 시즌 소유이면 `404 HANDOFF_ITEM_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

## 5. WATCH 내부 상태 변경 이벤트 수신

WATCH는 최소 한 번 전달 방식으로 역할 자료 상태 변경 이벤트를 BATON에 직접 전달한다. 이 API는 사용자용 워크스페이스 API가 아니라 두 서비스 사이의 수신 계약이며, 워크스페이스 공유 키와 분리한 전용 Bearer 토큰으로 보호한다.

```http
POST /api/v1/internal/resource-health-events
Authorization: Bearer <WATCH 이벤트 수신기 토큰>
Idempotency-Key: <본문 eventId와 같은 UUID>
Content-Type: application/json
```

```json
{
  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
  "eventType": "RESOURCE_HEALTH_CHANGED",
  "resourceReference": "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "sourceRevision": 7,
  "attemptId": "81ccb9da-f9f9-4abc-87fe-cf6193ee5f79",
  "previousHealth": "DEGRADED",
  "currentHealth": "BROKEN",
  "changedAt": "2026-08-02T03:04:05.123456789Z"
}
```

- `eventId`는 불변 이벤트 UUID이며 `Idempotency-Key`와 반드시 같아야 한다.
- `eventType`은 `RESOURCE_HEALTH_CHANGED`만 허용한다.
- `resourceReference`는 `baton-manager:<설정된 소스 이름공간>:role-resource:<정규 형식 UUID>` 형식이어야 한다. 이 검증은 참조의 소유 환경과 식별자 형식만 확인하며 `RoleResource` 존재 여부를 조회하지 않는다.
- `sourceRevision`은 0 이상인 모니터 스냅샷 리비전이다. 상태 이벤트의 순번이나 최신 상태를 선택하는 순번값이 아니다.
- `attemptId`는 점검 시도가 원인인 변경에서만 존재하며 그 밖의 변경에서는 생략할 수 있다.
- `previousHealth`와 `currentHealth`는 `UNKNOWN`, `HEALTHY`, `DEGRADED`, `BROKEN` 중 서로 다른 값이어야 한다.
- `changedAt`은 WATCH가 상태 변경을 기록한 RFC 3339 UTC 시각이며, MySQL 저장 범위와 맞춘 `1000-01-01T00:00:00Z` 이상 `+10000-01-01T00:00:00Z` 미만이어야 한다. 범위 밖 값은 재시도할 수 없는 `400 INVALID_INPUT`이다. 계약에 없는 필드는 거부하며 대상 URL, 응답 본문과 인증 정보는 받지 않는다.

신규 이벤트를 불변 인박스에 원자적으로 저장한 뒤 다음 접수증을 반환한다.

```http
HTTP/1.1 202 Accepted
```

```json
{
  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
  "acceptedAt": "2026-08-02T03:04:06.123456Z"
}
```

`202 Accepted`는 해당 이벤트 봉투의 영속 인박스 저장이 커밋됐다는 뜻이며 BATON 워크스페이스 상태 프로젝션이나 UI 갱신 완료를 뜻하지 않는다. 같은 `eventId`와 같은 전체 이벤트 봉투를 동일 재전송하면 새 행을 만들지 않고 최초 `acceptedAt`을 포함한 같은 접수증을 `202`로 반환한다. 같은 `eventId`를 다른 이벤트 봉투에 재사용하면 `409 WATCH_EVENT_ID_CONFLICT`다. `Idempotency-Key`와 본문 ID가 다르면 `400 IDEMPOTENCY_KEY_MISMATCH`, 설정된 이름공간의 정규 참조가 아니면 `400 WATCH_RESOURCE_REFERENCE_INVALID`다.

WATCH는 전달 순서를 보장하지 않으므로 BATON은 `sourceRevision`, `changedAt` 또는 도착 순서로 수신을 폐기하지 않고 이벤트 ID가 다른 모든 이벤트 봉투를 보존한다. 현재 수신 트랜잭션은 상태 프로젝션을 변경하지 않으며 인박스 보존 정책도 아직 채택하지 않았다.

## 6. 운영 상태 엔드포인트

```http
GET /actuator/health
```

이 경로는 Spring Boot Actuator 운영 엔드포인트이며 제품 API가 아니다. 현재 인증 없이 접근할 수 있다. 응답 세부 구조는 제품 DTO 계약이 아니라 Actuator 설정을 따른다.

## 7. 오류 응답

명시적으로 처리하는 입력 오류는 다음 형태를 사용한다.

```json
{
  "code": "INVALID_INPUT",
  "message": "name: 비어 있을 수 없습니다"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `code` | string | 클라이언트가 분기할 수 있는 안정적인 오류 코드다. |
| `message` | string | 사용자가 이해할 수 있는 오류 설명이다. |

현재 명시적으로 처리하는 오류는 다음과 같다.

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| `400` | `INVALID_INPUT` | DTO 형식·검증, 멱등 키 형식, WATCH 이벤트 봉투, IANA 시간대·일정·실제 마감 규칙 또는 안전하게 식별된 도메인 입력 오류 |
| `400` | `IDEMPOTENCY_KEY_MISMATCH` | WATCH 이벤트의 `Idempotency-Key`와 본문 `eventId`가 다름 |
| `400` | `WATCH_RESOURCE_REFERENCE_INVALID` | WATCH 이벤트의 자료 참조가 설정된 이름공간과 정규 형식 UUID에 맞지 않음 |
| `400` | `EMAIL_VERIFICATION_INVALID` | 자체 이메일 검증 토큰이 유효하지 않거나 만료·소비됨 |
| `400` | `PASSWORD_RESET_INVALID` | 비밀번호 재설정 토큰이 유효하지 않거나 만료·소비됨 |
| `400` | `CURRENT_PASSWORD_INVALID` | 인증된 자체 이메일 계정의 현재 비밀번호가 일치하지 않음 |
| `401` | `UNAUTHORIZED` | WATCH 이벤트 수신기가 비활성 상태이거나 전용 Bearer 토큰이 누락·중복·불일치함 |
| `401` | `INVALID_CREDENTIALS` | 자체 이메일 계정이 없거나 미검증 상태이거나 비밀번호가 일치하지 않음 |
| `401` | `AUTHENTICATION_REQUIRED` | `Account` 세션이 필요한 ROUND 관리·참여권 요청에 인증 세션이 없음 |
| `403` | `WORKSPACE_ACCESS_DENIED` | 공유 접근 키 누락 또는 불일치 |
| `403` | `WORKSPACE_CREATION_DENIED` | 설정된 파일럿 생성 키 누락 또는 불일치 |
| `403` | `WORKSPACE_RECOVERY_DENIED` | 운영자 복구 키 미설정·누락 또는 불일치 |
| `403` | `REQUEST_FORBIDDEN` | CSRF, 동일 출처, 권한 또는 필터 체인의 전체 거부 경계를 통과하지 못함 |
| `403` | `ROUND_PARTICIPATION_DENIED` | `Account`가 방의 팀 멤버십이나 활성 시즌 참여 조건을 충족하지 못함 |
| `403` | `BRIEF_ACCESS_DENIED` | `Account`가 요청 팀의 활동 중인 멤버십을 갖지 않음 |
| `404` | `TEAM_NOT_FOUND`, `SEASON_NOT_FOUND`, `MEMBER_NOT_FOUND`, `ROLE_NOT_FOUND`, `ROLE_HANDOFF_NOT_FOUND`, `ROLE_RESOURCE_NOT_FOUND`, `ROUTINE_NOT_FOUND`, `SEASON_ROUND_NOT_FOUND`, `ROUTINE_EXECUTION_NOT_FOUND`, `DECISION_NOT_FOUND`, `HANDOFF_ITEM_NOT_FOUND` | 요청 범위에서 리소스를 찾지 못했거나 보관된 기록을 활성 변경 API로 요청함 |
| `404` | `RESOURCE_NOT_FOUND` | Spring MVC가 처리할 요청 경로를 찾지 못함 |
| `404` | `ROUND_ROOM_NOT_FOUND` | 서버 권위 활성 방 매핑을 찾지 못했거나 요청 힌트가 일치하지 않음 |
| `404` | `BRIEF_EDITION_NOT_FOUND` | 권한 범위의 BRIEF 최신 불변 에디션이 없음 |
| `405` | `METHOD_NOT_ALLOWED` | 경로는 있지만 요청한 HTTP 메서드를 지원하지 않음 |
| `409` | `MEMBER_NAME_CONFLICT` | 같은 팀에 동일한 구성원 이름이 존재함 |
| `409` | `SEASON_NAME_CONFLICT` | 같은 팀에 동일한 시즌 이름이 존재함 |
| `409` | `SEASON_ENDED` | 종료된 시즌의 일반 콘텐츠를 변경하려 함 |
| `409` | `SEASON_SUCCESSOR_EXISTS` | 이미 후속 시즌이 있는 원본에서 다시 생성하거나 원본을 재개하려 함 |
| `409` | `ROLE_NAME_CONFLICT` | 같은 시즌에 동일한 역할 이름이 존재함 |
| `409` | `ROLE_HANDOFF_STATE_CONFLICT` | 역할 바통의 참여자·상태·역할 스냅샷이 요청과 맞지 않거나, 열린 바통 중 금지된 역할·바통 항목·자료 변경 또는 시즌 종료·전환을 시도함 |
| `409` | `ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED` | 바통 항목 없음, 미완료 항목 또는 역할 자료 없음 경고를 확인하지 않고 전달하려 함 |
| `409` | `ROUND_NAME_CONFLICT` | 같은 시즌에 동일한 회차 이름이 존재함 |
| `409` | `WORKSPACE_CONTENT_CONFLICT` | 같은 구성원, 시즌, 역할, 역할 바통, 역할 자료, 루틴 정의, 회차, 루틴 실행, 결정 또는 바통 항목을 다른 요청이 동시에 변경하거나 상태 검사용 행 잠금에 실패해 최신 워크스페이스 확인이 필요함 |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 같은 범위와 작업의 멱등 키를 의미가 다른 생성 요청에 재사용함 |
| `409` | `IDEMPOTENCY_KEY_CONFLICT` | 같은 범위와 작업의 생성 요청이 동시에 처리 중임. 같은 키와 요청으로 재시도해야 함 |
| `409` | `IDEMPOTENCY_REPLAY_EXPIRED` | 더 최신 접근 키 변경 뒤 과거 워크스페이스 생성·키 변경 응답을 동일 재처리함 |
| `409` | `WORKSPACE_ACCESS_KEY_CONFLICT` | 같은 팀의 접근 키가 다른 요청에서 동시에 변경됨 |
| `409` | `WATCH_EVENT_ID_CONFLICT` | 이미 저장된 WATCH 이벤트 ID를 다른 이벤트 봉투에 재사용함 |
| `409` | `IDENTITY_CONFLICT` | 공급자 신원 또는 자체 이메일을 안전하게 사용할 수 없음 |
| `409` | `LOCAL_PASSWORD_UNAVAILABLE` | 로그인 계정에 자체 이메일 비밀번호 자격 증명이 없음 |
| `409` | `ACCOUNT_MEMBERSHIP_CONFLICT` | 확인한 계정과 로그인 계정이 다르거나 `Account` 또는 `Member`가 다른 멤버십 연결과 충돌함 |
| `409` | `ROUND_ROOM_CONFLICT` | 방 ID 또는 역할 자료의 활성 매핑이 기존 기록과 충돌함 |
| `409` | `BRIEF_DELIVERY_INCOMPLETE` | 대상 팀·시즌의 BRIEF 연속성 outbox 전달이 끝나지 않아 생성할 수 없음 |
| `409` | `BRIEF_GENERATION_IN_PROGRESS` | 같은 주차·시간대·전달 watermark의 생성 실행 lease가 아직 유효함 |
| `429` | `AUTH_RATE_LIMITED` | 가입·검증·로그인 요청이 인증 요청률 제한을 초과함 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 요청 본문의 미디어 타입을 지원하지 않음 |
| `503` | `EMAIL_VERIFICATION_UNAVAILABLE` | 가입 게이트, 아웃박스 페이로드 보호 또는 메일 전달 인프라를 사용할 수 없음 |
| `503` | `IDENTITY_TEMPORARILY_UNAVAILABLE` | 신원 저장소 잠금 경합이나 일시적 인프라 장애로 가입·검증·자체 이메일 로그인을 처리하지 못함 |
| `503` | `PARTICIPATION_GRANT_UNAVAILABLE` | ROUND 참여권 서명 인프라를 사용할 수 없음 |
| `503` | `BRIEF_CONFIGURATION_ERROR` | BRIEF 인증·요청·응답 계약 또는 범위 설정이 맞지 않음 |
| `503` | `BRIEF_UNAVAILABLE` | BRIEF 네트워크·요청률 제한·서버 장애로 호출을 완료하지 못함 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류이며 내부 상세는 응답에 노출하지 않음 |

실제 MySQL 행 잠금 대기가 제한을 넘으면 새 워크스페이스·콘텐츠 생성의 멱등 예약은 기존 `409 IDEMPOTENCY_KEY_CONFLICT`, 기존 팀 접근 키 애그리거트는 `409 WORKSPACE_ACCESS_KEY_CONFLICT`, 공유 콘텐츠 애그리거트는 `409 WORKSPACE_CONTENT_CONFLICT`로 수렴한다. 위 계정 신원 인증 경계에서 명시적으로 `503`으로 분류한 경우를 제외한 일반 쿼리 시간 초과, 트랜잭션 시간 초과와 DB 커넥션 획득 실패는 사용자의 동시 수정으로 추측하지 않고 `500 INTERNAL_ERROR`로 처리한다.

예상하지 못한 예외와 Spring MVC가 식별한 요청 오류도 같은 `ErrorResponse` 형태로 정규화한다. 단, 클라이언트가 서버가 제공하는 모든 미디어 타입을 거부해 발생하는 `406 Not Acceptable`은 오류 JSON도 협상할 수 없으므로 본문 없이 응답한다. 이 응답도 `X-Request-ID`는 유지한다. 내부 예외 상세와 스택 추적은 응답에 노출하지 않고 서버 로그에만 남기며, 처리한 예외를 현재 HTTP 관측의 오류로 기록한다. Spring에서 처리하거나 필터 체인을 벗어난 5xx는 MDC와 응답 헤더가 같은 요청 ID를 사용하며 Caddy 액세스 로그도 최종 응답 헤더를 기록한다. Caddy가 직접 만든 413·502·503은 응답 헤더와 액세스 로그의 내장 `uuid`가 같은 엣지 요청 ID를 사용한다. 해당 로그에서는 제품 운영 키, 멱등 키와 외부 요청 ID 헤더를 제거한다. 브라우저 클라이언트는 운영자가 해당 경계의 로그를 찾을 수 있도록 5xx 안내에 이 값을 함께 표시한다.

새 제품 API를 추가할 때는 다음을 함께 결정한다.

- 오류 코드와 HTTP 상태
- 필드 단위 검증 실패 표현이 필요한지
- 존재하지 않는 리소스, 충돌, 권한 실패의 경계
- 로그에 남길 내부 정보와 응답에 공개할 정보의 분리

## 8. 인증과 권한

### 계정 세션과 기존 capability(권한 증표)를 분리한다

최종 사용자 신원은 공급자 중립 `Account`와 동일 출처 서버 `HttpSession`으로 고정한다. Google
OIDC, Naver OAuth2와 자체 이메일 로그인은 Spring Security의 표준 OAuth2 Client,
`DaoAuthenticationProvider`, CSRF, 세션 고정 공격 방어와 보안 컨텍스트 경계를 사용한다. 브라우저
저장소에는 BATON·공급자 액세스 토큰을 두지 않는다. 계정·신원과 ROUND 참여권의 상세 결정은
PRD-0005와 ADR-0017을 따른다.

기존 `X-Baton-Access-Key`는 워크스페이스 전체를 사용할 수 있는 파일럿 capability(권한 증표)로 남긴다.
`Account` 세션이나 장기 사용자 권한으로 확대 해석하지 않는다. 워크스페이스 범위 경로는
`application`의 공유 키 검증으로 보호하고 쿠키 인증을 사용하지 않으므로 해당 기존 경로만
CSRF 검사에서 제외한다. 공개 생성은 선택적 `X-Baton-Creation-Key`, 키 복구는 별도
`X-Baton-Recovery-Key`를 검증한다.

WATCH 내부 이벤트 경로는 전용 `Authorization: Bearer` 필터가 보호한다. 수신기가 비활성
상태이거나 토큰이 누락·중복·불일치하면 본문을 읽기 전에 `401 UNAUTHORIZED`와
`WWW-Authenticate: Bearer`, `Cache-Control: no-store`를 반환한다. WATCH 토큰, 워크스페이스 capability(권한 증표)와 `Account` 세션은
서로 대체할 수 없다.

### 브라우저 인증 API

모든 응답은 `Cache-Control: no-store`를 사용한다. 상태 변경 요청은 서버가 제공한 동적 CSRF
헤더, 정확히 일치하는 `Origin`과 `Sec-Fetch-Site: same-origin`을 함께 요구한다.

| 메서드 | 경로 | 요청 | 성공 응답 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/auth/csrf` | 없음 | `200 {csrfHeaderName, csrfToken}`. 토큰을 준비하기 위해 세션을 만들 수 있음 |
| `GET` | `/api/v1/auth/session` | 없음 | 미인증 `200 {authenticated:false}` 또는 인증 `200 {authenticated:true,accountId,csrfHeaderName,csrfToken}` |
| `GET` | `/api/v1/auth/providers` | 없음 | `200 {providers:["google","naver"],localRegistrationEnabled:true|false,passwordResetEnabled:true|false}`. 구성한 공급자, 새 가입과 재설정 메일 요청 가능 여부를 각각 반환 |
| `GET` | `/api/v1/auth/account` | 없음 | `200 {accountId,displayName,identities:[{provider,email?,emailVerified}]}`. 로그인 계정과 연결된 로그인 수단 조회 |
| `POST` | `/api/v1/auth/local/registrations` | JSON `{email,displayName}` | `202 {verificationRequired:true}`. 계정 존재 여부를 구분하지 않음 |
| `POST` | `/api/v1/auth/local/email-verifications` | JSON `{token,password}` | `204`. 토큰 소비·이메일 검증·최초 자격 증명 생성을 한 트랜잭션으로 완료 |
| `POST` | `/api/v1/auth/local/password-reset-requests` | JSON `{email}` | `202 {accepted:true}`. 계정 존재·검증 여부와 실제 메일 발송 완료를 구분하지 않음 |
| `POST` | `/api/v1/auth/local/password-resets` | JSON `{token,password}` | `204`. 토큰 소비·비밀번호 변경·기존 세션 버전 무효화를 한 트랜잭션으로 완료. 자동 로그인 없음 |
| `POST` | `/api/v1/auth/local/password-changes` | JSON `{currentPassword,newPassword}` | `204`. 현재 비밀번호 확인·변경·기존 세션 버전 무효화를 한 트랜잭션으로 완료하고 현재 세션도 종료 |
| `POST` | `/api/v1/auth/local/session` | 폼 `{email,password}` | `204`. 인증 성공 시 세션 ID 교체 |
| `POST` | `/api/v1/auth/logout` | 본문 없음 | `204`. 현재 세션과 `JSESSIONID` 무효화 |
| `POST` | `/api/v1/auth/session-revocations` | 본문 없음 | `204`. 계정 세션 버전을 증가시키고 현재 세션과 `JSESSIONID`도 즉시 무효화 |

가입 이메일은 최대 320자, 표시 이름은 최대 100자다. 최초 비밀번호는 12~128자이며 기본 저장은
Spring Security `DelegatingPasswordEncoder`의 PBKDF2 형식을 사용한다. 존재하지 않는 이메일,
미검증 신원과 잘못된 비밀번호는 모두 `401 INVALID_CREDENTIALS`로 일반화한다. 가입·검증·
로그인은 IP와 정규화한 식별자 단위 요청률 제한을 적용하고 초과 시 `429 AUTH_RATE_LIMITED`와
`Retry-After`를 반환한다.

재설정 이메일도 최대 320자, 토큰은 32~512자, 새 비밀번호는 12~128자다. 인증된 자체 이메일
계정만 메일을 발급하며 Google·Naver 신원이나 미인증 계정에는 발급하지 않는다. 링크는 발급 후
30분 동안 한 번만 사용할 수 있고, 아직 유효한 재설정 요청이 있으면 교체·재발송하지 않는다.
가입과 재설정의 메일 요청은 IP당 시간당 12회·정규화 이메일당 시간당 3회의 제한을 공유한다.
토큰 제출도 기존 검증 제한(IP당 10분당 30회·토큰당 10분당 5회)을 공유한다.
`BATON_AUTH_PASSWORD_RESET_ENABLED=false`이면 새 요청은 `503 EMAIL_VERIFICATION_UNAVAILABLE`이며
이미 발급한 링크의 제출은 허용한다. 토큰 오류는 `400 PASSWORD_RESET_INVALID`, 일시적인 DB 장애는
`503 IDENTITY_TEMPORARILY_UNAVAILABLE`이다. 성공·인증 오류 응답은 `Cache-Control: no-store`다.

`GET /api/v1/auth/account`는 인증된 계정의 표시 이름과 연결된 로그인 수단을 반환한다. `provider`는
`google`, `naver`, `local_email` 중 하나이며 공급자가 이메일을 제공하지 않으면 `email`은 `null`이다.
이 조회 결과는 공개 계정 연결·병합 기능을 제공하거나 이메일을 계정 식별자로 승격하지 않는다.

로그인 상태의 자체 이메일 비밀번호 변경은 현재 비밀번호 최대 128자와 새 비밀번호 12~128자를 받는다.
현재 비밀번호는 Spring Security 인코더로 확인하며 불일치는 `400 CURRENT_PASSWORD_INVALID`, 자체 이메일
자격 증명이 없는 계정은 `409 LOCAL_PASSWORD_UNAVAILABLE`이다. 성공하면 아직 사용하지 않은 비밀번호
재설정 링크를 폐기하고 모든 기존 계정 세션을 무효화한다. 명시적인 전체 세션 종료도 같은 세션 버전
경계를 사용한다. 두 작업은 현재 `JSESSIONID`와 보안 컨텍스트를 즉시 제거하고 자동 로그인하지 않는다.

메일 링크 재설정 뒤 기존 계정 세션과 다른 기기의 비밀번호 변경·전체 종료 이전 세션은 다음 요청부터
인증으로 인정하지 않는다. 세션 조회는
`200 {authenticated:false}`, 계정 인증이 필요한 API는 `401 AUTHENTICATION_REQUIRED`로 응답한다.
세션 확인 중 DB 장애는 `503 IDENTITY_TEMPORARILY_UNAVAILABLE`로 실패하고 세션 자체는 보존한다.
이미 처리 중인 요청을 취소하지 않으며 팀 공유 접근 키와 발급된 ROUND 참여권도 폐기하지 않는다.

신원 저장소 잠금 경합이나 일시적 인프라 장애로 가입·검증·자체 이메일 로그인을 처리하지
못하면 성공이나 잘못된 자격 증명처럼 숨기지 않고 `503 IDENTITY_TEMPORARILY_UNAVAILABLE`을 반환한다.
이메일 중복처럼 안전하게 식별한 의미적 충돌만 계정 열거를 막기 위해 등록 `202`로 일반화한다.

OAuth 시작 경로는 `/oauth2/authorization/google`, `/oauth2/authorization/naver`, 콜백은
`/login/oauth2/code/google`, `/login/oauth2/code/naver`다. 구성되지 않은 공급자는 노출하지 않고,
성공 뒤 `/login`으로 리디렉션한다. 콜백 실패는 `ErrorResponse` JSON을 반환하지 않고 다음
고정 응답으로 수렴한다.

| 실패 분류 | 응답 |
| --- | --- |
| 일반 OAuth 실패 | `302 Location: /login?oauthError=login_failed` |
| 신원 저장소 잠금 경합·일시적 인프라 장애 | `302 Location: /login?oauthError=temporarily_unavailable` |

두 응답은 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 사용한다. 공급자가 보낸 오류
코드·설명·URI와 내부 예외 상세를 리디렉션 URL이나 본문에 반영하지 않는다. 프런트는 두
`oauthError` 값만 허용 목록에 두어 미인증 로그인 양식에서 한 번 안내하고, 즉시
`history.replaceState`로 해당 쿼리만 지운다. `returnTo`, 그 밖의 쿼리와 해시, 같은 탭에 기억한
안전한 인증 복귀 경로는 유지한다. 알 수 없는 값은 안내하지 않고 동일하게 지우며 새로고침으로
안내를 재생하지 않는다.

토큰, 사용자 정보와 Google JWK 외부 호출은 BATON이 지정한 연결·읽기 시간 초과를 사용한다. 이메일
스냅샷을 근거로 계정을 자동 병합하지 않으며 최근 재인증·수명이 짧은 연결 의도 계약이 생기기
전에는 공개 계정 연결 API를 제공하지 않는다.

### `Account` 멤버십과 ROUND 관리 API

다음 API는 `Account` 세션과 기존 워크스페이스 접근 키를 모두 요구한다. `Account` 세션은
호출 주체를 증명하고 접근 키는 전환 기간의 팀 관리 capability(권한 증표)를 증명한다. 상태 변경 요청은
동적 CSRF와 정확한 동일 출처도 함께 요구하지만, 현재 연결 상태와 활성 방 매핑 GET은
CSRF 없이 조회한다.

| 메서드 | 경로 | 요청 | 성공 응답 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/account-memberships/current?teamId={teamId}` | 헤더 `X-Baton-Access-Key`, 본문 없음 | 미연결 `200 {claimed:false}` 또는 연결 `200 {claimed:true,accountId,teamId,memberId,claimedAt}` |
| `POST` | `/api/v1/account-membership-claims` | 헤더 `X-Baton-Access-Key`, JSON `{expectedAccountId,teamId,seasonId,memberId}` | `200 {accountId,teamId,memberId,claimedAt}` |
| `GET` | `/api/v1/round-room-mappings?teamId={teamId}&seasonId={seasonId}` | 헤더 `X-Baton-Access-Key`, 본문 없음 | `200 {mappings:[{roomId,teamId,seasonId,resourceId,createdAt,endedAt:null}]}`; 활성 매핑이 없으면 `mappings:[]` |
| `POST` | `/api/v1/round-room-mappings` | 헤더 `X-Baton-Access-Key`, JSON `{teamId,seasonId,resourceId}` | `200 {roomId,teamId,seasonId,resourceId,createdAt,endedAt:null}` |
| `DELETE` | `/api/v1/round-room-mappings/{roomId}` | 헤더 `X-Baton-Access-Key`, 본문 없음 | `200 {roomId,teamId,seasonId,resourceId,createdAt,endedAt}` |

`expectedAccountId`는 사용자가 연결을 확인한 화면의 계정 UUID이며 필수다. 서버는 요청의 인증 주체와
다르면 저장 로직을 호출하기 전에 `409 ACCOUNT_MEMBERSHIP_CONFLICT`로 거부한다. 해당 필드를
생략한 이전 요청은 `400 INVALID_INPUT`이다. 연결할 실제 계정은 항상 서버의 인증 주체로 결정하며,
클라이언트가 보낸 ID를 권한으로 사용하지 않는다. 프런트엔드와 직접 호출자는 이 필드를 함께 배포해야 한다.

멤버십 연결은 활동 중인 같은 팀 `Member`만 허용하고 `(accountId,teamId)`와 `memberId`를 각각
하나의 연결로 제한한다. 현재 연결 조회는 팀 범위 접근 키를 먼저 검증하고, 연결이 없으면 오류가
아닌 정확한 `claimed:false`를 반환한다. 구성원 활동이 종료되어도 영속적인 연결 사실은
`claimed:true`로 남으며 종료 시즌에서도 이 연결 이력 조회는 허용한다. 신규 연결은 종료 시즌의
읽기 전용 경계에서 거부하고 ROUND 참여 가능성은 별도 활동 중인 `Member` 규칙으로 판단한다. 방 매핑은
해당 팀·시즌의 역할 자료만 연결하며 활성 자료와 방 ID를 각각 하나로 제한한다. 현재
매핑 목록 조회는 팀 접근 키와 활동 중인 멤버십을 한 번 확인한 뒤 해당 팀·시즌의
서버 영속 매핑을 한 번에 조회해 권위로 반환한다. 브라우저 `sessionStorage`는 ROUND 입장 힌트일
뿐 조회 결과를 대체하지 않는다.
종료한 방 ID의 삭제 표식은 영구 보존하고 재사용하지 않는다.

### BRIEF 최신 에디션과 생성 API

다음 API는 `Account` 세션, 활동 중인 같은 팀 멤버십과 기존 워크스페이스 접근 키를 모두
요구한다. 생성 요청은 동적 CSRF와 정확한 동일 출처도 함께 요구한다.

| 메서드 | 경로 | 요청 | 성공 응답 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions/latest` | 헤더 `X-Baton-Access-Key`, 선택적 `If-None-Match`, 본문 없음 | `200` BRIEF 불변 에디션 전체 표현 또는 일치하는 `304` |
| `POST` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions` | 헤더 `X-Baton-Access-Key`, 본문 없음 | 새 생성 `201`, 같은 불변 상태 재사용 `200`과 생성 실행 요약 |

최신 조회는 BRIEF가 저장한 `ETag`를 유지한다. 생성은 BATON이 시즌 시간대의 현재 월요일과
완료된 BRIEF outbox 최대 ID를 고정한 V27 실행 기록을 먼저 사용한다. 새 생성 `201`은 최신
조회 경로를 `Location`으로 반환한다. 모든 성공 응답은 `Cache-Control: no-store`다. 세부
권한, 실행 상태와 BRIEF 서비스 결과 분류는 PRD-0008을 따른다.

### ROUND 참여권과 JWK

`POST /round/rooms/{roomId}/participation-grant/refresh`는 `Account` 세션, CSRF와 정확히 일치하는
동일 출처를 요구한다. 요청 본문은 생략하거나 서버 권위 매핑을 재확인할
`{teamId,seasonId,resourceId}` 세 필드만 보낼 수 있다. 본문을 생략하면 `Content-Type`도 보내지
않는다. 성공은 `200 {expiresAt,refreshAfterSeconds}`와 방 경로에만 적용되는
`__Secure-round_access` `Secure`·`HttpOnly`·`SameSite=Strict` 쿠키를 반환한다. JWT는 본문에 노출하지
않으며 수명은 300초, 갱신 지연은 240초다.

`GET /.well-known/round-participation-jwks.json`은 공개 RSA JWK 집합을
`application/jwk-set+json`, `Cache-Control: max-age=60, public`으로 반환한다. 비공개 RSA 필드는
노출하지 않는다.

### 필터 체인 기본 거부

명시하지 않은 요청은 모두 거부한다. `Account` 세션이 필요한 ROUND 관리·참여권 경로의 미인증은
`401 AUTHENTICATION_REQUIRED`, CSRF·권한 거부와 다른 기본 거부는
`403 REQUEST_FORBIDDEN` JSON으로 응답한다. 모두 `Cache-Control: no-store`를 유지한다. 허용한
경로의 `ERROR` 디스패치만 MVC까지 전달하고 직접 `/error`를 요청하는 일반 디스패치는 거부한다.

## 9. 아직 계약이 없는 제품 영역

다음 영역은 제품 기준선에는 포함되지만 HTTP 경로, 요청·응답 DTO와 상태값이 아직 확정되지 않았다.

- 모든 제품 기록의 영구 삭제
- 계정 비활성화·탈퇴, 관리자에 의한 세션 강제 만료, 단계 강화 신원 연결·병합, 초대와 세부 권한·감사 이력
- 지연·역할 공백을 전달할 외부 알림 채널과 선호·전달 결과

이 영역의 API를 추가할 때는 구현, 이 문서와 REST Docs 계약 테스트를 같은 변경에서 갱신한다.

## 10. 계약 검증

`SystemStatusRestDocsTest`, `WorkspaceRestDocsTest`, `WatchHealthEventRestDocsTest`, `AuthRestDocsTest`, `RoundAuthorizationRestDocsTest`와 `BriefEditionRestDocsTest`가 현재 애플리케이션 HTTP 계약과 스니펫을 검증한다. 성공 응답과 테스트가 명시한 대표 오류 응답은 `restdocs-api-spec` 리소스로도 기록하며, 같은 HTTP 오퍼레이션의 문서 식별자는 안정적인 `operationId` 접두사를 공유한다. 공개·캐시 가능한 `/.well-known/round-participation-jwks.json`을 제외한 모든 리소스는 실제 `X-Request-ID` 응답을 검증하고 디스크립터로 남기며, 생성 계약 검사도 같은 예외를 명시적으로 고정한다. Caddy가 애플리케이션보다 먼저 만드는 413과 업스트림 장애 502/503의 헤더·로그 상관관계는 프로덕션 런타임 스모크로 검증한다.

```bash
./gradlew --no-daemon :adapter-in-web:restDocsTest
```

일반 웹 모듈 테스트에서는 `restdocs` 태그를 제외하고, `check`가 `restDocsTest`를 별도로 실행한다. 따라서 전체 빌드에도 현재 REST Docs 계약 검증이 포함된다.

```bash
./gradlew --no-daemon build
```

기계 판독 가능한 계약과 프런트 타입은 다음 단일 흐름으로 생성한다.

```text
MockMvc + REST Docs
  → restdocs-api-spec
  → 결정적 정렬 + 계약 정규화
  → docs/api/openapi3.yaml
  → openapi-typescript
  → frontend/src/generated/api.ts
```

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

두 생성 파일은 프런트 단독·Docker 빌드에서도 Java 도구 체인을 요구하지 않도록 저장소에 추적한다. 직접 수정하지 않고 `generateApiContract`로 갱신한다. 정규화 계층은 생성기가 누락하는 요청 본문 필수성, Jakarta Validation, UUID·날짜 형식, 인증 세션의 정확한 두 응답 변형과 필수이면서 `null`을 허용하는 응답을 보정하며 OpenAPI 서버를 동일 출처 `/`로 유지한다. API 경로, 요청·응답 DTO, 헤더, 오류 상태나 열거형을 바꾸면 구현·REST Docs 디스크립터·이 문서와 두 생성 파일을 같은 변경에 포함한다. `checkApiContract`는 REST Docs에서 재생성한 OpenAPI와 추적 파일의 바이트 차이와 OpenAPI에서 재생성한 TypeScript 타입의 드리프트를 거부한다. 오퍼레이션별 경로·메서드·본문·헤더·상태와 공통 헤더는 실제 MockMvc REST Docs 계약 테스트와 디스크립터가 검증하며 별도 수기 목록이나 의미 검증기로 복제하지 않는다. Spring Security가 직접 처리하는 자체 세션·로그아웃도 실제 필터 체인 기반 REST Docs로 생성 계약에 포함하고, OAuth 시작·콜백 경로만 실제 필터 체인 보안 통합 테스트로 고정한다. 프런트 API 함수는 생성된 `paths`로 URI 템플릿과 HTTP 메서드 조합까지 검증한다.

## 11. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [BATON–WATCH 역할 자료 감시 계약](../0004_watch-integration-contract/spec.md)
- [테스트 전략](../../ADR/0002_test-strategy/adr.md)
- [첫 파일럿 자체 호스팅 배포](../../ADR/0003_pilot-self-hosted-deployment/adr.md)
- [테스트 기반 API 계약 생성](../../ADR/0004_test-derived-api-contract/adr.md)
- [공유 콘텐츠의 낙관적 수정 충돌](../../ADR/0005_optimistic-content-updates/adr.md)
- [루틴 정의와 회차 실행 분리](../../ADR/0006_routine-definition-and-round-execution/adr.md)
- [결정과 바통의 가역 보관](../../ADR/0007_reversible-record-archive/adr.md)
- [운영 회차 정정과 가역 보관](../../ADR/0008_revisable-round-lifecycle/adr.md)
- [서버 요청 시간 예산](../../ADR/0009_server-request-time-budget/adr.md)
- [구성원 활동 종료와 참조 보존](../../ADR/0010_reversible-member-lifecycle/adr.md)
- [시즌 종료와 다음 시즌 전환](../../ADR/0011_season_lifecycle/adr.md)
- [시즌 시간대와 수렴형 회차·마감 자동화](../../ADR/0012_round_schedule_and_deadline_automation/adr.md)
- [반복 루틴 정의의 가역 보관](../../ADR/0014_reversible-routine-archive/adr.md)
- [역할 바통 전달 생명주기](../../ADR/0013_role_handoff_lifecycle/adr.md)
- [WATCH 트랜잭셔널 아웃박스와 수렴형 동기화](../../ADR/0015_watch-transactional-outbox/adr.md)
- [WATCH 상태 변경 이벤트 트랜잭셔널 인박스](../../ADR/0016_watch-health-event-transactional-inbox/adr.md)
- [BATON 경유 BRIEF 에디션 조회와 생성](../0008_brief-edition-query-and-generation/spec.md)
- [BRIEF 조회·생성 애플리케이션 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
