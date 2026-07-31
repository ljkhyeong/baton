# HANDOFF

- `codex/manager-live-ready-20260731`에서 역할 바통 `V13`, BATON GO·ROUND 역할
  자료 링크, 기록 탐색 생성 시각의 `V14`, 공급자 중립
  `UserAccount`·`MemberIdentityBinding`의 `V15`를 한 기준선으로 합쳤고,
  Google OIDC·owner bootstrap·Spring Session 기반을 `V16`, 일반 구성원 초대를
  `V17`로 추가했다.
- Google OIDC는 Authorization Code + PKCE를 사용한다. 시작 경로는
  `/api/v1/auth/oidc/authorization/google`, callback은
  `/api/v1/auth/oidc/callback/google`이다. 검증한 issuer·subject는 별도 외부 신원 결속에만
  저장하고 인증 session principal에는 BATON 내부 account UUID만 둔다. provider access·
  refresh·ID token은 DB나 인증 완료 session에 저장하지 않는다.
- 인증 상태는 MySQL Spring Session JDBC에 저장한다. idle timeout은 30분, 최초 로그인부터
  absolute lifetime은 12시간이다. 운영 cookie는 host-only
  `__Host-baton_session; Secure; HttpOnly; SameSite=Lax; Path=/`, 로컬은 별도
  `baton_session`이다. production edge가 ROUND 정적 upstream의 cookie를 모두 제거하고
  signaling·TURN에는 검증한 참여권만 다시 조립하므로 BATON session을 보내지 않는다.
  `GET /api/v1/auth/session`, `GET /api/v1/me`, CSRF가 필요한
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
  유실 복구에는 팀·대상·멱등 UUID만 최소 journal로 저장한다. 로그아웃은 identity와
  workspace cache, 같은 탭의 ROUND entry context를 함께 제거한다.
- 사용자 계정과 팀 구성원 결속은 기존 roster를 보존하고 같은 팀에서 한 account당 구성원
  하나, 구성원당 account 하나만 허용한다. 활동 종료 구성원과 다른 account·구성원 재결속을
  거절하고 팀에는 `OWNER`를 하나만 둔다.
- 일반 workspace API와 역할 자료 열기는 access-key header가 없으면 session account의
  활동 중 `MemberIdentityBinding`, header가 있으면 명시적 레거시 키를 검증한다. 잘못된
  레거시 키는 session으로 fallback하지 않는다. session이 있는 모든 변경 요청은 header
  유무와 관계없이 CSRF가 필수이고, 익명 레거시 요청만 과도기 예외다. 활성
  `OWNER|MEMBER`는 세부 권한 계약 전까지 기존 workspace 범위를 함께 사용한다. session
  결정의 작성자와 역할 바통 확인자는 현재 account의 결속 구성원과 같아야 한다.
  mutation과 외부 권한 intent는 활동 중 구성원을 공유 잠금으로 다시 확인하며, ROUND
  참여권은 로컬 서명이 끝날 때까지 잠금을 유지한다.
- 프런트는 `baton-access-key:*`를 한 번 소비한 뒤 삭제하고 원문 키를 `localStorage`,
  React Query key나 ROUND entry context에 다시 저장하지 않는다. 결속된 사용자는
  credential 없는 workspace 주소를 사용하고, 미결속 레거시 사용자는 fragment 키를
  메모리에서만 사용한다. 최근 workspace 메타데이터는 session account별 `v2` 저장소에만
  기록하고 로그아웃·account 교체 때 이전 account의 query·mutation·최근 목록과 ROUND
  context를 제거한다. 로그인 신규 생성은 `POST /api/v1/me/workspaces`에서 명시적으로
  선택한 초기 구성원을 같은 transaction 안에 현재 account의 `OWNER`로 결속하고,
  access key 없는 응답과 clean workspace URL을 사용한다. 전송 직전 session account와
  동적 CSRF를 다시 확인하며 session 만료를 익명 레거시 생성으로 fallback하지 않는다.
  복구 journal은 mode·account·OWNER를 함께 고정하고 현재 account·생성 방식과 다른
  기록은 삭제하지 않은 채 목록에서 숨긴다.
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
- ROUND 참여권은 session principal과 활성 `MemberIdentityBinding`을 기준으로 발급한다.
  resource-owned endpoint는 저장된 팀·시즌·자료 URL에서 room을 다시 확인하고, 직접 초대
  fallback은 로그인 account가 접근 가능한 같은 room 자료가 정확히 하나일 때만 허용한다.
  공유 `X-Baton-Access-Key`는 참여 권한으로 쓰지 않는다.
- BATON은 active PKCS#8 RSA private key와 여러 public JWK의 key ring으로 최대 5분
  `RS256` JWT를 서명한다. JWT는 `participant` role, account UUID `sub`, season UUID
  `study_id`, canonical `room_id`와 매번 새 `jti`를 담고, JavaScript body가 아닌
  `Secure; HttpOnly; SameSite=Strict; Path=/round/rooms/{roomId}` cookie로만 전달한다.
  `/.well-known/jwks.json`은 public key만 ETag와 60초 public cache로 제공한다.
- ROUND browser provider는 명시적 입장에서 `grant → TURN → WebSocket`, TURN 갱신과
  signaling 재연결에서 `새 grant → 보호 요청` 순서를 지킨다. 정상 same-tab entry
  context는 공개 refresh endpoint에 팀·회차·자료 locator를 보내고, context가 없는 직접
  초대는 exact room 후보 fallback을 사용한다. locator는 권한이 아니며 서버가 공유
  잠금으로 다시 확인한다. 손상·필드 추가·room mismatch context는 fallback 없이 닫힌다.
- production Caddy는 `/room/{roomId}`와 `/round-ui/*`를 BATON-mode ROUND web으로,
  signal·TURN 경로를 private signaling으로, refresh 경로를 BATON app으로 전달한다.
  custom Caddy의 pre-auth rate limit, exact method·canonical path·query 거부, forwarding
  header 재작성과 1KB refresh body 제한을 사용한다. refresh는 BATON session 하나만,
  signal·TURN은 exact 참여권 하나만 upstream에 재조립하며 ROUND web·signaling·TURN의
  `Set-Cookie`는 제거한다. camera·microphone path policy와 안전한 로그 필터를 유지한다.
  ROUND web·signaling 이미지는 digest로 고정하고 전용 internal network에 두며 외부 TURN을
  전제로 한다.
- `ops/verify-round-live-readiness.sh`는 canonical production env의 RSA private/JWK pair와
  실제 배포 active JWK 일치, OIDC PKCE·session cookie, HTTPS redirect·보안 header, GO와
  외부 TURN TLS를 비밀 출력 없이 fail-closed로 검사한다. 기본은 단일 active key를 허용하고
  rotation overlap에서는 `--require-key-overlap`으로 local·remote 공개키 두 개 이상을
  강제한다. 회귀 테스트와 실제 두 계정 검증 절차는
  `docs/runbooks/round-https-live-verification.md`에 정리했다.
- 다음 제품 우선순위는 실제 Google/OIDC 계정 두 개와 외부 TURN을 포함한 HTTPS 환경에서
  `GO 302 → prejoin → grant → TURN → WSS`, 직접 초대, 만료 갱신·재연결, dual-key
  rotation과 로그 비노출을 브라우저로 검증한다. 그 전에는 `host` 권한과 즉시
  revocation을 추가하지 않는다.
- 실제 Google production client, 공개 redirect/cookie/CSRF, 최초 owner 발급·수락은 아직
  운영 환경에서 검증하지 않았다. OIDC를 켜기 전에 provider console의 callback이
  `https://<BATON_HOST>/api/v1/auth/oidc/callback/google`과 정확히 일치하는지 확인한다.
- 현재 작업 환경에는 실제 Google production client·두 계정, 공인 BATON/GO/ROUND 주소와
  외부 TURN credential이 없어 live readiness와 브라우저 runbook은 아직 실행하지 못했다.
  이 입력이 준비되기 전까지 실환경 gate 결과는 `NOT RUN`이며 배포 승인으로 해석하지 않는다.
- 파일럿 배포 사전점검·상태 감지와 기본 비활성화된 `External health sentinel` 구현·정적
  검증은 완료했지만 실제 공개 URL의 저장소 변수, 첫 예약 실행과 담당 계정의 GitHub Actions
  실패 알림 수신은 아직 검증하지 않았다.
- sentinel 워크플로가 `main`에 반영된 뒤 README 순서대로 실제 공개 URL의 수동 성공을
  확인하고 예약 검사를 활성화해야 한다.
- 실제 파일럿 데이터를 넣기 전 crypt remote에서 dump와 sidecar를 내려받아 별도 환경에
  import하고, `restore.sh`의 팀별 공유 키 무효화와
  `last-restore-recovery-targets.tsv`를 따른 새 키 발급·이전 링크 `403`·복구 완료 재백업까지
  자동 품질 게이트 밖의 실제 자격으로 확인해야 한다.
- 첫 그룹 스터디 실사용에서 조직 연속성 레이더가 놓칠 뻔한 책임이나 인수인계 공백을 한 번 이상 미리 발견하는지 확인하고 오탐과 행동 문구를 기록해야 한다.
- 첫 그룹 스터디에서 과거 결정의 결과·이유·관련 역할을 탐색 화면에서 짧은 흐름으로 다시 찾을 수 있는지 확인하고, 놓친 검색어·필터와 V14 이전 시각 미상 안내의 이해도를 기록해야 한다.
