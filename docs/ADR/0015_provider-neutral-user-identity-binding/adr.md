# ADR-0015: 로그인 공급자와 팀 구성원을 분리한 사용자 신원 결속 기반

- 상태: 채택
- 결정일: 2026-07-30

## 배경

BATON의 `Member`는 팀 roster의 표시 이름, 활동 상태와 역할·결정·바통 이력 참조를
소유한다. 현재 파일럿의 `X-Baton-Access-Key`는 팀 전체가 공유하는 읽기·쓰기 권한이므로
요청한 사람이 어느 구성원인지 증명하지 않는다.

ROUND 참여권은 사용자별 연결 수 제한과 감사를 위해 안정적인 `sub`가 필요하다. 팀 공유
키를 모든 참가자의 `sub`로 쓰면 세 번째 참가자부터 같은 사용자 제한에 걸리고, 요청마다
새 UUID를 쓰면 제한과 감사를 우회한다. 반대로 `Member`를 전역 로그인 계정으로 승격하면
팀별 이름 정정과 활동 종료가 계정 생명주기와 섞이고, 한 사용자의 여러 팀 참여도 표현하기
어렵다.

로그인 공급자, 세션 방식과 기존 팀의 최초 초대 bootstrap은 아직 제품 결정이 필요하다.
그 결정을 기다리는 동안에도 공급자 교체에 흔들리지 않는 내부 사용자 식별자와 roster
결속 불변식은 먼저 고정할 수 있다.

## 결정

### 내부 사용자 계정

- `UserAccount`는 BATON이 발급하는 불변 UUID와 UTC `createdAt`만 소유한다.
- 이메일, 비밀번호, OIDC issuer·subject와 provider token은 이 aggregate에 넣지 않는다.
  선택한 로그인 adapter가 별도 credential 또는 external identity 경계에서 계정을 찾는다.
- ROUND 참여권을 발급하게 되면 `sub`에는 provider subject나 `Member` UUID가 아니라
  BATON `UserAccount` UUID를 사용한다.
- 현재 변경에는 계정 생성 HTTP API, 로그인 adapter와 세션을 포함하지 않는다. 따라서
  migration 직후 `user_accounts`는 비어 있으며 검증된 인증 흐름이 채택되기 전에는
  브라우저가 계정을 만들 수 없다.

### 구성원 결속

- `MemberIdentityBinding`은 `memberId`, `teamId`, `userAccountId`, UTC `boundAt`과
  JPA version을 별도 테이블에 보존한다. 기존 `Member` 행과 과거 참조는 바꾸지 않는다.
- 한 `Member`는 최대 한 계정에만 결속된다. 한 계정은 같은 팀에서 최대 한 `Member`에만
  결속되지만 서로 다른 팀에서는 각각 하나의 roster 구성원과 연결될 수 있다.
- 기존 구성원은 결속 행 없이 그대로 이관한다. 결속은 활동 중 구성원에만 새로 만들 수
  있다.
- 같은 계정과 구성원의 재요청은 최초 `boundAt`을 유지하며 성공한다. 다른 계정으로
  재결속하거나 같은 팀의 다른 구성원을 추가로 차지하는 동작, 결속 해제는 초대·탈퇴·복구
  정책이 정해질 때까지 허용하지 않는다.
- application은 계정 행을 먼저, 구성원 행을 다음 순서로 배타 잠금한다. DB의 구성원
  primary key, `(member_id, team_id)` 복합 외래 키와
  `UNIQUE(team_id, user_account_id)`가 동시 요청의 마지막 방어선이다.

### 인증 경계

- `AuthenticatedAccount`는 향후 web adapter가 검증한 principal에서만 만들 application
  값이다. 요청 body, 임의 header, 공유 접근 키와 역할의 현재 담당자에서 account ID를
  추론하지 않는다.
- 이번 변경은 도메인·application·persistence 기반만 제공하고 결속 HTTP endpoint를
  공개하지 않는다. 향후 endpoint는 실제 로그인 principal과 일회성 초대 또는 동등한
  결속 권한을 함께 검증한 뒤 application use case를 호출해야 한다.
- `findActiveMember`는 account와 팀에 결속된 활동 중 구성원만 반환한다. 이후 ROUND
  참여권은 이 조회가 성공한 사용자에게 `participant`만 발급하고, `host`는 별도 권한
  행렬이 채택될 때까지 발급하지 않는다.
- 기존 공유 키 API는 점진 전환 동안 그대로 유지하지만 공유 키를 사용자 identity나 감사
  주체로 기록하지 않는다.

## 결과

### 장점

- 로그인 공급자를 바꾸거나 여러 공급자를 연결해도 BATON과 ROUND의 사용자 식별자가
  유지된다.
- 팀 roster의 이름·활동 생명주기와 전역 로그인 계정 생명주기가 분리된다.
- 기존 구성원과 역할·결정·바통 참조를 무중단으로 보존한다.
- 같은 팀의 중복 결속과 동시 claim을 application 잠금과 DB 제약에서 함께 차단한다.

### 비용과 한계

- 이 기반만으로는 사용자를 로그인시키거나 ROUND 참여권을 발급할 수 없다.
- 최초 OIDC 공급자, MySQL 기반 opaque session, 일회성 member invite와 기존 팀의 첫
  owner bootstrap, 계정 복구·탈퇴 정책을 별도 PRD와 ADR로 결정해야 한다.
- 결속 해제와 재결속을 금지했으므로 잘못 연결한 운영 복구 절차가 채택되기 전에는 DB를
  직접 수정해서는 안 된다.
- `UserAccount`가 비활성화·삭제 상태를 아직 갖지 않으므로 계정 생명주기 도입 때 migration과
  참여권 조회 조건을 함께 확장해야 한다.

## 검토한 대안

### `Member`를 전역 사용자 계정으로 사용

한 사용자의 여러 팀 참여를 표현하기 어렵고 roster 이름·활동 상태와 로그인 계정이
결합되므로 채택하지 않았다.

### 공유 접근 키와 구성원 선택을 사용자 신원으로 사용

키를 아는 사람이 다른 구성원을 선택해 사칭할 수 있고 ROUND 사용자별 제한과 감사의 근거가
되지 않으므로 채택하지 않았다.

### provider subject를 ROUND `sub`로 직접 사용

로그인 공급자를 바꾸거나 계정을 연결할 때 사용자 식별자가 달라지고 외부 식별자가 서비스
경계 전체로 누출되므로 채택하지 않았다.

### 인증 방식이 정해질 때까지 schema도 보류

로그인 구현과 roster 결속·ROUND 참여권 구현을 한 번에 묶어 위험과 변경량을 키우므로
채택하지 않았다.

## 검증

```bash
./gradlew --no-daemon :application:test \
  --tests '*MemberIdentityServiceTest' \
  --tests '*UserIdentityPolicyTest' \
  --tests '*UserIdentityMigrationTest'
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon build checkApiContract
```

V13 schema의 기존 구성원을 V14로 올려 그대로 보존하고, 사용자 계정·구성원 결속 생성,
활동 종료 구성원 거절, 동일 결속 재생, 계정당 팀별 한 구성원, 구성원당 한 계정과 팀
복합 외래 키를 검증한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [제품 개발 우선순위](../../PRD/0003_product-roadmap/spec.md)
- [구성원 활동 종료와 참조 보존](../0010_reversible-member-lifecycle/adr.md)
- [BATON GO를 통한 ROUND 역할 자료 링크](../0014_baton-go-round-resource-links/adr.md)
