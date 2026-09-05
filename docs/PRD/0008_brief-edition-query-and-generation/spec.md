# PRD-0008: BATON 경유 BRIEF 에디션 조회와 생성

- 상태: 채택
- 결정일: 2026-08-29
- 구현 상태: BATON API·서비스 client·V27 실행 기록·비공개 HTTPS 조립·주간 요약 화면 구현, 로컬 교차 서비스 검증 완료, 실제 원격 스테이징 검증 예정
- 범위: 인증된 BATON 사용자가 BRIEF 최신 불변 에디션을 조회하고 현재 주차 에디션 생성을 지시하는 경계

## 1. 목적

BATON 사용자는 BATON UI와 백엔드만 사용한다. BATON은 사용자 세션, 팀 멤버십과 워크스페이스
접근 권한을 판정하고 BRIEF는 사용자 계정·세션·멤버십을 복제하지 않는다. BATON 백엔드는
권한 확인 뒤 BRIEF의 불변 에디션을 중계하고, 권위 있는 시즌 시간대와 전달 완료 경계를
고정한 생성 명령을 호출한다.

## 2. 사용자 API

두 API는 활동 중인 `Account` 세션과 같은 팀 멤버십을 요구한다. 계정 권한을 사용하는 팀은
기존 팀 권한을 확인하고, 공유 키 방식의 팀은 `X-Baton-Access-Key`를 검사한다. 생성은
변경 권한, 동적 CSRF, 정확한 `Origin`과 `Sec-Fetch-Site: same-origin`도 요구한다.

| 메서드 | 경로 | 성공 |
| --- | --- | --- |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions/latest` | `200`과 BRIEF 불변 에디션 전체 표현 |
| `POST` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions` | 새 에디션 `201`, 같은 불변 상태 재사용 `200` |

최신 조회는 종료 시즌도 읽을 수 있지만 팀·시즌 범위와 활동 중인 현재 팀 멤버십을 확인한다.
응답은 BRIEF가 저장한 에디션 식별자, 세대, 주간 경계, 시간대, 원본 cursor, 규칙 버전과
항목 스냅샷을 그대로 표현한다. `ETag`는 BRIEF 응답 검증자를 유지하고
`Cache-Control: no-store`를 사용한다. `If-None-Match`는 Spring MVC 표준 조건부 응답으로
처리하며 BATON이 본문 해시나 별도 캐시를 만들지 않는다.

생성 요청 본문은 없다. BATON은 요청한 시즌의 IANA 시간대와 주입 `Clock`으로 현지 오늘을
계산하고, 그 날짜가 속한 월요일을 `weekStart`로 정한다. 브라우저가 주차·시간대·BRIEF
workspace ID를 제출하게 하지 않는다.

## 3. 전달 완료 경계와 실행 기록

생성 전에 BATON은 해당 팀·시즌의 BRIEF 연속성 outbox 최대 ID를 `deliveryWatermark`로
고정하고 그 범위의 모든 행이 `DELIVERED`인지 확인한다. 미완료 행이 있으면 BRIEF를 호출하지
않고 `409 BRIEF_DELIVERY_INCOMPLETE`를 반환한다. 영구 실패 outbox를 완료로 추측하지 않는다.

V27의 `brief_edition_generation_execution`은 다음 경계를 고유하게 보존한다.

```text
(teamId, seasonId, weekStart, zoneId, deliveryWatermark)
```

실행 상태는 `PENDING`, `PROCESSING`, `SUCCEEDED`, `RETRYABLE_FAILURE`,
`PERMANENT_FAILURE`다. claim은 1분 lease와 fencing token을 사용한다. 만료되지 않은 같은
실행은 `409 BRIEF_GENERATION_IN_PROGRESS`, 성공한 실행은 저장한 에디션 요약과 `ETag`를
재사용한다. 만료 lease나 재시도 가능 실패는 같은 실행 ID에서 새 token과 증가한 시도
횟수로 다시 claim한다. 외부 HTTP 호출 동안 MySQL 트랜잭션과 제품 행 잠금을 유지하지
않으며 완료 갱신은 현재 lease token이 일치할 때만 허용한다.

BRIEF 응답의 `workspaceId`와 `seasonId`가 요청 범위와 다르면 노출하거나 성공으로 저장하지
않고 영구 구성 오류로 끝낸다. 생성 응답은 `weekStart`와 `zoneId`도 생성 요청과 같아야 한다.
둘 중 하나라도 다르면 성공으로 저장하지 않고 기존 `BRIEF_SCOPE_MISMATCH` 영구 실패로
기록하며, 사용자에게 `503 BRIEF_CONFIGURATION_ERROR`를 반환한다. 최신 조회는 요청한
팀·시즌 범위만 확인하고 현재 주차나 시간대와 일치하도록 제한하지 않는다.

## 4. BRIEF 서비스 연결

- 이벤트 송신 token과 다른 서비스 전용 Bearer를 사용한다.
- `baton.brief.service-api.enabled`는 기본 `false`다.
- 활성화할 때 base URL은 경로 없는 HTTPS origin만 허용하며 redirect를 따르지 않는다.
- BATON 프로덕션 override는 Bearer를 Spring config tree로, 인증서 truststore를 고정 경로의
  PKCS12 파일로 마운트한다.
- BATON `app`과 BRIEF 서비스 전용 Caddy만 운영자가 만든 `Internal=true` Docker 네트워크를
  공유한다. BATON은 인증서 검증을 끄지 않는다.
- BRIEF 서비스 Caddy는 호스트 포트를 게시하지 않고 조회·생성 허용 목록 밖을 `404`로
  종료한다.

결과 분류는 다음과 같다.

| BRIEF 결과 | BATON 처리 |
| --- | --- |
| 최신 조회 `200`, 생성 `200`·`201` | 완료 저장 후 사용자에게 같은 생성 여부를 반환 |
| 최신 조회 `404` | `404 BRIEF_EDITION_NOT_FOUND` |
| 계약에 없는 `2xx`·`3xx`, 필수 응답 필드나 `ETag` 누락 | 구성 또는 계약 오류 `503 BRIEF_CONFIGURATION_ERROR`; 생성 실행은 영구 실패 |
| `400`, `401`, `403`, 그 밖의 계약 `4xx` | 구성 또는 계약 오류 `503 BRIEF_CONFIGURATION_ERROR`; 생성 실행은 영구 실패 |
| `429`, `5xx`, 네트워크 실패 | `503 BRIEF_UNAVAILABLE`; 생성 실행은 재시도 가능 실패 |

성공 응답 본문을 읽는 중 발생한 시간 초과나 연결 끊김도 네트워크 실패로 처리한다.
JSON 형식 오류나 필수 응답 필드 누락은 기존처럼 영구 구성 오류로 처리한다.

## 5. 응답 계약

최신 에디션은 BRIEF 전체 스냅샷을 반환한다. 항목의 `aggregateRevision`과 `revisionGap`은
이전 BRIEF 에디션에서 근거를 알 수 없으면 `null`일 수 있다.
항목의 `section`은 생성 당시의 `CURRENT_WEEK`(이번 주 변경)·`CARRY_OVER`(이전부터 미해소)를
그대로 중계한다. BRIEF V9 이전 항목은 `null`이며 서버·브라우저가 시각으로 재분류하지 않는다.
에디션 선정 규칙 `2`와 항목 투영 규칙 `1`은 서로 다른 버전이다. 기존 BATON 생성 성공 실행의
재사용 계약은 유지하며, 기존 결과를 분류하려고 과거 에디션을 덮어쓰지 않는다.

생성 응답은 다음 필드만 반환한다.

| 필드 | 의미 |
| --- | --- |
| `executionId` | BATON의 내구성 있는 생성 실행 UUID |
| `deliveryWatermark` | 생성 전에 완료를 확인한 BATON BRIEF outbox 최대 ID |
| `editionId` | BRIEF 불변 에디션 UUID |
| `generation` | 작업공간·시즌 범위 에디션 세대 |
| `sourceCursor` | BRIEF 로컬 수신 순서 cursor |
| `created` | 새 에디션 생성이면 `true`, 직전 불변 상태 재사용이면 `false` |

새 에디션 `201`은 최신 조회 경로를 `Location`으로 반환한다. 두 성공 상태 모두 에디션
`ETag`, `Cache-Control: no-store`와 `X-Request-ID`를 포함한다.

## 6. 화면과 비목표

### BATON 화면 연결

- PRD-0009의 인증된 ‘BRIEF 관심 항목’ 패널 안에서 ‘저장된 브리프’를 펼칠 때 최신 에디션을 조회한다.
- 생성 시점의 주차·시간대·시각·세대와 고정 항목을 표시한다. 현재 시즌 시간대나 현재 관심
  항목으로 과거 내용을 바꾸지 않으며, 최신 에디션을 이번 주 에디션이라고 추측하지 않는다.
- 저장된 `section`에 따라 이번 주 변경·이전부터 미해소를 나눠 표시한다. `null`은 별도의
  이전 브리프·분류 미기록 그룹으로 표시한다.
- 이전 항목의 `aggregateRevision`·`revisionGap`이 함께 `null`이면 ‘근거 미기록’으로 표시한다.
- ‘이번 주 브리프 생성’은 본문 없이 기존 POST API를 호출한다. 동적 CSRF 헤더는 공용 계정 API에서
  받고 `Origin`·`Sec-Fetch-Site`는 브라우저에 맡긴다. 서버의 주차·전달 경계·실행 기록을 복제하지 않는다.
- 명시적 클릭만 생성하며 자동 재시도하지 않는다. 대기 중에는 중복 제출을 막고 성공하면 최신
  조회를 갱신한다. `created=false`는 기존 브리프 재사용으로 표시한다.
- `404 BRIEF_EDITION_NOT_FOUND`, 전달 미완료·진행 중 `409`, 서비스 `503`과 사용자 권한 거부를 구분한다.
  실패하면 최신 브리프를 확인하고 필요한 경우 생성 버튼으로 다시 요청하도록 안내한다.
- 종료 시즌은 조회만 허용한다. 권한 거부 때 이전 에디션과 생성 결과를 감추고 조회 성공 뒤에만
  다시 표시한다. 브라우저에 BRIEF 서비스 token이나 별도 브리프 저장소를 두지 않는다.
- React Query의 범위별 조회·변경 요청과 공용 HTTP 클라이언트를 사용한다. 이 화면은 전체 본문을
  조회하며 별도 ETag 저장·조건부 요청 처리는 추가하지 않는다. 기존 API의 조건부 조회는 유지한다.

### 제외 범위

- 브라우저의 BRIEF 직접 호출, BRIEF 사용자 계정·세션·CORS
- BATON의 BRIEF 에디션 내용 재계산·수정·캐시 저장소
- BRIEF 생성 scheduler, 대상 registry나 별도 Idempotency-Key
- 생성 실행 운영자 재처리 API
- mTLS와 인증서 자동 발급·교체 체계

에디션 이력·단건·비교와 생성 전 준비 상태 안내는
[PRD-0010](../0010_brief-navigation-and-readiness/spec.md)에서 추가했다. 같은 사용자 권한과
불변 본문·ETag를 유지하며 조회 뒤에도 실제 생성에서 전달 경계를 다시 확인한다.

## 7. 검증

- application 테스트는 계정·팀 범위, 시즌 시간대의 월요일, 전달 미완료 차단과 BRIEF 범위
  불일치 거부를 확인한다. 생성 응답의 주차 또는 시간대가 요청과 다르면 영구 실패를
  기록하고 성공 저장을 호출하지 않는지도 확인한다.
- MySQL 통합 테스트는 V27 실행 고유 경계, 만료 lease 회수, 오래된 token 거부, 성공 재사용과
  전달 미완료 기록을 확인한다.
- 외부 adapter 테스트는 별도 Bearer, 생성 JSON, `ETag`, 인증·재시도 결과 분류를 확인한다.
- 보안 통합 테스트는 미인증 `401`, 조건부 조회 `304`, 생성 CSRF·동일 출처 경계를 확인한다.
- `BriefEditionRestDocsTest`가 두 사용자 API의 OpenAPI 계약을 생성한다.
- 운영 shell 테스트는 별도 token과 truststore, 서비스 Compose override와
  `Internal=true` 네트워크를 확인한다.
- `brief-edition.spec.ts`는 API 대역으로 미생성·전달 대기, 동적 CSRF·본문 없는 생성, 새 생성과
  재사용, 저장 시간대·이전 근거 `null`, 종료 시즌 생성 차단과 권한 거부를 확인한다.
  브라우저 테스트를 실제 공개 HTTPS 배포의 종단 간 검증으로 확대하지 않는다.

선택 실행 교차 서비스 테스트는 실제 BATON 앱·MySQL 8.4와 BRIEF 서비스 Caddy·앱·
PostgreSQL 18.6을 함께 기동해 다음을 확인했다.

- 계정 로그인·활동 중인 팀 멤버십·워크스페이스 접근 키를 거친 HTTPS 생성과 최신 조회
- BRIEF `ETag` 전달과 `If-None-Match` 조건부 `304`
- BRIEF 저장 뒤 BATON 실행 성공 상태를 재시도 상태로 되돌린 응답 유실 재현, 같은
  `executionId`·`editionId`와 에디션 한 건 수렴
- BRIEF의 새·직전 서비스 token 중첩 중 직전 token 성공, 직전 값 제거 뒤 기존 BATON의
  `503`, BATON을 새 token으로 전환한 뒤 조회와 새 범위 생성 성공
- 서비스 Caddy의 비루트 UID `10001`, file capability 없음, 읽기 전용 rootfs,
  `cap_drop=ALL`, 호스트 포트 없음과 Authorization 원문 로그 비노출

네트워크는 BATON data, BRIEF data·proxy와 `Internal=true` 서비스 경계를 분리했다. 이
검증의 인증서는 로컬 생성 CA이고 응답 유실은 실제 TCP 절단이 아닌 저장 상태 되돌리기다.
공인 DNS·ACME와 서로 다른 스테이징 호스트 사이의 호출은 아직 검증하지 않았다.

## 8. 주간 운영 요약 화면

- 오늘 화면의 `주간 운영 요약`에서 현재 관심 항목과 저장된 브리프를 조회한다. 현재 관심 항목은
  PRD-0009, 브리프 이력·비교·공유·인쇄와 원본 업무 연결은 PRD-0010을 따른다.
- 계정·팀·시즌·접근 키별로 조회와 화면 선택을 구분한다. 인증·권한 확인에 실패하면 이전
  내용을 숨기고, 브라우저 저장소에 브리프 내용을 쓰지 않는다.
- `BRIEF_EDITION_NOT_FOUND`만 아직 생성한 브리프가 없는 상태로 표시한다. 서비스 미설정·연결 실패,
  다른 `404`나 잘못된 응답을 빈 브리프로 취급하지 않는다.
- 생성 요청은 본문 없이 기존 API를 호출한다. 진행 중에는 중복 클릭을 막고 POST를 자동
  재시도하지 않는다. 성공하면 조회를 갱신하고 새 생성과 기존 결과 재사용을 구분한다.
- 전달 대기·다른 생성 진행 중·연결 설정 오류·일시 실패를 구분한다. 통신 오류로 생성 결과를
  확인하지 못하면 최신 브리프를 먼저 다시 조회하도록 안내한다. 서버 오류의 요청 ID를 유지한다.
- 종료 시즌은 기존 브리프만 조회한다. 주차는 응답의 `weekStart`, 시각은 응답의 `zoneId`로 표시하며
  지난주 브리프를 이번 주 브리프로 표시하지 않는다.
- 불변 항목의 신호·심각도·시각과 변경 근거는 저장된 값을 표시한다. 현재 업무명과 이동 대상은
  별도 원본 조회로 확인하며, 불변 본문을 바꾸거나 과거 업무명으로 표시하지 않는다.
- 알 수 없는 신호 유형·상태와 이전 브리프의 `null` 메타데이터를 허용한다.
- 이 화면 추가로 BRIEF 운영 설정을 활성화하지 않는다. 실제 원격 HTTPS 조회·생성 검증은 별도다.

## 관련 문서

- [현재 관심 항목 요약과 필터 조회](../0009_brief-current-attention/spec.md)
- [브리프 탐색과 원본 업무 연결](../0010_brief-navigation-and-readiness/spec.md)
- [BATON API 계약](../0002_api-contract/spec.md)
- [BRIEF 연속성 신호 생산 계약](../0007_brief-continuity-signal-producer/spec.md)
- [BRIEF 조회·생성 애플리케이션 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
- BRIEF `PRD-0023: BATON 백엔드 경유 조회`
- BRIEF `PRD-0024: BATON 주도 에디션 생성`
- BRIEF `PRD-0025: BATON 서비스 API 인증과 비공개 연결`
