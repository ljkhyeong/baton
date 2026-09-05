# PRD-0010: 브리프 이력 탐색·현재 업무 연결·생성 준비 상태

- 상태: 채택
- 결정일: 2026-09-05
- 범위: BATON 사용자 화면의 브리프 탐색과 원본 업무 연결, 생성 요청 전 안내

## 목적과 소유권

저장된 브리프에서 문제를 확인한 사용자가 현재 업무로 이동하고, 지난 기록과 비교하며,
변경사항 전달이 끝났는지 확인한 뒤 이번 주 생성을 요청할 수 있게 한다.

BRIEF는 기존 불변 에디션·이력·비교를 제공한다. BATON은 사용자 권한, 현재 역할·루틴 정보와
이벤트 전달·생성 실행 기록을 소유한다. 현재 업무명은 불변 에디션 본문에 추가하지 않으며
기존 에디션 ETag·선정 규칙·이벤트 계약·스키마를 바꾸지 않는다.

## 사용자 API

모든 경로는 `/api/v1/teams/{teamId}/seasons/{seasonId}/brief` 아래다. 계정 세션, 활동 중인
같은 팀 멤버십, 워크스페이스 접근 키를 확인한다. 종료 시즌도 조회할 수 있다. 원본 업무
일괄 조회는 읽기 전용 POST지만 기존 세션 정책에 따라 동적 CSRF와 동일 출처를 요구한다.
모든 응답은 `Cache-Control: no-store`다.

| 메서드 | 하위 경로 | 의미 |
| --- | --- | --- |
| `GET` | `/editions` | 생성 순번 내림차순의 불변 브리프 요약 이력 |
| `GET` | `/editions/{editionId}` | 선택한 브리프의 불변 본문과 ETag |
| `GET` | `/editions/{editionId}/changes?fromEditionId=...` | 기준에서 선택한 브리프로의 차이 |
| `POST` | `/sources/query` | 표시 중인 원본 참조의 현재 업무 이름·이동 대상 |
| `GET` | `/generation-readiness` | 현재 시즌의 전달·생성 요청 준비 상태 |

이력의 `beforeGeneration`은 선택적 양의 배타 커서다. `limit`은 기본 20, 범위 1~100이다.
응답은 BRIEF의 `editions`와 `nextBeforeGeneration`이며 마지막 커서는 null이다. 요약은
`editionId`, `generation`, `weekStart`, `zoneId`, `generatedAt`, `sourceCursor`,
`ruleVersion`, `itemCount`를 포함한다. 화면은 이전 페이지를 더 불러와 주차·생성 순번으로
브리프와 비교 기준을 선택한다.

식별자 단건은 BRIEF 전체 응답의 팀·시즌과 요청한 editionId를 확인한다. 다른 범위는
`404 BRIEF_EDITION_NOT_FOUND`로 존재를 감춘다. 비교는 양쪽 단건의 범위를 먼저 확인한 뒤
기존 BRIEF 비교 API를 호출한다. BRIEF의 비교 범위 검증을 사용자 권한으로 대체하지 않는다.

`added`, `removed`, `changed.before`·`changed.after`는 저장된 값을 그대로 중계한다.
`removed`는 화면에서 ‘제외’로 표시하고 실제 해소·삭제를 추론하지 않는다. `section`만
달라도 분류 변경을 표시하며 각 브리프의 저장 시간대와 규칙 버전을 유지한다.

## 현재 업무 연결

요청은 `sources` 배열에 `eventType`과 `sourceReference`를 담는다. 한 번에 1~100건이며
참조는 빈 값이 아닌 최대 512자다. 이 제한은 BATON의 업무 연결 요청에만 적용하며 BRIEF의
기존 수신·조회 계약을 바꾸지 않는다. 화면은 표시할 참조를 중복 제거하고 100건씩 요청한다.

응답 `sources`는 요청 순서를 유지하고 각 항목에 같은 정체성과 `target`을 반환한다.
`target`은 현재 이름 `title`, `roleId`, 선택적 `routineId`, 보관 여부 `archived`다.
역할의 routineId는 null이며, 연결할 수 없는 항목의 target도 null이다.

- `baton-continuity:<정규 UUID>`로 저장된 신호를 같은 팀·시즌에서 읽고 eventType까지 확인한다.
- 신호 UUID를 역할·루틴 UUID로 간주하지 않고 저장된 subjectId를 사용한다.
- 역할은 팀·시즌을, 루틴은 시즌과 현재 소유 역할의 팀·시즌을 다시 확인한다.
- 이전 참조, 미존재·삭제, 유형·범위 불일치는 이동 대상을 만들지 않는다.
- 현재 업무 이름은 과거 생성 당시 이름이 아니다. 별도 요청으로 표시하고 과거 에디션이나
  비교 결과에 합쳐 저장하지 않는다.
- 역할은 역할 상세로, 인수인계 신호는 해당 역할의 바통 화면으로, 루틴은 운영 화면의 해당
  루틴으로 이동한다. 보관 루틴은 보관함을 펼쳐 이동한다.
- 현재 워크스페이스 화면에 대상이 없으면 새로고침을 안내한다. 브라우저가 임의 URL을 만들거나
  다른 작업공간으로 이동하지 않는다.

## 생성 준비 상태

응답은 `status`, `pendingCount`, `failedCount`, `lastDeliveredAt`, `checkedAt`이다.
같은 팀·시즌의 기존 BRIEF outbox를 집계하고, 전달이 끝났으면 현재 주차·시즌 시간대·전달
watermark에 해당하는 기존 생성 실행을 읽는다. 조회만으로 실행을 선점·생성·재처리하지 않는다.

| 상태 | 의미와 화면 동작 |
| --- | --- |
| `DISABLED` | 서비스 조회 설정 비활성, 연결 준비 안내 |
| `SEASON_ENDED` | 종료 시즌, 조회만 허용 |
| `DELIVERY_FAILED` | 영구 전달 실패가 있어 운영자 확인 필요 |
| `DELIVERY_PENDING` | 전달 대기·처리 중 이벤트가 있어 생성 대기 |
| `GENERATION_FAILED` | 같은 생성 의도의 영구 실패로 설정 확인 필요 |
| `GENERATING` | 같은 생성 의도의 처리 lease가 확인 시각에 유효함 |
| `READY` | 전달 완료이며 생성 또는 저장된 결과 재사용을 요청할 수 있음 |

비활성·종료 시즌을 먼저 적용하고 실패 전달은 대기 전달보다 먼저 안내한다. 전달 대기 수는
`PENDING`·`PROCESSING`, 실패 수는 `FAILED` 행의 수다. 마지막 전달 성공 시각은 `DELIVERED`의
최대 completedAt이며 성공 기록이 없으면 null이다. 실패 완료 시각을 성공으로 표시하지 않는다.
전달 성공·확인 시각은 현재 시즌 시간대로 표시하고,
과거 브리프의 항목은 각 에디션에 저장된 시간대를 유지한다.

`READY`는 원본 전체 재조정·수신 완전성 또는 BRIEF 연결 성공 보장이 아니다. 저장된 이벤트
전달과 생성 실행을 기준으로 한 요청 준비 상태다. BRIEF sourceCursor와 BATON outbox ID를
비교하지 않는다. 화면은 상태를 새로고칠 수 있고 READY일 때만 생성 버튼을 활성화한다.
실제 POST 생성은 기존 권한·열린 시즌·전달 경계·lease 판정을 다시 수행한다. 조회 직후 새
이벤트가 생긴 경우에도 기존 409 차단이 유지된다. 생성 성공·실패 뒤 준비 상태를 다시 읽는다.

## 검증 기준과 제외 범위

- 애플리케이션은 범위 밖 단건·비교, 활동 종료 멤버십, 원본 정체성·업무 소속 불일치와
  생성 준비 상태를 검증한다.
- 기존 MySQL 통합 시나리오는 요청한 신호만 읽는 범위와 전달 집계·lease 조회를 확인한다.
- REST Docs와 보안 테스트가 새 사용자 경로·CSRF·필수 입력·null 응답·ETag를 담당한다.
- 기존 HTTPS 교차 서비스 시나리오에서 실제 불변 이력·비교와 권한 거부, BATON 원본 API로
  생성한 역할 신호의 업무 연결·전달 대기 상태를 확인한다.
- 브라우저는 이력 페이지, 분류 변경, 잘못된 비교 대상 거부, 원본 역할·보관 루틴 이동과
  생성 준비 상태를 확인한다. 브라우저 검증은 API 대역을 사용한다.

이번 주 해소 요약, 자동 주간 생성, RELAY 전달, 공인 HTTPS 배포는 이 기능에 포함하지 않는다.
BRIEF 운영자 API·공개 Caddy 허용 목록·이벤트 계약 팩도 바꾸지 않는다.

## 관련 문서

- [에디션 조회와 생성](../0008_brief-edition-query-and-generation/spec.md)
- [현재 관심 항목 조회](../0009_brief-current-attention/spec.md)
- [API 계약](../0002_api-contract/spec.md)
- [BRIEF 조회·생성 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
