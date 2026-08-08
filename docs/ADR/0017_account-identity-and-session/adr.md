# ADR-0017: 공급자 중립 Account와 동일 출처 서버 session

- 상태: 채택
- 결정일: 2026-08-08

## 배경

BATON의 공유 접근 키는 작은 파일럿에서 workspace 전체 읽기·쓰기를 공유하기 위한 capability다.
이 값과 팀 구성원 표시 이름은 실제 로그인 사용자를 증명하지 못하므로 ROUND 참여권 `sub`,
감사 주체나 장기 조직 권한의 기준이 될 수 없다.

사용자는 Google, Naver와 자체 이메일 로그인을 원한다. 공급자 subject와 이메일은 서로 다른
수명·검증 의미를 가지므로 하나를 내부 사용자 ID로 선택하면 계정 연결, 이메일 변경과 공급자
추가 때 신원이 갈라진다.

## 결정

### Account와 identity를 분리한다

`Account.id` canonical UUID를 BATON의 불변 내부 신원으로 사용한다. 각 로그인 수단은
`AccountIdentity(provider, providerSubject)`로 Account에 연결한다.

```text
Account
 ├─ GoogleIdentity(oidc sub)
 ├─ NaverIdentity(profile response.id)
 └─ LocalEmailIdentity(normalized email) ─ LocalCredential
```

이메일과 표시 이름은 변경 가능한 profile 값이다. 같은 이메일은 자동 account linking의
증거가 아니다. 최근 재인증과 수명이 짧은 server-side link intent가 구현되기 전에는 공개
identity 연결 endpoint를 제공하지 않는다.

### Spring Security 추상화를 사용한다

- Google: Spring Security OAuth2 Client의 OIDC login
- Naver: 같은 OAuth2 Client의 custom provider와 OAuth2 user service
- 자체 이메일: `DaoAuthenticationProvider`, `UserDetailsService`,
  `DelegatingPasswordEncoder`
- session fixation, CSRF, logout과 security context persistence: Spring Security 기본 경계

OAuth callback, state 검증, 비밀번호 hash format과 session 저장을 별도 사내 구현으로
복제하지 않는다. BATON application은 Account/identity 연결 규칙을 소유하고 web adapter가
Spring Authentication과 application port를 연결한다.

### 브라우저에는 opaque session만 제공한다

프런트엔드와 API가 같은 HTTPS origin이므로 서버 측 HttpSession을 채택한다. JWT access token을
localStorage에 넣지 않는다. 공급자 access token도 후속 API 용도가 없어 로그인 완료 뒤
제거한다.

단일 인스턴스 파일럿에서는 servlet container memory session을 허용한다. 재시작 로그아웃은
수용하지만 다중 replica 전에 shared session store와 key rotation을 새 결정으로 추가한다.

### 검증 메일은 transactional outbox로 전달한다

자체 이메일 가입·재발급 transaction은 Account, identity, challenge와 메일 전달 outbox를 함께
커밋한다. SMTP는 DB transaction 안에서 호출하지 않는다. 별도 scheduler가 짧은 lease로
`FOR UPDATE SKIP LOCKED` claim을 커밋한 뒤 전달하고, 성공·지수 backoff 재시도·최종 실패를
lease token 조건으로 기록한다.

재발급과 인증 완료는 같은 identity의 `PENDING` 또는 `PROCESSING` 전달을 `SUPERSEDED`로
바꾼다. dispatcher도 외부 호출 직전에 현재 challenge hash와 lease를 다시 확인한다. 이미 SMTP
provider 호출이 시작된 메일은 취소할 수 없지만, 재발급 transaction에서 이전 challenge가
무효화되므로 그 링크로 인증을 완료할 수 없다.

### ROUND 참여권 issuer는 BATON이다

Google/Naver token을 ROUND가 직접 검증하지 않는다. BATON이 현재 Account와 membership을
판단한 뒤 자체 RSA key로 짧은 참여권을 발급한다. 따라서 로그인 수단이 바뀌어도
`JWT sub = Account.id`가 유지된다.

JWK는 public key만 공개하고 새 key 선게시 → 새 issuance → overlap 종료 뒤 old key 제거 순서를
지킨다. v1 `role=participant`이며 조직 역할이나 최초 로그인 사용자를 host로 추측하지 않는다.

## 보안 결정

- local signup은 검증 메일 adapter와 rate limit이 없으면 production에서 비활성화한다.
- 비밀번호 원문은 저장·로그하지 않고 challenge에는 검증 token hash만 저장한다.
- 메일 링크를 transaction 이후 만들기 위한 검증 token과 수신 주소는 AES-256-GCM 암호문으로
  outbox의 `PENDING`/`PROCESSING` 수명 동안만 저장한다. 12-byte random nonce를 메시지마다
  만들고 identity ID, Account ID, application이 계산한 domain-separated token hash와
  microsecond 만료 시각을 AAD로 묶는다. 키는 DB 밖 production secret으로 관리한다.
  로그·`toString()`에서 key·ciphertext·nonce·평문을 제거하며
  `DELIVERED`·`SUPERSEDED`·`FAILED` 전환과 동시에 암호문·nonce·hash snapshot을 `NULL`로 지운다.
- production은 가입 gate와 무관하게 기존 backlog 복호화용 32-byte stable key를 요구한다.
  공개 local signup은 이 key와 SMTP, HTTPS public origin, 발신 주소, SMTP credential,
  인증·STARTTLS required·hostname 검증과 timeout이 모두 준비돼야 startup을 통과한다.
- external identity의 unique key는 provider와 provider subject다.
- 동일 이메일 자동 병합과 다른 Account에 연결된 identity 강제 이전을 거부한다.
- session mutation은 CSRF, exact same-origin과 Fetch Metadata를 적용한다.
- OAuth callback query, Cookie, Set-Cookie, Authorization과 credential header를 edge log에서
  제거한다.
- Caddy가 전달한 canonical HTTPS scheme과 host만 신뢰하도록 forwarded header 경계를
  구성한다.

## 결과

### 장점

- 각 로그인 identity의 공급자 profile·이메일 변경 뒤에도 내부 신원과 ROUND quota가 유지된다.
- 표준 OAuth2/OIDC, password와 session 방어를 Spring Security에 위임한다.
- ROUND는 BATON 내부 공급자를 모르고 단일 issuer/JWK만 신뢰한다.
- 계정과 팀 구성원을 분리해 기존 조직 기록을 이름이나 이메일로 잘못 backfill하지 않는다.

### 비용

- Account와 Member를 연결하는 claim·초대 lifecycle이 별도로 필요하다.
- 메일 전달, provider console, session 운영과 RSA key rotation이 새 운영 책임이 된다.
- outbox 암호화 키의 생성·배포·회수와 향후 rotation이 새 운영 책임이다.
- 첫 버전은 key ID와 overlap rotation을 지원하지 않으므로 `PENDING`/`PROCESSING` backlog가
  있는 동안 key를 즉시 교체하지 않는다.
- 서버 재시작 때 session이 사라지며 다중 인스턴스 전에 공유 session 저장소가 필요하다.
- 계정 병합을 자동화하지 않으며 step-up 연결 기능 전까지 공급자별 계정이 분리될 수 있다.

## 대안

### 이메일을 내부 사용자 ID로 사용

이메일은 변경되며 공급자별 검증 의미가 다르고 Naver에서 누락될 수 있어 채택하지 않았다.

### Google/Naver subject를 ROUND sub로 전달

같은 사람이 공급자를 바꾸면 다른 참가자로 보이고 BATON 계정 상태를 우회하므로 채택하지
않았다.

### 브라우저 JWT와 refresh token

현재 동일 출처 단일 웹 애플리케이션에 token 저장·회전·폐기 책임만 늘리므로 채택하지 않았다.
ROUND용 짧은 JWT는 JavaScript에 노출하지 않는 room-scoped cookie에 한정한다.

### 처음부터 Spring Authorization Server 도입

BATON은 범용 OAuth authorization server가 아니라 자신의 session으로 room-scoped 참여권을
발급한다. 전체 authorization endpoint, client registry와 consent 모델은 현재 필요하지 않아
`NimbusJwtEncoder`와 공개 JWK Set의 좁은 issuer 경계를 사용한다.

## 검증

- account/identity DB unique와 migration 보존 테스트
- Google OIDC와 Naver OAuth2 callback stub 계약 테스트
- local password hash·email verification·일반화 오류 테스트
- email outbox 원자 저장, AES-GCM/AAD tamper 거부, 재발급 supersession, lease 복구와 bounded
  retry 통합 테스트
- email collision 자동 병합과 공개 linking endpoint 거부 테스트
- session fixation, CSRF, logout과 unauthenticated session no-create 테스트
- room mapping uniqueness·tombstone과 membership 거부 테스트
- RS256, `kid`, claim, cookie와 key overlap 통합 테스트
- 실제 provider/SMTP 및 ROUND relay·WebSocket 파일럿 E2E

## 관련 문서

- [계정 인증과 ROUND 참여권 PRD](../../PRD/0005_account-and-round-authentication/spec.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [파일럿 자체 호스팅](../0003_pilot-self-hosted-deployment/adr.md)
