# ADR-0017: OWNER가 발급하는 일반 구성원 초대

- 상태: 채택
- 결정일: 2026-07-30

## 배경

ADR-0016은 Google OIDC session과 기존 팀의 첫 `OWNER` bootstrap을 만들었지만, 나머지
roster 구성원을 검증된 사용자 계정에 연결하는 제품 흐름은 남겨 두었다. 팀 공유
`X-Baton-Access-Key`로 초대를 발급하거나 token만으로 구성원을 선택하게 하면 공유 링크를
아는 사람이 다른 구성원의 신원을 결속할 수 있다.

프런트도 응답 유실을 복구하면서 원문 invitation token과 CSRF를 URL이나 영속 저장소에
남기지 않아야 한다. 수락과 폐기가 동시에 실행될 때는 하나의 terminal 상태만 커밋되어야
한다.

## 결정

### 별도 일반 구성원 invitation aggregate

- bootstrap invitation을 확장하지 않고 Flyway V16의 `member_invitations`를 별도
  aggregate로 둔다. bootstrap은 최초 `OWNER`, 일반 invitation은 `MEMBER` 결속만 소유한다.
- 현재 로그인 계정이 해당 팀의 `OWNER`에 결속되어 있고 그 owner 구성원이 활동 중일 때만
  발급·열린 목록·폐기를 허용한다. 기존 workspace 공유 키는 이 권한의 증거가 아니다.
- 대상은 같은 팀의 활동 중이며 account 결속과 열린 일반 invitation이 없는 구성원이다.
- production TTL은 정확히 24시간이다. application은 양수이며 최대 7일인 설정만 허용해
  비정상 장기 capability를 막는다.

### token과 멱등 재생

- 발급은 소문자 canonical UUID `Idempotency-Key`를 필수로 한다.
- 원문 token은 `mi1_` prefix와 256-bit HMAC-SHA-256 결과의 base64url body로 만든다.
  bootstrap과 일반 invitation은 HMAC domain을 분리하고 동일한 운영 secret을 사용한다.
- DB에는 token과 멱등 UUID의 SHA-256 hash만 저장한다. 원문 token은 최초 `201`과 같은
  요청의 `200` 재생에서만 반환한다.
- 같은 멱등 키와 같은 팀·대상·발급 계정은 invitation이 소비·폐기·만료된 뒤에도 최초
  token과 시각을 재생한다. 다만 현재 계정의 활성 OWNER 권한을 항상 먼저 다시 확인한다.
- 같은 키를 다른 payload에 재사용하면 `409 MEMBER_INVITATION_IDEMPOTENCY_KEY_REUSED`다.

### 미리보기·수락·폐기

- `POST /api/v1/identity/invitations/preview`는 bootstrap과 일반 token을 구분해 소비 없이
  팀·구성원·역할·만료를 반환한다. 일반 invitation은 현재 발급자의 활성 OWNER, 대상의
  활성·미결속과 현재 계정의 같은 팀 미결속을 다시 확인한다.
- `POST /api/v1/identity/invitations/accept`도 token 종류를 dispatch한다. 일반 invitation
  성공은 현재 account를 기존 구성원에 `MEMBER`로 결속하고 token을 같은 transaction에서
  소비한다.
- 최초 수락 account의 재시도는 발급 OWNER가 이후 비활성화되어도 최초 결속을 반환한다.
  다른 account의 재사용은 거절한다.
- `POST /api/v1/teams/{teamId}/member-invitations/{invitationId}/revocation`은 현재 활성
  OWNER만 호출하며 같은 폐기는 최초 시각으로 재생한다. 소비된 invitation은 폐기할 수 없다.
- 모든 identity 응답은 `Cache-Control: no-store`, session 변경은 CSRF 필수다.

### 동시성과 저장

- mutation은 account, 팀 구성원 UUID 정렬 순서, invitation 순서로 잠근다. 수락과 폐기가
  겹치면 DB의 `revoked_at`·`consumed_at` terminal-state 제약과 JPA version으로 둘 중
  하나만 커밋한다.
- 수락은 잠금 순서를 결정하기 전에 invitation 상태를 읽어야 한다. 이 관찰은 JPA entity가
  아닌 scalar projection으로 수행해, 다른 transaction이 상태를 바꾼 뒤 `FOR UPDATE`
  조회할 때 1차 캐시의 오래된 version과 충돌하지 않게 한다.
- 실제 invitation을 잠근 뒤 만료와 terminal 상태를 다시 확인한다. 잠금 대기 중 TTL을
  넘긴 invitation도 수락하지 않는다.

### 프런트엔드

- 홈은 session 상태, Google 로그인·로그아웃과 token 미리보기·명시적 수락을 제공한다.
- 작업 공간의 `계정·초대` dialog는 session account의 팀 membership을 다시 조회하고
  `OWNER`에게만 발급·열린 목록·폐기를 보여 준다.
- 원문 token과 CSRF는 React mutation memory에서만 사용하고 URL, query, fragment,
  localStorage와 sessionStorage에 넣지 않는다. 발급 mutation cache는 즉시 폐기한다.
- 불명확한 발급 실패를 같은 UUID로 복구하기 위해 localStorage에는 `teamId`, `memberId`,
  `idempotencyKey`만 최소 저장한다. 팀별 Web Lock으로 여러 탭의 동시 POST를 막고, 안전한
  저장·잠금을 사용할 수 없으면 발급을 시작하지 않는다.
- identity query key는 account ID를 포함한다. 로그아웃은 account 범위 identity cache를
  제거하지만 파일럿 workspace 공유 키는 별도 경계로 유지한다.
- API 응답과 경로는 OpenAPI 생성 `operations`·`paths` 타입에 결속한다.

## 결과

### 장점

- 공유 키가 아닌 검증된 session과 현재 membership으로 초대 권한을 판정한다.
- 원문 비밀을 저장하지 않으면서 응답 유실과 다중 탭 재시도를 복구한다.
- 미리보기로 사용자가 결속할 roster 자리를 확인하고, 수락·폐기 경합은 하나의 상태로
  수렴한다.
- bootstrap과 일반 invitation의 역할·수명·운영 경계를 독립적으로 바꿀 수 있다.

### 비용과 한계

- invitation table, 정리 정책, HMAC secret과 TTL 운영 책임이 추가된다.
- 기존 workspace API는 계속 공유 키로 보호한다. `OWNER`·`MEMBER` 결속만으로 전체
  workspace 읽기·쓰기 권한이나 역할 바통 감사 주체가 되지는 않는다.
- 한 계정의 다중 팀 탐색, 계정 비활성화·탈퇴·복구, 잘못된 결속의 운영 복구와 여러 OIDC
  공급자 연결은 후속 범위다.
- 일반 invitation은 외부 알림을 보내지 않는다. OWNER가 원문 token을 별도 안전 채널로
  전달해야 한다.

## 검토한 대안

### bootstrap invitation에 MEMBER 역할 추가

내부 operator 전용 최초 owner 경계와 제품 OWNER가 사용하는 일반 초대의 권한·TTL·폐기
정책이 섞이므로 채택하지 않았다.

### 공유 접근 키로 일반 초대 발급

공유 링크를 가진 모든 브라우저가 OWNER를 사칭할 수 있어 채택하지 않았다.

### token을 URL 또는 브라우저 저장소에 보관

브라우저 기록, referrer, screenshot, extension과 장기 저장소를 통해 capability가 확산될
수 있어 채택하지 않았다.

### 발급 응답 snapshot을 DB에 저장

원문 token 저장 범위를 넓히지 않고 HMAC 결정론과 hash-only 멱등 기록으로 같은 응답을
복원할 수 있어 채택하지 않았다.

## 검증

```bash
./gradlew --no-daemon :application:test --tests '*MemberInvitation*'
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon checkApiContract
cd frontend && npm run build && npm run e2e -- identity.spec.ts
bash ops/tests/pilot-readiness-test.sh
```

단위 테스트는 TTL, hash-only 저장, 멱등 재생, 미리보기, 수락·폐기와 같은 계정 재생을
검증한다. MySQL 통합 테스트는 같은 키·다른 키 경합과 수락 대 폐기를 검증하고, 브라우저
테스트는 동적 CSRF, token 비저장, journal 재시도, Web Lock, 로그아웃 cache 격리를
검증한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [제품 로드맵](../../PRD/0003_product-roadmap/spec.md)
- [Google OIDC 세션과 일회성 owner bootstrap](../0016_google-oidc-session-owner-bootstrap/adr.md)
- [로그인 공급자와 팀 구성원을 분리한 사용자 신원 결속 기반](../0015_provider-neutral-user-identity-binding/adr.md)
