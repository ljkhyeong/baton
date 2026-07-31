# ADR-0020: 로그인 생성자와 초기 OWNER의 원자 결속

- 상태: 채택
- 결정일: 2026-07-31

## 배경

ADR-0019는 로그인 account의 활성 구성원 결속을 workspace 권한의 기본 경계로
올렸지만, 기존 `POST /api/v1/workspaces`는 익명 파일럿과의 호환을 위해 계속 공유
접근 키를 반환한다. 따라서 로그인 사용자가 새 팀을 만들어도 어느 초기 roster 구성원이
그 account의 `OWNER`인지 알 수 없고, 생성 직후에는 다시 레거시 fragment를 사용해야
했다.

팀·시즌·구성원을 만든 뒤 별도 invitation으로 OWNER를 결속하면 다음 중간 상태가 생길 수
있다.

- 팀은 생성됐지만 OWNER 결속은 실패해 로그인 사용자가 새 workspace를 열 수 없다.
- 응답 유실 재생이나 다른 account의 멱등 키 재사용이 잘못된 소유권으로 이어진다.
- 로그인 session이 만료된 요청이 익명 레거시 생성으로 조용히 바뀌어 접근 키가 다시
  브라우저에 노출된다.

신규 팀은 생성 순간부터 session 권한으로 열 수 있어야 하고, 기존 레거시 생성 계약은
마이그레이션 기간 동안 그대로 유지해야 한다.

## 결정

### 별도 session 생성 계약

- 로그인 전용 `POST /api/v1/me/workspaces`를 추가한다. 기존
  `POST /api/v1/workspaces`의 익명·파일럿 생성 키·공유 키 응답 계약은 바꾸지 않는다.
- 새 경로는 인증 session, 현재 session의 동적 CSRF token과 기존 workspace 생성 형식의
  고엔트로피 `Idempotency-Key`를 요구한다. `X-Baton-Creation-Key`와
  `X-Baton-Access-Key`는 받지 않는다.
- 요청은 기존 팀·시즌·구성원 입력에 `ownerMemberName`을 추가한다. 앞뒤 공백을
  정규화한 값이 초기 `memberNames`에 정확히 존재해야 한다.
- 성공 응답은 `teamId`와 `seasonId`만 반환한다. `accessKey`는 응답 DTO, 브라우저 URL,
  저장소와 로그에 노출하지 않는다.

### 한 transaction 안의 생성과 결속

- application service의 한 transaction에서 팀, 최초 시즌, 초기 구성원을 저장하고 선택한
  구성원을 현재 account의 `OWNER`로 결속한다.
- 결속은 account와 구성원 행을 잠그고 account당 같은 팀 구성원 하나, 구성원당 account
  하나, 팀별 OWNER 하나와 활동 중 구성원 조건을 다시 확인한다.
- account, OWNER 선택 또는 identity 제약이 유효하지 않으면 팀·시즌·구성원과 결속을
  모두 rollback한다. 최초 정규화 요청·account·OWNER 선택이 같은 재생만 멱등 성공으로
  본다.
- 기존 schema가 요구하는 팀 접근 키 hash와 운영 복구 경계는 유지하되 session 생성에는
  호출자 입력으로 계산할 수 없는 256-bit CSPRNG 내부 키를 사용한다. 원문은 session 생성
  결과에서 반환하지 않는다. 이 내부 호환 때문에 새 Flyway migration은 필요하지 않다.

### 멱등성과 브라우저 경계

- session 생성 fingerprint는 기존 정규화 생성 입력에 별도 mode domain, account UUID와
  정규화한 OWNER 이름을 포함한다. 같은 멱등 키를 다른 mode, account, OWNER 또는 생성
  입력에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`다.
- 같은 session 생성 요청을 재생하면 현재 OWNER 결속을 다시 만들거나 수정하지 않고
  최초의 동일한 팀·시즌 식별자를 반환한다. 이후 OWNER 이름·활동 상태는 현재 권한
  판정의 입력이지 과거 생성 결과를 바꾸는 조건이 아니다. 응답에 공유 키가 없으므로
  이후 레거시 접근 키가 회전돼도 `IDEMPOTENCY_REPLAY_EXPIRED`로 바꾸지 않는다.
- 프런트는 로그인 상태에서 OWNER를 명시적으로 선택해야만 요청한다. 전송 직전에 같은
  account의 최신 session과 CSRF credential을 다시 확인하고, 실패 시 레거시 생성으로
  fallback하지 않는다.
- 복구 journal은 생성 mode, account UUID와 OWNER 이름을 함께 저장한다. account가
  바뀌거나 session이 만료되면 요청을 보내지 않고 같은 account의 재로그인을 요구한다.
  현재 account와 생성 mode가 다른 journal은 보존하되 복구 목록에서는 숨겨 다른 계약의
  멱등 키로 새 요청을 만들지 않게 한다. 성공 뒤에는 접근 키 fragment 없는 workspace
  URL로 이동한다.

## 결과

### 장점

- 새 팀은 생성 직후부터 로그인 account의 OWNER 권한으로 접근한다.
- 팀 생성만 커밋되거나 다른 account·구성원이 소유권을 재생하는 부분 성공을 막는다.
- session 만료가 레거시 접근 키 발급으로 내려가는 silent downgrade를 차단한다.
- 기존 공유 링크와 익명 파일럿 온보딩을 깨지 않고 단계적으로 session 생성으로 전환한다.

### 비용과 한계

- 마이그레이션 기간에는 session 생성과 레거시 생성 두 계약을 함께 테스트하고 운영한다.
- DB에는 레거시 복구와 기존 schema 호환을 위한 접근 키 hash가 남는다. 원문 키를
  session 응답에 노출하지 않는 것과 레거시 키를 완전히 폐기하는 것은 별도 단계다.
- 명시 OWNER 결속은 새 팀 생성에만 적용된다. 기존 팀은 bootstrap 또는 일반 invitation
  계약을 계속 사용한다.
- 계정 생명주기, 세부 역할 권한, 변경 감사와 여러 OIDC 공급자 연결 정책은 해결하지
  않는다.

## 검증

```bash
./gradlew --no-daemon :application:test --tests '*WorkspaceUseCaseTest' \
  --tests '*MemberIdentityServiceTest'
./gradlew --no-daemon :adapter-in-web:test --tests '*WorkspaceSecurityTest'
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon checkApiContract
cd frontend && npm run typecheck && npm run build
cd frontend && npm run e2e -- identity.spec.ts
```

검증은 신규 생성·OWNER 결속의 동시 성공과 rollback, 동일 요청 재생, account·OWNER·mode
멱등 충돌, session 만료·CSRF 누락의 레거시 fallback 금지, 응답·URL·저장소의 접근 키
비노출을 포함한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [제품 로드맵](../../PRD/0003_product-roadmap/spec.md)
- [공급자 중립 사용자 신원 결속](../0015_provider-neutral-user-identity-binding/adr.md)
- [Google OIDC session과 owner bootstrap](../0016_google-oidc-session-owner-bootstrap/adr.md)
- [세션 구성원 기반 workspace 권한 전환](../0019_session-based-workspace-authorization/adr.md)
