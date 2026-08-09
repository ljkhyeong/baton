# ADR-0018: BATON 공개 edge에 ROUND runtime을 opt-in 통합

- 상태: 채택
- 결정일: 2026-08-09

## 배경

BATON은 Account session, AccountMembership, authoritative room mapping과 300초 RS256 참여권
발급을 구현했다. ROUND는 같은 참여권의 JWK 검증, room-bound TURN credential과 WebSocket
signaling을 구현했다. 로컬 private-CA E2E도 두 서비스를 같은 browser origin으로 연결하지만,
기존 production Compose와 Caddy는 BATON app·web·MySQL만 배포하므로 실제 공개 `/room`·TURN·WSS
경로는 제공하지 않는다.

ROUND의 standalone edge를 그대로 붙이면 Basic Auth, cookie path와 public route 계약이 BATON
mode와 충돌한다. coturn까지 BATON Compose에 넣으면 공인 IP, NAT·방화벽, 3478/5349와 relay
port 범위, TLS 인증서 회전의 실패 수명주기가 DB·session 배포와 결합된다.

## 결정

### BATON Caddy를 유일한 공개 HTTP ingress로 둔다

```text
인터넷
  │ 80/443
  ▼
BATON Caddy ─────────────── BATON 정적 web
  │ /api, refresh, JWK          │
  ├──────────────────────────► BATON app ── MySQL
  │ /room, /round-ui
  ├──────────────────────────► ROUND web
  │ public TURN/WSS rewrite
  └──────────────────────────► ROUND signaling

브라우저 ◄──────── credential ───── ROUND signaling
    │
    └──────── media relay ───────── 외부 coturn
```

호스트에는 Caddy의 80/443만 게시한다. ROUND web과 signaling은 host port를 갖지 않으며,
각각 Caddy와만 공유하는 별도 internal network에 둔다. Caddy는 signaling network에서
`${BATON_HOST}` alias를 소유한다. signaling은 issuer나 TLS SNI를 내부 주소로 바꾸지 않고
`https://${BATON_HOST}/.well-known/round-participation-jwks.json`을 조회하면서 public NAT hairpin에
의존하지 않는다.

public route는 다음으로 고정한다.

- `POST /round/rooms/{roomId}/participation-grant/refresh`: BATON app
- `GET /round/rooms/{roomId}/signal` → ROUND `/rooms/{roomId}/signal`
- `POST /round/rooms/{roomId}/turn-credentials` → ROUND `/api/rooms/{roomId}/turn-credentials`
- `/room/{roomId}`, `/round-ui/**`: BATON-mode ROUND web

Standalone 내부 path, ROUND actuator와 metrics는 공개하지 않는다. runtime이 비활성화되면 예약된
ROUND UI·signal·TURN path는 BATON SPA fallback이나 존재하지 않는 upstream의 502가 아니라
`404`와 `Cache-Control: no-store`로 닫는다.

### proxy credential을 allowlist한다

Caddy는 client가 보낸 `Forwarded`와 모든 `X-Forwarded-*`를 버리고 실제 client address,
canonical host·443·HTTPS 값을 다시 만든다. `Origin`, Fetch Metadata와 WebSocket upgrade는
보존한다.

ROUND signaling에는 raw Cookie header에 exact-case `__Secure-round_access`가 하나일 때만 그 cookie를
재구성해 전달한다. 같은 이름의 중복이나 대소문자 변형은 upstream 전에 `401`·`no-store`로 거부한다. BATON
`JSESSIONID`, Authorization·Proxy-Authorization, workspace/creation/recovery key, CSRF,
Idempotency-Key와 외부 request ID는 제거한다. ROUND web에는 Cookie와 Authorization을 전혀
전달하지 않는다. access log에서도 Cookie·Set-Cookie와 credential header를 제거한다.

ROUND 문서에만 camera·microphone·display-capture self 권한, blob media와 same-origin WSS CSP를
적용한다. BATON 관리 화면의 기존 금지 정책은 유지한다.

세 public room-scoped path는 ROUND가 이미 채택한 commit-pinned `mholt/caddy-ratelimit` Caddy
module을 재사용해 client IP당 1분 120회로 제한한다. IPv6 key는 /64로 묶는다. TURN의
participant/global quota와 signaling admission 제한은 이 pre-auth edge 제한을 보완하며 대신하지
않는다.

### runtime과 grant 발급 gate를 분리한다

`BATON_ROUND_RUNTIME_ENABLED`는 ROUND image·network·route를,
`BATON_ROUND_PARTICIPATION_GRANT_ENABLED`는 BATON signer를 연다. grant가 켜졌으면 runtime도
반드시 켜져야 하지만 runtime만 먼저 켜는 dark rollout은 허용한다.

검증된 wrapper가 runtime gate에 따라 저장소의 고정 `compose.round.production.yml` overlay를
선택한다. 호출자는 Compose file, project, profile이나 ambient Compose 변수를 바꿀 수 없다.
`up`에는 wrapper가 기본 세 서비스와 활성 ROUND 서비스를 항상 명시하고, `up`과 `down`에
`--remove-orphans`를 강제한다. 따라서 호출자가 일부 서비스만 적어도 Caddy gate와 optional
runtime이 함께 reconcile되고 enabled→disabled 전환에서 이전 ROUND 컨테이너가 제거된다.
`stop`, `down`, `kill`, `rm`, `logs`, `ps`는 gate가 닫힌 뒤에도 overlay를 포함해 잔존 서비스를
관리한다. 운영 명령은 positive allowlist로 제한하고 one-off `run`, signal-proxy `attach`,
model 변환, image publication, scaling, data volume 삭제와 topology override는 거부한다. `config`는 exact
`--quiet`만 허용한다. lifecycle 변경과 restore는 env·checkout·호출 UID와 무관하게 미리
provision한 owner-only `/srv/baton/state/production-lifecycle.lock` inode의 `flock`을 공유한다.

rollout은 runtime dark rollout → 내부 health·public route 확인 → grant signer 활성화 순서다.
rollback은 grant와 runtime gate를 함께 닫는 즉시 차단 절차다. grant gate가 refresh와 public
JWK를 함께 닫으므로 현재 계약은 기존 참여권을 만료까지 유지하는 graceful drain을 제공하지
않는다.

### release image와 secret provenance를 검증한다

운영 Compose는 인접 ROUND checkout을 build context로 사용하지 않는다. web은
`round-baton-web@sha256:...`, signaling은 `round-signaling@sha256:...` exact digest만 받는다.
preflight는 두 이미지를 pull하고 다음을 확인한다.

- web의 `io.round.auth-mode=baton`
- 두 이미지의 `org.opencontainers.image.revision`이 설정한 40자 revision과 일치
- 두 이미지의 `io.round.release.tag-object`가 유효하고 서로 일치

Signaling의 issuer, audience, cookie name, JWK URL, grant 최대 수명과 allowed origin은
`BATON_HOST`와 protocol 상수에서 Compose가 파생하며 운영 env로 다시 열지 않는다. signaling은
단일 replica, read-only root, `/tmp` tmpfs와 `no-new-privileges`로 실행한다.

ROUND runtime의 유일한 secret은 외부 coturn과 공유하는 정확히 32-byte의 64 lowercase-hex
값이다. env에는 owner-only host file 경로만 기록한다. wrapper는 원문을 환경으로 읽지 않고
file-backed Compose secret으로 `/run/secrets/round.turn.shared-secret`에 직접 mount하며, 두
ROUND 컨테이너를 그 host file 소유자의 비루트 UID/GID로 실행해 일반 Compose가 무시하는
secret `uid`·`gid`·`mode` 메타데이터에 의존하지 않는다. BATON RSA private key, DB, OAuth와
session secret은 ROUND에 전달하지 않는다.

### coturn과 health를 별도 운영 경계로 둔다

coturn은 BATON Compose 밖에서 운영한다. 공인 IP와 NAT, 3478/5349, UDP relay port 범위,
TLS certificate, allocation quota와 shared-secret 동시 회전은 ROUND 운영 단위가 소유한다.
BATON은 검증된 UDP·TCP·TLS URL 목록과 signaling용 secret 사본만 관리한다.

ROUND web `/healthz`와 signaling `/actuator/health/readiness`는 container readiness다. 공개
BATON `/actuator/health`에는 ROUND를 합치지 않는다. 이 health들은 JWK fetch나 실제 TURN
allocation·media relay를 증명하지 않으므로 public staging의 fresh grant probe를 별도로 둔다.

DB restore는 app·web뿐 아니라 project에 남은 ROUND web·signaling도 `exited`여야 시작한다.
복원 중 기존 membership에서 발급된 grant로 signaling이 계속되는 상태를 허용하지 않는다.

## 결과

### 장점

- 브라우저는 하나의 HTTPS origin과 room-scoped Secure cookie만 사용한다.
- BATON identity·membership과 ROUND의 in-memory media 책임이 DB 공유 없이 분리된다.
- immutable release, configtree secret과 최소 proxy credential이 공급망·비밀 노출 면적을 줄인다.
- runtime 장애가 BATON Caddy·app readiness를 막지 않고 ROUND path에만 국한된다.
- coturn의 공개 network·certificate 수명주기를 BATON DB rollback과 분리한다.

### 비용과 한계

- 두 release image의 promotion, TURN secret 동시 배포와 외부 relay monitor가 새 운영 책임이다.
- signaling은 in-memory 상태라 한 replica만 허용하며 scale-out에는 공유 room/admission 상태가 필요하다.
- 자동 검증은 local private CA와 credential 발급까지 다루지만 공인 DNS·ACME, 실제 외부 TURN
  allocation/media relay와 production key rotation을 대신하지 않는다.

## 검증

- production env/secret/image label fail-closed fixture
- production Caddy module, 비활성 route 404와 실제 pre-auth 429 runtime smoke
- local private-CA session → membership → refresh → TURN credential → WSS E2E
- restore 중 app·web·ROUND stopped-state fixture
- public staging의 UDP·TCP·TLS TURN allocation, relay media와 key overlap rehearsal

## 관련 문서

- [첫 파일럿 자체 호스팅](../0003_pilot-self-hosted-deployment/adr.md)
- [계정 identity와 session](../0017_account-identity-and-session/adr.md)
- [계정 인증과 ROUND 참여권 PRD](../../PRD/0005_account-and-round-authentication/spec.md)
- [BATON GO 교차 서비스 링크 계약](../../../../short-url/docs/PRD/0003_cross-service-link-contract/spec.md)
