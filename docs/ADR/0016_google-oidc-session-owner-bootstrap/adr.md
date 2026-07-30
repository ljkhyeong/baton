# ADR-0016: Google OIDC 세션과 일회성 owner bootstrap

- 상태: 채택
- 결정일: 2026-07-30

## 배경

ADR-0015는 로그인 공급자와 분리된 `UserAccount` UUID와 기존 팀 roster의
`MemberIdentityBinding`을 만들었지만, 실제 로그인 principal과 기존 팀의 첫 소유자를
안전하게 연결하는 흐름은 정하지 않았다. 팀 공유 `X-Baton-Access-Key`나 요청 body의
계정 UUID를 사용자 신원으로 사용하면 누구나 다른 구성원을 사칭할 수 있다.

ROUND 참여권도 안정적인 사용자별 `sub`가 필요하다. 따라서 공유 키를 바로 없애기 전에
검증된 로그인 principal, 서버 저장 세션과 한 번만 사용할 수 있는 기존 팀 결속 권한을
먼저 도입한다.

## 결정

### OIDC 로그인과 내부 계정

- 첫 공급자는 Google이지만 application과 저장 모델은 공급자 중립적으로 유지한다.
- 브라우저 로그인은 OIDC Authorization Code + PKCE를 사용한다.
- 시작 경로는 `GET /api/v1/auth/oidc/authorization/google`, callback은
  `GET /api/v1/auth/oidc/callback/google`이다.
- web adapter는 검증된 OIDC `issuer`와 `subject`로 `OidcExternalIdentity`를 조회한다.
  처음 보는 외부 신원이면 새 `UserAccount`와 외부 신원 결속을 한 transaction에서 만들고,
  이후에는 같은 내부 계정 UUID를 principal로 사용한다.
- provider access token, refresh token과 ID token은 BATON DB나 HTTP session에 저장하지
  않는다. 외부 `subject`도 BATON·ROUND의 공개 사용자 식별자로 사용하지 않는다.
- OIDC 기능은 `BATON_IDENTITY_OIDC_ENABLED=false`가 기본이다. 활성화한 운영 환경만
  Google client ID·secret과 고정 callback template
  `{baseUrl}/api/v1/auth/oidc/callback/{registrationId}`를 주입한다.

### 서버 세션과 CSRF

- 인증 상태는 Spring Session JDBC의 MySQL opaque session으로 저장한다. Flyway가 session
  table을 소유하며 Spring의 자동 schema 초기화는 사용하지 않는다.
- inactivity timeout은 30분, 최초 로그인부터 absolute lifetime은 12시간이다. absolute
  lifetime을 넘은 세션은 요청 시 즉시 무효화한다.
- 운영 cookie는 host-only `__Host-baton_session`, `Secure`, `HttpOnly`, `SameSite=Lax`,
  `Path=/`이고 `Domain`을 설정하지 않는다. 로컬 개발은 secure가 아닌 별도
  `baton_session` 이름을 사용한다.
- `GET /api/v1/auth/session`은 익명 상태 또는 인증된 내부 account ID와 CSRF header 이름·
  token을 `Cache-Control: no-store`로 반환한다.
- session을 사용하는 변경 요청은 CSRF token을 요구한다. 로그아웃은
  `POST /api/v1/session/logout`이고 세션과 security context를 폐기한다.
- 인증 실패와 CSRF 실패는 HTML redirect 대신 안정적인 JSON 오류를 반환한다.

### 기존 팀의 최초 owner bootstrap

- operator만 내부 네트워크에서
  `POST /api/v1/identity/bootstrap-invitations`를 호출한다.
- 요청은 `X-Baton-Identity-Bootstrap-Key`, canonical UUID `Idempotency-Key`와
  `{teamId, memberId}`를 요구한다. 운영 bootstrap key는 팀 공유 키·복구 키·초대 HMAC
  secret과 별도로 생성한다.
- 외부 Caddy는 이 exact 발급 경로를 `404`로 끝내고 application으로 전달하지 않는다.
  operator는 app 컨테이너 또는 내부 네트워크 endpoint를 사용한다.
- 발급 token은 256-bit 이상의 비밀 capability다. server secret과 멱등 키로 결정론적으로
  파생해 동일 요청의 응답 유실을 복구하고, DB에는 token과 멱등 키의 SHA-256 hash만
  저장한다. 원문 token은 최초 `201`과 같은 요청의 `200` 재생에서만 반환한다.
- 운영 TTL은 정확히 1시간이고 application도 1시간보다 긴 TTL을 거절한다. 대상은 같은
  팀의 활동 중이며 아직 다른 계정에 결속되지 않은 구성원이어야 하고, 팀에 기존 owner가
  없어야 한다.
- 로그인한 사용자는 CSRF token과 함께
  `POST /api/v1/identity/invitations/accept`에 `{token}`을 보낸다. 성공 transaction은
  invitation을 소비하고 현재 `UserAccount`를 기존 `Member`에 결속하며 팀의 유일한
  `OWNER` 역할을 부여한다.
- invitation은 한 번만 사용할 수 있고 만료·폐기·다른 계정의 재생을 거절한다. application은
  폐기를 지원하지만 이번 범위에서는 revoke HTTP endpoint를 공개하지 않는다.
- token은 URL, query, redirect, cookie와 로그에 넣지 않는다. 발급·수락 응답은 모두
  `Cache-Control: no-store`다.

### 데이터와 동시성

- Flyway V15가 OIDC 외부 신원, owner bootstrap invitation, 팀별 identity role과 Spring
  Session table을 추가한다.
- `(issuer, subject)`는 한 내부 계정에만 연결되고 한 팀에는 `OWNER`가 하나만 존재한다.
- invitation 수락은 계정, invitation, 구성원을 같은 순서로 배타 잠금한 뒤 결속과 owner
  유일성을 한 transaction에서 확인한다. application 검증과 DB unique constraint를 함께
  사용해 동시 수락에서도 소유자가 둘 생기지 않게 한다.
- 절대 시각은 주입된 `Clock`과 UTC `Instant`를 사용한다.

### 운영 경계

- production profile은 bootstrap key와 invitation HMAC secret이 누락되거나 32자 미만,
  서로 같거나 기존 운영 secret과 재사용되면 시작 또는 사전점검에서 실패한다.
- OIDC를 켰을 때만 Google 자격증명 overlay를 Compose에 추가한다. OIDC가 꺼진 환경에는
  Google 자격증명을 전달하지 않는다.
- Caddy access log는 전체 request headers와 request URI, response `Set-Cookie`를
  제거한다. session cookie, OIDC code·state, invitation token, Authorization과 운영
  bootstrap key를 기록하지 않는다.
- callback과 cookie의 secure 판단을 위해 production에서는 trusted proxy가 전달한
  HTTPS scheme을 사용한다.

## 결과

### 장점

- 공급자 subject와 token을 서비스 경계에 퍼뜨리지 않고 안정적인 BATON 사용자 UUID를
  만든다.
- 브라우저가 비밀번호나 provider token을 보관하지 않고 서버가 세션 폐기와 timeout을
  통제한다.
- 기존 roster를 다시 만들지 않고 검증된 사용자와 일회성 capability를 함께 요구해 최초
  owner를 결속한다.
- 외부 edge, 로그 redaction, 만료·단일 사용과 DB 유일 제약이 bootstrap token 탈취와
  중복 수락의 피해를 줄인다.

### 비용과 한계

- Spring Session table과 만료 정리, OIDC client secret, invitation HMAC secret의 운영
  책임이 생긴다.
- 이번 변경은 일반 구성원 invitation, owner가 발급하는 초대 UI, 계정 비활성화·탈퇴,
  여러 OIDC 공급자 연결과 계정 복구를 구현하지 않는다.
- 기존 workspace API는 점진 전환을 위해 계속 공유 접근 키를 사용한다. 로그인 세션만으로
  기존 workspace 변경 권한을 얻지는 않는다.
- 프런트 로그인·bootstrap 수락 화면과 BATON의 ROUND 참여권/JWKS 발급은 후속 범위다.

## 검토한 대안

### 공유 접근 키로 account를 선택

키를 아는 사람이 임의 구성원을 선택해 사칭할 수 있어 채택하지 않았다.

### invitation token만으로 owner 결속

탈취한 token만으로 계정을 만들거나 다른 사람에게 결속할 수 있으므로 검증된 OIDC 세션을
함께 요구한다.

### JWT를 BATON 로그인 세션으로 사용

즉시 폐기와 server-side session 제어가 복잡해지고 browser storage에 credential을 둘
위험이 커져 첫 파일럿에는 JDBC opaque session을 선택했다.

### Google subject를 내부 사용자 ID로 사용

공급자 변경·계정 연결 때 식별자가 흔들리고 외부 식별자가 ROUND까지 확산되므로 채택하지
않았다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:test
./gradlew --no-daemon :adapter-in-web:test
./gradlew --no-daemon :bootstrap:mysqlTest
bash ops/tests/pilot-readiness-test.sh
bash ops/tests/production-runtime-smoke.sh
```

OIDC account mapping, PKCE와 provider token 비저장, 익명·인증 session, CSRF, idle·absolute
만료, 발급 멱등 재생, hash-only 저장, 단일 사용·만료·폐기, 팀별 owner 유일성, production
secret fail-closed, 외부 발급 경로 차단과 access log 비노출을 확인한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [로그인 공급자와 팀 구성원을 분리한 사용자 신원 결속 기반](../0015_provider-neutral-user-identity-binding/adr.md)
- [BATON GO를 통한 ROUND 역할 자료 링크](../0014_baton-go-round-resource-links/adr.md)
