# PRD-0009: BATON 경유 BRIEF 관심 항목 요약과 필터 조회

- 상태: 채택
- 결정일: 2026-08-31
- 범위: 인증된 팀 구성원이 BRIEF 현재 관심 항목의 요약과 필터 목록을 조회하는 흐름

## 목적과 권한

‘오늘’ 화면에서 BRIEF가 마지막으로 수신한 관심 항목을 읽는다. BATON 자체 레이더의 현재
판정이나 BRIEF 불변 에디션을 대체하지 않는다. 두 서비스의 반영 시점은 다를 수 있다.

PRD-0008과 같은 `Account` 세션, 활동 중인 같은 팀 멤버십과 `X-Baton-Access-Key`를 요구한다.
BATON은 팀·시즌과 접근 키를 확인하고 멤버십을 검사한 뒤에만 BRIEF를 호출한다. 종료 시즌도
읽을 수 있으며 BRIEF의 빈 결과나 개수 `0`을 권한 또는 시즌 존재 판정에 사용하지 않는다.

## 사용자 API

| 메서드 | 경로 | 성공 응답 |
| --- | --- | --- |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items/summary` | `200 {highCount, mediumCount, revisionGapCount}` |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items` | `200 {items, nextCursor}` |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items/transitions` | `200 {transitions, nextBeforeAggregateRevision}` |

모든 성공 응답은 `Cache-Control: no-store`와 `X-Request-ID`를 제공한다. 현재 투영에는
불변 에디션 `ETag`를 재사용하지 않는다. 조회에는 CSRF token을 요구하지 않는다.

### 활성 요약

세 개수는 음수가 아닌 64비트 정수이며 항상 존재한다.

- `highCount`: 활성 `HIGH` 항목 수
- `mediumCount`: 활성 `MEDIUM` 항목 수
- `revisionGapCount`: 누적 공백이 기록된 활성 항목 수

공백 항목 수는 심각도별 개수와 겹친다. 세 값을 더하지 않는다. 빈 범위의 요약은 모두 `0`이다.
요약에는 상태·심각도·공백 필터를 적용하지 않는다.

### 목록 조건과 커서

| 매개변수 | 기본값과 의미 |
| --- | --- |
| `status` | `ACTIVE` 기본, `RESOLVED` 선택 가능 |
| `severity` | `HIGH` 또는 `MEDIUM`, 생략하면 제한 없음 |
| `revisionGap` | `true` 또는 `false`, 생략하면 제한 없음 |
| `afterEventType` | 이전 `nextCursor.eventType` |
| `afterSourceReference` | 이전 `nextCursor.sourceReference` |
| `limit` | `1..100`, 기본 `20` |

조건은 교집합이며 BRIEF가 조건을 적용한 뒤 페이지를 읽는다. BATON에서 전체 목록을 내려받아
필터링하지 않는다. 정체성의 `eventType`, `sourceReference` 오름차순과 배타 커서를 유지한다.
두 커서 필드는 함께 제공하거나 함께 생략한다. 원본 참조는 불투명 문자열이며 BRIEF가 반환한
값을 그대로 사용한다. BRIEF의 문자·길이 규칙을 BATON 검증기로 복제하지 않고, BRIEF의 잘못된
조회 조건 `400`을 안전한 `400 INVALID_INPUT`으로 변환한다.

`items`의 각 항목은 `reasonCode`, `severity`, `sourceReference`, `status`, `observedAt`,
`aggregateRevision`, `ruleVersion`, `revisionGap`을 반환한다. `nextCursor`는 항상 존재하며
마지막 페이지는 `null`이다. 상태·심각도·원본 리비전은 BRIEF 값을 그대로 표시한다.
BATON 이벤트 v1 세 종류와 v2 다섯 종류를 모두 읽되 새로운 신호 의미를 만들지 않는다.

목록과 요약은 서로 다른 요청 시점의 값이다. 개수 일치나 요청 간 스냅샷을 보장하지 않는다.
필터·팀·시즌이 바뀌거나 최신 상태를 새로고침하면 커서를 버리고 첫 페이지부터 읽는다.
`revisionGap=false`는 공백 탐지 기록이 없다는 뜻이지 원본 전달 완료 보장이 아니다.

## 상태 전이 상세

`eventType`과 `sourceReference`로 같은 관심 항목을 선택한다. 선택적
`beforeAggregateRevision`은 양의 64비트 배타 커서이고 `limit`은 기본 `20`, 범위 `1..100`이다.
원본 참조는 목록에서 받은 값을 그대로 사용하고 URL 인코딩은 공용 HTTP 클라이언트에 맡긴다.
문자·길이 검증은 BRIEF가 소유하며 거부한 `400`은 기존 `INVALID_INPUT`으로 중계한다.

`transitions`는 실제 적용 기록만 원본 리비전 내림차순으로 반환한다. 각 항목은 `eventId`,
`aggregateRevision`, `state`, `observedAt`, `detectedRevisionGap`, `sourceSeverity`를 포함한다.
`sourceSeverity`는 v2의 원본 `CRITICAL`·`WARNING`이며 v1은 `null`이다. 화면에서는 긴급·주의·
미기록으로 표시하고 현재 표시 심각도로 과거 값을 추정하지 않는다. 마지막 페이지의
`nextBeforeAggregateRevision`은 `null`이다. 기록이 없으면 빈 배열이며 원본 존재 여부를 판정하지 않는다.
같은 상태에서도 심각도·근거 변경으로 전이가 생길 수 있어 활성·해소 전환 횟수로 집계하지 않는다.
`detectedRevisionGap`은 해당 전이에서 새로 발견한 공백이며 현재 항목의 누적 공백을 복제하지 않는다.
원문 payload·fingerprint·미적용 수신 증거와 재구축 API는 노출하지 않는다.

목록의 ‘상태 변화 보기’에서 열고 과거 페이지와 최신 전이 새로고침을 제공한다. 목록 필터·
페이지·팀·시즌 변경과 목록 새로고침은 열린 항목과 전이 커서를 초기화한다. 조회 실패를 빈 이력으로
바꾸지 않으며 전이 조회의 `401`·`403`도 이전 목록과 요약을 감춘다.

## 서비스 연결과 실패

기존 BRIEF 서비스 API 설정·별도 Bearer·비공개 HTTPS와 `RestClient`를 재사용한다. 원본 이벤트
전달 token을 재사용하거나 공개 BRIEF Caddy 경로를 늘리지 않는다. 설정 기본값은 비활성이다.

| 결과 | 사용자 응답 |
| --- | --- |
| 계정 세션 없음 | `401 AUTHENTICATION_REQUIRED` |
| 활동 중인 같은 팀 멤버십 없음 | `403 BRIEF_ACCESS_DENIED` |
| 잘못된 접근 키 또는 팀·시즌 | 기존 워크스페이스 `403`·`404` |
| 잘못된 enum·Boolean·limit·커서 조합, BRIEF 목록 조건 거부 | `400 INVALID_INPUT` |
| BRIEF 비활성, 연결 실패, `429`·`5xx` | `503 BRIEF_UNAVAILABLE` |
| BRIEF 인증 실패·기타 계약 상태, 본문 누락·잘못된 표현 | `503 BRIEF_CONFIGURATION_ERROR` |

BRIEF 원문 오류나 자격 증명은 사용자에게 전달하지 않는다. 연결 실패와 계약 오류를 `0`이나
빈 목록으로 바꾸지 않는다. 브라우저는 64비트 JSON 수를 안전한 정수로 표현할 수 없으면
반올림해서 표시하지 않고 응답 오류로 다룬다.

## 화면

- ‘오늘’ 화면의 접힌 `BRIEF 관심 항목` 패널을 열 때 조회한다.
- 로그인·팀 구성원 연결·활동 상태가 준비되지 않으면 필요한 다음 행동을 안내한다.
- 요약 버튼으로 활성 심각도 또는 공백 조건의 첫 페이지를 연다.
- 상태·심각도·공백 선택, 다음 페이지와 첫 페이지 새로고침을 제공한다.
- 로딩·조회 실패·빈 결과를 구분하고 권한 거부 때 이전 요약과 목록을 감춘다.
- React Query 키에 계정·팀·시즌·접근 키·필터·커서를 포함한다. 서버 응답을 별도 상태나
  브라우저 영속 저장소에 복제하지 않는다.
- 원본 참조를 URL이나 역할 ID로 추측하지 않고 텍스트로 표시한다.
- 항목별 상태 변화와 PRD-0008의 저장된 브리프 조회·생성은 필요할 때 펼쳐서 사용한다.

## 검증 범위

- REST Docs는 요약·목록 표현, 필터·커서 전달과 대표 오류를 검증하고 OpenAPI·TypeScript를 생성한다.
- 실제 보안 필터는 두 경로의 미인증을 거부한다. 애플리케이션 테스트는 활동 종료 구성원의
  외부 조회 차단을 확인한다.
- 외부 클라이언트 테스트는 `false` 필터·특수문자 커서 인코딩, 필수 요약 필드 누락과 실패 분류를 확인한다.
- 기존 선택 실행 `BriefEditionHttpsEndToEndTest`는 실제 두 JAR·MySQL 8.4·PostgreSQL 18.6·
  서비스 Caddy에서 로그인·멤버십·접근 키, 빈 요약, 심각도·공백 교집합과 필터된 다음 페이지,
  잘못된 시즌·접근 키와 서비스 token 거부를 확인한다. 조회 입력은 격리된 BRIEF DB의 대표
  현재 투영으로 준비하며 이벤트 생산·전달 완료의 근거로 사용하지 않는다.
- `brief-attention.spec.ts`는 브라우저에서 요약 선택·커서 초기화·필터 유지·빈 결과·장애·권한
  거부, 상태 전이의 과거 페이지·공백 의미와 로그인 안내를 확인한다.
- 같은 HTTPS 교차 시나리오에서 격리된 BRIEF 수신 기록을 준비해 특수문자 참조의 상태 전이·배타
  커서와 공백 근거를 확인한다. 원본 생산 검증은 별도 `BriefDeliveryEndToEndTest`가 담당한다.
  브라우저 테스트는 API 대역을 사용하므로 실제 브라우저→두 백엔드 전체 검증은 아니다.

로컬 HTTPS 인증서는 검증 전용 CA다. 공인 DNS·실제 운영 비밀·스테이징 활성화와 이벤트
계약 팩의 안정 버전 승격은 포함하지 않는다.

## 비목표

- 현재 항목의 상태 변경, 원본 재전달과 공백 해제
- 운영자 수신 증거·재구축 API 공개
- 새 테이블·인덱스·마이그레이션·캐시 저장소·스케줄러
- 목록 전체 개수·정렬 선택·자유 검색·원본 링크 추측

## 관련 문서

- [BATON API 계약](../0002_api-contract/spec.md)
- [BRIEF 에디션 조회와 생성](../0008_brief-edition-query-and-generation/spec.md)
- [BRIEF 조회·생성 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
- BRIEF PRD-0023·0027·0028
