# PRD-0008: BATON 경유 주간 요약 조회와 생성

- 상태: 채택
- 결정일: 2026-08-29
- 구현 상태: BATON API·서비스 클라이언트·V27 실행 기록·비공개 HTTPS 연결·주간 요약 화면 구현. 로컬 서비스 간 검증 완료, 실제 원격 스테이징 검증 예정
- 범위: 인증된 BATON 사용자의 최신 주간 요약 조회와 이번 주 요약 생성

## 1. 목적

주간 요약은 생성 당시 내용을 보관하며 저장 후 수정하지 않는다. 각 생성 결과는 별도 버전으로 구분한다.

BATON 사용자는 BATON UI와 백엔드만 사용한다. BATON은 사용자 세션, 활동 중인 팀 구성원 여부와 작업 공간
접근 권한을 확인한다. BRIEF는 사용자 계정·세션·구성원 정보를 복제하지 않는다. BATON 백엔드는
권한 확인 뒤 BRIEF에 저장된 주간 요약을 그대로 전달하고, BATON에 저장된 시즌 시간대와 전달 완료 경계를
고정한 생성 명령을 호출한다.

## 2. 사용자 API

두 API는 활동 중인 `Account` 세션과 같은 팀의 구성원 연결을 요구한다. 계정 권한을 사용하는 팀은
기존 팀 권한을 확인하고, 공유 키 방식의 팀은 `X-Baton-Access-Key`를 검사한다. 생성은
변경 권한, 동적 CSRF, 정확한 `Origin`과 `Sec-Fetch-Site: same-origin`도 요구한다.

| 메서드 | 경로 | 성공 |
| --- | --- | --- |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions/latest` | `200`과 BRIEF에 저장된 최신 주간 요약 |
| `POST` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions` | 새 요약 `201`, 같은 결과 재사용 `200` |

최신 조회는 종료 시즌도 읽을 수 있지만 팀·시즌 범위와 현재 구성원이 활동 중인지 확인한다.
응답은 BRIEF가 저장한 요약 식별자, 요약 버전, 주간 경계, 시간대, 원본 커서, 규칙 버전과
항목 스냅샷을 그대로 표현한다. `ETag`는 BRIEF 응답 검증자를 유지하고
`Cache-Control: no-store`를 사용한다. `If-None-Match`는 Spring MVC 표준 조건부 응답으로
처리하며 BATON이 본문 해시나 별도 캐시를 만들지 않는다.

생성 요청 본문은 없다. BATON은 요청한 시즌의 IANA 시간대와 주입 `Clock`으로 현지 오늘을
계산하고, 그 날짜가 속한 월요일을 `weekStart`로 정한다. 브라우저가 주차·시간대·BRIEF
`workspaceId`를 제출하게 하지 않는다.

## 3. 전달 완료 경계와 실행 기록

생성 전에 BATON은 해당 팀·시즌의 BRIEF 업무 점검 아웃박스 최대 ID를 `deliveryWatermark`로
고정하고 그 범위의 모든 행이 `DELIVERED`인지 확인한다. 미완료 행이 있으면 BRIEF를 호출하지
않고 `409 BRIEF_DELIVERY_INCOMPLETE`를 반환한다. 영구 실패 아웃박스를 완료로 간주하지 않는다.

V27의 `brief_edition_generation_execution`은 다음 생성 요청과 결과를 고유하게 보존한다.

```text
(teamId, seasonId, weekStart, zoneId, deliveryWatermark)
```

실행 상태는 `PENDING`, `PROCESSING`, `SUCCEEDED`, `RETRYABLE_FAILURE`,
`PERMANENT_FAILURE`다. 작업은 1분 처리 임대(`lease`)와 작업 구분 토큰(`fencing token`)으로 선점한다. 만료되지 않은 같은
실행은 `409 BRIEF_GENERATION_IN_PROGRESS`, 성공한 실행은 저장한 주간 요약과 `ETag`를
재사용한다. 만료된 처리 임대나 재시도 가능 실패는 같은 실행 ID에서 새 토큰과 증가한 시도
횟수로 다시 선점한다. 외부 HTTP 호출 동안 MySQL 트랜잭션과 제품 행 잠금을 유지하지
않으며 완료 갱신은 현재 처리 임대 토큰이 일치할 때만 허용한다.

BRIEF 응답의 `workspaceId`와 `seasonId`가 요청 범위와 다르면 노출하거나 성공으로 저장하지
않고 영구 구성 오류로 끝낸다. 생성 응답은 `weekStart`와 `zoneId`도 생성 요청과 같아야 한다.
둘 중 하나라도 다르면 성공으로 저장하지 않고 기존 `BRIEF_SCOPE_MISMATCH` 영구 실패로
기록하며, 사용자에게 `503 BRIEF_CONFIGURATION_ERROR`를 반환한다. 최신 조회는 요청한
팀·시즌 범위만 확인하고 현재 주차나 시간대와 일치하도록 제한하지 않는다.

## 4. BRIEF 서비스 연결

- 이벤트 송신 토큰과 다른 서비스 전용 Bearer를 사용한다.
- `baton.brief.service-api.enabled`는 기본 `false`다.
- 활성화할 때 기본 URL은 경로 없는 HTTPS 출처만 허용하며 리디렉션을 따르지 않는다.
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

최신 주간 요약은 BRIEF에 저장된 전체 내용을 반환한다. 항목의 `aggregateRevision`과 `revisionGap`은
이전 BRIEF 주간 요약에서 근거를 알 수 없으면 `null`일 수 있다.
항목의 `section`은 생성 당시의 `CURRENT_WEEK`(이번 주 변경)·`CARRY_OVER`(이전 주부터 미해결)를
그대로 중계한다. BRIEF V9 이전 항목은 `null`이며 서버·브라우저가 시각으로 재분류하지 않는다.
주간 요약 선정 규칙 `2`와 항목 투영 규칙 `1`은 서로 다른 버전이다. 기존 BATON 생성 성공 실행의
재사용 계약은 유지하며, 기존 결과를 분류하려고 과거 주간 요약을 덮어쓰지 않는다.

생성 응답은 다음 필드만 반환한다.

| 필드 | 의미 |
| --- | --- |
| `executionId` | 재시도해도 유지되는 BATON 생성 요청 UUID |
| `deliveryWatermark` | 생성 전에 완료를 확인한 BATON BRIEF 아웃박스 최대 ID |
| `editionId` | BRIEF 주간 요약 UUID |
| `generation` | 작업 공간·시즌별 요약 버전 |
| `sourceCursor` | BRIEF 로컬 수신 순서 커서 |
| `created` | 새 요약을 만들었으면 `true`, 직전 결과를 재사용했으면 `false` |

새 주간 요약 `201`은 최신 조회 경로를 `Location`으로 반환한다. 두 성공 상태 모두 주간 요약
`ETag`, `Cache-Control: no-store`와 `X-Request-ID`를 포함한다.

## 6. 화면과 비목표

### BATON 화면 연결

오늘 화면의 ‘주간 업무 점검’에서 저장된 요약을 조회하고 생성한다. 화면 표시와 오류 처리는
[주간 업무 점검 화면](#8-주간-업무-점검-화면), 이력·비교·공유·인쇄는
[PRD-0010](../0010_brief-navigation-and-readiness/spec.md)을 따른다.

React Query와 공용 HTTP 클라이언트를 사용한다. 생성 요청의 동적 CSRF 헤더는 공용 계정 API에서
받고 `Origin`·`Sec-Fetch-Site`는 브라우저에 맡긴다. 서버의 주차·전달 경계·실행 기록을 복제하지 않는다.
화면은 전체 본문을 조회하며 별도 ETag 저장·조건부 요청 처리를 추가하지 않는다. API의 조건부 조회는 유지한다.
브라우저에 BRIEF 서비스 토큰을 두지 않는다.

### 제외 범위

- 브라우저의 BRIEF 직접 호출, BRIEF 사용자 계정·세션·CORS
- BATON의 BRIEF 주간 요약 내용 재계산·수정·캐시 저장소
- BRIEF 생성 스케줄러, 대상 레지스트리나 별도 Idempotency-Key
- 생성 실행 운영자 재처리 API
- mTLS와 인증서 자동 발급·교체 체계

주간 요약 이력·단건·비교와 생성 전 준비 상태 안내는
[PRD-0010](../0010_brief-navigation-and-readiness/spec.md)에서 추가했다. 같은 사용자 권한과
저장된 본문·ETag를 유지하며 조회 뒤에도 실제 생성에서 전달 경계를 다시 확인한다.

## 7. 검증

- 애플리케이션 테스트는 계정·팀 범위, 시즌 시간대의 월요일, 전달 미완료 차단과 BRIEF 범위
  불일치 거부를 확인한다. 생성 응답의 주차 또는 시간대가 요청과 다르면 영구 실패를
  기록하고 성공 저장을 호출하지 않는지도 확인한다.
- MySQL 통합 테스트는 V27 실행 고유 경계, 만료된 처리 임대 회수, 오래된 토큰 거부, 성공 재사용과
  전달 미완료 기록을 확인한다.
- 외부 어댑터 테스트는 별도 Bearer 토큰, 생성 JSON, `ETag`, 인증·재시도 결과 분류를 확인한다.
- 보안 통합 테스트는 미인증 `401`, 조건부 조회 `304`, 생성 CSRF·동일 출처 경계를 확인한다.
- `BriefEditionRestDocsTest`가 두 사용자 API의 OpenAPI 계약을 생성한다.
- 운영 셸 테스트는 별도 토큰과 truststore, 서비스 Compose override와
  `Internal=true` 네트워크를 확인한다.
- `brief-edition.spec.ts`는 API 대역으로 미생성·전달 대기, 동적 CSRF·본문 없는 생성, 새 생성과
  재사용, 저장 시간대·이전 근거 `null`, 종료 시즌 생성 차단과 권한 거부를 확인한다.
  브라우저 테스트를 실제 공개 HTTPS 배포의 종단 간 검증으로 확대하지 않는다.

선택 실행 교차 서비스 테스트는 실제 BATON 앱·MySQL 8.4와 BRIEF 서비스 Caddy·앱·
PostgreSQL 18.6을 함께 기동해 다음을 확인했다.

- 계정 로그인·활동 중인 팀 구성원 연결·작업 공간 접근 키를 거친 HTTPS 생성과 최신 조회
- BRIEF `ETag` 전달과 `If-None-Match` 조건부 `304`
- BRIEF 저장 뒤 BATON 실행 성공 상태를 재시도 상태로 되돌린 응답 유실 재현, 같은
  `executionId`·`editionId`와 주간 요약 한 건 유지
- BRIEF의 새·직전 서비스 토큰 중첩 중 직전 토큰 성공, 직전 값 제거 뒤 기존 BATON의
  `503`, BATON을 새 토큰으로 전환한 뒤 조회와 새 범위 생성 성공
- 서비스 Caddy의 비루트 UID `10001`, Linux 파일 권한 없음, 읽기 전용 루트 파일 시스템,
  `cap_drop=ALL`, 호스트 포트 없음과 Authorization 원문 로그 비노출

네트워크는 BATON data, BRIEF data·proxy와 `Internal=true` 서비스 경계를 분리했다. 이
검증의 인증서는 로컬 생성 CA이고 응답 유실은 실제 TCP 절단이 아닌 저장 상태 되돌리기다.
공인 DNS·ACME와 서로 다른 스테이징 호스트 사이의 호출은 아직 검증하지 않았다.

## 8. 주간 업무 점검 화면

- 오늘 화면의 `주간 업무 점검`에서 현재 점검 항목과 저장된 주간 요약을 조회한다. 현재 점검 항목은
  PRD-0009, 주간 요약 이력·비교·공유·인쇄와 원본 업무 연결은 PRD-0010을 따른다.
- 계정·팀·시즌·접근 키별로 조회와 화면 선택을 구분한다. 인증·권한 확인에 실패하면 이전
  내용을 숨기고, 브라우저 저장소에 주간 요약 내용을 쓰지 않는다.
- `BRIEF_EDITION_NOT_FOUND`만 아직 생성한 주간 요약이 없는 상태로 표시한다. 서비스 미설정·연결 실패,
  다른 `404`나 잘못된 응답을 빈 주간 요약으로 취급하지 않는다.
- 생성 요청은 본문 없이 기존 API를 호출한다. 진행 중에는 중복 클릭을 막고 POST를 자동
  재시도하지 않는다. 성공하면 조회를 갱신하고 새 생성과 기존 결과 재사용을 구분한다.
- 전달 대기·다른 생성 진행 중·연결 설정 오류·일시 실패를 구분한다. 통신 오류로 생성 결과를
  확인하지 못하면 최신 주간 요약을 먼저 다시 조회하도록 안내한다. 서버 오류의 요청 ID를 유지한다.
- 종료 시즌은 기존 주간 요약만 조회한다. 주차는 응답의 `weekStart`, 시각은 응답의 `zoneId`로 표시하며
  지난주 요약을 이번 주 요약으로 표시하지 않는다.
- ‘저장된 주간 요약’을 펼치면 최신 주간 요약을 조회한다. 생성 당시의 주차·시간대·시각·요약 버전과 항목을 표시한다.
- 저장된 `section`으로 이번 주 변경과 이전 주부터 미해결 항목을 나눈다. 이전 항목의 `null`은 분류 미기록 그룹으로 표시한다.
- 항목의 유형·심각도·시각과 변경 근거는 저장 당시 값을 표시한다. 현재 업무명과 이동 대상은
  별도 원본 조회로 확인하며, 저장된 본문을 바꾸거나 과거 업무명으로 표시하지 않는다.
- 알 수 없는 신호 유형·심각도·상태는 응답 오류로 처리한다. 이전 요약에서 `section`이 없거나 `null`이면 분류 미기록으로 표시한다. `aggregateRevision`·`revisionGap`은 함께 `null`인 경우만 변경 근거 미기록으로 허용한다.
- 이 화면 추가로 BRIEF 운영 설정을 활성화하지 않는다. 실제 원격 HTTPS 조회·생성 검증은 별도다.

## 관련 문서

- [현재 점검 항목 요약과 필터 조회](../0009_brief-current-attention/spec.md)
- [주간 요약 탐색과 원본 업무 연결](../0010_brief-navigation-and-readiness/spec.md)
- [BATON API 계약](../0002_api-contract/spec.md)
- [BRIEF 업무 점검 이벤트 발행 계약](../0007_brief-continuity-signal-producer/spec.md)
- [BRIEF 조회·생성 애플리케이션 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
- BRIEF `PRD-0023: BATON 백엔드 경유 조회`
- BRIEF `PRD-0024: BATON 주도 주간 요약 생성`
- BRIEF `PRD-0025: BATON 서비스 API 인증과 비공개 연결`
