# HANDOFF

- `codex/manager-integration-identity-20260730`에서 역할 바통 `V13`, BATON GO·ROUND 역할
  자료 링크, 공급자 중립 `UserAccount`·`MemberIdentityBinding`의 `V14`를 한 기준선으로
  합쳤고, Google OIDC·owner bootstrap·Spring Session 기반을 `V15`, 일반 구성원 초대를
  `V16`으로 추가했다.
- Google OIDC는 Authorization Code + PKCE를 사용한다. 시작 경로는
  `/api/v1/auth/oidc/authorization/google`, callback은
  `/api/v1/auth/oidc/callback/google`이다. 검증한 issuer·subject는 별도 외부 신원 결속에만
  저장하고 인증 session principal에는 BATON 내부 account UUID만 둔다. provider access·
  refresh·ID token은 DB나 인증 완료 session에 저장하지 않는다.
- 인증 상태는 MySQL Spring Session JDBC에 저장한다. idle timeout은 30분, 최초 로그인부터
  absolute lifetime은 12시간이다. 운영 cookie는 host-only
  `__Host-baton_session; Secure; HttpOnly; SameSite=Lax; Path=/`, 로컬은 별도
  `baton_session`이다. `GET /api/v1/auth/session`, `GET /api/v1/me`, CSRF가 필요한
  `POST /api/v1/session/logout` 계약이 열려 있다.
- 기존 팀의 첫 owner는 내부 운영자가
  `POST /api/v1/identity/bootstrap-invitations`에
  `X-Baton-Identity-Bootstrap-Key`, 소문자 canonical UUID `Idempotency-Key`와
  `{teamId, memberId}`를 보내 invitation을 발급한다. 최초 `201`, 같은 요청 재생 `200`이며
  운영 TTL은 1시간이다. production Caddy는 외부의 이 exact path를 빈 `404`로 끝내므로
  app 컨테이너 또는 신뢰한 내부 네트워크에서만 호출한다.
- 로그인 사용자는 `POST /api/v1/identity/invitations/preview`로 대상 팀·구성원·역할을
  먼저 확인하고 `POST /api/v1/identity/invitations/accept`로 결속한다. bootstrap token은
  팀의 유일한 `OWNER`, `mi1_` 일반 token은 `MEMBER`를 만든다. token은 단일 사용이며 같은
  account의 응답 유실 재생만 현재 결속을 반환한다. DB에는 invitation token과 멱등 키의
  SHA-256 hash만 저장한다.
- 현재 활성 `OWNER`는 session과 CSRF로
  `POST /api/v1/teams/{teamId}/member-invitations`를 호출해 일반 구성원 초대를 발급하고,
  `GET`으로 열린 초대를 조회하며
  `POST /api/v1/teams/{teamId}/member-invitations/{invitationId}/revocation`으로 폐기한다.
  canonical UUID 멱등 키의 같은 요청은 상태가 끝난 뒤에도 같은 token을 재생하지만 발급자의
  현재 OWNER 권한은 먼저 다시 확인한다. 운영 TTL은 정확히 24시간이다.
- 홈에는 Google 로그인·로그아웃과 초대 미리보기·수락 화면, 작업 공간에는 OWNER 전용
  `계정·초대` 화면이 있다. 원문 token과 CSRF는 URL·영속 저장소에 넣지 않고, 발급 응답
  유실 복구에는 팀·대상·멱등 UUID만 최소 journal로 저장한다. 로그아웃은 계정 범위 identity
  cache를 제거하지만 기존 workspace 공유 키는 유지한다.
- 사용자 계정과 팀 구성원 결속은 기존 roster를 보존하고 같은 팀에서 한 account당 구성원
  하나, 구성원당 account 하나만 허용한다. 활동 종료 구성원과 다른 account·구성원 재결속을
  거절하고 팀에는 `OWNER`를 하나만 둔다.
- 기존 workspace API는 점진 전환 동안 계속 `X-Baton-Access-Key`로 보호한다. OIDC session과
  owner bootstrap 성공만으로 기존 workspace 권한이 생기지 않으며 공유 키를 사용자 신원이나
  역할 바통 감사 주체로 기록하지 않는다.
- production env에는 기존 DB·workspace 비밀과 별도로
  `BATON_IDENTITY_BOOTSTRAP_KEY`, `BATON_IDENTITY_INVITATION_HMAC_SECRET`이 필수다.
  두 값은 32자 이상이며 서로와 다른 운영 비밀을 재사용하지 않는다.
  `BATON_IDENTITY_OIDC_ENABLED=true`일 때만 Google client ID·secret과 고정 redirect template
  overlay를 Compose에 추가한다.
- Caddy access log는 전체 request headers와 request URI, response `Set-Cookie`를 제거한다.
  session cookie, Authorization, OIDC code·state, invitation token, bootstrap key와 멱등 키를
  로그에 남기지 않는다. runtime smoke는 외부 bootstrap `404`와 이 sentinel 비노출을
  함께 검증한다.
- 2026-07-30에 격리 MySQL과 BATON, BATON GO, ROUND web을 실제로 띄우고 Chromium에서
  `역할 자료 클릭 → BATON GO 302 → ROUND 초대 화면 → 입장 준비 → 프리조인` 흐름을
  확인했다. 프리조인까지 signaling 요청은 0건이고 navigation에는 BATON 접근 키,
  Authorization, Referer, query와 fragment가 없었다.
- 같은 BATON GO 생성 intent는 `201 → 200`으로 동일 short URL을 반환하고 활성 중 `302`,
  만료 뒤 `410`이다. 프런트는 불명확한 클릭 실패에 같은 UUID와 만료 시각을 재사용한다.
  GO URL에는 이후에도 BATON 접근 키, OIDC/session 값이나 ROUND join ticket을 넣지 않는다.
- ROUND의 BATON room-aware signaling/TURN endpoint와 자동 signaling 재연결 전 참여권 갱신
  hook은 준비됐지만 BATON 참여권 발급기·RS256/JWKS·same-origin edge와 실제 browser
  provider는 아직 구현하지 않았다. 권한 행렬 전에는 `participant`만 발급하고 `host`는
  보류한다.
- 다음 제품 우선순위는 session principal과 활성 구성원 결속으로 BATON ROUND grant와
  RS256/JWKS·same-origin edge를 만들고 `grant → TURN → WSS`, 만료 갱신과 재연결을 실제
  브라우저로 검증하는 것이다. 권한 행렬 전에는 `participant`만 발급하고 `host`는 보류한다.
- 실제 Google production client, 공개 redirect/cookie/CSRF, 최초 owner 발급·수락은 아직
  운영 환경에서 검증하지 않았다. OIDC를 켜기 전에 provider console의 callback이
  `https://<BATON_HOST>/api/v1/auth/oidc/callback/google`과 정확히 일치하는지 확인한다.
- 파일럿 배포 사전점검·상태 감지와 기본 비활성화된 `External health sentinel` 구현·정적
  검증은 완료했지만 실제 공개 URL의 저장소 변수, 첫 예약 실행과 담당 계정의 GitHub Actions
  실패 알림 수신은 아직 검증하지 않았다.
- 첫 실제 복구 리허설에서 `restore.sh`의 팀별 공유 키 무효화,
  `last-restore-recovery-targets.tsv`를 이용한 새 키 발급과 이전 링크의 `403`을 확인해야 한다.
