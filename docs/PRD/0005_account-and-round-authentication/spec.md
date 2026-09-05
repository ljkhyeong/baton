# PRD-0005: 계정 인증과 ROUND 참여권 계약

- 상태: 채택
- 작성일: 2026-08-08
- 대상: BATON 계정 로그인, 신원 연결, ROUND 참여권

## 1. 목적

BATON의 파일럿 공유 접근 키를 사용자 신원으로 확대 해석하지 않고, Google·Naver·자체
이메일 로그인을 공급자 중립 `Account` 모델로 수용한다. 처음 보는 신원은 별도 `Account`를
만들며, 인증된 계정이 팀 구성원과 연결된 경우에만 BATON이 ROUND 방 참여권을 발급한다.

이 문서는 로그인 공급자, 내부 신원, 브라우저 세션과 ROUND 참여권의 첫 계약을 고정한다.
팀 초대와 세부 권한 행렬 전체를 완료된 것으로 선언하지 않는다.

## 2. 핵심 원칙

### 공급자와 무관한 내부 계정

- BATON `Account.id`는 정규 형식 UUID이며 재할당하지 않는다.
- Google OIDC `sub`, Naver 프로필 `response.id`, 정규화한 자체 이메일은 각각 외부 신원의
  식별자일 뿐 BATON 계정 ID가 아니다.
- ROUND 참여권의 `sub`는 항상 BATON `Account.id` 문자열이다.
- 표시 이름, 이메일, 팀 구성원 이름, 공유 접근 키는 계정 식별자로 사용하지 않는다.

### 이메일로 자동 병합하지 않는다

- `(provider, providerSubject)`가 외부 신원의 유일 키다.
- 서로 다른 공급자가 같은 이메일을 반환해도 계정을 자동 병합하지 않는다.
- 새 외부 신원을 기존 계정에 붙이는 공개 연결 API는 제공하지 않는다. 최근 재인증으로
  계정 소유를 다시 증명하고 OAuth 상태를 결합하는 단계 강화 인증 계약을 먼저 추가한다.
- 다른 계정에 이미 연결된 신원은 계정 병합으로 추측하지 않고 충돌로 거부한다.

### 프로토콜의 실제 의미를 보존한다

- Google 로그인은 OpenID Connect이며 검증된 ID Token의 `sub`를 사용한다.
- Naver 로그인은 현재 제공되는 OAuth 2.0 인가 코드 흐름과 프로필 API를 사용하며
  `response.id`를 사용한다. Naver 흐름을 OIDC 또는 ID Token 검증으로 잘못 문서화하지 않는다.
- 자체 이메일 로그인은 검증된 이메일과 비밀번호 자격 증명을 사용한다.

## 3. 계정과 신원 모델

| 개념 | 소유 상태 |
| --- | --- |
| Account | 내부 UUID, 표시 이름, 생성·변경 시각 |
| AccountIdentity | 공급자, 공급자 주체 식별자, 이메일 스냅샷과 검증 여부, 마지막 인증 시각 |
| LocalCredential | 신원 ID, Spring Security 인코더 형식의 비밀번호 해시, 생성·변경 시각 |
| EmailVerificationChallenge | 원문이 아닌 토큰 해시, 만료·소비 시각 |
| AccountMembership | 계정, 팀, 기존 BATON 구성원의 명시적 연결 |

자체 이메일은 앞뒤 공백을 제거하고 Unicode나 공급자별 별칭 규칙을 추측하지 않은 채
소문자화한다. Google·Naver가 반환한 이메일은 연락처 스냅샷이며 외부 주체 식별자를 대체하지
않는다.

비밀번호 원문은 저장하거나 로그에 남기지 않는다. 챌린지에는 이메일 검증 토큰 해시만
저장한다. 커밋 이후 검증 메일을 만들기 위한 토큰과 수신 주소는 애플리케이션의
AES-256-GCM으로 암호화해 전달 아웃박스에 보관한다. 메시지별 12바이트 무작위 논스와
신원 ID, `Account` ID, 애플리케이션이 계산한 도메인 분리 챌린지 해시, 마이크로초
만료 시각을 AAD로 묶고, `DELIVERED`·`SUPERSEDED`·`FAILED` 종료 상태 전환과 함께
암호문·논스·해시 스냅샷을 제거한다. 암호화 키는 DB 밖의 프로덕션 비밀값 파일로
주입하며 DB·백업 읽기 권한도 자격 증명 수준으로 제한한다.
정규화 이메일과 검증 여부는 `AccountIdentity`가 소유한다. 비밀번호는 Spring Security
`DelegatingPasswordEncoder`의 `{id}encoded` 형식으로 저장한다. 신규 자격 증명은 128자
Unicode 계약에 72바이트 제한을 만들지 않는 PBKDF2로 인코딩하고, 기존 `{bcrypt}` 해시는 검증
뒤 점진적으로 업그레이드할 수 있게 유지한다.

## 4. 브라우저 인증

BATON은 동일 공개 HTTPS 출처의 서버 측 `HttpSession`을 사용한다.

- 세션 쿠키는 `HttpOnly`, `Secure`, `SameSite=Lax`, 호스트 전용, `Path=/`다.
- 인증 성공 시 Spring Security의 세션 고정 보호로 세션 ID를 교체한다.
- 브라우저 저장소에 BATON 접근 토큰, Google/Naver 접근 토큰 또는 비밀번호를 저장하지
  않는다.
- OAuth 공급자의 접근·갱신 토큰, Google ID 토큰과 사용자 정보 클레임은 신원 해석
  중에만 유지한다. 최초 세션 저장 전 계정 UUID와 `ROLE_ACCOUNT`만 가진 인증 주체로
  축소하며 공급자 토큰·클레임은 BATON 세션에 보존하지 않는다.
- 단일 인스턴스 파일럿은 메모리 세션을 사용하며 재시작 시 재로그인을 허용한다. 다중
  인스턴스 전에는 Spring Session 같은 공유 저장소를 별도 결정한다.

쿠키 인증을 사용하는 모든 상태 변경은 CSRF를 요구한다. 기존
`X-Baton-Access-Key` 전용 API의 임시 CSRF 예외를 세션 권한으로 확대하지 않는다.

작업 공간에서 로그인할 때 접근 키가 브라우저에 저장되어 있으면 같은 탭에서 로그인한 뒤 원래
팀·시즌으로 돌아간다. 접근 키 저장에 실패하면 로그인 링크를 새 탭으로 열고, 공유 링크가 남은
원래 탭을 닫지 않도록 안내한다. 로그인 후 원래 탭으로 돌아오거나 새로고침하면 새 세션을 조회한다.
접근 키를 로그인 URL, `returnTo` 쿼리나 OAuth 요청에 복제하지 않는다.

로그아웃이 성공하면 진행 중인 세션 조회를 취소한 뒤 화면의 인증 상태와 기기에 저장한 작업 공간
접근 정보를 정리한다. 로그아웃 전에 시작한 조회의 응답이 늦게 도착해도 이전 계정을 다시 표시하지 않는다.

## 5. 자체 이메일 계정

화면 제목은 ‘로그인’, ‘계정 만들기’, ‘이메일 인증’으로 구분하고 각 단계에서 필요한 입력을
안내한다. 이메일 인증 링크를 연 것만으로 인증 완료를 표시하지 않는다. 비밀번호를 제출하기
전에는 ‘비밀번호를 설정해 이메일 인증을 완료하세요.’로 안내하고 서버가 성공을 반환한 뒤에만
인증 완료를 표시한다. 로그인 화면에는 향후 계정 연결 계획과 토큰 처리 같은 내부 설명을 넣지 않는다.

1. 사용자가 이메일과 표시 이름으로 가입을 요청한다. 이 단계에서는 비밀번호를 받거나
   자격 증명을 만들지 않는다.
2. 서버는 응답에서 이메일 존재 여부를 구분하지 않고 검증 안내를 반환한다.
3. 처음 보는 이메일에는 만료되는 단일 사용 토큰을 발급하고 `Account`, 신원, 챌린지와
   메일 전달 아웃박스를 한 트랜잭션에서 저장한다.
4. 아직 검증되지 않은 신원에 유효한 챌린지가 남아 있으면 같은 일반화된 `202`만 반환한다.
   이 경로는 토큰, `Account` 표시 이름, 챌린지와 전달 아웃박스를 전혀 변경하지 않는다.
5. 기존 챌린지가 만료된 뒤에만 새 토큰과 아웃박스를 발급한다. 최초 `Account` 표시 이름은
   보존하고, 이전 대기·선점 전달은 대체하며 디스패처는 SMTP 호출 직전에 현재
   챌린지 해시를 다시 확인한다. 이미 외부 SMTP 호출이 시작된 이전 메일은 취소할 수 없지만
   해당 토큰은 더 이상 검증에 사용할 수 없다.
6. 스케줄러는 임대를 먼저 커밋한 뒤 트랜잭션 밖에서 메일을 보낸다. 실패는 최대 8회
   제한된 지수 백오프로 재시도하고 이후 운영 실패로 남긴다.
7. 사용자는 검증 토큰과 최초 비밀번호를 함께 제출한다. 토큰 소비, 이메일 검증과 최초
   자격 증명 생성이 한 트랜잭션에서 완료된 뒤에만 자체 이메일 로그인을 허용한다.
8. 로그인 실패는 이메일 존재, 미검증, 비밀번호 불일치를 외부 응답에서 구분하지 않는다.

공개 가입은 SMTP 또는 동등한 검증 메일 전달 어댑터, 발급 제한과 실패 관측이 구성된
환경에서만 활성화한다. 저장소에 자격 증명을 넣지 않으며 설정이 불완전하면 안전하게 닫힌 상태로 실패한다.
실패 관측은 공통 외부 연동 지표와 `./ops/check-integration-delivery.sh`를 사용한다. 원시
`FAILED` 수는 모든 종료 원인을 보존하되, 이메일 검증 토큰의 자연 만료인
`VERIFICATION_TOKEN_EXPIRED`는 조치 대상 영구 실패 지표에서 제외한다. `SUPERSEDED`는 실패가
아니며, SMTP 인증·전송 오류나 재시도 소진처럼 조치가 필요한 실패와 만료 임대만 운영 점검을
실패시킨다. 공개 가입은 실제 SMTP 환경에서 이 지표와 주기 점검까지 확인한 뒤 활성화한다.
프로덕션은 가입 게이트와 무관하게 기존 아웃박스 복호화용 Base64 32바이트 고정 키를 요구한다.
공개 가입 게이트는 SMTP, HTTPS 공개 출처, 발신 주소, SMTP 자격 증명, 인증·STARTTLS
필수 설정·호스트명 검증과 시간 초과가 모두 준비된 경우에만 기동을 통과한다.
메일 전달이 비활성화된 환경에서도 내부 트랜잭션은 SMTP를 직접 호출하지 않는다. 디스패처는
전달을 선점하지 않고 멈추되 독립적인 만료 스케줄러는 만료된 `PENDING`과 임대가 만료된
`PROCESSING` 전달을 `FAILED(VERIFICATION_TOKEN_EXPIRED)`로 바꾸고 암호문, 논스와 챌린지
해시 스냅샷을 제거한다. 따라서 전달 설정을 잠시 끈 사실만으로 아직 유효한 페이로드를 소모하지
않고, 설정이 계속 꺼져 있어도 만료된 비밀은 DB에 남지 않는다.
인증 기능 조회 응답은 이 게이트를 노출하고 프런트는 기존 자체 이메일 로그인과 새 가입 가능 여부를
분리한다. 게이트가 닫힌 환경에서는 로그인 폼을 유지하되 새 계정 행동 버튼과 가입 폼을 노출하지
않는다.
비밀번호 재설정은 같은 검증된 이메일과 일회성 토큰 원칙을 따르는 후속 계약으로 추가한다.

## 6. Google·Naver 로그인과 신원 경계

- Spring Security OAuth2 Client가 인가 요청의 `state`, 콜백과 공급자 응답을
  검증한다.
- Google은 표준 OIDC 사용자 서비스를 사용하고 `email_verified=true`가 아닌 이메일을 자동
  연결 근거로 사용하지 않는다.
- Naver는 사용자 정의 공급자 설정과 OAuth2 사용자 서비스로 `response.id`를 필수 검증한다.
- 일반 로그인에서 처음 본 외부 신원은 새 BATON `Account`를 만든다.
- 같은 이메일 스냅샷을 가진 다른 공급자 신원이 있어도 기존 `Account`를 선택하지 않는다.
- 공개 계정 연결 엔드포인트는 최근 재인증과 연결 의도 수명·감사 계약을 구현할 때까지 닫는다.
- 마지막 로그인 수단 제거와 계정 병합은 첫 버전 범위가 아니다.

## 7. 기존 구성원 연결

기존 파일럿 `Member`는 팀 안의 표시·역할 대상이고 로그인 신원이 아니다. 이름으로 `Account`를
추측하지 않는다.

전환 기간에는 인증된 `Account`가 기존 워크스페이스 접근 키를 함께 제시하고 명시적으로 한
`Member`를 연결한다. 서버는 인증을 확인한 뒤 다음 조건을 만족할 때만 멤버십을 저장한다.

- `Account` 세션과 CSRF가 유효하다.
- 화면에서 확인한 `expectedAccountId`가 요청을 인증한 계정 ID와 같다. 다르면 저장 전에 거부한다.
- 공유 접근 키가 해당 팀에 유효하다.
- `Member`가 해당 팀에 속하고 활동 중이다.
- 같은 `(accountId, teamId)`와 같은 `Member`가 다른 `Account`에 이미 연결되지 않았다.

연결이 완료된 뒤 ROUND 참여권은 공유 접근 키가 아니라 `AccountMembership`으로 판단한다.
향후 초대 계약이 도입되면 공유 키 연결 진입점을 닫되 이미 연결한 멤버십은 보존한다.
워크스페이스 구성원 관리 화면은 현재 `Account` 세션과 팀 접근 키를 함께 사용해 연결 상태를
조회하고, 미연결 계정에만 활동 중 `Member` 선택과 변경 불가 경고를 제공한다. 이전 조회 결과가
남아 있어도 세션 재조회에 실패한 동안에는 새 연결을 막고 로그인 상태 재확인을 제공한다. 새로고침 뒤에도
서버에서 연결을 다시 조회하며 캐시는 `accountId + teamId` 경계를 포함한다. 구성원 활동이
종료되어도 영속적인 연결 사실은 남고, 실제 ROUND 참여권 발급 가능 여부만 별도로 거부한다.

## 8. 인증 HTTP 계약

### `GET /api/v1/auth/csrf`

로그인·가입 전에 사용할 CSRF 헤더 이름과 토큰을 반환한다. 이 엔드포인트는 CSRF 토큰을
위해 세션을 만들 수 있으며 `Cache-Control: no-store`를 사용한다.

### `GET /api/v1/auth/session`

이 엔드포인트는 세션이 없을 때 새 세션을 만들지 않는다. 미인증 응답은 추가 필드 없는
다음 JSON이다.

```json
{"authenticated":false}
```

인증 응답은 ROUND 브라우저 계약과 정확히 일치시킨다.

```json
{
  "authenticated": true,
  "accountId": "8e448211-66ae-44ab-9888-c4960648c22b",
  "csrfHeaderName": "X-CSRF-TOKEN",
  "csrfToken": "opaque-csrf-token"
}
```

두 응답 모두 `200 OK`, `Cache-Control: no-store`다.

### 자체 이메일·세션 엔드포인트

| 메서드·경로 | 요청 | 성공 |
| --- | --- | --- |
| `POST /api/v1/auth/local/registrations` | JSON `{email,displayName}` | `202 {verificationRequired:true}` |
| `POST /api/v1/auth/local/email-verifications` | JSON `{token,password}` | `204` |
| `POST /api/v1/auth/local/session` | 폼 `{email,password}` | `204` |
| `POST /api/v1/auth/logout` | 본문 없음 | `204` |

모두 CSRF와 정확히 일치하는 동일 출처 정책을 적용한다. 등록 응답은 계정 존재 여부를 공개하지 않는다.
로그인 성공은 서버 세션을 만들고 세션 ID를 교체하며, 로그아웃은 현재 세션과 인증
쿠키를 무효화한다. 자체 이메일 가입·검증·로그인은 각각 요청률 제한을 적용하고 초과 시
`429 AUTH_RATE_LIMITED`와 `Retry-After`를 반환한다. 존재하지 않는 계정, 미검증 계정과 비밀번호
불일치는 `401 INVALID_CREDENTIALS`, 검증 토큰 오류는 `400 EMAIL_VERIFICATION_INVALID`로
일반화한다. 신원 저장소의 잠금 경합이나 일시적 인프라 장애로 가입·검증·자체 이메일 로그인을
처리하지 못하면 `503 IDENTITY_TEMPORARILY_UNAVAILABLE`을 반환하고, 의미상 이메일
중복만 등록 `202`로 일반화한다.

### OAuth 엔드포인트

- 시작: `/oauth2/authorization/google`, `/oauth2/authorization/naver`
- 콜백: `/login/oauth2/code/google`, `/login/oauth2/code/naver`

콜백 실패는 JSON 오류 응답 대신 다음 고정 브라우저 리디렉션으로 수렴한다.

| 실패 분류 | 응답 |
| --- | --- |
| 일반 OAuth 실패 | `302 Location: /login?oauthError=login_failed` |
| 신원 저장소 잠금 경합·일시적 인프라 장애 | `302 Location: /login?oauthError=temporarily_unavailable` |

두 응답은 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 사용한다. 공급자의 오류 코드·
설명·URI와 내부 예외 상세를 리디렉션 URL이나 본문에 반영하거나 인증 세션에 보존하지 않는다.
프런트는 최초 진입 쿼리에서 두 `oauthError` 값만 스냅샷으로 저장하고 허용 목록으로 검증해 미인증 로그인 폼에
각각 아래 안내를 `alert`로 한 번 노출한다.

- `login_failed`: `소셜 로그인을 완료하지 못했습니다.` / `다시 시도하거나 다른 로그인 수단을 선택해 주세요.`
- `temporarily_unavailable`: `현재 인증 요청을 처리할 수 없습니다.` / `잠시 후 다시 시도해 주세요.`

안내를 위한 값을 스냅샷으로 저장한 직후 `history.replaceState`로 URL의 `oauthError`만 지운다. 명시적인
`returnTo`, 그 밖의 쿼리와 프래그먼트, 같은 탭에 기억한 안전한 인증 복귀 경로는 유지한다. 알 수 없는
값은 안내하지 않고 지우며, 정리된 URL을 새로고침해도 이전 안내를 재생하지 않는다.

공급자 자격 증명이 설정되지 않은 등록은 노출하지 않는다. 콜백 쿼리의 `code`, `state`,
`error`, `error_description`과 쿠키·Authorization 값은 엣지 접근 로그에 남기지 않는다.
토큰·사용자 정보·Google JWK 외부 호출은 명시적인 연결·읽기 시간 초과를 사용한다. 공개 신원
연결 엔드포인트는 제공하지 않는다.

## 9. ROUND 방 매핑과 참여권

BATON은 각 정규 ROUND `roomId`를 정확히 하나의 활성
`(teamId, seasonId, resourceId)`에 연결한다. 하나의 리소스도 활성 방 하나만 가진다.
매핑 종료 뒤 방 ID 삭제 표식은 영구 보존하고 재사용하지 않는다.

관리 API는 `Account` 세션과 `X-Baton-Access-Key`를 모두 요구한다. 변경 요청은 CSRF와 정확한
동일 출처도 요구하지만 현재 연결과 활성 방 매핑 조회 GET은 CSRF 없이 사용할 수 있다.

- `GET /api/v1/account-memberships/current?teamId={teamId}`: 미연결이면 정확히
  `200 {claimed:false}`, 연결됐으면
  `200 {claimed:true,accountId,teamId,memberId,claimedAt}`를 반환한다.
- `POST /api/v1/account-membership-claims`: `{expectedAccountId,teamId,seasonId,memberId}`를 받아
  `200 {accountId,teamId,memberId,claimedAt}`를 반환한다. 확인한 계정 ID가 없으면 `400 INVALID_INPUT`,
  현재 로그인 계정과 다르면 저장 전에 `409 ACCOUNT_MEMBERSHIP_CONFLICT`로 거부한다.
- `GET /api/v1/round-room-mappings?teamId={teamId}&seasonId={seasonId}`:
  해당 범위의 모든 활성 매핑을
  `200 {mappings:[{roomId,teamId,seasonId,resourceId,createdAt,endedAt:null}]}`으로 반환하며,
  활성 매핑이 없으면 정확히 `200 {mappings:[]}`를 반환한다.
- `POST /api/v1/round-room-mappings`: `{teamId,seasonId,resourceId}`를 받아
  `200 {roomId,teamId,seasonId,resourceId,createdAt,endedAt:null}`을 반환한다.
- `DELETE /api/v1/round-room-mappings/{roomId}`: 활성 매핑을 종료하고 같은 형태에
  `endedAt`을 채운 `200`을 반환한다.

현재 멤버십 조회는 팀 범위 접근 키를 먼저 검증하고 연결이 없으면 정확히 `claimed:false`를
반환한다. 구성원 활동이 종료되어도 영속적인 연결 사실은 유지하며 종료 시즌에서도 조회할 수
있다. 신규 연결은 활동 중인 같은 팀 `Member`만 허용하고 종료 시즌의 읽기 전용 경계에서는 거부한다.
현재 방 매핑 목록 조회는 팀 접근 키와 활동 중인 멤버십을 한 번 확인한 뒤 팀·시즌의
서버 영속 매핑을 한 번에 조회해 권위로 반환한다. 브라우저 `sessionStorage`는 ROUND 입장과 복귀를
돕는 힌트이며 매핑의
존재·종료 여부를 결정하지 않는다.

`POST /round/rooms/{roomId}/participation-grant/refresh`는 BATON이 직접 처리한다.

- 정확히 일치하는 동일 출처 `Origin`, `Sec-Fetch-Site: same-origin`, 세션과 동적 CSRF를 요구한다.
- 현재 `AccountMembership`, 활성 시즌과 권위 있는 방 매핑을 확인한다.
- v1 역할은 `participant`로 고정하고 `host`를 추측하지 않는다.
- 성공마다 새로운 `jti`, 300초 수명의 RS256 JWT와 240초 갱신 지연을 발급한다.
- JWT `sub`는 `Account.id`, `study_id`는 매핑의 `teamId`, `room_id`는 경로와 매핑의 `roomId`다.
- JWT는 본문이 아니라 방 범위 `__Secure-round_access` HttpOnly 쿠키에만 둔다.
- 성공·실패와 쿠키 만료 규칙은 BATON GO PRD-0003 5절을 따른다.

성공 응답은 `200 {expiresAt,refreshAfterSeconds}`다. 힌트를 보내지 않으면 본문과 `Content-Type`을
모두 생략하고, 보낼 때는 `{teamId,seasonId,resourceId}` 세 필드만 허용한다. 미인증은
`401 AUTHENTICATION_REQUIRED`, 멤버십·시즌 조건 거부는 `403 ROUND_PARTICIPATION_DENIED`,
매핑 부재나 힌트 불일치는 `404 ROUND_ROOM_NOT_FOUND`로 수렴한다.

## 10. JWK와 키 회전

- 참여권은 BATON 전용 RSA 비공개 키로 `RS256` 서명한다.
- JOSE `kid`는 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`와 정확히 일치한다.
- 공개 JWK Set만 HTTPS 엔드포인트에 공개하며 비공개 키는 BATON 런타임 밖으로 배포하지 않는다.
- 공개 경로는 `GET /.well-known/round-participation-jwks.json`이며
  `application/jwk-set+json`, `Cache-Control: max-age=60, public`을 사용한다.
- ROUND는 JWK Set을 60초 캐시하고 최초 적재와 캐시 미스 재시도를 포함한 원격 소스 접근을
  JVM별 30초 구간에서 최대 두 번으로 제한한다. 제한 중인 알 수 없는 `kid` 참여권은 추가
  조회 없이 `401`로 거부한다.
- 새 키를 JWK Set에 60초보다 길게 먼저 게시하고 발급을 새 `kid`로 전환한다.
- 이전 공개 키는 300초 참여권 수명, 60초 시계 오차와 ROUND 캐시 갱신을 모두 지난 뒤 제거한다.

## 11. 비범위와 운영 전 확인

첫 구현에 포함하지 않는다.

- 이메일만 근거로 한 계정 병합
- 공급자 간 계정 연결과 최근 재인증(단계 강화 인증)
- 팀·시즌·역할 전체 권한 행렬과 완전한 초대 생명주기
- 공급자 토큰을 이용한 Google/Naver 추가 API 호출
- 다중 인스턴스 세션과 키 관리 서비스
- `host` 참여권과 강제 퇴장 권한
- 계정 비활성화·탈퇴, 기존 세션 강제 만료와 발급된 참여권의 조기 폐기

격리된 `e2e:fullstack`은 테스트 전용 검증 자체 이메일 계정과 실제 브라우저 세션을 사용해
로컬 로그인, `AccountMembership` 연결, 권위 있는 방 매핑,
참여권 갱신, 방 범위 쿠키와 공개 JWK 기반 JWT 서명·클레임·재발급을 검증한다.
이 검증은 루프백 HTTP의 Vite 개발 프록시를 사용하며 Caddy TLS, ROUND 런타임,
TURN·WebSocket과 실제 이메일 가입·외부 OAuth 공급자를 포함하지 않는다.
세션 ID 교체는 기존 세션을 주입한 실제 Spring Security 필터 체인 테스트가 별도로 검증한다.

선택 실행 `e2e:round-edge`는 명시한 ROUND 저장소의 기존 BATON 웹·시그널링 이미지와
테스트 전용 Caddy, 로컬 사설 CA·JVM 신뢰 저장소, 임시 MySQL을 조립한다. 실제 HTTPS 브라우저
세션에서 위 생산자 흐름을 수행한 뒤 방 범위 `Secure` 쿠키로 공개 TURN 자격 증명
엔드포인트와 WSS `room.join`까지 검증한다. 엣지는 ROUND에 참여 쿠키만 전달하고 BATON
세션·Authorization·워크스페이스 자격 증명은 제거하며, 내부 ROUND 경로를 공개하지 않는다.
이 검증은 공인 DNS·ACME, 프로덕션 이미지·프로덕션 Caddy, 실제 coturn 할당·미디어 중계,
외부 OAuth·SMTP와 배포 키 회전을 대신하지 않는다.

프로덕션 배포는 별도 `BATON_ROUND_RUNTIME_ENABLED` 게이트와 고정 Compose 오버레이로
`round-baton-web`·`round-signaling`의 정확한 다이제스트 사용을 명시적으로 선택한다. 사전점검은 릴리스 리비전,
태그 객체와 BATON 인증 모드를 미리 검증하고, 실제 `up`·`create`·`pull`은 생명주기 잠금 안에서
검증된 환경을 보호된 `0600` 스냅샷으로 동결한다. 이미지 가져오기·증명과 Compose가 같은 스냅샷을
사용한 뒤에만 배포 경계에 도달하며 종료 시 스냅샷을 제거한다. BATON Caddy는 갱신을 계속
BATON에 남기고 공개 TURN·WSS만 내부 ROUND 경로로 재작성하며, 시그널링에는 참여
쿠키 이름이 정확한 대소문자로 하나일 때만 그 값을 전달한다. 중복이나 대소문자 변형은 엣지에서
`401`·`no-store`로 거부한다. 런타임과 참여권 게이트를 분리해 기능 비활성 런타임 배포 뒤 서명자를 열고,
참여권이 켜진 상태에서 런타임만 끄는 구성은 검증기가 거부한다. 세 방 범위 경로는
커밋 고정 Caddy 요청률 제한 모듈로 범위가 제한된 인증 전 요청률 제한을 적용한다.
참여권 게이트를 닫으면 참여권 재발급과 공개 JWK가 함께 닫히므로 운영 비활성화는 기존 참여권도
즉시 사용할 수 없게 하는 차단 절차이며 만료까지의 점진적 종료를 보장하지 않는다.

coturn은 BATON Compose에 포함하지 않는다. 운영 환경에는 자격 증명이 포함되지 않은 UDP·TCP·TLS TURN URL과
소유자 전용 공유 비밀값 파일 경로만 둔다. 시그널링에는 이 호스트 파일을 읽기 전용 바인드로
Spring 구성 트리에 전달하고 비루트 컨테이너 UID/GID를 호스트 소유자와 일치시킨다. 로컬 Compose 파일
소스가 별도 `0400` 파일을 실체화한다고 가정하지 않는다. 공인 IP, NAT·방화벽, TURN TLS
인증서, 할당과 실제 미디어 중계는 외부 ROUND 운영 단위와 공개 스테이징 점검이 소유한다.
BATON 공개 상태와 ROUND 컨테이너 준비 상태는 이 중계 성공을 대신하지 않는다.

운영 공개 전에는 실제 Google, Naver, SMTP 자격 증명과 공개 HTTPS 출처에서 세 방식의
가입·로그인을 각각 확인하고, 공개 Caddy·ROUND 경로에서 각 `Account`의 동일 JWT `sub`로
갱신 → TURN → WebSocket 입장하는지 검증한다. 공급자 간
계정 연결 검증은 단계 강화 인증 기능을 구현한 뒤 별도 수행한다. 공급자 콘솔과 SMTP 설정이
없는 자동 테스트를 실계정 검증으로 확대 해석하지 않는다.

## 12. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [API 계약](../0002_api-contract/spec.md)
- [제품 로드맵](../0003_product-roadmap/spec.md)
- [계정 신원과 세션 결정](../../ADR/0017_account-identity-and-session/adr.md)
- [BATON GO 교차 서비스 링크 계약](../../../../short-url/docs/PRD/0003_cross-service-link-contract/spec.md)
