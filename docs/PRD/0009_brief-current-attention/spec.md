# PRD-0009: BRIEF 주간 업무 점검 조회

- 상태: 채택
- 결정일: 2026-08-31
- 범위: 인증된 팀 구성원이 BRIEF 현재 점검 항목의 요약과 필터 목록을 조회하는 흐름

## 목적과 권한

‘오늘’ 화면에서 BRIEF가 수신한 이벤트를 바탕으로 계산한 점검 항목을 조회한다.
BATON의 ‘조치할 항목’과 저장된 주간 요약은 각각 따로 제공하며, 두 서비스의 반영 시점은 다를 수 있다.

PRD-0008과 같은 활동 중 계정·팀 멤버십을 요구한다. 계정 권한을 사용하는 팀은 기존 팀
읽기 권한을 검사하고, 공유 키 방식의 팀은 `X-Baton-Access-Key`도 검사한다.
BATON은 팀·시즌과 접근 권한을 확인한 뒤에만 BRIEF를 호출한다. 종료 시즌도
읽을 수 있으며 BRIEF의 빈 결과나 개수 `0`을 권한 또는 시즌 존재 판정에 사용하지 않는다.

## 화면 용어

화면 제목은 ‘주간 업무 점검’이다. 점검 항목의 `ACTIVE`는 ‘미해결’,
`RESOLVED`는 ‘해결’로 표시한다. 이 상태는 점검에서 발견한 문제의 상태이며 업무 완료 여부가 아니다.
`aggregateRevision`은 ‘원본 변경 번호’, `sourceReference`는 ‘원본 항목 ID’,
`revisionGap`은 ‘변경 기록 누락’으로 표시한다. 누락 개수는 해당 항목 수이며 누락 건수가 아니다.
반복 업무 관련 신호는 ‘반복 업무 누락·반복 업무 지연 누적’, 역할 준비 신호는 ‘인수인계 준비 미완료’로 표시한다. API 필드·상태 코드와 판정 기준은 그대로 유지한다. 원본 ID와 변경 번호는 각 목록 아래의 ‘연동 상세’에 모아 기본으로 접어둔다.

## 사용자 API

| 메서드 | 경로 | 성공 응답 |
| --- | --- | --- |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items/summary` | `200 {highCount, mediumCount, revisionGapCount}` |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items` | `200 {items, nextCursor}` |
| `GET` | `/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items/transitions` | `200 {transitions, nextBeforeAggregateRevision}` |

모든 성공 응답은 `Cache-Control: no-store`와 `X-Request-ID`를 제공한다. 현재 상태 조회에는
저장된 주간 요약의 `ETag`를 재사용하지 않는다. 조회에는 CSRF token을 요구하지 않는다.

### 미해결 항목 요약

세 개수는 음수가 아닌 64비트 정수이며 항상 존재한다.

- `highCount`: 미해결 `HIGH` 항목 수
- `mediumCount`: 미해결 `MEDIUM` 항목 수
- `revisionGapCount`: 누적 공백이 기록된 미해결 항목 수

공백 항목 수는 심각도별 개수와 겹친다. 세 값을 더하지 않는다. 빈 범위의 요약은 모두 `0`이다.
요약에는 상태·심각도·변경 기록 누락 필터를 적용하지 않는다.

### 이번 주 해결 요약

`GET /api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items/resolutions`는 같은 권한
확인 뒤 시즌 IANA 시간대와 주입한 `Clock`으로 현재 월요일을 정하고 BRIEF PRD-0030을 조회한다.
사용자는 임의 주차·시간대를 지정하지 않는다. 종료 시즌도 현재 달력 주차 기준으로 읽는다.

응답은 `weekStart`, `zoneId`, `windowStart`, `windowEnd`, `evaluatedAt`, `resolvedCount`,
`items`, `nextCursor`다. 목록 항목은 `reasonCode`, `sourceReference`, `resolvedAt`,
`resolvedRevision`을 포함한다. 시각과 리비전은 미해결에서 해결로 바뀐 원본 기록의 값이다.

선택적 `afterEventType`과 `afterSourceReference`는 함께 제공하는 배타 커서다. `limit`은
기본 20, 범위 1~100이며 BRIEF에 그대로 전달한다. 목록은 복합 식별자 오름차순이다.
`resolvedCount`는 커서와 무관한 전체 개수이며 목록과 같은 조회에서 같은 조건으로 계산한다.
마지막 페이지의 `nextCursor`는 null이고 빈 페이지라도 전체 개수는 유지한다.
기간은 현지 월요일 자정 이상부터 다음 월요일 자정 미만이다. 클라이언트는 BRIEF가 반환한
주차·시간대·실제 자정 경계를 요청과 대조한다. 목록 누락, 음수 개수, 잘못된 항목·해결 시각과
마지막 항목에 맞지 않는 커서는 계약 오류로 처리한다. HTTP 입력 오류는 기존 `400`으로 처리한다.
배포 시 BRIEF의 상세 응답을 먼저 반영한다. 이전 응답의 목록 누락을 빈 목록으로 대체하지 않는다.
성공은 `200`·`no-store`, 연결 실패는 `503`이며 실패를 0건으로 바꾸지 않는다.

연속된 마지막 미해결→해결 전환의 원본 발생 시각이 이번 주 안이고 현재도 해결 상태인 항목만
센다. 같은 해결 상태의 연속 갱신은 시점을 옮기지 않는다. 재발·최초 해결 상태와 해결
시점을 확정할 수 없는 공백은 제외한다. 이 수치는 현재 확인 가능한 해결 항목 수이며 전체
업무 완료 수·주간 요약의 `removed` 개수·과거 저장 내용이 아니다.

화면은 미해결 요약과 별도로 주차·시간대·확인 시각과 함께 표시하고 첫 페이지 새로고침 때
다시 읽는다. 건수를 누르면 해결 목록·시점을 펼치며 원본 항목 ID와 해결 시 변경 번호는
목록 아래의 ‘연동 상세’에 접어 둔다. 현재 업무 이름과 이동은 PRD-0010의 기존 권한 확인·원본 연결을 사용한다.
목록 페이지를 넘긴 뒤 응답 주차·시간대가 이전 페이지와 다르면 항목을 감추고 첫 페이지
새로고침을 안내한다. 페이지 사이의 원본 변경을 고정된 스냅샷으로 해석하지 않는다.
오류일 때 이전 건수·목록을 감추고 재시도를 제공하며 권한 거부는 기존 점검 항목 영역과
함께 감춘다. 원본 이벤트·스키마·투영 규칙은 변경하지 않는다.

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
필터링하지 않는다. 식별 기준인 `eventType`, `sourceReference` 오름차순과 배타 커서를 유지한다.
두 커서 필드는 함께 제공하거나 함께 생략한다. 원본 참조는 내부 형식을 해석하지 않고 BRIEF가 반환한 값을 그대로 사용한다. BRIEF의 문자·길이 규칙을 BATON 검증기로 복제하지 않고, BRIEF의 잘못된
조회 조건 `400`을 안전한 `400 INVALID_INPUT`으로 변환한다.

`items`의 각 항목은 `reasonCode`, `severity`, `sourceReference`, `status`, `observedAt`,
`aggregateRevision`, `ruleVersion`, `revisionGap`을 반환한다. `nextCursor`는 항상 존재하며
마지막 페이지는 `null`이다. 상태·심각도·원본 리비전은 BRIEF 값을 그대로 표시한다.
BATON 이벤트 v1 세 종류와 v2 다섯 종류를 모두 읽되 새로운 신호 의미를 만들지 않는다.

목록과 요약은 서로 다른 요청 시점의 값이다. 개수 일치나 요청 간 스냅샷을 보장하지 않는다.
필터·팀·시즌이 바뀌거나 최신 상태를 새로고침하면 커서를 버리고 첫 페이지부터 읽는다.
`revisionGap=false`는 공백 탐지 기록이 없다는 뜻이지 원본 전달 완료 보장이 아니다.

## 변경 이력 상세

`eventType`과 `sourceReference`로 같은 점검 항목을 선택한다. 선택적
`beforeAggregateRevision`은 양의 64비트 배타 커서이고 `limit`은 기본 `20`, 범위 `1..100`이다.
원본 참조는 목록에서 받은 값을 그대로 사용하고 URL 인코딩은 공용 HTTP 클라이언트에 맡긴다.
문자·길이 검증은 BRIEF가 소유하며 거부한 `400`은 기존 `INVALID_INPUT`으로 중계한다.

`transitions`는 실제 적용 기록만 원본 리비전 내림차순으로 반환한다. 각 항목은 `eventId`,
`aggregateRevision`, `state`, `observedAt`, `detectedRevisionGap`, `sourceSeverity`를 포함한다.
`sourceSeverity`는 v2의 원본 `CRITICAL`·`WARNING`이며 v1은 `null`이다. 화면에서는 긴급·주의·
미기록으로 표시하고 현재 표시 심각도로 과거 값을 추정하지 않는다. 마지막 페이지의
`nextBeforeAggregateRevision`은 `null`이다. 기록이 없으면 빈 배열이며 원본 존재 여부를 판정하지 않는다.
같은 상태에서도 심각도·근거 변경으로 전이가 생길 수 있어 미해결·해결 전환 횟수로 집계하지 않는다.
`detectedRevisionGap`은 해당 전이에서 새로 발견한 공백이며 현재 항목의 누적 공백을 복제하지 않는다.
원문 payload·fingerprint·미적용 수신 증거와 재구축 API는 노출하지 않는다.

목록의 ‘변경 이력 보기’에서 열고 과거 페이지와 최신 이력 새로고침을 제공한다. 목록 필터·
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

- ‘오늘’ 화면의 접힌 ‘주간 업무 점검’ 패널을 열 때 조회한다.
- 로그인·팀 구성원 연결·활동 상태가 준비되지 않으면 필요한 다음 행동을 안내한다.
- 요약 버튼으로 활성 심각도 또는 공백 조건의 첫 페이지를 연다.
- 상태·심각도·공백 선택, 다음 페이지와 ‘목록 새로고침’을 제공한다.
- 로딩·조회 실패·빈 결과를 구분하고 권한 거부 때 이전 요약과 목록을 감춘다.
- React Query 키에 계정·팀·시즌·접근 키·필터·커서를 포함한다. 서버 응답을 별도 상태나
  브라우저 영속 저장소에 복제하지 않는다.
- 원본 참조를 URL이나 역할 ID로 추측하지 않는다. PRD-0010의 BATON 원본 연결 조회로 현재
  업무명과 이동 대상을 확인하며, 연결할 수 없는 이전 참조는 텍스트로 남긴다.
- 항목별 상태 변화와 PRD-0008의 저장된 주간 요약 조회·생성은 필요할 때 펼쳐서 사용한다.

## 검증 범위

- REST Docs는 요약·목록 표현, 필터·커서 전달과 대표 오류를 검증하고 OpenAPI·TypeScript를 생성한다.
- 실제 보안 필터는 두 경로의 미인증을 거부한다. 애플리케이션 테스트는 활동 종료 구성원의
  외부 조회 차단을 확인한다.
- 외부 클라이언트 테스트는 `false` 필터·특수문자 커서 인코딩, 필수 요약 필드 누락과 실패 분류를 확인한다.
- 기존 선택 실행 `BriefEditionHttpsEndToEndTest`는 실제 두 JAR·MySQL 8.4·PostgreSQL 18.6·
  서비스 Caddy에서 로그인·멤버십·접근 키, 빈 요약, 심각도·변경 기록 누락 교집합과 필터된 다음 페이지,
  잘못된 시즌·접근 키와 서비스 token 거부를 확인한다. 조회 입력은 격리된 BRIEF DB의 대표
  현재 점검 상태 데이터로 준비하며 이벤트 생산·전달 완료의 근거로 사용하지 않는다.
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

- [주간 요약 탐색·업무 연결·생성 준비](../0010_brief-navigation-and-readiness/spec.md)

- [BATON API 계약](../0002_api-contract/spec.md)
- [주간 요약 조회와 생성](../0008_brief-edition-query-and-generation/spec.md)
- [BRIEF 조회·생성 경계](../../ADR/0020_brief-query-generation-boundary/adr.md)
- BRIEF PRD-0023·0027·0028
