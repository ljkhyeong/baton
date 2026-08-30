# ADR-0018: BATON 공개 엣지에 ROUND 런타임을 선택적으로 통합

- 상태: 채택
- 결정일: 2026-08-09

## 배경

BATON은 Account 세션, `AccountMembership`, 서버 권위 방 매핑과 300초 RS256 참여권
발급을 구현했다. ROUND는 같은 참여권의 JWK 검증, 방에 결합된 TURN 자격 증명과 WebSocket
시그널링을 구현했다. 로컬 사설 CA E2E도 두 서비스를 같은 브라우저 출처로 연결하지만,
기존 프로덕션 Compose와 Caddy는 BATON 애플리케이션·웹·MySQL만 배포하므로 실제 공개 `/room`·TURN·WSS
경로는 제공하지 않는다.

ROUND의 독립 엣지를 그대로 붙이면 Basic Auth, 쿠키 경로와 공개 경로 계약이 BATON
모드와 충돌한다. coturn까지 BATON Compose에 넣으면 공인 IP, NAT·방화벽, 3478/5349와 릴레이
포트 범위, TLS 인증서 회전의 실패 생명주기가 DB·세션 배포와 결합된다.

## 결정

### BATON Caddy를 유일한 공개 HTTP 진입점으로 둔다

```text
인터넷
  │ 80/443
  ▼
BATON Caddy ─────────────── BATON 정적 웹
  │ /api, 갱신, JWK             │
  ├──────────────────────────► BATON 애플리케이션 ── MySQL
  │ /room, /round-ui
  ├──────────────────────────► ROUND 웹
  │ 공개 TURN/WSS 재작성
  └──────────────────────────► ROUND 시그널링

브라우저 ◄──────── 자격 증명 ───── ROUND 시그널링
    │
    └──────── 미디어 릴레이 ────── 외부 coturn
```

호스트에는 Caddy의 80/443만 게시한다. ROUND 웹과 시그널링은 호스트 포트를 갖지 않으며,
각각 Caddy와만 공유하는 별도 내부 네트워크에 둔다. Caddy는 시그널링 네트워크에서
`${BATON_HOST}` 별칭을 소유한다. 시그널링은 발급자나 TLS SNI를 내부 주소로 바꾸지 않고
`https://${BATON_HOST}/.well-known/round-participation-jwks.json`을 조회하면서 공개 NAT 헤어핀에
의존하지 않는다.

공개 경로는 다음으로 고정한다.

- `POST /round/rooms/{roomId}/participation-grant/refresh`: BATON 애플리케이션
- `GET /round/rooms/{roomId}/signal` → ROUND `/rooms/{roomId}/signal`
- `POST /round/rooms/{roomId}/turn-credentials` → ROUND `/api/rooms/{roomId}/turn-credentials`
- `/room/{roomId}`, `/round-ui/**`: BATON 모드 ROUND 웹

독립 실행 내부 경로, ROUND Actuator와 메트릭은 공개하지 않는다. 런타임이 비활성화되면 예약된
ROUND UI·시그널·TURN 경로는 BATON SPA 대체 응답이나 존재하지 않는 업스트림의 502가 아니라
`404`와 `Cache-Control: no-store`로 닫는다.

### 프록시 자격 증명을 허용 목록으로 제한한다

Caddy는 클라이언트가 보낸 `Forwarded`와 모든 `X-Forwarded-*`를 버리고 실제 클라이언트 주소,
정규 호스트·443·HTTPS 값을 다시 만든다. `Origin`, Fetch Metadata와 WebSocket 업그레이드는
보존한다.

ROUND 시그널링에는 원문 `Cookie` 헤더에 대소문자가 정확히 일치하는 `__Secure-round_access`가 하나일 때만 그 쿠키를
재구성해 전달한다. 같은 이름의 중복이나 대소문자 변형은 업스트림 전에 `401`·`no-store`로 거부한다. BATON
`JSESSIONID`, `Authorization`·`Proxy-Authorization`, 워크스페이스·생성·복구 키, CSRF,
`Idempotency-Key`와 외부 요청 ID는 제거한다. ROUND 웹에는 `Cookie`와 `Authorization`을 전혀
전달하지 않는다. 접근 로그에서도 `Cookie`·`Set-Cookie`와 자격 증명 헤더를 제거한다.

ROUND 문서에만 `camera`·`microphone`·`display-capture`의 `self` 권한, `blob:` 미디어와 동일 출처 WSS CSP를
적용한다. BATON 관리 화면의 기존 금지 정책은 유지한다.

세 공개 방 범위 경로는 ROUND가 이미 채택한 커밋 고정 `mholt/caddy-ratelimit` Caddy
모듈을 재사용해 클라이언트 IP당 1분 120회로 제한한다. IPv6 키는 /64로 묶는다. TURN의
참가자·전역 할당량과 시그널링 입장 제한은 이 인증 전 엣지 제한을 보완하며 대신하지
않는다.

### 런타임과 참여권 발급 게이트를 분리한다

`BATON_ROUND_RUNTIME_ENABLED`는 ROUND 이미지·네트워크·경로를,
`BATON_ROUND_PARTICIPATION_GRANT_ENABLED`는 BATON 서명자를 연다. 참여권 발급이 켜졌으면 런타임도
반드시 켜져야 하지만 런타임만 먼저 켜는 다크 롤아웃은 허용한다.

검증된 래퍼가 런타임 게이트에 따라 저장소의 고정 `compose.round.production.yml` 오버레이를
선택한다. 호출자는 Compose 파일, 프로젝트, 프로필이나 주변 Compose 변수를 바꿀 수 없다.
`up`에는 래퍼가 기본 세 서비스와 활성 ROUND 서비스를 항상 명시하고, `up`과 `down`에
`--remove-orphans`를 강제한다. 따라서 호출자가 일부 서비스만 적어도 Caddy 게이트와 선택적
런타임이 함께 정합성을 맞추고 활성화→비활성화 전환에서 이전 ROUND 컨테이너가 제거된다.
`stop`, `down`, `kill`, `rm`, `logs`, `ps`는 게이트가 닫힌 뒤에도 오버레이를 포함해 잔존 서비스를
관리한다. 운영 명령은 명시적 허용 목록으로 제한하고 일회성 `run`, 시그널 프록시 `attach`,
모델 변환, 이미지 게시, 확장, 데이터 볼륨 삭제와 토폴로지 재정의는 거부한다. `config`는 정확히
`--quiet`만 허용한다. 생명주기 변경과 복원은 환경 변수·체크아웃·호출 UID와 무관하게 미리
마련한 소유자 전용 `/srv/baton/state/production-lifecycle.lock` 아이노드의 `flock`을 공유한다.

롤아웃은 런타임 다크 롤아웃 → 내부 상태·공개 경로 확인 → 참여권 서명자 활성화 순서다.
롤백은 참여권과 런타임 게이트를 함께 닫는 즉시 차단 절차다. 참여권 게이트가 갱신과 공개
JWK를 함께 닫으므로 현재 계약은 기존 참여권을 만료까지 유지하는 점진적 소진을 제공하지
않는다.

### 운영자가 고정한 릴리스 이미지와 비밀값 호환성을 검증한다

운영 Compose는 인접 ROUND 체크아웃을 빌드 컨텍스트로 사용하지 않는다. 웹은
`round-baton-web@sha256:...`, 시그널링은 `round-signaling@sha256:...` 정확한 다이제스트만 받는다.
사전점검은 두 이미지를 가져오고 다음을 확인한다.

- 웹의 `io.round.auth-mode=baton`
- 두 이미지의 `org.opencontainers.image.revision`이 설정한 40자 리비전과 일치
- 두 이미지의 `io.round.release.tag-object`가 유효하고 서로 일치

운영자가 보호된 환경 설정에 기록한 정확한 다이제스트를 이미지 신뢰 기준으로 삼는다. 이 검증은
가져온 이미지가 그 다이제스트와 일치하고 BATON 런타임 계약에 맞는지는 확인하지만, 레지스트리
소유자·게시자 서명·빌드 출처나 공급망 증명을 확인하지 않는다. 운영자는 배포 전에 승인한 ROUND
릴리스에서 두 다이제스트와 리비전을 대조해 환경 설정에 기록한다.

시그널링의 발급자, 수신자, 쿠키 이름, JWK URL, 참여권 최대 수명과 허용 출처는
`BATON_HOST`와 프로토콜 상수에서 Compose가 파생하며 운영 환경 변수로 다시 열지 않는다. 시그널링은
단일 복제본, 읽기 전용 루트, `/tmp` tmpfs와 `no-new-privileges`로 실행한다.

ROUND 런타임의 유일한 비밀값은 외부 coturn과 공유하는 정확히 32바이트의 64자 소문자 16진수
값이다. 환경 변수에는 소유자 전용 호스트 파일 경로만 기록한다. 래퍼는 원문을 환경으로 읽지 않고
파일 기반 Compose 비밀값으로 `/run/secrets/round.turn.shared-secret`에 직접 마운트하며, 두
ROUND 컨테이너를 그 호스트 파일 소유자의 비루트 UID/GID로 실행해 일반 Compose가 무시하는
비밀값 `uid`·`gid`·`mode` 메타데이터에 의존하지 않는다. BATON RSA 개인 키, DB, OAuth와
세션 비밀값은 ROUND에 전달하지 않는다.

### coturn과 상태 확인을 별도 운영 경계로 둔다

coturn은 BATON Compose 밖에서 운영한다. 공인 IP와 NAT, 3478/5349, UDP 릴레이 포트 범위,
TLS 인증서, 할당량과 공유 비밀값 동시 회전은 ROUND 운영 단위가 소유한다.
BATON은 검증된 UDP·TCP·TLS URL 목록과 시그널링용 비밀값 사본만 관리한다.

ROUND 웹 `/healthz`와 시그널링 `/actuator/health/readiness`는 컨테이너 준비 상태다. 공개
BATON `/actuator/health`에는 ROUND를 합치지 않는다. 이 상태 확인은 JWK 가져오기나 실제 TURN
할당·미디어 릴레이를 증명하지 않으므로 공개 스테이징의 새 참여권 점검을 별도로 둔다.

DB 복원은 애플리케이션·웹뿐 아니라 프로젝트에 남은 ROUND 웹·시그널링도 `exited`여야 시작한다.
복원 중 기존 구성원 연결에서 발급된 참여권으로 시그널링이 계속되는 상태를 허용하지 않는다.

## 결과

### 장점

- 브라우저는 하나의 HTTPS 출처와 방 범위 `Secure` 쿠키만 사용한다.
- BATON 로그인 신원·구성원 연결과 ROUND의 메모리 내 미디어 책임이 DB 공유 없이 분리된다.
- 운영자가 고정한 불변 다이제스트, 구성 트리 비밀값과 최소 프록시 자격 증명이 이미지 교체와
  비밀 노출 면적을 줄인다.
- 런타임 장애가 BATON Caddy·애플리케이션 준비 상태를 막지 않고 ROUND 경로에만 국한된다.
- coturn의 공개 네트워크·인증서 생명주기를 BATON DB 롤백과 분리한다.

### 비용과 한계

- 두 릴리스 이미지의 승격, TURN 비밀값 동시 배포와 외부 릴레이 모니터링이 새 운영 책임이다.
- 시그널링은 메모리 내 상태라 한 복제본만 허용하며 수평 확장에는 공유 방·입장 상태가 필요하다.
- 자동 검증은 로컬 사설 CA와 자격 증명 발급까지 다루지만 공인 DNS·ACME, 실제 외부 TURN
  할당·미디어 릴레이와 프로덕션 키 회전을 대신하지 않는다.

## 검증

- 프로덕션 환경 변수·비밀값·이미지 레이블의 실패 시 차단 픽스처
- 프로덕션 Caddy 모듈, 비활성 경로 404와 실제 인증 전 429 런타임 스모크 테스트
- 로컬 사설 CA 세션 → 구성원 연결 → 갱신 → TURN 자격 증명 → WSS E2E
- 복원 중 애플리케이션·웹·ROUND 중지 상태 픽스처
- 공개 스테이징의 UDP·TCP·TLS TURN 할당, 미디어 릴레이와 키 중첩 예행연습

## 관련 문서

- [첫 파일럿 자체 호스팅](../0003_pilot-self-hosted-deployment/adr.md)
- [계정 로그인 신원과 세션](../0017_account-identity-and-session/adr.md)
- [계정 인증과 ROUND 참여권 PRD](../../PRD/0005_account-and-round-authentication/spec.md)
- [BATON GO 교차 서비스 링크 계약](../../../../short-url/docs/PRD/0003_cross-service-link-contract/spec.md)
