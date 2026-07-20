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

페이지네이션 형식, 멱등키, 낙관적 잠금 표현은 이를 필요로 하는 실제 API가 설계될 때 확정한다.

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
- 성공 상태: `201 Created`
- `Location`: 생성한 팀·시즌의 workspace 조회 경로

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

`startDate`와 `endDate`는 시간대 없는 ISO 8601 달력 날짜다. 시작일은 종료일보다 늦을 수 없고 구성원은 한 명 이상이어야 한다.

응답:

```json
{
  "teamId": "8a4ec48a-56fa-4bb0-a411-4230f627f3e6",
  "seasonId": "7ccf1568-ae38-48a0-b2bf-30dd366e9ce7",
  "accessKey": "생성 시 한 번만 반환하는 공유 키"
}
```

클라이언트는 식별자를 불투명한 값으로 다룬다. `accessKey` 원문은 생성 응답 이후 다시 조회할 수 없으며 서버는 SHA-256 해시만 저장한다.

### 팀·시즌 범위와 접근 키

이후 파일럿 API는 다음 범위를 공유한다.

```text
/api/v1/teams/{teamId}/seasons/{seasonId}
```

모든 요청에 다음 헤더가 필요하다.

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

응답은 한 화면을 구성하는 다음 projection을 반환한다.

| 필드 | 내용 |
| --- | --- |
| `team` | `id`, `name` |
| `season` | `id`, `name`, `startDate`, `endDate` |
| `members` | `id`, `name`, `initials`, `tone` 목록 |
| `roles` | 역할, 담당자·기간, 책임과 위험 신호 목록 |
| `routines` | 현재 시즌 루틴과 완료 상태 목록 |
| `decisions` | 결정, 서버 생성 시각, 작성자 이름과 관련 역할 목록 |
| `handoffItems` | 역할별 바통 항목과 완료 여부 목록 |

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

루틴 응답의 `phase`는 `BEFORE`, `DURING`, `AFTER`, `status`는 `WAITING`, `DONE` 중 하나다. 결정의 `createdAt`은 서버 `Clock`으로 생성한 UTC ISO 8601 instant다. 바통의 `category`는 `RESPONSIBILITY`, `ROUTINE`, `RESOURCE`, `ADVICE` 중 하나다.

### 역할 생성

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/roles
```

- 성공 상태: `201 Created`
- 같은 팀의 역할 이름은 중복될 수 없다.
- `currentMemberId`와 `nextMemberId`는 해당 팀 구성원이어야 한다.
- 두 담당 날짜가 모두 있으면 시작일은 종료일보다 늦을 수 없다.

요청은 역할 응답에서 `id`를 제외한 `name`, `purpose`, 선택적 담당자·기간, `responsibilities`, 선택적 `risk`를 사용한다. 응답은 생성된 역할이다.

### 운영 루틴

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/routines
```

요청 필드는 `title`, `phase`, `dueLabel`, `ownerRoleId`, `detail`이다. `ownerRoleId`는 해당 팀 역할이어야 하며 새 루틴은 서버에서 항상 `WAITING`으로 시작한다. 성공 상태는 `201 Created`다.

완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 갱신된 루틴을 반환한다. 현재 계약은 회차별 실행 이력이나 지연 자동 판정을 제공하지 않는다.

### 결정 기록 생성

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/decisions
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

작성자와 관련 역할은 해당 팀 소속이어야 하고 관련 역할은 한 개 이상이며 중복될 수 없다. 성공 상태는 `201 Created`다. 응답의 `id`, `createdAt`, `authorName`은 서버가 결정한다.

### 바통 항목

생성:

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items
```

요청 필드는 `roleId`, `label`, `category`다. 새 항목은 서버에서 항상 미완료로 시작한다. 성공 상태는 `201 Created`다.

완료 상태 변경:

```http
PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion
```

```json
{ "completed": true }
```

성공 상태는 `200 OK`이고 갱신된 항목을 반환한다.

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
| `400` | `INVALID_INPUT` | DTO 형식·검증 또는 안전하게 식별된 도메인 입력 오류 |
| `403` | `WORKSPACE_ACCESS_DENIED` | 공유 접근 키 누락 또는 불일치 |
| `404` | `TEAM_NOT_FOUND`, `SEASON_NOT_FOUND`, `MEMBER_NOT_FOUND`, `ROLE_NOT_FOUND`, `ROUTINE_NOT_FOUND`, `HANDOFF_ITEM_NOT_FOUND` | 요청 범위에서 리소스를 찾지 못함 |
| `409` | `ROLE_NAME_CONFLICT` | 같은 팀에 동일한 역할 이름이 존재함 |

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

워크스페이스 범위 경로는 Spring Security 사용자 인증 대신 application의 공유 키 검증으로 보호한다. 공유 키는 URL fragment를 포함한 초대 링크로 전달하며, 서버 요청에는 `X-Baton-Access-Key` 헤더로 보낸다. 쿠키 인증을 사용하지 않으므로 이 파일럿 경로만 CSRF 검사에서 제외한다.

그 밖의 요청에 적용되는 HTTP Basic은 개발 기반의 임시 설정이다. 공유 키도 소규모 파일럿 접근 경계일 뿐 최종 인증·권한 계약이 아니다. 쿠키 세션, Bearer 토큰, 소셜 로그인, 조직 초대 방식 중 무엇을 채택할지는 별도 결정 전까지 확정하지 않는다.

## 8. 아직 계약이 없는 제품 영역

다음 영역은 제품 기준선에는 포함되지만 HTTP 경로, 요청·응답 DTO와 상태값이 아직 확정되지 않았다.

- 기존 팀의 시즌·구성원 추가와 수정
- 역할, 시즌, 구성원, 루틴, 결정과 바통 항목의 수정·삭제
- 반복 루틴 정의와 회차별 실행 기록의 분리
- 지연 자동 판정, 실제 deadline과 조직별 시간대
- 계정, 초대, 팀·시즌별 권한과 감사 이력
- 자료 URL의 구조화와 바통 전달·수락 상태

이 영역의 API를 추가할 때는 구현, 이 문서와 REST Docs 계약 테스트를 같은 변경에서 갱신한다.

## 9. 계약 검증

`SystemStatusRestDocsTest`와 `WorkspaceRestDocsTest`가 현재 HTTP 계약과 스니펫을 검증한다.

```bash
./gradlew --no-daemon :adapter-in-web:restDocsTest
```

일반 웹 모듈 테스트에서는 `restdocs` 태그를 제외하고, `check`가 `restDocsTest`를 별도로 실행한다. 따라서 전체 빌드에도 현재 REST Docs 계약 검증이 포함된다.

```bash
./gradlew --no-daemon build
```

## 10. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [테스트 전략](../../ADR/0002_test-strategy/adr.md)
