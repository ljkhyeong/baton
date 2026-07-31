# ROUND 공인 HTTPS 실입장 검증 runbook

## 목적과 승인 기준

이 문서는 실제 Google OIDC 계정 두 개, BATON GO, BATON, 별도 ROUND signaling과 외부
TURN을 공인 HTTPS/WSS 환경에서 연결해 다음 경계를 한 번에 검증한다.

1. 역할 자료의 `GO 302 → ROUND prejoin` 이동
2. 명시적 입장의 `participation grant → TURN credential → WSS` 순서
3. 같은 탭의 역할 자료 문맥이 없는 직접 초대 입장
4. 5분 TURN credential 수명 한 주기의 선제 갱신과 relay 유지
5. fresh grant를 선행하는 bounded signaling 재연결
6. RS256 dual-key rotation 중 기존 연결 유지와 새 연결 성공
7. URL, 브라우저 자료, 운영 로그와 검증 산출물의 비밀 비노출

모든 필수 항목이 `PASS`여야 배포를 승인한다. 한 항목이라도 `FAIL` 또는 `NOT RUN`이면
승인하지 않는다. 이 검증은 ROUND `host` 권한이나 즉시 membership revocation을 검증하거나
그 기능의 구현을 승인하지 않는다.

기준 문서는 [README](../../README.md), [ADR-0018](../ADR/0018_round-participation-grants/adr.md),
ROUND 저장소의 `docs/architecture.md`, `docs/deployment.md`,
`docs/pilot-checklist.md`, `docs/adr/0001-round-independent-service.md`다.

## 증거 취급 원칙

검증을 시작하기 전에 두 운영자와 두 참여자에게 아래 원칙을 공유한다.

- 계정은 `OWNER-A`, `MEMBER-B` 같은 당일 별칭으로만 기록한다. Google 이메일, OIDC
  subject와 BATON account UUID는 기록하지 않는다.
- 초대 token, owner bootstrap token, session/JWT, CSRF, 멱등 키, BATON access key,
  TURN username/credential, 전체 short URL과 room ID를 문서·티켓·메신저 공개 채널에
  남기지 않는다.
- URL query·fragment, 로그, 스크린샷, HAR, Playwright trace·video, 화면 녹화와 장기
  clipboard에 위 값을 남기지 않는다. DevTools의 **Preserve log**는 끄고 Network 기록을
  내보내지 않는다.
- 브라우저에서 cookie를 확인할 때는 value 열을 가린 metadata 보기만 사용한다. raw
  `Cookie`, `Set-Cookie`, Authorization header, TURN 응답 body와 WebSocket frame을 열거나
  복사하지 않는다.
- `chrome://webrtc-internals` 또는 동등한 RTC getStats 화면은 실시간으로만 확인한다.
  dump를 저장하지 않고 IP, SDP, ICE candidate 문자열, room ID가 보이는 화면을 캡처하지
  않는다.
- 안전 채널로 받은 초대 token이나 직접 초대 주소를 한 번 붙여 넣었다면 곧바로 clipboard를
  다른 무해한 문자열로 덮고, 채널의 보존 정책에 따라 원문을 제거한다.
- 결과에는 `PASS/FAIL`, UTC 시각, 브라우저/OS 버전, 네트워크 종류, HTTP 상태 범주,
  보호 요청 수·순서와 `candidate type=relay`만 남긴다. 원문 식별자와 credential은 남기지
  않는다.

이 원칙을 지키기 위해 증거를 더 적게 남겨야 하면 더 적게 남긴다. 원문을 수집한 뒤
마스킹하는 방식은 허용하지 않는다.

## 역할과 준비물

| 역할 | 책임 |
| --- | --- |
| 배포 운영자 | readiness, 배포 상태, 키 교체, rollback 판단 |
| 검증 기록자 | 값이 없는 최소 증거와 UTC 시각만 기록 |
| `OWNER-A` | 첫 번째 실제 Google 계정과 OWNER Chrome 프로필 |
| `MEMBER-B` | 두 번째 실제 Google 계정과 MEMBER Chrome 프로필 |

다음 조건을 모두 준비한다.

- `OWNER-A`와 `MEMBER-B`는 서로 다른 실제 Chrome 프로필과 서로 다른 Google 계정을
  사용한다. 가능하면 서로 다른 기기와 OS를 쓴다.
- 두 브라우저는 서로 다른 외부 네트워크를 쓴다. 예: 유선/가정 Wi-Fi와 별도 이동통신
  hotspot. SSID와 공인 IP는 기록하지 않는다.
- BATON, GO, ROUND web/signaling은 공인 인증서가 있는 같은 BATON HTTPS origin에서
  노출되고, TURN은 그 호스트/NAT 밖의 외부 서비스로 운영한다.
- ROUND web과 signaling 이미지는 검토한 digest로 고정하고 signaling replica는 하나만
  둔다. BATON mode에서 standalone `/signal`, `/api/turn-credentials`를 공개하지 않는다.
- 검증용 팀에는 같은 canonical room을 가리키는 활성 ROUND 역할 자료가 정확히 하나만
  있어야 한다. 그래야 context-free 직접 초대가 모호하지 않다.
- 실제 카메라·마이크 사용 동의를 받는다. 민감한 배경이나 대화는 사용하지 않는다.
- 키 회전 시작 전 이전 private key와 dual-key JWK Set을 승인된 secret/config manager에
  rollback 가능하게 보존한다. private JWK는 JWK Set에 넣지 않는다.

## 1. 운영 readiness와 기동

운영 env의 내용을 터미널에 출력하지 않는다. 아래 helper는 값 대신 항목별 상태와 종료
코드만 제공해야 한다.

```bash
./ops/preflight-production.sh /absolute/path/to/.env.production
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh up -d --no-build
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh ps
./ops/verify-round-live-readiness.sh /absolute/path/to/.env.production
```

`verify-round-live-readiness.sh`를 실행하지 못하면 이 단계는 `NOT RUN`, 종료 코드가 0이
아니면 `FAIL`이고 실입장 승인을 진행하지 않는다. readiness는 적어도 다음을 값 없이
확인해야 한다.

- Google OIDC 활성화, 고정 HTTPS callback과 공개 issuer/origin 일치
- GO와 ROUND grant 활성화, 같은 공개 HTTPS origin, digest 고정 이미지
- active signer와 private/public key 일치, 2048-bit 이상 RSA, public-only JWK Set
- 외부 TURN URI, 별도 shared secret, 300초 TURN credential TTL
- host-only session cookie 설정과 exact 허용 origin; participation cookie는 4단계에서 확인
- 공인 DNS·TLS, 외부 TURN listener와 필요한 방화벽/relay port의 별도 운영 확인

실제 Compose 조작은 항상 `ops/production-compose.sh`를 사용한다. raw `docker compose`
명령, 특히 rendered secret을 노출할 수 있는 `docker compose config`와
`production-compose.sh config`는 실행하지 않는다. `production-compose.sh logs`로 raw
로그를 터미널에 출력하지 않는다.

readiness 실패, mutable image tag, 인증서 경고, TURN shared secret 불일치, 다중 signaling
replica 중 하나라도 발견되면 중단한다.

## 2. 실제 OIDC와 구성원 결속

1. `OWNER-A` 프로필에서 BATON HTTPS 홈을 열고 Google OIDC 로그인을 완료한다. 주소 표시줄에
   OIDC `code`, `state` 또는 token이 남지 않고 BATON의 clean callback 이후 화면으로
   돌아오는지만 확인한다.
2. 기존 팀의 첫 OWNER라면 승인된 내부 운영 경계에서 owner bootstrap invitation을 한 번
   발급한다. 외부 Caddy 경로, 즉석 `curl`, shell history와 command argument에 bootstrap
   key/token을 넣지 않는다. token-safe 내부 helper가 없는 환경에서는 임의 명령을 만들지
   말고 이 단계를 중단한다.
3. bootstrap token은 지정된 일회성 안전 채널로 `OWNER-A`에게 전달한다. `OWNER-A`는 홈에서
   preview 후 명시적으로 수락하고, 작업 공간의 `계정·초대` 화면이 OWNER에게만 보이는지
   확인한다.
4. `OWNER-A`는 해당 화면에서 `MEMBER-B`의 활동 중 roster 구성원에 대한 일반 초대를
   발급한다. 원문 token은 현재 화면에서만 다루고 같은 안전 채널로 전달한다.
5. `MEMBER-B`는 별도 Chrome 프로필과 별도 Google 계정으로 로그인한 뒤 preview와 수락을
   완료한다. 두 브라우저 모두 clean workspace URL을 사용하고 access key를 쓰지 않는다.

기록은 다음처럼 값 없이 남긴다.

| 항목 | 최소 증거 |
| --- | --- |
| Google OIDC 두 계정 | `PASS`, UTC, Chrome/OS 두 조합 |
| owner bootstrap | `PASS`, preview 후 수락, token 저장 없음 |
| 일반 구성원 초대 | `PASS`, preview 후 수락, token 저장 없음 |
| 권한 분리 | `OWNER-A=OWNER`, `MEMBER-B=MEMBER` |

## 3. 역할 자료에서 GO 302와 prejoin 확인

`OWNER-A` 브라우저에서 DevTools Network를 열되 Preserve log, screenshot, HAR와 record
export를 모두 끈다. request/response header와 body는 열지 않는다.

1. Network 목록을 지우고 BATON 작업 공간의 ROUND 역할 자료를 클릭한다.
2. GO 요청 한 건이 `302`이고 최종 navigation이 같은 BATON origin의 `/room/<redacted>`
   형태인지 화면에서만 확인한다. 최종 주소에는 query와 fragment가 없어야 한다.
3. ROUND 초대/입장 준비를 거쳐 prejoin에 머문다. 명시적 **입장**은 아직 누르지 않는다.
4. 초대 화면부터 prejoin까지 아래 보호 요청이 모두 `0`건인지 목록의 요청 종류와 개수만
   확인한다.
   - participation-grant POST
   - room-scoped TURN credential POST
   - room-scoped WebSocket upgrade
5. GO target과 최종 navigation의 query/fragment에 BATON access key, Authorization,
   session/JWT와 invitation token이 없음을 화면에서 확인한다. Referer와 header 비노출은
   raw header를 열지 않고 뒤의 로그/edge gate에서 확인한다.

통과 증거는 `GO=302`, `target=same-origin /room/<redacted>`,
`prejoin protected requests=0` 세 항목이다. Network 목록은 확인 직후 지운다.

## 4. 명시적 입장 순서와 cookie 속성

`OWNER-A`에서 Network 목록을 다시 지우고 **입장**을 누른다. 동적 CSRF를 읽는 BATON
session GET은 grant보다 앞설 수 있다. 그 준비 요청을 제외한 세 **보호 전송 요청의 상대
순서**가 다음과 정확히 일치해야 한다.

1. 역할 자료 소유 participation-grant POST: `204`
2. room-scoped TURN credential POST: `200`
3. room-scoped WebSocket upgrade: `101`

grant가 실패했는데 TURN/WSS로 진행하거나, TURN이 실패했는데 WSS로 진행하면 `FAIL`이다.
standalone endpoint가 한 번이라도 호출되어도 `FAIL`이다.

cookie value를 가린 metadata 보기에서 `__Secure-round_access`의 속성만 확인한다.

- `Secure=true`
- `HttpOnly=true`
- `SameSite=Strict`
- `Path=/round/rooms/<현재 방>`
- `Domain` 없음, 즉 host-only
- `Max-Age`가 `1..300`초

JWT 문자열, `kid`, claim과 cookie value는 확인하거나 기록하지 않는다. BATON session과
ROUND participation cookie가 브라우저에 함께 있어도 ROUND upstream에는 participation
cookie 하나만 전달되어야 하며, 이는 뒤의 로그/edge 비노출 gate에서 값 없이 확인한다.

## 5. context-free 직접 초대와 두 참여자 연결

1. `/room/<redacted>` 직접 초대 주소를 `MEMBER-B`에게 일회성 안전 채널로 전달하고 붙여 넣은
   clipboard를 즉시 덮는다.
2. `MEMBER-B`는 역할 자료를 먼저 열지 않은 새 탭에서 주소를 연다. 해당 방의 same-tab
   entry context가 없는 상태여야 한다.
3. prejoin까지 보호 요청이 `0`건인지 다시 확인한다.
4. 명시적 입장 뒤 room fallback participation-grant POST가 `204`이고 이어서
   `grant → TURN 200 → WSS 101`인지 확인한다. endpoint 종류만 `room fallback`으로 기록하고
   전체 path나 room ID는 기록하지 않는다.
5. 두 브라우저에 참가자 두 명, 양방향 미디어와 DataChannel 채팅이 보이는지 확인한다.
   음소거·카메라 토글이 상대 화면에 반영되는지도 확인한다.

resource context가 없는 직접 초대가 resource-owned endpoint를 호출하거나, 손상된 문맥을
room fallback으로 강등하면 `FAIL`이다.

## 6. 외부 TURN relay 증명

두 브라우저가 서로 다른 외부 네트워크에 있는 상태에서 RTC getStats의 selected candidate
pair만 실시간 확인한다.

- selected/nominated candidate pair의 state가 `succeeded`
- 선택된 pair가 참조하는 local candidate의 `candidateType=relay`
- `bytesSent`와 `bytesReceived`가 두 번의 관찰 사이에 증가
- 미디어와 DataChannel 채팅이 계속 양방향으로 동작

IP/address, port, protocol credential, candidate 문자열, SDP와 전체 stats dump는 기록하지
않는다. 각 브라우저의 결과는 `OWNER-A relay PASS`, `MEMBER-B relay PASS`와 관찰 UTC만
남긴다. `host` 또는 `srflx`만 선택되면 외부 TURN 검증은 실패다.

## 7. 5분 TURN 수명 주기 갱신

운영 overlay의 TURN credential TTL은 300초다. 현재 브라우저는 잔여 수명의 약 20%를
안전 여유로 두므로 정상적으로는 발급 후 약 4분경 선제 갱신한다. 최소 5분 이상 같은 통화를
유지해 한 주기를 끝까지 관찰한다.

1. 두 브라우저의 최초 TURN 성공 UTC를 각각 기록한다.
2. 이후 refresh에서 fresh participation-grant POST가 먼저 성공하고, 그 다음 새 TURN
   credential POST가 성공하는지 요청 종류·순서만 확인한다.
3. TURN 응답 body의 username, credential, URL과 만료 epoch는 열지 않는다.
4. 갱신 뒤 ICE configuration 교체/재협상이 완료되고 RTC getStats의 selected pair가 계속
   `relay`이며 bytes가 증가하는지 확인한다.
5. TURN refresh 자체가 불필요한 WSS 교체를 만들지 않고 미디어·채팅이 끊기지 않는지
   확인한다.

`TURN → grant` 역순, grant 없는 TURN 재시도, 5분이 지나도 갱신 없음, relay 이탈과 통화
중단은 모두 `FAIL`이다.

## 8. bounded signaling 재연결

먼저 `MEMBER-B`의 네트워크를 짧게 끊었다 복구해 정상 회복을 확인한다. replacement WSS
직전에 fresh grant가 성공해야 하며, 기존 local media가 유지되고 상대 peer가 다시
연결되어야 한다.

자동 재연결의 bounded 종료도 승인된 점검 시간에 별도로 확인한다. 현재 기본값은 최대 6회,
대기 간격은 약 `0.5s → 1s → 2s → 4s → 4s → 4s`다. 릴리스에서 이 값이 바뀌었다면
배포한 digest의 설정을 기준으로 예상 상한을 먼저 기록한다.

두 참여자에게 영향을 알린 뒤 signaling만 일시 중지한다.

```bash
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh stop round-signaling
```

브라우저가 예상 상한을 넘겨 무한 요청하지 않고 terminal reconnect 안내에 도달하는지
요청 **개수**만 확인한다. 종료 뒤 signaling을 복구한다.

```bash
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh up -d --no-build round-signaling
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh ps round-signaling
```

사용자의 명시적 재연결로 `fresh grant → WSS 101`을 확인하고 두 참여자의 relay 미디어와
채팅을 다시 확인한다. signaling 중지는 휘발성 room state와 socket을 제거하므로 실제 업무
회의가 없는 검증 창에서만 수행한다. 자동 시도 상한 초과, grant 없는 replacement WSS,
복구 뒤 page refresh 없이는 재입장 불가, leave 뒤 timer 지속은 `FAIL`이다.

## 9. dual-key rotation 리허설

### 9.1 사전 조건과 시간 계산

두 브라우저가 relay로 연결된 상태에서 시작한다. 키는 실제 값 대신 `retiring`과 `new`로만
부른다. 이전 공개키 제거 가능 시각은 다음보다 빨라서는 안 된다.

```text
remove-not-before = 마지막 retiring-key grant 가능 UTC
                  + 최대 grant 수명
                  + 가장 긴 JWK/key-ring cache
                  + 허용 clock skew
                  + 운영 safety margin
```

현재 기준선은 grant 최대 300초, BATON public/key-ring cache 60초, ROUND 허용 skew 60초다.
다른 runtime cache가 더 길면 그 값을 사용한다. 따라서 기본값에서도 최소 420초에 운영
safety margin을 더하고, 실제 설정이 더 크면 그 시간을 우선한다. 기록에는 계산 결과 UTC만
남기고 `kid`나 key material은 남기지 않는다.

### 9.2 새 public key 선공개

1. 승인된 secret/config manager에서 새 2048-bit 이상 RSA key pair를 준비한다.
2. active signer는 retiring key로 유지한 채 JWK Set에 새 **public** key를 추가한다.
   두 public key만 있고 private JWK, 중복 `kid`, 비-RS256 key가 없는지 readiness로 확인한다.
3. config를 반영하고 app만 재조립한다.

```bash
./ops/preflight-production.sh /absolute/path/to/.env.production
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh up -d --no-build app
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh ps app round-signaling
./ops/verify-round-live-readiness.sh --require-key-overlap \
  /absolute/path/to/.env.production
```

4. 적어도 가장 긴 JWK cache와 edge 전파 시간이 지난 뒤 readiness를 다시 통과한다.
5. app 재기동 중에도 이미 성립한 ROUND WebSocket의 미디어·채팅이 유지되는지 확인한다.

### 9.3 active signer 전환

1. env의 active signer와 private key file을 new key로 원자적으로 바꾼다. JWK Set은
   retiring+new 두 public key를 계속 제공한다.
2. readiness가 active private/public 일치를 확인한 뒤 app만 재조립한다.

```bash
./ops/verify-round-live-readiness.sh --require-key-overlap \
  /absolute/path/to/.env.production
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh up -d --no-build app
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh ps app round-signaling
./ops/verify-round-live-readiness.sh --require-key-overlap \
  /absolute/path/to/.env.production
```

3. 전환 완료 UTC를 `마지막 retiring-key grant 가능 UTC`로 보수적으로 기록한다.
4. `OWNER-A`의 기존 연결과 media/chat이 유지되는지 확인한다.
5. `MEMBER-B`에서 TURN 한 주기 갱신 또는 짧은 네트워크 단절을 유도한다. fresh grant 뒤
   TURN 또는 replacement WSS가 성공하고 relay 연결이 회복되는지 확인한다.
6. 계산한 overlap 동안 두 public key를 유지한다. overlap 중 최소 한 번
   `fresh grant → TURN`, 한 번 `fresh grant → replacement WSS`와 기존 연결 유지를 확인한다.

### 9.4 retiring public key 제거

1. `remove-not-before`가 지났고 두 브라우저 검증이 모두 `PASS`인지 확인한다.
2. JWK Set에서 retiring **public** key만 제거한다. active new private key는 그대로 둔다.
3. readiness를 통과한 뒤 app만 재조립하고 가장 긴 cache 전파 시간을 기다린다.

```bash
./ops/verify-round-live-readiness.sh /absolute/path/to/.env.production
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh up -d --no-build app
BATON_PRODUCTION_ENV_FILE=/absolute/path/to/.env.production \
  ./ops/production-compose.sh ps app round-signaling
./ops/verify-round-live-readiness.sh /absolute/path/to/.env.production
```

4. 두 브라우저에서 다시 fresh grant, TURN refresh와 한 번의 replacement WSS를 확인한다.
5. 양쪽 selected candidate가 계속 `relay`이고 media/chat이 유지되는지 확인한다.

### 9.5 즉시 rollback 조건과 순서

다음 중 하나면 이전 public key를 제거하지 않거나 즉시 rollback한다.

- readiness 또는 app 기동 실패
- grant `503`, fresh grant 뒤 TURN/WSS 인증 실패
- 기존 WebSocket의 예상하지 않은 종료, bounded reconnect 실패
- relay 이탈, 양방향 media/chat 손실
- JWK Set에 private material 노출 또는 로그·URL·증거의 credential 노출
- 배포 digest, issuer, audience, origin 또는 active private/public 불일치

retiring public key를 아직 제거하지 않았다면 dual-key JWK Set을 유지한 채 active signer와
private key를 retiring key로 되돌리고 helper로 app만 재조립한다. 이미 제거했다면 먼저
dual-key public JWK Set을 복원하고 cache 전파/readiness를 확인한 뒤 signer rollback을
수행한다. new key가 유출된 사건이라면 그 key로 rollback하지 않고 보안 사고 절차를 따른다.
rollback 중에도 raw key/JWK/env를 터미널에 출력하지 않는다.

## 10. 로그와 비노출 gate

검증 시간 범위의 Caddy, BATON, ROUND signaling과 TURN 로그는 승인된 보안 조회 화면이나
값을 stdout에 내지 않는 전용 scanner에서만 검사한다. raw container log, URI, header와
payload를 터미널에 출력하거나 파일로 export하지 않는다. 실제 secret을 외부 로그 검색창의
검색어로 전송하지 않는다.

다음 원문 필드/값이 저장되지 않아야 한다.

- request/response headers, `Cookie`, `Set-Cookie`, Authorization, Referer
- 전체 URI, query, fragment, GO short URL과 room ID
- OIDC code/state, session ID/JWT, CSRF, invitation/bootstrap token, 멱등 키
- JWT `sub`, `study_id`, `room_id`, `jti`와 private JWK/key material
- TURN username/credential/shared secret, SDP, ICE candidate와 raw IP address

허용 증거는 시간 구간별 route class, HTTP status/count, WSS 연결/종료 count, TURN 발급/제한
count, 짧은 비가역 client hash와 PASS/FAIL뿐이다. request ID도 운영 조회 중 상관관계에만
사용하고 runbook 결과에 장기 보관하지 않는다.

전용 live log scanner가 없다면 raw 로그를 출력해 대신 확인하지 않는다. 그 경우 이 gate는
`NOT RUN`이며 배포를 승인하지 않는다. 저장소의 synthetic runtime smoke가 통과했더라도
그 결과는 실제 공인 환경의 이 gate를 대신하지 않는다.

## 11. 결과 기록 양식

아래 표만 복사해 사용한다. `<redacted>` 자리에 원문 식별자를 넣지 않는다.

| Gate | UTC | 환경/브라우저 최소 정보 | 최소 관찰 | 결과 |
| --- | --- | --- | --- | --- |
| Readiness |  | 운영 host 별칭, image digest 확인 여부 | helper exit 0 | PASS/FAIL |
| OIDC-A/B |  | Chrome/OS 두 조합 | callback clean | PASS/FAIL |
| OWNER/MEMBER |  | 계정 별칭만 | preview/accept | PASS/FAIL |
| GO/prejoin |  | OWNER-A | `302`, 보호 요청 `0` | PASS/FAIL |
| Initial order |  | OWNER-A | `grant 204 → TURN 200 → WSS 101` | PASS/FAIL |
| Cookie metadata |  | OWNER-A | Secure/HttpOnly/Strict/path/host-only/TTL | PASS/FAIL |
| Direct invite |  | MEMBER-B | room fallback, 보호 요청 `0` 전 입장 | PASS/FAIL |
| TURN relay |  | 네트워크 종류 두 개 | 양쪽 `candidate type=relay`, bytes 증가 | PASS/FAIL |
| TURN refresh |  | 5분 이상 | fresh grant 선행, relay 유지 | PASS/FAIL |
| WSS reconnect |  | 재연결 창 | fresh grant 선행, 상한 내 종료/회복 | PASS/FAIL |
| Dual-key add |  | 운영자 | dual-public readiness, 기존 연결 유지 | PASS/FAIL |
| Signer switch |  | 운영자 | fresh TURN/WSS 성공 | PASS/FAIL |
| Retiring removal |  | remove-not-before UTC | cache 후 fresh TURN/WSS 성공 | PASS/FAIL |
| Log non-disclosure |  | 검증 UTC 범위 | 금지 필드/값 `0` | PASS/FAIL |

최종 승인 기록은 `전체 PASS`, 검증 UTC 범위, 참여자 별칭, 브라우저/OS, 네트워크 종류와
rollback 수행 여부만 포함한다.

## 12. 종료와 정리

1. 두 브라우저에서 정상 **나가기**를 눌러 reconnect·ICE·TURN timer가 더 이상 동작하지
   않는지 요청 개수로 확인한다.
2. Network 목록과 RTC 진단 화면을 지우고 닫는다. HAR, trace, video와 screenshot이 생성되지
   않았는지 확인한다.
3. clipboard와 일회성 안전 채널의 invitation/bootstrap token, 직접 초대 주소를 정책에
   따라 제거한다.
4. 두 Chrome 프로필에서 BATON logout을 수행한다. 브라우저 비밀번호/동기화 저장 여부를
   확인한다.
5. key rotation rollback material은 승인된 보존 기한까지 secret manager에만 유지한다.
6. `FAIL` 또는 `NOT RUN` 항목은 원문 비밀 없이 현상·UTC·영향 경계만 이슈에 남기고 배포
   승인을 보류한다.
