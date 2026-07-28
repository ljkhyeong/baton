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
- 식별자는 클라이언트가 형식이나 정렬 의미를 추론하지 않는 불투명한 값으로 다룬다.
- HTTP DTO와 application 결과 타입을 분리한다.
- 컨트롤러는 요청 검증과 변환을 담당하고 업무 규칙은 application 또는 domain에 둔다.
- 기존 필드의 의미를 바꾸거나 제거하는 변경은 새 버전 또는 명시적 호환 전략 없이 진행하지 않는다.

페이지네이션 형식은 이를 필요로 하는 실제 API가 설계될 때 확정한다. 워크스페이스·콘텐츠 생성과 접근 키 변경의 멱등 계약 및 동시 충돌은 아래 파일럿 API 절에서 정의한다.

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

키가 없거나 올바르지 않으면 `403 Forbidden`과 `WORKSPACE_ACCESS_DENIED`를 반환한다. 팀에 속하지 않는 시즌, 구성원 또는 역할 식별자를 다른 팀 경로에 사용할 수 없다.

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
| `season` | `id`, `name`, `startDate`, `endDate` |
| `members` | `id`, `name`, `initials`, `tone` 목록 |
| `roles` | 역할, 담당자·기간, 책임과 위험 신호 목록 |
| `routines` | 현재 시즌의 반복 루틴 정의 목록. 완료 상태는 포함하지 않음 |
| `rounds` | nullable `archivedAt`을 가진 시즌 회차와 회차 생성 시 복사된 루틴 실행 목록 |
| `decisions` | 결정, 서버 생성 시각, 작성자 식별자·이름, 관련 역할과 nullable `archivedAt` 목록 |
| `handoffItems` | 역할별 바통 항목, 완료 여부와 nullable `archivedAt` 목록 |
| `resources` | 역할별 자료의 제목, 외부 링크와 선택 설명 목록 |

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

루틴 정의 응답의 `phase`는 `BEFORE`, `DURING`, `AFTER` 중 하나이고 완료 상태는 없다. `rounds[].routineExecutions[]`는 생성 당시 루틴의 `routineId`, `title`, `phase`, `dueLabel`, `ownerRoleId`, `detail`을 스냅샷으로 보존하고 `status`를 `WAITING` 또는 `DONE`으로 가진다. 새로 생성하거나 수정하는 회차의 `meetingDate`는 필수지만, V5 이전의 루틴 상태를 이관한 `회차 도입 이전 기록`은 실제 날짜를 알 수 없어 운영자가 수정할 때까지 응답에서 `null`이다. 회차의 `archivedAt`은 활성 상태에서 `null`, 보관 상태에서 서버 `Clock`으로 생성한 UTC ISO 8601 instant다. workspace projection은 활성·보관 회차를 모두 반환하며 프런트엔드는 일반 운영 선택과 완료 계산에서는 활성 회차만 사용하고 보관 회차는 복원 가능한 보관함으로 나눈다. 결정의 `createdAt`은 서버 `Clock`으로 생성한 UTC ISO 8601 instant이고 `authorMemberId`는 수정 폼과 다른 클라이언트가 작성자를 이름으로 역추론하지 않게 하는 식별자다. 결정과 바통 항목의 `archivedAt`도 같은 활성·보관 표현을 사용한다. 바통의 `category`는 `RESPONSIBILITY`, `ROUTINE`, `RESOURCE`, `ADVICE` 중 하나다. `resources[]`는 `id`, `roleId`, `title`, `url`, nullable `description`을 가진다.

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

서버는 팀별로 이미 사용한 키 변경 멱등 해시를 보관한다. 같은 멱등 키로 만든 결과 뒤에 더 최신 키 변경이 완료됐다면 `409 IDEMPOTENCY_REPLAY_EXPIRED`를 반환하며, 폐기된 과거 접근 키를 다시 발급하지 않는다. 서로 다른 회전·복구 요청이 같은 팀에 동시에 반영되려 하면 하나는 `409 WORKSPACE_ACCESS_KEY_CONFLICT`를 받으며, 클라이언트는 최신 접근 상태를 확인한 뒤 새 멱등 키로 명시적으로 다시 시도한다.

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

`Idempotency-Key` 형식과 응답 유실 재생 규칙은 회전과 같다. 복구 결과 재생도 매번 올바른 `X-Baton-Recovery-Key`를 먼저 검증하며, 회전과 복구에 같은 원문 멱등 키를 사용해도 작업별로 분리된 결과를 만든다.

### 콘텐츠 생성 멱등성

역할, 루틴, 회차, 결정, 바통 항목과 역할 자료를 만드는 여섯 `POST` 요청에는 워크스페이스 생성과 같은 형식의 `Idempotency-Key`가 필수다. 서버는 재생 요청에서도 현재 `X-Baton-Access-Key`를 먼저 검증하며, 팀·시즌·작업 종류별로 멱등 결과를 분리한다. 따라서 같은 원문 키를 다른 작업 종류나 다른 작업 공간에서 독립적으로 사용할 수 있지만, 클라이언트는 각 사용자 의도마다 새 키를 사용한다.

같은 키와 의미가 같은 정규화 요청을 다시 보내면 새 리소스를 만들지 않고 최초에 생성된 리소스의 같은 `id`와 현재 표현을 `201 Created`로 반환한다. 그 사이 회차의 이름·모임 날짜·보관 상태·루틴 실행 상태, 바통 항목의 완료 상태, 결정·바통의 내용이나 보관 상태가 바뀌었다면 재생 응답에는 현재 상태가 보인다. 보관된 회차·결정·바통도 `archivedAt`이 있는 현재 표현으로 반환되므로 재생 성공을 활성 기록의 재생성으로 해석하지 않는다. 회차 생성 뒤 루틴 정의를 추가하거나 수정해도 재생은 최초 회차의 실행 식별자, 구성과 스냅샷을 바꾸지 않는다. 재생 일치 여부는 현재 표현이 아니라 최초 생성 요청의 fingerprint로 판단하므로, 정정된 이름·날짜를 원래 생성 키와 함께 보내면 `409 IDEMPOTENCY_KEY_REUSED`다. 같은 범위·작업의 키를 그 밖의 의미가 다른 요청에 재사용해도 같은 오류를 반환하고, 동일 키 예약이 동시에 충돌하면 `409 IDEMPOTENCY_KEY_CONFLICT`다. 동시 충돌을 받은 클라이언트는 새 키를 만들지 않고 잠시 뒤 같은 키와 같은 요청으로 재시도한다.

요청 fingerprint는 도메인 입력과 같이 문자열 앞뒤 공백과 도메인이 같은 값으로 취급하는 선택적 빈 문자열을 정규화한다. 책임과 관련 역할처럼 순서가 응답에 보존되는 목록은 순서까지 요청 의미에 포함한다. 서버는 원문 멱등 키 대신 작업·팀·시즌으로 범위를 분리한 SHA-256 기반 해시만 저장하며, 멱등 예약과 리소스 생성은 한 트랜잭션에서 커밋하거나 함께 롤백한다.

브라우저 클라이언트는 요청 전에 정규화 요청과 멱등 키를 내구 저장하고 다시 읽어 확인해야 한다. 저장할 수 없거나 브라우저 전체의 미완료 콘텐츠 생성 기록이 20개에 도달하면 새 생성을 전송하지 않는다. 성공 또는 같은 결과의 재생을 확인한 뒤에만 기록을 지우며, 네트워크 오류·서버 오류·동시 충돌·접근 키 오류에는 보존한다. 같은 키의 다른 요청으로 판정되면 해당 기록을 지우고 사용자의 명시적인 새 제출을 요구한다.

### 역할

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/roles
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

- 성공 상태: `201 Created`
- 같은 팀의 역할 이름은 중복될 수 없다.
- `currentMemberId`와 `nextMemberId`는 해당 팀 구성원이어야 한다.
- 두 담당 날짜가 모두 있으면 시작일은 종료일보다 늦을 수 없다.

요청은 역할 응답에서 `id`를 제외한 `name`, `purpose`, 선택적 담당자·기간, `responsibilities`, 선택적 `risk`를 사용한다. `name`은 최대 100자, `purpose`는 최대 1000자다. `responsibilities`는 필수 목록이며 최대 100개까지 받고 각 책임은 공백만으로 구성될 수 없으며 최대 500자다. 선택적 `risk`는 최대 1000자다. 응답은 생성된 역할이다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 역할은 해당 팀 소속이어야 하고 이름 중복, 구성원 소속과 담당 기간 규칙을 다시 검증한다. 자기 자신의 현재 이름은 중복으로 보지 않는다. 응답은 수정된 역할이다. 같은 역할을 먼저 읽은 다른 수정과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 workspace를 다시 확인해야 한다.

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

`roleId`는 해당 팀 역할이어야 한다. `title`은 필수이며 최대 200자, `url`은 사용자 정보가 없는 절대 `http` 또는 `https` 주소이며 최대 2048자다. `description`은 선택이고 최대 1000자다. 성공 상태는 `201 Created`이며 생성된 자료를 반환한다.

BATON 서버는 URL 대상을 요청하거나 내용·가용성·신뢰성을 확인하지 않는다. 프런트엔드는 링크를 새 탭에서 열고 `noopener noreferrer`를 적용한다. 링크 대상의 접근 권한과 안전성은 사용자가 확인해야 한다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `title`, `url`, `description` 전체 표현을 사용하고 성공 상태는 `200 OK`다. 대상 자료는 요청 팀 역할에 연결되어 있어야 하며 `roleId`를 같은 팀의 다른 역할로 바꿀 수 있다. 자료가 없거나 다른 팀 소유이면 `404 ROLE_RESOURCE_NOT_FOUND`, 새 소유 역할이 해당 팀에 없으면 `404 ROLE_NOT_FOUND`다. 같은 자료 수정 transaction이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받고 최신 workspace를 다시 확인해야 한다.

### 운영 루틴

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/routines
Idempotency-Key: <32~200자의 고엔트로피 값>
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청 필드는 `title`, `phase`, `dueLabel`, `ownerRoleId`, `detail`이다. `ownerRoleId`는 해당 팀 역할이어야 한다. 이 리소스는 반복 정의이므로 완료 상태를 갖지 않으며 성공 상태는 `201 Created`다.

정의 수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 전체 필드를 사용하며 성공 상태는 `200 OK`다. 대상 루틴은 해당 시즌 소속이고 `ownerRoleId`는 해당 팀 역할이어야 한다. 제목, 단계, 기한 문구, 담당 역할과 상세를 바꾸며 이미 생성한 회차의 실행 스냅샷은 바꾸지 않는다. 같은 정의를 수정하는 두 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받아 상대 변경을 덮어쓰지 않는다.

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

서버는 회차 생성 transaction에서 현재 시즌의 모든 루틴 정의를 각각 독립된 실행으로 복사하고 처음 상태를 `WAITING`으로 둔다. 응답은 회차 `id`, `name`, `meetingDate`, nullable `archivedAt`과 `routineExecutions`를 반환한다. 새 회차의 `archivedAt`은 `null`이다. 각 실행은 `id`, `roundId`, 원본 `routineId`, 스냅샷 필드와 `status`를 가진다. 회차 생성 뒤 추가하거나 수정한 루틴은 기존 회차에 반영되지 않고 다음에 만드는 회차부터 반영된다. 같은 멱등 요청을 재생하면 실행을 다시 만들지 않고 최초 회차 식별자와 현재 이름·날짜·보관·실행 상태를 반환한다.

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

요청은 이름과 모임 날짜의 전체 표현이다. `name`의 정규화·길이·시즌 안 유일성, `meetingDate`의 ISO 8601 형식과 시즌 기간 규칙은 생성과 같다. 성공 상태는 `200 OK`이고 수정된 회차 전체를 반환한다. 회차 `id`와 기존 루틴 실행의 `id`, 원본 루틴 식별자, 스냅샷 필드와 `status`는 바뀌지 않는다. V5 이관 회차의 `null` 날짜는 서버가 임의로 채우지 않으며, 운영자가 이 API로 정정할 때 실제 시즌 내 날짜를 반드시 제공한다.

보관된 회차는 수정할 수 없으며, 없거나 다른 시즌 소속인 회차와 같은 `404 SEASON_ROUND_NOT_FOUND`를 반환한다. 이름 유일성은 보관 여부와 무관하게 시즌 전체에 적용되므로 보관된 회차의 이름도 예약된다. 다른 회차와 이름이 겹치면 `409 ROUND_NAME_CONFLICT`, 같은 회차의 수정·보관이 겹쳐 늦은 저장이 발생하면 `409 WORKSPACE_CONTENT_CONFLICT`다.

회차 보관·복원:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "archived": true }
```

`archived: true`는 서버 `Clock`의 UTC instant를 `archivedAt`에 기록하고, `false`는 `archivedAt`을 `null`로 되돌려 복원한다. 이미 같은 상태라면 최초 보관 시각 또는 활성 상태를 유지한다. 성공 상태는 `200 OK`이고 실행 목록을 포함한 회차 전체를 반환한다. 보관·복원은 회차나 루틴 실행을 삭제·재생성하지 않으므로 실행 식별자, 스냅샷과 완료 상태를 그대로 보존한다. 보관된 회차도 workspace projection의 `rounds`에 남고 프런트엔드는 활성 운영 목록과 보관함을 나눠 표시한다.

대상이 없거나 다른 시즌 소속이면 `404 SEASON_ROUND_NOT_FOUND`, 겹친 회차 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다. 보관·복원은 회차 행의 배타적 쓰기 잠금, 실행 완료는 같은 행의 공유 읽기 잠금을 사용한다. 따라서 보관과 완료는 서로 직렬화되지만 완료 요청끼리는 부모 잠금을 함께 통과하고 같은 실행을 겹쳐 바꾸면 실행의 낙관적 잠금으로 한쪽이 `409 WORKSPACE_CONTENT_CONFLICT`가 된다. 보관이 먼저 반영되면 기다리던 완료 요청은 보관 상태를 확인한 뒤 `404 SEASON_ROUND_NOT_FOUND`로 끝나며, 완료가 먼저 반영되면 보관은 그 완료 상태를 보존한다.

회차별 완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/routine-executions/{executionId}/completion
X-Baton-Access-Key: <워크스페이스 접근 키>
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 갱신된 루틴 실행을 반환한다. 회차는 요청한 시즌 소속의 활성 회차이고 실행은 해당 회차 소속이어야 한다. 보관된 회차는 완료 상태를 바꿀 수 없으며 `404 SEASON_ROUND_NOT_FOUND`다. 같은 실행을 바꾸는 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`다. 기존의 `PATCH /routines/{routineId}/completion`은 정의와 실행의 의미를 섞으므로 제거했으며, 실제 deadline과 지연 자동 판정은 아직 계약하지 않는다.

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

작성자와 관련 역할은 해당 팀 소속이어야 하고 관련 역할은 한 개 이상이며 중복될 수 없다. 성공 상태는 `201 Created`다. 응답의 `id`, `createdAt`, `authorName`은 서버가 결정하고, `authorMemberId`는 요청한 작성자 식별자를 반환한다. 새 결정의 `archivedAt`은 `null`이다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `title`, `reason`, `alternative`, `authorMemberId`, `roleIds` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 `createdAt`은 최초 생성 시각을 유지한다. 대상 결정은 요청 시즌 소속이어야 하고 보관되지 않은 활성 기록이어야 한다. 작성자와 관련 역할의 팀 소속, 관련 역할의 최소 개수와 중복 금지를 다시 검증한다. 대상이 없거나 다른 시즌 소속이거나 보관 상태이면 `404 DECISION_NOT_FOUND`다. 같은 결정을 먼저 읽은 다른 수정·보관 transaction과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

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

요청 필드는 `roleId`, `label`, `category`다. 새 항목은 서버에서 항상 미완료로 시작하고 `archivedAt`은 `null`이다. 성공 상태는 `201 Created`다.

수정:

```http
PUT /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}
X-Baton-Access-Key: <워크스페이스 접근 키>
```

요청은 생성과 같은 `roleId`, `label`, `category` 전체 표현을 사용한다. 성공 상태는 `200 OK`이고 기존 `completed` 값은 유지한다. 대상 항목은 요청 팀의 역할에 연결된 활성 기록이어야 하고 새 `roleId`도 같은 팀 역할이어야 한다. 대상이 없거나 다른 팀 소유이거나 보관 상태이면 `404 HANDOFF_ITEM_NOT_FOUND`, 새 소유 역할이 없으면 `404 ROLE_NOT_FOUND`다. 같은 항목을 먼저 읽은 수정·완료·보관 transaction과 커밋이 겹치면 늦은 요청은 `409 WORKSPACE_CONTENT_CONFLICT`를 받는다.

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

결정과 같은 규칙으로 `true`는 최초 보관 UTC instant를 `archivedAt`에 기록하고 `false`는 `null`로 되돌린다. 성공 상태는 `200 OK`이고 기존 완료 여부를 포함한 항목 전체를 반환한다. 보관된 항목도 workspace projection의 `handoffItems`에 남으며 프런트가 활성 바통과 보관함으로 나눈다. 대상이 없거나 다른 팀 소유이면 `404 HANDOFF_ITEM_NOT_FOUND`, 겹친 변경은 `409 WORKSPACE_CONTENT_CONFLICT`다.

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
| `400` | `INVALID_INPUT` | DTO 형식·검증, 멱등 키 형식 또는 안전하게 식별된 도메인 입력 오류 |
| `403` | `WORKSPACE_ACCESS_DENIED` | 공유 접근 키 누락 또는 불일치 |
| `403` | `WORKSPACE_CREATION_DENIED` | 설정된 파일럿 생성 키 누락 또는 불일치 |
| `403` | `WORKSPACE_RECOVERY_DENIED` | 운영자 복구 키 미설정·누락 또는 불일치 |
| `404` | `TEAM_NOT_FOUND`, `SEASON_NOT_FOUND`, `MEMBER_NOT_FOUND`, `ROLE_NOT_FOUND`, `ROLE_RESOURCE_NOT_FOUND`, `ROUTINE_NOT_FOUND`, `SEASON_ROUND_NOT_FOUND`, `ROUTINE_EXECUTION_NOT_FOUND`, `DECISION_NOT_FOUND`, `HANDOFF_ITEM_NOT_FOUND` | 요청 범위에서 리소스를 찾지 못했거나 보관된 기록을 활성 변경 API로 요청함 |
| `409` | `ROLE_NAME_CONFLICT` | 같은 팀에 동일한 역할 이름이 존재함 |
| `409` | `ROUND_NAME_CONFLICT` | 같은 시즌에 동일한 회차 이름이 존재함 |
| `409` | `WORKSPACE_CONTENT_CONFLICT` | 같은 역할, 역할 자료, 루틴 정의, 회차, 루틴 실행, 결정 또는 바통 항목을 다른 요청이 동시에 변경해 최신 workspace 확인이 필요함 |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 같은 범위와 작업의 멱등 키를 의미가 다른 생성 요청에 재사용함 |
| `409` | `IDEMPOTENCY_KEY_CONFLICT` | 같은 범위와 작업의 생성 요청이 동시에 처리 중임. 같은 키와 요청으로 재시도해야 함 |
| `409` | `IDEMPOTENCY_REPLAY_EXPIRED` | 더 최신 접근 키 변경 뒤 과거 워크스페이스 생성·키 변경 응답을 재생함 |
| `409` | `WORKSPACE_ACCESS_KEY_CONFLICT` | 같은 팀의 접근 키가 다른 요청에서 동시에 변경됨 |

안전하게 식별되지 않은 내부 `IllegalArgumentException`의 상세 메시지는 응답에 노출하지 않는다. 그 밖의 예외를 같은 형태로 정규화하는 전체 정책은 아직 구현되지 않았으므로 모든 `5xx`가 이 형태라고 가정하지 않는다.

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

- 기존 팀의 시즌·구성원 추가와 수정
- 모든 제품 기록의 영구 삭제
- 지연 자동 판정, 실제 deadline과 조직별 시간대
- 계정, 초대, 팀·시즌별 권한과 감사 이력
- 바통 전달·수락 상태

이 영역의 API를 추가할 때는 구현, 이 문서와 REST Docs 계약 테스트를 같은 변경에서 갱신한다.

## 9. 계약 검증

`SystemStatusRestDocsTest`와 `WorkspaceRestDocsTest`가 현재 HTTP 계약과 스니펫을 검증한다. 성공 응답과 테스트가 명시한 대표 오류 응답은 restdocs-api-spec resource로도 기록하며, 같은 HTTP operation의 문서 식별자는 안정적인 `operationId` prefix를 공유한다.

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

두 생성 파일은 프런트 단독·Docker 빌드에서도 Java 도구 체인을 요구하지 않도록 저장소에 추적한다. 직접 수정하지 않고 `generateApiContract`로 갱신한다. 정규화 계층은 생성기가 누락하는 request body 필수성, Jakarta Validation, UUID·날짜 형식과 required-nullable 응답을 보정하며 OpenAPI server를 동일 출처 `/`로 유지한다. API 경로, request·response DTO, 헤더, 오류 상태나 enum을 바꾸면 구현·REST Docs descriptor·이 문서와 두 생성 파일을 같은 변경에 포함한다. `checkApiContract`는 REST Docs에서 재생성한 OpenAPI와 추적 파일, 22개 operation의 경로·method·본문·헤더·상태 기준선, OpenAPI에서 재생성한 TypeScript 타입의 드리프트를 모두 거부한다. 프런트 API 함수는 generated `paths`로 URI template과 HTTP method 조합까지 검증한다.

## 10. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [테스트 전략](../../ADR/0002_test-strategy/adr.md)
- [첫 파일럿 자체 호스팅 배포](../../ADR/0003_pilot-self-hosted-deployment/adr.md)
- [테스트 기반 API 계약 생성](../../ADR/0004_test-derived-api-contract/adr.md)
- [공유 콘텐츠의 낙관적 수정 충돌](../../ADR/0005_optimistic-content-updates/adr.md)
- [루틴 정의와 회차 실행 분리](../../ADR/0006_routine-definition-and-round-execution/adr.md)
- [결정과 바통의 가역 보관](../../ADR/0007_reversible-record-archive/adr.md)
- [운영 회차 정정과 가역 보관](../../ADR/0008_revisable-round-lifecycle/adr.md)
