# PRD-0005: 계정 인증과 ROUND 참여권 계약

- 상태: 채택
- 작성일: 2026-08-08
- 대상: BATON 계정 로그인, identity 연결, ROUND participation grant

## 1. 목적

BATON의 파일럿 공유 접근 키를 사용자 신원으로 확대 해석하지 않고, Google·Naver·자체
이메일 로그인을 공급자 중립 `Account` 모델로 수용한다. 처음 보는 identity는 별도 Account를
만들며, 인증된 계정이 팀 구성원과 연결된 경우에만 BATON이 ROUND room 참여권을 발급한다.

이 문서는 로그인 공급자, 내부 신원, 브라우저 session과 ROUND 참여권의 첫 계약을 고정한다.
팀 초대와 세부 권한 행렬 전체를 완료된 것으로 선언하지 않는다.

## 2. 핵심 원칙

### 공급자와 무관한 내부 계정

- BATON `Account.id`는 canonical UUID이며 재할당하지 않는다.
- Google OIDC `sub`, Naver 프로필 `response.id`, 정규화한 자체 이메일은 각각 외부 identity의
  식별자일 뿐 BATON 계정 ID가 아니다.
- ROUND participation grant의 `sub`는 항상 BATON `Account.id` 문자열이다.
- 표시 이름, 이메일, 팀 구성원 이름, 공유 접근 키는 계정 식별자로 사용하지 않는다.

### 이메일로 자동 병합하지 않는다

- `(provider, providerSubject)`가 외부 identity의 유일 키다.
- 서로 다른 공급자가 같은 이메일을 반환해도 계정을 자동 병합하지 않는다.
- 새 외부 identity를 기존 계정에 붙이는 공개 연결 API는 제공하지 않는다. 최근 재인증으로
  계정 소유를 다시 증명하고 OAuth state를 결합하는 step-up 계약을 먼저 추가한다.
- 다른 계정에 이미 연결된 identity는 계정 병합으로 추측하지 않고 충돌로 거부한다.

### 프로토콜의 실제 의미를 보존한다

- Google 로그인은 OpenID Connect이며 검증된 ID Token의 `sub`를 사용한다.
- Naver 로그인은 현재 제공되는 OAuth 2.0 authorization-code 흐름과 프로필 API를 사용하며
  `response.id`를 사용한다. Naver 흐름을 OIDC 또는 ID Token 검증으로 잘못 문서화하지 않는다.
- 자체 이메일 로그인은 검증된 이메일과 비밀번호 credential을 사용한다.

## 3. 계정과 identity 모델

| 개념 | 소유 상태 |
| --- | --- |
| Account | 내부 UUID, 표시 이름, 생성·변경 시각 |
| AccountIdentity | provider, provider subject, 이메일 snapshot과 검증 여부, 마지막 인증 시각 |
| LocalCredential | identity ID, Spring Security encoder 형식의 비밀번호 hash, 생성·변경 시각 |
| EmailVerificationChallenge | 원문이 아닌 token hash, 만료·소비 시각 |
| AccountMembership | account, team, 기존 BATON member의 명시적 연결 |

자체 이메일은 앞뒤 공백을 제거하고 Unicode나 공급자별 별칭 규칙을 추측하지 않은 채
소문자화한다. Google·Naver가 반환한 이메일은 연락처 snapshot이며 외부 subject를 대체하지
않는다.

비밀번호 원문은 저장하거나 로그에 남기지 않는다. challenge에는 이메일 검증 token hash만
저장한다. commit 이후 검증 메일을 만들기 위한 token과 수신 주소는 application의
AES-256-GCM으로 암호화해 delivery outbox에 보관한다. 메시지별 12-byte random nonce와
identity ID, Account ID, application이 계산한 domain-separated challenge hash, microsecond
만료 시각을 AAD로 묶고, `DELIVERED`·`SUPERSEDED`·`FAILED` terminal 상태 전환과 함께
ciphertext·nonce·hash snapshot을 제거한다. 암호화 키는 DB 밖의 production secret 파일로
주입하며 DB·백업 read 권한도 credential 수준으로 제한한다.
정규화 이메일과 검증 여부는 `AccountIdentity`가 소유한다. 비밀번호는 Spring Security
`DelegatingPasswordEncoder`의 `{id}encoded` 형식으로 저장한다. 신규 credential은 128자
Unicode 계약에 72-byte 제한을 만들지 않는 PBKDF2로 encode하고, 기존 `{bcrypt}` hash는 검증
뒤 점진적으로 upgrade할 수 있게 유지한다.

## 4. 브라우저 인증

BATON은 동일 public HTTPS origin의 서버 측 `HttpSession`을 사용한다.

- session cookie는 `HttpOnly`, `Secure`, `SameSite=Lax`, host-only, `Path=/`다.
- 인증 성공 시 Spring Security의 session fixation 보호로 session ID를 교체한다.
- 브라우저 저장소에 BATON access token, Google/Naver access token 또는 비밀번호를 저장하지
  않는다.
- OAuth 공급자의 access·refresh token은 후속 공급자 API를 사용하지 않으므로 인증 완료 뒤
  BATON session에 보존하지 않는다.
- 단일 인스턴스 파일럿은 메모리 session을 사용하며 재시작 시 재로그인을 허용한다. 다중
  인스턴스 전에는 Spring Session 같은 공유 저장소를 별도 결정한다.

cookie 인증을 사용하는 모든 상태 변경은 CSRF를 요구한다. 기존
`X-Baton-Access-Key` 전용 API의 임시 CSRF 예외를 session 권한으로 확대하지 않는다.

## 5. 자체 이메일 계정

1. 사용자가 이메일과 표시 이름으로 가입을 요청한다. 이 단계에서는 비밀번호를 받거나
   credential을 만들지 않는다.
2. 서버는 응답에서 이메일 존재 여부를 구분하지 않고 검증 안내를 반환한다.
3. 신규 또는 아직 검증되지 않은 계정에는 만료되는 단일 사용 token을 발급하고 Account,
   identity, challenge와 메일 delivery outbox를 한 transaction에서 저장한다.
4. scheduler는 lease를 먼저 commit한 뒤 transaction 밖에서 메일을 보낸다. 실패는 최대 8회
   bounded exponential backoff로 재시도하고 이후 운영 실패로 남긴다.
5. 사용자는 검증 token과 최초 비밀번호를 함께 제출한다. token 소비, 이메일 검증과 최초
   credential 생성이 한 transaction에서 완료된 뒤에만 자체 이메일 로그인을 허용한다.
6. 재발급은 이전 대기·선점 delivery를 supersede하고 dispatcher는 SMTP 호출 직전에 현재
   challenge hash를 다시 확인한다. 이미 외부 SMTP 호출이 시작된 이전 메일은 취소할 수 없지만
   해당 token은 더 이상 검증에 사용할 수 없다.
7. 로그인 실패는 이메일 존재, 미검증, 비밀번호 불일치를 외부 응답에서 구분하지 않는다.

공개 가입은 SMTP 또는 동등한 검증 메일 전달 adapter, 발급 제한과 실패 관측이 구성된
환경에서만 활성화한다. 저장소에 credential을 넣지 않으며 설정이 불완전하면 fail-closed 한다.
production은 가입 gate와 무관하게 기존 outbox 복호화용 Base64 32-byte stable key를 요구한다.
공개 가입 gate는 SMTP, HTTPS public origin, 발신 주소, SMTP credential, 인증·STARTTLS
required·hostname 검증과 timeout이 모두 준비된 경우에만 startup을 통과한다.
공개 가입 gate가 비활성화된 환경에서도 내부 transaction은 SMTP를 직접 호출하지 않으며,
남아 있는 outbox가 있으면 disabled adapter 실패를 재시도·최종 실패 상태로 기록한다.
비밀번호 재설정은 같은 검증된 이메일과 일회성 token 원칙을 따르는 후속 계약으로 추가한다.

## 6. Google·Naver 로그인과 identity 경계

- Spring Security OAuth2 Client가 authorization request의 `state`, callback과 공급자 응답을
  검증한다.
- Google은 표준 OIDC user service를 사용하고 `email_verified=true`가 아닌 이메일을 자동
  연결 근거로 사용하지 않는다.
- Naver는 custom provider 설정과 OAuth2 user service로 `response.id`를 필수 검증한다.
- 일반 로그인에서 처음 본 external identity는 새 BATON Account를 만든다.
- 같은 이메일 snapshot을 가진 다른 provider identity가 있어도 기존 Account를 선택하지 않는다.
- 공개 account-link endpoint는 최근 재인증과 연결 의도 수명·감사 계약을 구현할 때까지 닫는다.
- 마지막 로그인 수단 제거와 계정 병합은 첫 버전 범위가 아니다.

## 7. 기존 구성원 claim

기존 파일럿 `Member`는 팀 안의 표시·역할 대상이고 로그인 신원이 아니다. 이름으로 Account를
추측하지 않는다.

전환 기간에는 인증된 Account가 기존 workspace 접근 키를 함께 제시하고 명시적으로 한
Member를 claim한다. 서버는 다음을 한 transaction에서 확인한다.

- Account session과 CSRF가 유효하다.
- 공유 접근 키가 해당 team에 유효하다.
- Member가 해당 team에 속하고 활동 중이다.
- 같은 `(accountId, teamId)`와 같은 Member가 다른 Account에 이미 연결되지 않았다.

claim이 완료된 뒤 ROUND 참여권은 공유 접근 키가 아니라 AccountMembership으로 판단한다.
향후 초대 계약이 도입되면 공유 키 claim 진입점을 닫되 이미 연결한 membership은 보존한다.

## 8. 인증 HTTP 계약

### `GET /api/v1/auth/csrf`

로그인·가입 전에 사용할 CSRF header 이름과 token을 반환한다. 이 endpoint는 CSRF token을
위해 session을 만들 수 있으며 `Cache-Control: no-store`를 사용한다.

### `GET /api/v1/auth/session`

이 endpoint는 session이 없을 때 새 session을 만들지 않는다. 미인증 응답은 추가 필드 없는
다음 JSON이다.

```json
{"authenticated":false}
```

인증 응답은 ROUND 브라우저 계약과 정확히 맞춘다.

```json
{
  "authenticated": true,
  "accountId": "8e448211-66ae-44ab-9888-c4960648c22b",
  "csrfHeaderName": "X-CSRF-TOKEN",
  "csrfToken": "opaque-csrf-token"
}
```

두 응답 모두 `200 OK`, `Cache-Control: no-store`다.

### 자체 이메일·session endpoint

| Method·path | 요청 | 성공 |
| --- | --- | --- |
| `POST /api/v1/auth/local/registrations` | JSON `{email,displayName}` | `202 {verificationRequired:true}` |
| `POST /api/v1/auth/local/email-verifications` | JSON `{token,password}` | `204` |
| `POST /api/v1/auth/local/session` | form `{email,password}` | `204` |
| `POST /api/v1/auth/logout` | 본문 없음 | `204` |

모두 CSRF와 exact same-origin 정책을 적용한다. 등록 응답은 계정 존재 여부를 공개하지 않는다.
로그인 성공은 server session을 만들고 session ID를 교체하며, 로그아웃은 현재 session과 인증
cookie를 무효화한다. 자체 이메일 가입·검증·로그인은 각각 rate limit을 적용하고 초과 시
`429 AUTH_RATE_LIMITED`와 `Retry-After`를 반환한다. 존재하지 않는 계정, 미검증 계정과 비밀번호
불일치는 `401 INVALID_CREDENTIALS`, 검증 token 오류는 `400 EMAIL_VERIFICATION_INVALID`로
일반화한다.

### OAuth endpoint

- 시작: `/oauth2/authorization/google`, `/oauth2/authorization/naver`
- callback: `/login/oauth2/code/google`, `/login/oauth2/code/naver`

공급자 credential이 설정되지 않은 등록은 노출하지 않는다. callback query의 `code`, `state`와
cookie·Authorization 값은 edge access log에 남기지 않는다. token·user-info·Google JWK 외부
호출은 명시적인 connect/read timeout을 사용한다. 공개 identity link endpoint는 제공하지 않는다.

## 9. ROUND room mapping과 participation grant

BATON은 각 canonical ROUND `roomId`를 정확히 하나의 active
`(teamId, seasonId, resourceId)`에 연결한다. 하나의 resource도 active room 하나만 가진다.
mapping 종료 뒤 room ID tombstone은 영구 보존하고 재사용하지 않는다.

관리 API는 Account session, CSRF, exact same-origin과 `X-Baton-Access-Key`를 모두 요구한다.

- `POST /api/v1/account-membership-claims`: `{teamId,seasonId,memberId}`를 받아
  `200 {accountId,teamId,memberId,claimedAt}`를 반환한다.
- `POST /api/v1/round-room-mappings`: `{teamId,seasonId,resourceId}`를 받아
  `200 {roomId,teamId,seasonId,resourceId,createdAt,endedAt:null}`을 반환한다.
- `DELETE /api/v1/round-room-mappings/{roomId}`: active mapping을 종료하고 같은 shape에
  `endedAt`을 채운 `200`을 반환한다.

`POST /round/rooms/{roomId}/participation-grant/refresh`는 BATON이 직접 처리한다.

- exact same-origin `Origin`, `Sec-Fetch-Site: same-origin`, session과 동적 CSRF를 요구한다.
- 현재 AccountMembership, active season과 authoritative room mapping을 확인한다.
- v1 role은 `participant`로 고정하고 `host`를 추측하지 않는다.
- 성공마다 fresh `jti`, 300초 수명의 RS256 JWT와 240초 refresh delay를 발급한다.
- JWT `sub`는 Account.id, `study_id`는 mapping teamId, `room_id`는 path와 mapping의 roomId다.
- JWT는 body가 아니라 room-scoped `__Secure-round_access` HttpOnly cookie에만 둔다.
- 성공·실패와 cookie 만료 규칙은 BATON GO PRD-0003 5절을 따른다.

성공 응답은 `200 {expiresAt,refreshAfterSeconds}`다. hint를 보내지 않으면 body와 `Content-Type`을
모두 생략하고, 보낼 때는 `{teamId,seasonId,resourceId}` 세 필드만 허용한다. 미인증은
`401 AUTHENTICATION_REQUIRED`, membership·시즌 조건 거부는 `403 ROUND_PARTICIPATION_DENIED`,
mapping 부재나 hint 불일치는 `404 ROUND_ROOM_NOT_FOUND`로 수렴한다.

## 10. JWK와 키 회전

- 참여권은 BATON 전용 RSA private key로 `RS256` 서명한다.
- JOSE `kid`는 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`와 정확히 일치한다.
- public JWK Set만 HTTPS endpoint에 공개하며 private key는 BATON runtime 밖으로 배포하지 않는다.
- 공개 경로는 `GET /.well-known/round-participation-jwks.json`이며
  `application/jwk-set+json`, `Cache-Control: max-age=60, public`을 사용한다.
- 새 key를 JWK Set에 먼저 게시하고 issuance를 새 `kid`로 전환한다.
- 이전 public key는 300초 grant 수명, 60초 skew와 ROUND cache 갱신을 모두 지난 뒤 제거한다.

## 11. 비범위와 운영 전 확인

첫 구현에 포함하지 않는다.

- 이메일만 근거로 한 계정 병합
- 공급자 간 account linking과 최근 재인증(step-up)
- 팀·시즌·역할 전체 권한 행렬과 완전한 초대 lifecycle
- provider token을 이용한 Google/Naver 추가 API 호출
- 다중 인스턴스 session과 key 관리 서비스
- `host` 참여권과 강제 퇴장 권한
- 계정 비활성화·탈퇴, 기존 session 강제 만료와 발급된 참여권의 조기 폐기

운영 공개 전에는 실제 Google, Naver, SMTP credential로 세 방식의 가입·로그인을 각각 확인하고,
각 Account의 동일 JWT `sub`로 refresh → TURN → WebSocket 입장하는지 검증한다. 공급자 간
account linking 검증은 step-up 기능을 구현한 뒤 별도 수행한다. provider console과 SMTP 설정이
없는 자동 테스트를 실계정 검증으로 확대 해석하지 않는다.

## 12. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [API 계약](../0002_api-contract/spec.md)
- [제품 로드맵](../0003_product-roadmap/spec.md)
- [계정 identity와 session 결정](../../ADR/0017_account-identity-and-session/adr.md)
- [BATON GO 교차 서비스 링크 계약](../../../../short-url/docs/PRD/0003_cross-service-link-contract/spec.md)
