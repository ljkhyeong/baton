# ADR-0019: 세션 구성원 기반 워크스페이스 권한과 레거시 접근 키 전환

- 상태: 채택
- 결정일: 2026-07-31

## 배경

파일럿 워크스페이스는 팀 전체가 공유하는 `X-Baton-Access-Key`로 조회와 변경을
허용한다. 브라우저는 확인한 원문 키를 origin 전체의 `localStorage`에 보관하고 공유
URL fragment로 전달한다. 이 방식은 account와 구성원 결속이 없던 초기 파일럿에는
단순했지만, 다음 문제가 있다.

- 같은 origin의 모든 JavaScript가 저장된 모든 팀 접근 키를 읽을 수 있다.
- ADR-0018의 ROUND web을 BATON same-origin으로 제공하면 ROUND bundle의 XSS나 공급망
  침해 영향이 BATON의 장기 workspace bearer key까지 확장된다.
- 요청 주체가 어느 account와 구성원인지 알 수 없어 사용자별 권한과 감사 기반으로
  발전할 수 없다.
- 로그아웃 뒤에도 접근 키와 account와 무관한 workspace cache가 남아 공유 브라우저의
  다음 사용자가 이전 projection을 볼 수 있다.

ADR-0015~0017은 공급자 중립 account, Google OIDC session, 활성 roster 구성원 결속과
OWNER 발급 invitation을 마련했다. 기존 링크 사용자를 즉시 잠그지 않으면서 이 신원을
workspace 권한의 기본 경계로 올리고, 브라우저의 장기 접근 키 저장을 제거할 전환 계약이
필요하다.

## 결정

### 상호 배타적인 두 인증 방식

- 일반 workspace 조회·변경과 역할 자료 `open-link` application 경계는 문자열 키 대신
  `SessionAccount` 또는 `LegacyAccessKey` 중 하나인 명시적 권한 값으로 받는다.
- `X-Baton-Access-Key`가 비어 있지 않으면 요청은 명시적인 레거시 방식이다. 키가
  틀렸을 때 로그인 session으로 조용히 재시도하지 않는다.
- 레거시 header가 없고 검증된 `BatonAccountPrincipal`이 있으면 session 방식이다.
  둘 다 없으면 기존과 같은 `403 WORKSPACE_ACCESS_DENIED`로 거절한다.
- session 방식은 path의 `teamId`에 결속된 활동 중 `Member`가 있어야 한다. 초기
  전환에서는 `OWNER`와 `MEMBER` 모두 기존 workspace 읽기·쓰기 범위를 이어 받는다.
  역할별 세부 권한과 감사 정책을 이 인증 전환에 섞지 않고 별도 계약으로 확정한다.
- session 결정 생성·수정의 `authorMemberId`와 역할 바통 전달·수락·취소의
  `confirmedByMemberId`는 현재 account에 결속된 활동 중 구성원과 같아야 한다.
  레거시 방식의 같은 필드는 공유 키 보유자의 선언으로 유지하며 로그인 감사 증거로
  승격하지 않는다.
- 접근 키 회전과 운영자 복구는 기존 레거시 복구 계약으로 유지한다. session workspace
  화면은 이를 기본 기능으로 노출하지 않는다.

### CSRF와 동시 상태 변경

- session이 있는 모든 `POST`, `PUT`, `PATCH`, `DELETE` 요청은 access key header를
  함께 보냈는지와 관계없이 session의 동적 CSRF token을 요구한다.
- 로그인 session이 없는 사용자가 비어 있지 않은 레거시 header로 호출하는
  팀·시즌 경로만 마이그레이션 기간 동안 CSRF 예외다. 브라우저가 공개하지 않는
  workspace 생성·내부 bootstrap·운영자 복구의 기존 정확한 예외는 별도로 유지한다.
- session mutation은 구성원 결속을 조회한 뒤 활동 상태가 바뀌는 틈을 허용하지 않도록
  해당 `Member`를 공유 잠금으로 읽고 transaction 종료까지 유지한다. 구성원 활동 종료는
  이 잠금과 같은 순서로 직렬화된다.
- membership 실패, 다른 팀 결속과 활동 종료는 팀이나 결속 존재 여부를 더 드러내지 않고
  `WORKSPACE_ACCESS_DENIED`로 수렴한다.

### 브라우저 전환

- 로그인 account가 해당 팀의 활동 중 구성원으로 결속되어 있으면 access key 없이
  session 방식으로 workspace를 연다. 변경 요청과 `open-link`는 요청 직전의 동적 CSRF
  credential을 사용한다.
- React Query workspace cache 식별자에는 account ID, team ID와 season ID만 넣고 원문
  access key를 넣지 않는다. 로그아웃이나 account 교체 때 workspace query·mutation
  cache와 같은 탭의 ROUND entry context를 제거한다.
- 최근 workspace의 팀·시즌 이름과 locator도
  `baton-recent-workspaces:v2:{accountId}`로 나눠 session workspace만 기록한다.
  로그아웃이나 account 교체 때 이전 account의 목록을 제거하고, 범위가 없는 기존
  `baton-recent-workspaces:v1` 목록은 표시하지 않고 폐기한다.
- `baton-access-key:*` 항목은 기존 설치를 전환할 때 선택적으로 읽은 뒤 삭제한다.
  다른 workspace 생성·콘텐츠 생성·시즌 전환 멱등 journal을 보존하기 위해
  `localStorage.clear()`는 사용하지 않는다.
- 결속되지 않은 기존 사용자는 URL fragment 또는 전환 시 한 번 소비한 기존 키를
  메모리에서만 레거시 방식으로 사용할 수 있다. 이를 `localStorage`, React Query key,
  session storage나 ROUND entry context에 다시 저장하지 않는다.
- session 방식의 공유 주소는 credential 없는 workspace locator다. 실제 권한 전달은
  OWNER의 구성원 invitation으로 수행한다. 레거시 화면만 마이그레이션 기간 동안
  fragment 공유와 키 회전을 제공한다.
- 기존 `POST /api/v1/workspaces`는 익명 파일럿 호환을 위해 access key를 반환한다.
  로그인 신규 생성은 ADR-0020의 별도 `POST /api/v1/me/workspaces`에서 명시 OWNER
  결속과 함께 처리하고 credential 없는 locator로 이동한다.

## 결과

### 장점

- same-origin ROUND JavaScript가 장기 저장된 workspace bearer key를 한 번에 탈취할
  표면을 제거한다.
- workspace 권한이 공급자와 무관한 account와 활동 중 roster 구성원에 결속된다.
- session과 레거시 요청의 실패·CSRF 의미가 섞이지 않아 silent downgrade를 막는다.
- 로그아웃과 account 교체 뒤 이전 workspace projection과 ROUND 진입 문맥이 남지 않는다.
- 기존 공유 링크는 제한된 과도기 경로로 계속 사용할 수 있다.

### 비용과 한계

- 레거시 사용자와 session 사용자가 공존하는 동안 두 인증 방식과 서로 다른 CSRF 규칙을
  테스트하고 운영해야 한다.
- fragment는 origin-wide 영속 저장보다 범위가 작지만 여전히 복사·주소창·브라우저
  기록에 노출될 수 있는 bearer secret이다. 레거시 호환을 영구 권한 방식으로 보지 않는다.
- 활성 `MEMBER`와 `OWNER`의 workspace 변경 범위는 아직 같다. 행위자별 역할 바통,
  결정·자료 변경 권한과 감사 기록은 후속 권한 행렬이 필요하다.
- same-origin ROUND bundle은 HttpOnly session cookie를 읽을 수 없어도 BATON API를
  same-origin으로 호출할 수 있으므로 계속 BATON browser trust boundary 안에 있다.
  완전한 프런트 격리가 필요하면 별도 UI origin을 사용해야 한다.
- session 생성 팀에도 레거시 schema·운영 복구 호환을 위한 접근 키 hash는 남는다.
  ADR-0020은 호출자가 계산할 수 없는 내부 CSPRNG 값으로 이를 만들고 원문을 노출하지
  않지만, 레거시 키의 완전 폐기는 별도 전환이 필요하다.

## 검증

```bash
./gradlew --no-daemon :application:test --tests '*Workspace*' --tests '*MemberIdentity*'
./gradlew --no-daemon :adapter-in-web:test --tests '*WorkspaceSecurityTest'
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon checkApiContract
cd frontend && npm run build && npm run e2e -- workspace.spec.ts identity.spec.ts
bash ops/tests/pilot-readiness-test.sh
```

검증은 session 조회·변경, CSRF 누락 거절, 명시적 잘못된 레거시 key의 session fallback
금지, 익명 레거시 호환, 활동 종료와 mutation 경합, access key 영속 저장 제거,
account 교체 cache 격리와 `open-link`의 access key 비노출을 포함한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [제품 로드맵](../../PRD/0003_product-roadmap/spec.md)
- [공급자 중립 사용자 신원 결속](../0015_provider-neutral-user-identity-binding/adr.md)
- [Google OIDC session과 owner bootstrap](../0016_google-oidc-session-owner-bootstrap/adr.md)
- [OWNER 발급 일반 구성원 초대](../0017_owner-issued-member-invitations/adr.md)
- [신원 기반 ROUND 참여권](../0018_round-participation-grants/adr.md)
- [로그인 생성자와 초기 OWNER의 원자 결속](../0020_atomic-owned-workspace-creation/adr.md)
