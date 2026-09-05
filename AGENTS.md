# BATON 작업 지침

## 작업 방식

- `HANDOFF.md`에서 남은 작업을 확인하고, 변경 전에 `rg`로 관련 구현과 사용처를 찾는다.
- 사용자 요청과 앞선 합의에서 범위를 판단하고 구현·검증·커밋까지 진행한다. 결과에 영향을 주는 정보가 빠져 있을 때만 질문하며, 이미 승인된 행동은 다시 승인받지 않는다.
- 사용자 지시는 이 파일과 스킬의 작업 관례보다 우선한다. 지침 때문에 멈춰야 하면 해당 파일과 문구, 막힌 이유를 알린다.
- 응답·커밋·코드 주석·PR 리뷰·문서는 실무 용어로 간결하게 한국어로 쓴다. 경로·명령·식별자·표준 이름은 원형을 유지한다.
- 표준 API와 기존 구현으로 충분하면 래퍼·검증기·공용 계층을 추가하지 않는다. 요청과 무관한 리팩터링은 하지 않는다.

## 필요한 문서만 읽기

| 확인할 내용 | 기준 문서 |
| --- | --- |
| 실행법·현재 기능·문서 색인 | [README](README.md) |
| 제품 동작·용어 | [PRD-0001](docs/PRD/0001_product-baseline/spec.md)과 해당 기능 PRD |
| HTTP 계약 | [PRD-0002](docs/PRD/0002_api-contract/spec.md) |
| 모듈·패키지 경계 | [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md) |
| 테스트 선택·작성 기준 | [ADR-0002](docs/ADR/0002_test-strategy/adr.md) |

로컬 `.agents/skills/baton-*` 스킬은 담당 작업에 맞는 것만 사용한다. HTTP 계약, DB 마이그레이션, 시간 계산, 프런트엔드, 테스트 정리, 문서 작업을 구분하고 나머지 백엔드 작업에는 `baton-spring-backend`를 사용한다. 참고 문서와 다른 스킬을 일괄로 읽지 않는다. 스킬이 없는 환경에서는 위 문서와 실제 코드·설정으로 판단한다.

## 구현에서 지킬 경계

- Java 21과 기존 패키지 구조를 유지한다. 프로덕션 의존 방향은 `bootstrap → adapter-in-web/out-* → application → domain`이다. 웹은 `port.in`을 호출하며, 외부 연동 어댑터끼리 직접 참조하지 않는다.
- HTTP 형태 검증은 웹 DTO, 권한·소유권·트랜잭션은 애플리케이션, 불변식은 도메인에 둔다. 중복 검증은 같은 조건과 실패 시점을 보장할 때만 제거한다. 인증·DB 제약·잠금·부작용 직전 검증은 각각의 책임을 보존한다.
- Spring Data 저장소 인터페이스는 DevTools 프록시 호환성을 위해 `public`으로 선언한다. 업무 시간에는 주입한 `Clock`, 달력 계산에는 명시적인 `ZoneId`를 사용한다.
- 스키마는 `bootstrap/src/main/resources/db/migration`의 새 Flyway 마이그레이션으로 변경한다. 적용된 파일을 수정하지 않고 `ddl-auto: validate`를 유지한다.
- 일반 영속성은 JPA, 아웃박스·인박스의 선점과 원자적 중복 제거 등 명시적 SQL이 필요한 작업은 JDBC 어댑터가 맡는다.
- 프런트엔드는 `app`에 최상위 조립, `pages`에 화면 조합, `features`에 기능, `shared`에 공용 코드를 둔다. 서버 상태는 React Query와 공용 API 클라이언트를 사용하고, 쿼리 키에 필요한 계정·팀·시즌·필터 범위를 포함한다.
- 응답 디코더는 타입·식별자와 요청 대상의 일치, 안전한 렌더링에 필요한 날짜·시간대·포함 관계를 확인한다. 서버의 상태 전이와 파생 상태를 다시 계산하지 않는다.
- 제품 API는 `/api/v1`, 오류는 `ErrorResponse`의 `code`와 사용자용 `message`를 사용한다. 계약 변경은 구현·REST Docs·PRD-0002에 함께 반영한다.
- `docs/api/openapi3.yaml`과 `frontend/src/generated/api.ts`는 직접 수정하지 않는다. `./gradlew --no-daemon generateApiContract`로 생성하고 `checkApiContract`로 확인한다.
- 계정 세션·팀 권한·공유 키·외부 서비스 토큰을 구분한다. 인증 변경은 [PRD-0005](docs/PRD/0005_account-and-round-authentication/spec.md)와 [PRD-0009](docs/PRD/0009_team-account-access/spec.md), WATCH 수신은 [PRD-0004](docs/PRD/0004_watch-integration-contract/spec.md)를 따른다.
- 비밀값과 운영 자격 증명은 커밋하지 않는다. 로컬 설정이나 테스트 결과를 실제 운영 검증으로 설명하지 않는다.

## 변경 범위에 맞는 검증

관련 테스트부터 실행한다. 필요한 검증이 통과하면 마무리하고, 새 변경·실패·미확인 위험이 있을 때만 범위를 넓히거나 재실행한다. 단순 문구 변경을 그대로 따라 쓰는 테스트나 라이브러리 자체를 재검증하는 테스트는 추가하지 않는다.

| 변경 | 우선 검증 |
| --- | --- |
| 문서·지침 | 링크·참조 경로 확인, `git diff --check` |
| 정책·아키텍처 | `./gradlew --no-daemon :application:policyTest` |
| Spring·DB·Flyway·트랜잭션 | `./gradlew --no-daemon :application:useCaseTest` |
| HTTP 계약 | `./gradlew --no-daemon :adapter-in-web:restDocsTest`와 계약 생성·확인 |
| 프런트 소스 | `cd frontend`에서 `npm run build`; 테스트 수정·UI 동작 변경에는 `npm run typecheck`도 실행 |
| UI 동작·반응형 배치 | 담당 Playwright 파일 또는 태그; 시각 변경은 데스크톱·390px 화면 확인 |

JUnit에는 한국어 문장형 `@DisplayName`과 담당 태그(`policy`, `usecase`, `restdocs`)를 유지한다. 클래스별 실행은 해당 작업에 `--tests`를 붙인다. Testcontainers는 `--no-daemon`으로 실행하고, 선택한 테스트가 0개면 검증 완료로 보고하지 않는다.

전체 빌드·E2E와 실제 서비스·배포 검증의 실행 조건은 ADR-0002, 명령과 검증 한계는 [README 검증 명령](README.md#검증-명령)을 따른다.

## 문서와 마무리

- 변경에 영향을 받는 기준 문서만 갱신한다. 구현 설명이 틀리면 코드와 테스트를 확인해 바로잡고, 미구현·미검증 상태는 구분한다.
- `README.md`는 소개·실행법·색인, PRD는 제품·계약, ADR은 채택한 기술 결정을 맡는다. `HANDOFF.md`에는 남은 작업만 두고 완료 이력이나 장기 규칙을 복사하지 않는다.
- 기본 작업 브랜치는 `codex/`를 사용한다. 작업이 끝나면 자신의 변경을 의도별로 나눠 한국어로 커밋한다. 접두사는 `Feat:`, `Fix:`, `Refactor:`, `Test:`, `Docs:`, `Chore:`를 사용한다.
- 최종 응답은 결과부터 설명하고, 핵심 변경·실행한 검증·미실행 검증·문서 반영 여부를 짧게 보고한다.
