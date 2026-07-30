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
- 시즌의 모임 날짜와 로컬 시각은 해당 시즌의 IANA `timeZone`으로 해석하고, 계산을 마친 예정·마감 시각은 UTC instant로 반환한다.
- 식별자는 클라이언트가 형식이나 정렬 의미를 추론하지 않는 불투명한 값으로 다룬다.
- HTTP DTO와 application 결과 타입을 분리한다.
- 컨트롤러는 요청 검증과 변환을 담당하고 업무 규칙은 application 또는 domain에 둔다.
- 기존 필드의 의미를 바꾸거나 제거하는 변경은 새 버전 또는 명시적 호환 전략 없이 진행하지 않는다.
- 모든 `/api/v1` 성공·오류 응답은 본문 유무와 관계없이 `X-Request-ID` 헤더를 포함한다.

페이지네이션 형식은 이를 필요로 하는 실제 API가 설계될 때 확정한다. 워크스페이스·콘텐츠 생성과 접근 키 변경의 멱등 계약 및 동시 충돌은 아래 파일럿 API 절에서 정의한다.

`X-Request-ID`는 서버가 요청마다 생성하는 UUID 형태의 진단 식별자다. 클라이언트는 값을 불투명하게 다루며 운영 문의와 서버 로그 상관관계에만 사용한다. 외부 요청의 같은 이름 헤더는 신뢰하거나 재사용하지 않고, 이 값으로 인증·권한·멱등성 판단 또는 metrics label을 만들지 않는다. Spring이 처리한 응답은 애플리케이션이 생성한 값을 유지하고, 요청 본문 제한이나 upstream 장애처럼 Caddy가 직접 응답할 때만 Caddy가 누락된 헤더를 자체 UUID로 채운다.

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
- `Location`: 생성한 팀·시즌의 workspace 조회 경로
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
  "accessKey": "최초 생성 또는 동일 멱등 재생에서만 반환하는 공유 키"
}
```

클라이언트는 식별자를 불투명한 값으로 다룬다. `accessKey` 원문은 아래의 동일 멱등 요청 재생 외에는 다시 조회할 수 없으며 서버는 접근 키와 멱등 키의 SHA-256 기반 해시만 저장한다.

같은 `Idempotency-Key`와 의미가 같은 정규화 요청을 다시 보내면 새 팀을 만들지 않고 최초의 `teamId`, `seasonId`, `accessKey`를 같은 `201 Created` 응답으로 반환한다. 문자열 앞뒤 공백과 구성원 입력 순서는 정규화한다. 같은 키를 다른 요청에 사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. 동일 키가 동시에 처리되어 DB 고유 제약에서 충돌하면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환하므로 클라이언트는 잠시 뒤 같은 키와 요청으로 재시도한다.

멱등 키는 응답 유실을 복구할 때까지 접근 자격과 같은 수준으로 다루고 다른 생성 요청에 재사용하지 않는다. 브라우저 클라이언트는 생성 요청 전에 멱등 키를 내구 저장하고 다시 읽어 확인해야 하며, 저장할 수 없으면 요청하지 않는다. 접근 키가 회전 또는 운영자 복구된 뒤 과거 생성 요청을 재생하면 더 이상 유효하지 않은 옛 키를 반환하는 대신 `409 IDEMPOTENCY_REPLAY_EXPIRED`를 반환한다.

`BATON_WORKSPACE_CREATION_KEY`가 비어 있는 로컬 환경에서는 생성 키 없이 만들 수 있다. 프로덕션 파일럿은 이 설정을 필수로 주입하며, 누락되거나 일치하지 않는 `X-Baton-Creation-Key`에는 `403 WORKSPACE_CREATION_DENIED`를 반환한다.

### 팀·시즌 범위와 접근 키

이후 파일럿 API는 다음 범위를 공유한다.

```text
/api/v1/teams/{teamId}/seasons/{seasonId}
```

운영자 복구 API를 제외한 워크스페이스 조회·변경 요청에는 다음 헤더가 필요하다.

```http
X-Baton-Access-Key: <workspace access key>
```

키가 없거나 올바르지 않으면 `403 Forbidden`과 `WORKSPACE_ACCESS_DENIED`를 반환한다. 팀에 속하지 않는 시즌·구성원 식별자를 다른 팀 경로에 사용할 수 없고, 역할과 그 역할을 참조하는 루틴·결정·바통 항목·역할 바통·자료는 다른 시즌 경로에 사용할 수 없다.

### 워크스페이스 조회

```http
GET /api/v1/teams/{teamId}/seasons/{seasonId}/workspace
```

- 성공 상태: `200 OK`
- 요청 본문: 없음
- `Cache-Control: no-store`

응답은 한 화면을 구성하는 다음 projection을 반환한다.

| 필드 | 내용 |
| --- | --- |
| `team` | `id`, `name` |
| `season` | 요청한 시즌의 `id`, `name`, `startDate`, `endDate`, nullable `endedAt`, nullable `previousSeasonId`, IANA `timeZone`, nullable `roundSchedule` |
| `seasons` | 같은 팀의 서버 권위 시즌 목록. 각 항목은 `season`과 같은 필드를 가짐 |
| `members` | 팀 구성원의 `id`, `name`, `initials`, `tone`, nullable `deactivatedAt` 목록 |
| `roles` | 현재 시즌의 역할 snapshot, 담당자·기간, 책임과 위험 신호 목록 |
| `routines` | 현재 시즌의 반복 루틴 정의와 nullable한 실제 마감 규칙 목록. 완료 상태는 포함하지 않음 |
| `rounds` | 생성 출처·시간 상태·nullable `archivedAt`을 가진 시즌 회차와 회차 생성 시 복사된 실제 마감·루틴 실행 목록 |
| `decisions` | 결정, 서버 생성 시각, 작성자 식별자·이름, 관련 역할과 nullable `archivedAt` 목록 |
| `handoffItems` | 역할별 바통 항목, 완료 여부와 nullable `archivedAt` 목록 |
| `resources` | 역할별 자료의 제목, 외부 링크와 선택 설명 목록 |
| `roleHandoffs` | 역할별 바통 준비·전달·수락·취소 이력과 전달 시점 준비도 스냅샷 목록 |

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

구성원의 `deactivatedAt`은 활동 중이면 `null`, 활동 종료 상태이면 서버 `Clock`으로 생성한 UTC ISO 8601 instant다. workspace projection은 기존 역할·결정 참조를 표시할 수 있도록 두 상태의 구성원을 모두 반환한다. 시즌의 `endedAt`은 운영 중이면 `null`, 운영자가 명시적으로 종료했으면 서버 `Clock`으로 생성한 UTC ISO 8601 instant다. `endDate`가 지났다는 이유만으로 자동 종료하지 않는다. `previousSeasonId`는 최초 시즌이면 `null`, 다음 시즌이면 원본 시즌 UUID다. `timeZone`은 최대 64자의 유효한 IANA 식별자이고 기존·최초 시즌의 기본값은 `Asia/Seoul`이다. 시즌 목록은 시작일과 UUID 내림차순으로 정렬한다.

`roundSchedule`이 설정되지 않았으면 `null`이다. 설정된 일정은 `firstMeetingDate`, 시즌 시간대 기준 `meetingTime`, `WEEKLY` 또는 `BIWEEKLY`인 `recurrence`, `0..30`의 `generationLeadDays`, `enabled`, 서버가 다음에 처리할 `nextOccurrenceDate`를 가진다.

루틴 정의 응답의 `phase`는 `BEFORE`, `DURING`, `AFTER` 중 하나이고 완료 상태는 없다. `deadlineDayOffset`과 `deadlineTime`은 둘 다 `null`이거나 함께 값이 있으며, 날짜 오프셋은 모임 날짜 기준 `-30..30`일이다. `rounds[].routineExecutions[]`는 생성 당시 루틴의 `routineId`, `title`, `phase`, `dueLabel`, `ownerRoleId`, `detail`을 스냅샷으로 보존하고 `status`를 `WAITING` 또는 `DONE`으로 가진다. 실행의 `deadlineAt`은 실제 마감 규칙이 없으면 `null`, 있으면 모임 날짜·오프셋·시즌 시간대로 계산한 UTC ISO 8601 instant다. `timingStatus`는 `UNSCHEDULED`, `PLANNED`, `IN_PROGRESS`, `OVERDUE`, `COMPLETED` 중 하나다.

회차의 `origin`은 `MANUAL` 또는 `AUTOMATIC`이고 자동 회차만 원래 발생일 `scheduledOccurrenceDate`와 시즌 시간대의 모임 시각을 UTC로 변환한 `scheduledAt`을 가진다. 회차 `timingStatus`는 `PLANNED`, `IN_PROGRESS`, `OVERDUE`, `COMPLETED` 중 하나다. 새로 생성하거나 수정하는 수동 회차의 `meetingDate`는 필수지만, V5 이전의 루틴 상태를 이관한 `회차 도입 이전 기록`은 실제 날짜를 알 수 없어 운영자가 수정할 때까지 응답에서 `null`이다. 회차의 `archivedAt`은 활성 상태에서 `null`, 보관 상태에서 서버 `Clock`으로 생성한 UTC ISO 8601 instant다. workspace projection은 활성·보관 회차를 모두 반환하며 프런트엔드는 일반 운영 선택과 완료 계산에서는 활성 회차만 사용하고 보관 회차는 복원 가능한 보관함으로 나눈다. 결정의 `createdAt`은 서버 `Clock`으로 생성한 UTC ISO 8601 instant이고 `authorMemberId`는 수정 폼과 다른 클라이언트가 작성자를 이름으로 역추론하지 않게 하는 식별자다. 결정과 바통 항목의 `archivedAt`도 같은 활성·보관 표현을 사용한다. 바통 항목의 `category`는 `RESPONSIBILITY`, `ROUTINE`, `RESOURCE`, `ADVICE` 중 하나다. `resources[]`는 `id`, `roleId`, `title`, `url`, nullable `description`을 가진다.

`roleHandoffs[]`는 `id`, `roleId`, 이전·다음 담당자 `fromMemberId`·`toMemberId`, 이전 담당 시작일 `outgoingAssignmentStartDate`·nullable 종료일 `outgoingAssignmentEndDate`, 수락 뒤 적용할 `incomingAssignmentStartDate`·nullable `incomingAssignmentEndDate`, `status`, 상태별 시각과 확인자, 전달 시점 준비도 스냅샷을 가진다. 상태는 `PREPARING`, `TRANSFERRED`, `ACCEPTED`, `CANCELLED` 중 하나다. `preparedAt`은 항상 존재하고 `transferredAt`, `acceptedAt`, `cancelledAt`과 각 `transferredByMemberId`, `acceptedByMemberId`, `cancelledByMemberId`는 해당 전환 전까지 `null`이다. `activeItemCount`, `incompleteItemCount`, `resourceCount`도 전달 전에는 `null`이고 전달 뒤에는 당시 수치를 보존한다. `warningAcknowledged`는 전달 시 준비도 경고를 명시적으로 확인했는지 나타낸다. 완료·취소한 이력도 projection에 남으며, 역할마다 `PREPARING` 또는 `TRANSFERRED` 상태의 열린 이력은 하나만 존재한다.

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

`timeZone`은 최대 64자의 유효한 IANA 식별자다. `firstMeetingDate`는 시즌 기간 안에 있어야 하고 `meetingTime`은 시즌 시간대 기준 ISO 8601 로컬 시각이다. `recurrence`는 `WEEKLY` 또는 `BIWEEKLY`, `generationLeadDays`는 `0..30`이다. `enabled: false`는 다음 발생일 커서를 보존한 채 자동 생성을 일시 중지한다. 설정을 바꿔도 이미 처리한 커서를 과거로 되감지 않고 새 반복 주기의 다음 가능한 날짜로 정렬한다.

일정을 활성화하려면 시즌의 모든 루틴에 `deadlineDayOffset`과 `deadlineTime`이 있어야 한다. 활성 일정이 있는 동안에는 마감이 없는 루틴을 새로 만들거나 기존 루틴의 마감 규칙을 제거할 수 없다. 회차가 하나라도 생성된 뒤에는 시즌 시간대를 바꿀 수 없다. 종료 시즌에서는 일정을 바꿀 수 없다.

성공 상태는 `200 OK`이고 `timeZone`과 `roundSchedule`을 포함한 갱신된 시즌 전체 표현을 반환한다. `roundSchedule.nextOccurrenceDate`는 서버가 다음에 처리할 발생일이다. 입력·IANA 시간대·시즌 기간·마감 규칙 또는 기존 회차 뒤 시간대 변경 제한 위반은 `400 INVALID_INPUT`, 접근 실패는 `403 WORKSPACE_ACCESS_DENIED`, 팀·시즌이 없으면 해당 `404`, 종료 시즌이나 겹친 변경은 `409 SEASON_ENDED` 또는 `409 WORKSPACE_CONTENT_CONFLICT`다.

종료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/ending
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "ended": true }
```

`true`는 최초 종료 UTC instant를 `endedAt`에 기록하고 같은 상태를 반복해도 그 시각을 유지한다. `false`는 `endedAt`을 `null`로 되돌린다. 후속 시즌이 있거나 같은 팀의 다른 활성 시즌이 있으면 다시 열 수 없다. 성공 상태는 `200 OK`이고 갱신된 시즌을 반환한다.

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

새 시즌 시작일은 원본 시즌 종료일보다 늦어야 한다. 두 선택 목록은 각각 최대 100개이고 중복이나 다른 시즌 식별자를 허용하지 않는다. 선택한 루틴의 담당 역할도 `copyRoleIds`에 포함해야 한다.

서버는 한 transaction에서 원본 시즌을 종료하고 후속 시즌과 선택한 정의를 만든다. 역할은 이름·목적·책임·위험 신호를 새 UUID로 복사하되 현재·다음 담당자와 담당 기간을 비운다. 루틴도 실제 마감 규칙과 함께 새 UUID로 복사하고 새 역할 UUID를 참조한다. 새 시즌은 원본 시즌의 `timeZone`을 이어 받지만 `roundSchedule`, 발생 커서, 회차·실행, 결정, 바통 항목, 역할 바통 이력과 역할 자료는 복사하지 않고 원본 시즌에 남긴다. 같은 원본 시즌에는 후속 시즌을 하나만 만들 수 있고 한 팀에는 종료되지 않은 시즌을 하나만 둔다. 열린 역할 바통이 있으면 원본 시즌을 종료하거나 후속 시즌을 만들지 않고 `409 ROLE_HANDOFF_STATE_CONFLICT`를 반환한다.

성공 상태는 `201 Created`이고 `Location`은 새 시즌 workspace 경로다. 응답은 종료된 `sourceSeason`, 생성한 `season`, `sourceRoleId`와 새 `roleId`의 `copiedRoles`, `sourceRoutineId`와 새 `routineId`의 `copiedRoutines`를 반환한다. 같은 멱등 키와 정규화 요청을 재생하면 같은 시즌과 식별자 대응을 반환하며, 역할·루틴 선택 순서는 요청 fingerprint에서 의미 없는 집합으로 정렬한다.

종료 시즌은 workspace 조회, 시즌 전환, 접근 키 회전·복구와 다음 시즌 시작을 허용한다. 구성원·역할·자료·루틴·회차·실행·결정·바통 항목·역할 바통의 생성·수정·완료·보관·복원은 `409 SEASON_ENDED`로 거절한다. 프런트엔드도 같은 경계를 읽기 전용으로 표시하지만 서버 검증이 권위다.

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
  "accessKey": "최초 변경 또는 동일 멱등 재생에서만 반환하는 새 접근 키"
}
```

최초 요청은 현재 접근 키가 유효할 때만 회전할 수 있다. 성공하는 즉시 이전 키와 이전 공유 링크는 더 이상 사용할 수 없으며 새 원문 키는 서버에 저장하지 않는다.

`Idempotency-Key`의 형식은 워크스페이스 생성과 같다. 서버가 키 변경을 커밋한 뒤 응답만 유실된 경우, 클라이언트는 이전 접근 키와 같은 멱등 키로 재시도할 수 있다. 서버는 현재 접근 키 검증보다 동일 작업 재생을 먼저 확인해 같은 새 키를 반환한다. 따라서 완료 전 멱등 키는 접근 자격과 같은 수준으로 보호해야 하며, 브라우저 클라이언트는 이를 내구 저장하고 다시 읽어 확인할 수 없으면 회전 요청을 시작하지 않는다.

서버는 팀별로 이미 사용한 키 변경 멱등 해시를 보관한다. 신규 회전의 멱등 결과와 새 접근 키는 `teamId + Idempotency-Key`로 파생하므로 응답 유실 뒤 같은 팀의 다른 시즌 경로로 재시도해도 같은 결과를 반환한다. 이전 배포에서 시즌 범위로 시작한 변경도 저장된 원래 결과를 호환 재생할 수 있다. 같은 멱등 키로 만든 결과 뒤에 더 최신 키 변경이 완료됐다면 `409 IDEMPOTENCY_REPLAY_EXPIRED`를 반환하며, 폐기된 과거 접근 키를 다시 발급하지 않는다. 서로 다른 회전·복구 요청이 같은 팀에 동시에 반영되려 하면 하나는 `409 WORKSPACE_ACCESS_KEY_CONFLICT`를 받으며, 클라이언트는 최신 접근 상태를 확인한 뒤 새 멱등 키로 명시적으로 다시 시도한다.

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

`Idempotency-Key` 형식, 팀 범위와 응답 유실 재생 규칙은 회전과 같다. 복구 결과 재생도 매번 올바른 `X-Baton-Recovery-Key`를 먼저 검증하며, 회전과 복구에 같은 원문 멱등 키를 사용해도 작업별로 분리된 결과를 만든다.

### 콘텐츠 생성 멱등성

구성원, 역할, 루틴, 회차, 결정, 바통 항목, 역할 자료와 역할 바통 준비를 만드는 여덟 `POST` 요청에는 워크스페이스 생성과 같은 형식의 `Idempotency-Key`가 필수다. 서버는 재생 요청에서도 현재 `X-Baton-Access-Key`를 먼저 검증하며, 팀·시즌·작업 종류별로 멱등 결과를 분리한다. 따라서 같은 원문 키를 다른 작업 종류나 다른 작업 공간에서 독립적으로 사용할 수 있지만, 클라이언트는 각 사용자 의도마다 새 키를 사용한다.

같은 키와 의미가 같은 정규화 요청을 다시 보내면 새 리소스를 만들지 않고 최초에 생성된 리소스의 같은 `id`와 현재 표현을 `201 Created`로 반환한다. 그 사이 구성원의 이름·활동 상태, 회차의 이름·모임 날짜·보관 상태·루틴 실행 상태, 바통 항목의 완료 상태, 결정·바통 항목의 내용이나 보관 상태 또는 역할 바통의 전환 상태가 바뀌었다면 재생 응답에는 현재 상태가 보인다. 보관된 회차·결정·바통 항목도 `archivedAt`이 있는 현재 표현으로 반환되므로 재생 성공을 활성 기록의 재생성으로 해석하지 않는다. 역할 바통 준비 재생도 같은 `role`과 `handoff`의 현재 표현을 반환하며 완료·취소한 이력을 새로 열지 않는다. 회차 생성 뒤 루틴 정의를 추가하거나 수정해도 재생은 최초 회차의 실행 식별자, 구성과 스냅샷을 바꾸지 않는다. 재생 일치 여부는 현재 표현이 아니라 최초 생성 요청의 fingerprint로 판단하므로, 정정된 이름·날짜를 원래 생성 키와 함께 보내면 `409 IDEMPOTENCY_KEY_REUSED`다. 같은 범위·작업의 키를 그 밖의 의미가 다른 요청에 재사용해도 같은 오류를 반환하고, 동일 키 예약이 동시에 충돌하면 `409 IDEMPOTENCY_KEY_CONFLICT`다. 동시 충돌을 받은 클라이언트는 새 키를 만들지 않고 잠시 뒤 같은 키와 같은 요청으로 재시도한다.

요청 fingerprint는 도메인 입력과 같이 문자열 앞뒤 공백과 도메인이 같은 값으로 취급하는 선택적 빈 문자열을 정규화한다. 책임과 관련 역할처럼 순서가 응답에 보존되는 목록은 순서까지 요청 의미에 포함한다. 서버는 원문 멱등 키 대신 작업·팀·시즌으로 범위를 분리한 SHA-256 기반 해시만 저장하며, 멱등 예약과 리소스 생성은 한 트랜잭션에서 커밋하거나 함께 롤백한다.

브라우저 클라이언트는 요청 전에 정규화 요청과 멱등 키를 내구 저장하고 다시 읽어 확인해야 한다. 저장할 수 없거나 브라우저 전체의 미완료 콘텐츠 생성 기록이 20개에 도달하면 새 생성을 전송하지 않는다. 성공 또는 같은 결과의 재생을 확인한 뒤에만 기록을 지우며, 네트워크 오류·서버 오류·동시 충돌·접근 키 오류에는 보존한다. 같은 키의 다른 요청으로 판정되면 해당 기록을 지우고 사용자의 명시적인 새 제출을 요구한다.

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

성공 상태는 `201 Created`이며 응답은 생성된 구성원의 `id`, 정규화한 `name`, 표시용 `initials`, `tone`, nullable `deactivatedAt`을 반환한다. 새 구성원의 `deactivatedAt`은 `null`이다. 같은 `Idempotency-Key`와 같은 정규화 이름을 다시 보내면 구성원을 중복 생성하지 않고 최초 구성원의 같은 `id`와 현재 표현을 `201 Created`로 반환한다.

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

표시 이름은 생성과 같은 정규화·길이·팀별 유일성 규칙을 따른다. 활동 종료 구성원의 이름도 계속 예약되며, 대상 구성원 자신의 현재 이름은 중복으로 보지 않는다. 성공 상태는 `200 OK`이고 같은 UUID, 정정된 이름·이니셜, 기존 활동 상태를 반환한다. 이름은 현재 표시 정보이므로 이를 참조한 기존 역할과 결정의 표시 이름도 새 이름으로 보이지만, 별도의 당시 이름 snapshot을 만들지는 않는다.

활동 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation
X-Baton-Access-Key: <워크스페이스 접근 키>
Content-Type: application/json

{
  "deactivated": true
}
```

`deactivated: true`는 최초 활동 종료 시각을 `deactivatedAt`에 기록하고, `false`는 값을 `null`로 만들어 다시 활성화한다. 같은 상태를 다시 요청해도 최초 활동 종료 시각을 바꾸지 않고 `200 OK`로 현재 표현을 반환한다. 활동 종료는 기존 역할 담당자와 결정 작성자 참조를 바꾸지 않으며 workspace projection에서도 구성원을 제거하지 않는다. 새 역할 담당자와 새 결정 작성자는 활동 중 구성원만 허용한다. 기존 역할·결정 수정은 같은 위치의 기존 활동 종료 구성원 참조를 그대로 유지할 수 있지만, 활동 종료 구성원을 다른 위치나 다른 기록에 새로 배정할 수는 없다.

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

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 역할은 해당 팀·시즌 소속이어야 하고 시즌 안의 이름 중복, 구성원 소속·활동 상태와 담당 기간 규칙을 다시 검증한다. 기존 현재·다음 위치에 있던 활동 종료 구성원 ID를 같은 위치에 유지하는 것은 허용하지만 활동 종료 구성원을 새 위치에 배정할 수는 없다. 자기 자신의 현재 이름은 중복으로 보지 않는다. 응답은 수정된 역할이다. 같은 역할을 먼저 읽은 다른 수정과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 workspace를 다시 확인해야 한다.

열린 역할 바통이 `PREPARING`이면 현재·다음 담당자와 담당 기간은 바통 준비 시점 값으로 고정하지만 역할의 이름·목적·책임·위험 신호는 수정할 수 있다. `TRANSFERRED`이면 수락 또는 취소 전까지 역할 전체를 수정할 수 없다. 위반은 `409 ROLE_HANDOFF_STATE_CONFLICT`다.

### 역할 바통 전달

역할 바통은 바통북의 항목과 별개인 역할 교대 이력이다. 한 역할에는 `PREPARING` 또는 `TRANSFERRED` 상태의 열린 바통을 하나만 둘 수 있고, `ACCEPTED`와 `CANCELLED` 이력은 삭제하지 않고 workspace projection에 남긴다.

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

`TRANSFERRED` 상태에서 준비 당시 다음 담당자 ID를 선언해야 한다. 해당 구성원이 계속 활동 중이고 준비한 담당 기간이 여전히 시즌 안에 있으면, 한 transaction에서 바통을 `ACCEPTED`로 바꾸고 역할의 현재 담당자를 다음 담당자로, `nextMemberId`를 `null`로, 담당 기간을 준비 때 고정한 다음 담당 기간으로 전환한다. 성공 상태는 `200 OK`이며 갱신한 역할과 바통을 함께 반환한다.

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

`roleId`는 요청한 시즌의 역할이어야 한다. `title`은 필수이며 최대 200자, `url`은 사용자 정보가 없는 절대 `http` 또는 `https` 주소이며 최대 2048자다. `description`은 선택이고 최대 1000자다. 성공 상태는 `201 Created`이며 생성된 자료를 반환한다.

BATON 서버는 저장 시 URL 대상을 요청하거나 내용·가용성·신뢰성을 확인하지 않는다.
일반 링크 대상의 접근 권한과 안전성은 사용자가 확인해야 한다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `title`, `url`, `description` 전체 표현을 사용하고 성공 상태는 `200 OK`다. 대상 자료는 요청한 시즌의 역할에 연결되어 있어야 하며 `roleId`를 같은 시즌의 다른 역할로 바꿀 수 있다. 자료가 없거나 다른 시즌 소유이면 `404 ROLE_RESOURCE_NOT_FOUND`, 새 소유 역할이 해당 시즌에 없으면 `404 ROLE_NOT_FOUND`다. 같은 자료 수정 transaction이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 workspace를 다시 확인해야 한다. 생성 대상이나 수정 전·후 소유 역할에 `TRANSFERRED` 바통이 있으면 `409 ROLE_HANDOFF_STATE_CONFLICT`다.

열기:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link
Idempotency-Key: <canonical UUID>
X-Baton-Access-Key: <워크스페이스 접근 키>
Content-Type: application/json
```

```json
{
  "expiresAt": "2026-07-30T12:15:00Z"
}
```

서버는 접근 키와 팀·시즌·자료 소속을 먼저 확인한다. `expiresAt`은 서버 현재 시각보다
미래이고 15분 이하여야 한다. 성공 상태는 `200 OK`이고 공개 navigation URL을 중간
cache에 남기지 않도록 `Cache-Control: no-store`를 반환하며, 응답은 다음 형태다.

```json
{
  "navigationUrl": "https://go.example/l/VOvLShvx93kQpj8x7w2HYQ",
  "routingMode": "BATON_GO",
  "expiresAt": "2026-07-30T12:15:00Z"
}
```

일반 외부 URL이거나 GO 연동이 비활성 상태이면 저장된 URL을 `navigationUrl`로,
`routingMode`를 `DIRECT`, `expiresAt`을 `null`로 반환한다. 설정된 ROUND public
origin과 정확히 같고 userinfo·query·fragment가 없으며
`/room/{canonical-room-id}` 경로인 자료만 GO에 `ROUND` 상대 경로로 전달한다.
GO에는 BATON 접근 키와 원래 전체 URL을 보내지 않는다.
GO 응답은 요청한 target system·path·purpose·활성 기간과 일치하고 폐기되지 않아야 한다.
반환한 short URL도 설정된 GO public origin의 query·fragment·userinfo 없는 canonical
`/l/{code}`여야 하며, 그렇지 않으면 navigation URL로 신뢰하지 않는다. 설정된 ROUND
origin이지만 canonical room 계약을 벗어난 URL은 일반 자료로 직접 열지 않고
`400 INVALID_ROUND_RESOURCE_URL`로 거절한다.

같은 열기 intent를 재시도할 때 클라이언트는 UUID와 `expiresAt`을 모두 재사용한다.
프런트엔드는 성공 URL로 같은 탭에서 referrer 없이 이동하고, 오류가 나면 원본 ROUND
URL로 우회하지 않는다.
canonical UUID가 아니면 `400 INVALID_LINK_IDEMPOTENCY_KEY`, 만료 정책을 벗어나면
`400 INVALID_LINK_EXPIRY`다. GO가 같은 키의 다른 payload 사용을 보고하면
`409 LINK_GATEWAY_CONFLICT`, 연결·인증·응답 계약 실패는
`502 LINK_GATEWAY_UNAVAILABLE`이다. GO 발급 실패를 원본 ROUND URL 직접 열기로
조용히 우회하지 않는다.

### 운영 루틴

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/routines
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청 필드는 `title`, `phase`, `dueLabel`, nullable `deadlineDayOffset`, nullable `deadlineTime`, `ownerRoleId`, `detail`이다. `ownerRoleId`는 요청한 시즌의 역할이어야 한다. `deadlineDayOffset`은 모임 날짜 기준 `-30..30`일이고 `deadlineTime`은 시즌 시간대 기준 ISO 8601 로컬 시각이다. 두 필드는 함께 설정하거나 함께 `null`이어야 한다. `dueLabel`은 사람이 읽는 설명으로 계속 필수이며 서버가 이를 파싱해 마감을 추론하지 않는다. 이 리소스는 반복 정의이므로 완료 상태를 갖지 않으며 성공 상태는 `201 Created`다.

정의 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 루틴은 해당 시즌 소속이고 `ownerRoleId`도 같은 시즌 역할이어야 한다. 제목, 단계, 기한 문구, 실제 마감 규칙, 담당 역할과 상세를 바꾸며 이미 생성한 회차의 실행 스냅샷과 `deadlineAt`은 바꾸지 않는다. 자동 회차 일정이 활성화되어 있으면 마감 규칙을 비울 수 없다. 같은 정의를 수정하는 두 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받아 상대 변경을 덮어쓰지 않는다.

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

서버는 회차 생성 transaction에서 현재 시즌의 모든 루틴 정의를 각각 독립된 실행으로 복사하고 처음 상태를 `WAITING`으로 둔다. 실제 마감 규칙이 있는 실행은 모임 날짜와 시즌 시간대로 UTC `deadlineAt`을 계산해 함께 스냅샷한다. 응답은 회차 `id`, `name`, `meetingDate`, nullable `archivedAt`, `origin`, nullable `scheduledOccurrenceDate`, nullable `scheduledAt`, `timingStatus`와 `routineExecutions`를 반환한다. 이 API로 만든 회차는 `origin: MANUAL`이고 두 일정 메타데이터는 `null`이며 새 회차의 `archivedAt`은 `null`이다. 각 실행은 `id`, `roundId`, 원본 `routineId`, 스냅샷 필드, `status`, nullable `deadlineAt`과 `timingStatus`를 가진다. 회차 생성 뒤 추가하거나 수정한 루틴은 기존 회차에 반영되지 않고 다음에 만드는 회차부터 반영된다. 같은 멱등 요청을 재생하면 실행을 다시 만들지 않고 최초 회차 식별자와 현재 이름·날짜·보관·실행 상태를 반환한다.

활성화한 주간·격주 일정은 별도 사용자 요청 없이 선행 생성일에 자동 회차를 만든다. 자동 회차는 `origin: AUTOMATIC`, 반복 일정의 원래 발생일 `scheduledOccurrenceDate`와 모임 시각의 UTC `scheduledAt`을 보존한다. `(seasonId, scheduledOccurrenceDate)`는 유일하므로 scheduler가 같은 발생을 다시 처리해도 회차를 중복 생성하지 않는다.

실행 `timingStatus`는 다음 규칙으로 조회 시 계산한다.

- 저장 상태가 `DONE`이면 `COMPLETED`
- 완료되지 않았고 `deadlineAt`이 없으면 `UNSCHEDULED`
- 시즌 현지 날짜가 마감 현지 날짜보다 이르면 `PLANNED`
- 마감 현지 날짜이지만 현재 instant가 `deadlineAt` 전이면 `IN_PROGRESS`
- 완료되지 않았고 현재 instant가 `deadlineAt`과 같거나 지났으면 `OVERDUE`

회차 `timingStatus`는 모든 실행이 완료되면 `COMPLETED`, 하나라도 지연이면 `OVERDUE`, 진행 중이거나 일부 완료된 실행이 있으면 `IN_PROGRESS`, 그 밖에는 `PLANNED`다. 실행이 없는 자동 회차는 `scheduledAt`에 도달하면 `IN_PROGRESS`다.

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

보관된 회차는 수정할 수 없으며, 없거나 다른 시즌 소속인 회차와 같은 `404 SEASON_ROUND_NOT_FOUND`를 반환한다. 자동 회차의 이름과 날짜는 발생 identity이므로 활성 상태에서도 직접 수정할 수 없고 `400 INVALID_INPUT`이다. 이름 유일성은 보관 여부와 무관하게 시즌 전체에 적용되므로 보관된 회차의 이름도 예약된다. 다른 회차와 이름이 겹치면 `409 ROUND_NAME_CONFLICT`, 같은 회차의 수정·보관이 겹쳐 늦은 저장이 발생하면 `409 WORKSPACE_CONTENT_CONFLICT`다.

회차 보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

`archived: true`는 서버 `Clock`의 UTC instant를 `archivedAt`에 기록하고, `false`는 `archivedAt`을 `null`로 되돌려 복원한다. 이미 같은 상태라면 최초 보관 시각 또는 활성 상태를 유지한다. 수동·자동 회차 모두 보관·복원할 수 있다. 성공 상태는 `200 OK`이고 실행 목록을 포함한 회차 전체를 반환한다. 보관·복원은 회차나 루틴 실행을 삭제·재생성하지 않으므로 실행 식별자, 생성 출처·예정 발생일, 마감 스냅샷과 완료 상태를 그대로 보존한다. 보관된 회차도 workspace projection의 `rounds`에 남고 프런트엔드는 활성 운영 목록과 보관함을 나눠 표시한다.

대상이 없거나 다른 시즌 소속이면 `404 SEASON_ROUND_NOT_FOUND`, 겹친 회차 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다. 보관·복원은 회차 행의 배타적 쓰기 잠금, 실행 완료는 같은 행의 공유 읽기 잠금을 사용한다. 따라서 보관과 완료는 서로 직렬화되지만 완료 요청끼리는 부모 잠금을 함께 통과하고 같은 실행을 겹쳐 바꾸면 실행의 낙관적 잠금으로 한쪽이 `409 WORKSPACE_CONTENT_CONFLICT`가 된다. 보관이 먼저 반영되면 기다리던 완료 요청은 보관 상태를 확인한 뒤 `404 SEASON_ROUND_NOT_FOUND`로 끝나며, 완료가 먼저 반영되면 보관은 그 완료 상태를 보존한다.

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

요청은 생성과 같은 `title`, `reason`, `alternative`, `authorMemberId`, `roleIds` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 `createdAt`은 최초 생성 시각을 유지한다. 대상 결정은 요청 시즌 소속이어야 하고 보관되지 않은 활성 기록이어야 한다. 작성자의 팀 소속과 활동 상태, 관련 역할의 같은 시즌 소속, 최소 개수와 중복 금지를 다시 검증한다. 기존 활동 종료 작성자 ID를 그대로 유지하는 것은 허용하지만 다른 활동 종료 구성원으로 바꿀 수는 없다. 대상이 없거나 다른 시즌 소속이거나 보관 상태이면 `404 DECISION_NOT_FOUND`다. 같은 결정을 먼저 읽은 다른 수정·보관 transaction과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

`archived: true`는 서버 `Clock`의 UTC instant를 `archivedAt`에 기록하고, `false`는 `archivedAt`을 `null`로 되돌려 복원한다. 이미 같은 상태라면 기존 보관 시각을 바꾸지 않는다. 성공 상태는 `200 OK`이고 변경된 결정 전체를 반환한다. 보관은 영구 삭제가 아니며 보관된 결정도 workspace projection의 `decisions`에 남는다. 프런트엔드는 활성 기록과 보관함을 나눠 표시하고, 보관 상태에서는 내용 수정보다 복원만 제공한다. 대상이 없거나 다른 시즌 소속이면 `404 DECISION_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

### 바통 항목

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청 필드는 `roleId`, `label`, `category`다. `roleId`는 요청한 시즌의 역할이어야 한다. 새 항목은 서버에서 항상 미완료로 시작하고 `archivedAt`은 `null`이다. 성공 상태는 `201 Created`다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `label`, `category` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 기존 `completed` 값은 유지한다. 대상 항목은 요청한 시즌의 역할에 연결된 활성 기록이어야 하고 새 `roleId`도 같은 시즌 역할이어야 한다. 대상이 없거나 다른 시즌 소유이거나 보관 상태이면 `404 HANDOFF_ITEM_NOT_FOUND`, 새 소유 역할이 없으면 `404 ROLE_NOT_FOUND`다. 같은 항목을 먼저 읽은 수정·완료·보관 transaction과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 갱신된 항목을 반환한다. 보관된 항목은 완료 상태를 바꿀 수 없으며 `404 HANDOFF_ITEM_NOT_FOUND`다. 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

결정과 같은 규칙으로 `true`는 최초 보관 UTC instant를 `archivedAt`에 기록하고 `false`는 `null`로 되돌린다. 성공 상태는 `200 OK`이고 기존 완료 여부를 포함한 항목 전체를 반환한다. 보관된 항목도 workspace projection의 `handoffItems`에 남으며 프런트가 활성 바통과 보관함으로 나눈다. 대상이 없거나 다른 시즌 소유이면 `404 HANDOFF_ITEM_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

## 5. 운영 상태 엔드포인트

```http
GET /actuator/health
```

이 경로는 Spring Boot Actuator 운영 엔드포인트이며 제품 API가 아니다. 현재 인증 없이 접근할 수 있다. 응답 세부 구조는 제품 DTO 계약이 아니라 Actuator 설정을 따른다.

## 6. 오류 응답

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
| `400` | `INVALID_INPUT` | DTO 형식·검증, 멱등 키 형식, IANA 시간대·일정·실제 마감 규칙 또는 안전하게 식별된 도메인 입력 오류 |
| `400` | `INVALID_LINK_IDEMPOTENCY_KEY` | 역할 자료 열기 intent의 멱등 키가 canonical UUID가 아님 |
| `400` | `INVALID_LINK_EXPIRY` | 역할 자료용 GO 링크 만료가 과거이거나 15분 제한을 넘음 |
| `400` | `INVALID_ROUND_RESOURCE_URL` | 설정된 ROUND origin의 역할 자료 URL이 canonical room 경로 계약을 벗어남 |
| `403` | `WORKSPACE_ACCESS_DENIED` | 공유 접근 키 누락 또는 불일치 |
| `403` | `WORKSPACE_CREATION_DENIED` | 설정된 파일럿 생성 키 누락 또는 불일치 |
| `403` | `WORKSPACE_RECOVERY_DENIED` | 운영자 복구 키 미설정·누락 또는 불일치 |
| `404` | `TEAM_NOT_FOUND`, `SEASON_NOT_FOUND`, `MEMBER_NOT_FOUND`, `ROLE_NOT_FOUND`, `ROLE_HANDOFF_NOT_FOUND`, `ROLE_RESOURCE_NOT_FOUND`, `ROUTINE_NOT_FOUND`, `SEASON_ROUND_NOT_FOUND`, `ROUTINE_EXECUTION_NOT_FOUND`, `DECISION_NOT_FOUND`, `HANDOFF_ITEM_NOT_FOUND` | 요청 범위에서 리소스를 찾지 못했거나 보관된 기록을 활성 변경 API로 요청함 |
| `404` | `RESOURCE_NOT_FOUND` | Spring MVC가 처리할 요청 경로를 찾지 못함 |
| `405` | `METHOD_NOT_ALLOWED` | 경로는 있지만 요청한 HTTP method를 지원하지 않음 |
| `409` | `MEMBER_NAME_CONFLICT` | 같은 팀에 동일한 구성원 이름이 존재함 |
| `409` | `SEASON_NAME_CONFLICT` | 같은 팀에 동일한 시즌 이름이 존재함 |
| `409` | `SEASON_ENDED` | 종료된 시즌의 일반 콘텐츠를 변경하려 함 |
| `409` | `SEASON_SUCCESSOR_EXISTS` | 이미 후속 시즌이 있는 원본에서 다시 생성하거나 원본을 재개하려 함 |
| `409` | `ROLE_NAME_CONFLICT` | 같은 시즌에 동일한 역할 이름이 존재함 |
| `409` | `ROLE_HANDOFF_STATE_CONFLICT` | 역할 바통의 참여자·상태·역할 snapshot이 요청과 맞지 않거나, 열린 바통 중 금지된 역할·바통 항목·자료 변경 또는 시즌 종료·전환을 시도함 |
| `409` | `ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED` | 바통 항목 없음, 미완료 항목 또는 역할 자료 없음 경고를 확인하지 않고 전달하려 함 |
| `409` | `ROUND_NAME_CONFLICT` | 같은 시즌에 동일한 회차 이름이 존재함 |
| `409` | `WORKSPACE_CONTENT_CONFLICT` | 같은 구성원, 시즌, 역할, 역할 바통, 역할 자료, 루틴 정의, 회차, 루틴 실행, 결정 또는 바통 항목을 다른 요청이 동시에 변경하거나 상태 검사용 행 잠금에 실패해 최신 workspace 확인이 필요함 |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 같은 범위와 작업의 멱등 키를 의미가 다른 생성 요청에 재사용함 |
| `409` | `IDEMPOTENCY_KEY_CONFLICT` | 같은 범위와 작업의 생성 요청이 동시에 처리 중임. 같은 키와 요청으로 재시도해야 함 |
| `409` | `IDEMPOTENCY_REPLAY_EXPIRED` | 더 최신 접근 키 변경 뒤 과거 워크스페이스 생성·키 변경 응답을 재생함 |
| `409` | `WORKSPACE_ACCESS_KEY_CONFLICT` | 같은 팀의 접근 키가 다른 요청에서 동시에 변경됨 |
| `409` | `LINK_GATEWAY_CONFLICT` | 같은 역할 자료 열기 멱등 키를 다른 GO payload에 재사용함 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 요청 본문의 media type을 지원하지 않음 |
| `502` | `LINK_GATEWAY_UNAVAILABLE` | BATON GO 연결·인증·응답 계약을 완료하지 못함 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류이며 내부 상세는 응답에 노출하지 않음 |

실제 MySQL 행 잠금 대기가 제한을 넘으면 새 워크스페이스·콘텐츠 생성의 멱등 예약은 기존 `409 IDEMPOTENCY_KEY_CONFLICT`, 기존 팀 접근 키 aggregate는 `409 WORKSPACE_ACCESS_KEY_CONFLICT`, 공유 콘텐츠 aggregate는 `409 WORKSPACE_CONTENT_CONFLICT`로 수렴한다. 일반 쿼리 timeout, transaction timeout과 DB 커넥션 획득 실패는 사용자의 동시 수정으로 추측하지 않고 `500 INTERNAL_ERROR`로 처리한다.

예상하지 못한 예외와 Spring MVC가 식별한 요청 오류도 같은 `ErrorResponse` 형태로 정규화한다. 단, 클라이언트가 서버가 제공하는 모든 media type을 거부해 발생하는 `406 Not Acceptable`은 오류 JSON도 협상할 수 없으므로 본문 없이 응답한다. 이 응답도 `X-Request-ID`는 유지한다. 내부 예외 상세와 stack trace는 응답에 노출하지 않고 서버 로그에만 남기며, 처리한 예외를 현재 HTTP observation의 오류로 기록한다. Spring에서 처리하거나 필터 체인을 벗어난 5xx는 MDC와 응답 헤더가 같은 요청 ID를 사용하며 Caddy access log도 최종 응답 헤더를 기록한다. Caddy가 직접 만든 413·502·503은 응답 헤더와 access log의 내장 `uuid`가 같은 edge 요청 ID를 사용한다. 해당 로그에서는 제품 운영 키, 멱등 키와 외부 요청 ID 헤더를 제거한다. 브라우저 클라이언트는 운영자가 해당 경계의 로그를 찾을 수 있도록 5xx 안내에 이 값을 함께 표시한다.

새 제품 API를 추가할 때는 다음을 함께 결정한다.

- 오류 코드와 HTTP 상태
- 필드 단위 검증 실패 표현이 필요한지
- 존재하지 않는 리소스, 충돌, 권한 실패의 경계
- 로그에 남길 내부 정보와 응답에 공개할 정보의 분리

## 7. 인증과 권한

최종 인증 방식은 미결정이다.

현재 Spring Security 설정은 다음 경로를 filter-chain 수준에서 공개한다.

- `/actuator/health`
- `/api/v1/system/status`
- `POST /api/v1/workspaces`
- `/api/v1/teams/{teamId}/seasons/{seasonId}/**`

워크스페이스 범위 경로는 Spring Security 사용자 인증 대신 application의 공유 키 검증으로 보호한다. 공유 키는 URL fragment를 포함한 초대 링크로 전달하며, 서버 요청에는 `X-Baton-Access-Key` 헤더로 보낸다. 키 회전은 현재 공유 키, 키 복구는 생성 권한과 분리된 파일럿 운영자 복구 키로 application 경계에서 검증한다. 쿠키 인증을 사용하지 않으므로 이 파일럿 경로만 CSRF 검사에서 제외한다.

공개 생성 경로도 application에서 선택적 `X-Baton-Creation-Key`를 검증한다. 로컬 기본값은 생성 키 미설정이라 생성은 열려 있지만 복구 키 미설정 상태의 복구는 항상 거절한다. 프로덕션 Compose와 `production` 프로필은 `BATON_WORKSPACE_CREATION_KEY`, `BATON_WORKSPACE_RECOVERY_KEY`를 모두 필수로 요구하며, 프로덕션 프로필은 각 값이 32자보다 짧거나 두 값이 같아도 시작을 거절한다.

그 밖의 요청은 fallback 사용자 인증이나 서버 세션 없이 기본 거부한다. 명시한 파일럿 경로의 `ERROR` dispatch만 허용해 실제 서버 오류가 보안 거부로 가려지지 않게 하며, 직접 `/error`를 요청하는 일반 dispatch는 계속 거부한다. 공유 키도 소규모 파일럿 접근 경계일 뿐 최종 인증·권한 계약이 아니다. 쿠키 세션, Bearer 토큰, 소셜 로그인, 조직 초대 방식 중 무엇을 채택할지는 별도 결정 전까지 확정하지 않는다.

## 8. 아직 계약이 없는 제품 영역

다음 영역은 제품 기준선에는 포함되지만 HTTP 경로, 요청·응답 DTO와 상태값이 아직 확정되지 않았다.

- 모든 제품 기록의 영구 삭제
- 계정, 초대, 팀·시즌별 권한과 감사 이력
- 지연·역할 공백을 전달할 외부 알림 채널과 선호·전달 결과

이 영역의 API를 추가할 때는 구현, 이 문서와 REST Docs 계약 테스트를 같은 변경에서 갱신한다.

## 9. 계약 검증

`SystemStatusRestDocsTest`와 `WorkspaceRestDocsTest`가 현재 애플리케이션 HTTP 계약과 스니펫을 검증한다. 성공 응답과 테스트가 명시한 대표 오류 응답은 restdocs-api-spec resource로도 기록하며, 같은 HTTP operation의 문서 식별자는 안정적인 `operationId` prefix를 공유한다. 모든 resource는 실제 `X-Request-ID` 응답을 assertion하고 descriptor로 남기며, 생성 계약 검사는 모든 operation과 응답 상태에서 이 공통 헤더를 확인한다. Caddy가 애플리케이션보다 먼저 만드는 413과 upstream 장애 502/503의 헤더·로그 상관관계는 production runtime smoke로 검증한다.

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
  → deterministic ordering + contract normalization
  → docs/api/openapi3.yaml
  → openapi-typescript
  → frontend/src/generated/api.ts
```

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

두 생성 파일은 프런트 단독·Docker 빌드에서도 Java 도구 체인을 요구하지 않도록 저장소에 추적한다. 직접 수정하지 않고 `generateApiContract`로 갱신한다. 정규화 계층은 생성기가 누락하는 request body 필수성, Jakarta Validation, UUID·날짜 형식과 required-nullable 응답을 보정하며 OpenAPI server를 동일 출처 `/`로 유지한다. API 경로, request·response DTO, 헤더, 오류 상태나 enum을 바꾸면 구현·REST Docs descriptor·이 문서와 두 생성 파일을 같은 변경에 포함한다. `checkApiContract`는 REST Docs에서 재생성한 OpenAPI와 추적 파일, 34개 operation의 경로·method·본문·헤더·상태 기준선, OpenAPI에서 재생성한 TypeScript 타입의 드리프트를 모두 거부한다. 프런트 API 함수는 generated `paths`로 URI template과 HTTP method 조합까지 검증한다.

## 10. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
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
- [역할 바통 전달 생명주기](../../ADR/0013_role_handoff_lifecycle/adr.md)
