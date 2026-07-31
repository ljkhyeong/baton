# ADR-0018: 신원 기반 ROUND 참여권과 same-origin 입장 경계

- 상태: 채택
- 결정일: 2026-07-31

## 배경

ADR-0014는 역할 자료의 canonical ROUND room을 BATON GO의 짧은 링크로 여는 경계를
정했다. GO 링크는 room 위치만 전달하므로 BATON 접근 키, 로그인 session이나 ROUND
참여권을 포함하지 않는다. 이후 ADR-0015~0017에서 공급자 중립 계정, Google OIDC
session과 활성 팀 구성원 결속이 마련되어 실제 로그인 사용자를 기준으로 ROUND 입장
권한을 판단할 수 있게 되었다.

ROUND는 별도 마이크로서비스로 signaling과 TURN credential을 소유하며, RS256 JWT를
로컬에서 검증하는 BATON 모드를 이미 제공한다. 이제 BATON이 참여 권한과 서명키를
소유하면서도 토큰을 JavaScript, URL, 저장소와 로그에 노출하지 않는 발급·갱신·배포
계약이 필요하다.

## 결정

### 권한과 대상

- 참여권 발급은 로그인 session, 유효한 CSRF와 해당 팀의 활동 중
  `MemberIdentityBinding`을 모두 요구한다. 팀 공유 `X-Baton-Access-Key`는 참여 권한의
  증거로 사용하지 않는다.
- 서버는 path의 팀·시즌·역할 자료를 저장소에서 다시 조회한다. 자료 URL은 설정한 BATON
  same-origin과 정확히 일치하고 `/room/{canonicalRoomId}`여야 한다. 브라우저가 보낸
  `roomId`는 권한 대상에 사용하지 않는다.
- 초기 계약의 역할 claim은 항상 `participant`다. BATON 역할 담당자와 ROUND `host`
  권한의 의미를 별도로 정의하기 전에는 이름이나 화면 상태에서 host를 추론하지 않는다.
- GO는 계속 credential 없는 room locator만 소유한다. short URL target, query와 fragment에
  참여권, session, 접근 키와 입장 컨텍스트를 넣지 않는다.

### HTTP와 cookie

참여권은 다음 session API로 발급하거나 갱신한다.

```http
POST /api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/round-participation-grant
```

- exact `Origin`, `Sec-Fetch-Site: same-origin`과 session의 동적 CSRF header가 모두
  일치해야 한다.
- 성공은 body 없는 `204 No Content`와 `Cache-Control: no-store`다.
- JWT는 응답 body에 넣지 않고 host-only
  `__Secure-round_access` cookie로만 전달한다. cookie는 `Secure`, `HttpOnly`,
  `SameSite=Strict`, `Path=/round/rooms/{roomId}`, `Max-Age<=300`을 사용하고 `Domain`을
  생략한다.
- BATON session은 browser가 host-only를 강제하는
  `__Host-baton_session; Secure; Path=/`을 유지한다. ROUND 정적 web proxy는 모든 request
  cookie를 제거하고 보호 전송 proxy는 알려진 host-only BATON session의 앞·뒤 공존과
  exact 단일 `__Secure-round_access=<JWS>`만 허용한 뒤 Cookie header를 참여권 하나로
  다시 조립한다. 따라서 BATON session은 ROUND web·TURN·WebSocket upstream에 전달되지
  않는다.
- 신원·membership·자료 경계 실패는 안정적인 오류 코드로 거절한다. 설정·키 파일·서명
  장애는 원본 ROUND로 우회하지 않고 `503 ROUND_GRANT_SIGNER_UNAVAILABLE`로 닫힌다.
- 두 발급 경로는 후보 조회 뒤에도 팀·시즌·역할 자료와 활동 중 session 구성원을
  공유 잠금으로 다시 검증한다. RS256 서명은 로컬 작업이므로 참여권 검증 transaction
  안에서 끝내 잠금을 서명 완료까지 유지하고, 구성원 활동 종료와 발급 순서를
  선형화한다.

### 서명과 공개키 교체

- BATON은 PKCS#8 RSA private key로 `RS256` JWT를 서명한다. header는 활성 `kid`를
  포함한다.
- claim은 exact `iss`, `aud=round`, account UUID `sub`, 초 단위 `iat`, 최대 5분의
  `exp`, 매 발급마다 새 canonical UUID `jti`, canonical `room_id`, season UUID
  `study_id`, `role=participant`다.
- private key와 여러 public RSA JWK를 담은 JWK Set은 별도 read-only 운영 파일로
  주입한다. 활성 private key의 modulus·public exponent는 같은 `kid`의 공개키와
  일치해야 하며 2048 bit 미만 키, private JWK, 중복 `kid`와 비-RS256 key는 거절한다.
- `GET /.well-known/jwks.json`은 public key만 반환하고 ETag와
  `Cache-Control: public, max-age=60, must-revalidate`를 사용한다.
- 익명 요청마다 키 파일을 다시 파싱하지 않는다. BATON은 검증이 끝난 공개 key ring
  snapshot을 60초 동안 재사용하고 만료 뒤 한 요청만 동기화해 다시 읽는다. 잘못된 새
  snapshot은 signer unavailable로 닫으며 마지막 검증 snapshot을 무기한 연장하지 않는다.
- 교체는 새 공개키 추가, cache 전파, 새 `kid` 서명 전환, 기존 참여권과 검증 cache의
  충분한 overlap 뒤 이전 공개키 제거 순서로 수행한다. private key는 ROUND에 전달하지
  않는다.

### 브라우저 수명주기

- 역할 자료 클릭 직전에 프런트는 URL에서 canonical room을 검증하고, 같은 탭의
  `sessionStorage`에 version과 `teamId`, `seasonId`, `resourceId`, `roomId`만 기록한다.
  쓰기와 재읽기 검증이 실패하면 이동하지 않는다. 이 값은 권한이 아니며 access key,
  CSRF와 token을 포함하지 않는다.
- ROUND의 직접 초대·landing·prejoin은 보호 API, media와 WebSocket을 호출하지 않는다.
  사용자가 명시적으로 입장할 때만 `참여권 → TURN → 최초 WebSocket` 순서로 진행한다.
- TURN 갱신·재시도는 `새 참여권 → TURN → ICE 설정 교체`, signaling 재연결은 replacement
  socket 생성 전에 새 참여권을 발급한다.
- 같은 room에서 동시에 진행 중인 발급만 하나로 합치며, 완료 결과는 cache하지 않는다.
  standalone 모드는 참여권 API를 호출하지 않고 기존 동작을 유지한다.

### same-origin edge와 배포

- BATON edge는 `/room/{roomId}` HTML과 `/round-ui/*` 정적 자산을 별도 BATON-mode
  ROUND 웹 이미지에서 제공한다. 공개 room 경로는 GO 계약과 같은 `/room/{roomId}`이며
  `/round-ui/`는 자산 base로만 사용한다.
- `/round/rooms/{roomId}/signal`은 ROUND 내부 `/rooms/{roomId}/signal`,
  `/round/rooms/{roomId}/turn-credentials`는
  `/api/rooms/{roomId}/turn-credentials`로 rewrite한다.
- edge는 exact 단일 ROUND 참여권 cookie, WebSocket upgrade와 원래 `Origin`만 보존한다.
  다른 cookie와 `Authorization`, client가 보낸 `Forwarded`·`X-Forwarded-*`는 제거하고
  canonical HTTPS forwarding 정보만 다시 설정한다. 두 보호 경로에는 IP 기준 pre-auth
  rate limit과 `no-store`를 적용한다.
- ROUND 표면에만 camera·microphone `(self)`, blob media와 same-origin WSS를 허용한다.
  기존 BATON 화면은 camera·microphone을 계속 차단한다.
- 기본 production Compose는 참여권 기능을 끈다. 명시적 overlay는 OIDC·GO, BATON과 같은
  ROUND 공개 origin, digest로 고정한 ROUND web/signaling 이미지, signer key ring과 TURN
  비밀을 모두 요구하고 Java·ROUND port를 host에 게시하지 않는다.

## 결과

### 장점

- 참여 권한은 공유 키가 아니라 검증된 사용자와 활성 membership에 결속된다.
- GO, BATON과 ROUND의 데이터·런타임 소유권을 섞지 않고도 브라우저에는 한 origin의
  자연스러운 입장 흐름을 제공한다.
- private key와 JWT가 ROUND·JavaScript·URL·브라우저 저장소·로그로 확산되지 않는다.
- 이미 연결된 signaling은 BATON 장애의 hot path 영향을 받지 않으며 새 발급과 재연결만
  fail-closed한다.

### 비용과 한계

- RSA key ring, JWK cache overlap, 별도 BATON 웹 이미지와 edge rewrite를 함께 운영해야
  한다.
- 현재 참여자는 모두 `participant`다. host 권한, 강제 퇴장과 실시간 권한 폐기는 후속
  계약이다.
- same-origin ROUND bundle은 BATON browser trust boundary 안에 있다.
  [ADR-0019](../0019_session-based-workspace-authorization/adr.md)는 workspace 권한을
  session 구성원 결속으로 전환하고 origin-wide `localStorage` access key를 제거해 장기
  bearer key 탈취 범위를 줄인다. 다만 same-origin API 호출 권한까지 완전히 격리하려면
  별도 UI origin이 필요하다.
- 참여권 만료만으로 이미 열린 WebSocket을 종료하지 않는다. membership 폐기 직후의 즉시
  연결 종료가 필요하면 ROUND의 명시적 revocation 정책이 추가로 필요하다.
- 실제 배포 승인은 HTTPS full-stack에서 grant, TURN, WSS, 재연결, key rotation과
  로그 비노출을 검증한 뒤에만 가능하다.

## 검증

```bash
./gradlew --no-daemon :application:test --tests '*RoundParticipationGrant*'
./gradlew --no-daemon :adapter-out-external:test --tests '*RoundParticipationGrant*'
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon checkApiContract
cd frontend && npm run build && npm run e2e -- workspace.spec.ts
bash ops/tests/pilot-readiness-test.sh
```

ROUND 저장소에서는 web·rtc-core 테스트, Java signaling BATON 경계 테스트,
`baton-web-runtime` 이미지와 BATON production overlay를 함께 검증한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [제품 로드맵](../../PRD/0003_product-roadmap/spec.md)
- [BATON GO를 통한 ROUND 역할 자료 링크](../0014_baton-go-round-resource-links/adr.md)
- [공급자 중립 사용자 신원 결속](../0015_provider-neutral-user-identity-binding/adr.md)
- [Google OIDC session과 owner bootstrap](../0016_google-oidc-session-owner-bootstrap/adr.md)
- [OWNER 발급 일반 구성원 초대](../0017_owner-issued-member-invitations/adr.md)
- [세션 구성원 기반 workspace 권한 전환](../0019_session-based-workspace-authorization/adr.md)
