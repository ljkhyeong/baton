# Repository Guidelines

## 세션 시작

- 작업 전에 `HANDOFF.md`를 읽는다.
- 프로젝트 개요와 실행법은 `README.md`에서 확인한다.
- 제품 동작은 `docs/PRD/0001_product-baseline/spec.md`, HTTP 계약은 `docs/PRD/0002_api-contract/spec.md`를 기준으로 삼는다.
- 구조나 테스트 기준을 바꾸기 전에 관련 `docs/ADR/`을 읽는다.
- 문서와 코드가 다르면 실행 가능한 코드와 테스트를 확인한 뒤 같은 변경에서 문서를 바로잡는다.

## 모듈 책임과 의존 방향

- `domain/`: 엔티티, 값 객체, 정책, 도메인 예외와 핵심 규칙
- `application/`: 유스케이스, application service, transaction과 `port.in`/`port.out`
- `adapter-in-web/`: controller, HTTP DTO, validation, exception handler와 web security
- `adapter-out-persistence/`: JPA repository와 persistence port 구현
- `adapter-out-external/`: 외부 HTTP와 외부 서비스 port 구현
- `bootstrap/`: `@SpringBootApplication`, `application*.yml`, Flyway와 runtime 조립
- `frontend/`: Vite + React + TypeScript 웹 애플리케이션

프로덕션 의존 방향은 `bootstrap → adapter-in-web/out-* → application → domain`이다.

- domain은 application, adapter와 bootstrap을 참조하지 않는다.
- application은 adapter와 bootstrap을 참조하지 않는다.
- web adapter는 outbound adapter와 `port.out`을 직접 호출하지 않는다.
- outbound adapter끼리 직접 참조하지 않는다.
- controller에 업무 규칙을 두지 않는다.
- Gradle의 test dependency는 production dependency 방향과 구분한다.

경계 변경 시 `LayerDependencyPolicyTest`를 함께 확인한다.

## Java 코드 규칙

- Java 21 toolchain과 `com.personal.baton.<layer>.<feature>` 패키지 구조를 유지한다.
- 클래스는 `UpperCamelCase`, 메서드와 필드는 `lowerCamelCase`를 사용한다.
- HTTP DTO는 역할이 드러나는 `Request`와 `Response` 이름을 사용한다.
- HTTP shape 검증은 DTO/controller, 유스케이스와 권한·소유권 검증은 application, 불변식은 domain에서 수행한다.
- application의 입력 port는 `port.in`, 외부 의존성은 `port.out`에 둔다.
- port 메서드를 구현하거나 명시적으로 재선언하면 `@Override`를 붙인다.
- Spring Data repository interface는 DevTools 분리 클래스 로더에서도 프록시할 수 있도록 `public`으로 선언한다.
- import로 충분한 타입을 코드 본문에 FQCN으로 쓰지 않는다.
- 시간 의존 코드는 `Instant.now()`나 `LocalDateTime.now()`를 직접 호출하지 않고 `Clock`을 주입한다. 달력 날짜·주기·로컬 마감 의미가 있을 때만 명시적인 `ZoneId`를 함께 사용한다.
- stream은 순수 변환에 사용하고, 상태 변경·분기·부분 실패·transaction effect는 명시적 흐름을 우선한다.
- 변경 전에 `rg`로 같은 이유의 유사 패턴을 전체 검색한다.

## 프런트엔드 코드 규칙

- Vite + React + TypeScript와 strict compiler 설정을 유지한다.
- `frontend/src/app`은 최상위 provider와 route wiring을 소유한다.
- `frontend/src/pages`는 route 단위 화면을 소유한다.
- `frontend/src/features/<feature>`는 기능 UI, form과 상태를 소유한다.
- `frontend/src/shared`는 공용 API client, 타입, UI, hook과 utility를 소유한다.
- page에는 세부 업무 로직을 쌓지 않고 owning feature로 내린다.
- 서버 상태는 React Query에 두고 `frontend/src/shared/api`의 공용 client, `ApiError`와 QueryClient를 사용한다.
- 팀이나 시즌에 속한 서버 상태 key에는 `teamId`와 `seasonId`를 포함해 다른 작업 공간의 cache가 섞이지 않게 한다.
- 접근성 이름, keyboard interaction, mobile/desktop layout과 `prefers-reduced-motion`을 함께 확인한다.
- 사용자에게 보이는 문구는 내부 enum 이름보다 다음 행동과 실제 상황을 설명한다.

## API 계약

- 제품 API는 `/api/v1` 아래에 둔다.
- controller는 application의 `port.in`만 호출한다.
- 외부 DTO와 application/domain 타입을 분리한다.
- 오류는 안정적인 `code`와 사용자용 `message`를 가진 `ErrorResponse`로 수렴시킨다.
- API 경로, DTO, 오류 코드나 HTTP 상태가 바뀌면 구현, REST Docs 테스트와 `docs/PRD/0002_api-contract/spec.md`를 함께 갱신한다.
- OpenAPI의 `operationId`는 REST Docs resource 식별자에서 생성하므로 camelCase로 안정적으로 유지한다. 같은 operation의 오류 resource 식별자는 해당 `operationId`를 prefix로 사용하고 canonical summary·description을 공유한다.
- 경로 변수가 있는 REST Docs 요청은 `RestDocumentationRequestBuilders`를 사용하고, enum과 배열 원소 타입, request validation constraint를 생성 스키마에서 잃지 않도록 `EnumFields`, `itemsType`, `ConstrainedFields`를 사용한다.
- 외부 계약인 응답 헤더는 MockMvc assertion과 `responseHeaders` descriptor를 함께 유지한다.
- `docs/api/openapi3.yaml`과 `frontend/src/generated/api.ts`는 생성 파일이다. 직접 수정하지 않고 `./gradlew --no-daemon generateApiContract`로 갱신하며, API 변경 뒤 `checkApiContract`로 드리프트를 확인한다.
- 인증 방식은 미결정이다. 현재 HTTP Basic을 최종 계약으로 확대 해석하거나 그 위에 새 제품 흐름을 고정하지 않는다.

## DB와 설정

- schema 변경은 `bootstrap/src/main/resources/db/migration`의 Flyway migration으로만 수행한다.
- migration 이름은 `V<number>__description.sql` 형식을 사용하고 적용된 migration을 수정하지 않는다.
- JPA schema는 `ddl-auto: validate`를 유지한다.
- 일반 저장과 aggregate 접근은 JPA를 사용하고, 명확한 조회·집계 요구가 확인되면 MyBatis 도입을 검토한다.
- 환경별 설정은 `application-*.yml`, 공통 설정은 `application.yml`에 둔다.
- 비밀값, 운영 credential과 환경별 주소를 저장소에 하드코딩하지 않는다.
- 인증과 배포 방식이 결정되지 않았으므로 임시 로컬 설정을 운영 기준으로 문서화하지 않는다.

## 테스트와 검증

모든 명령은 저장소 루트와 Gradle Wrapper를 기준으로 한다. 변경 범위를 덮는 가장 좁은 검증부터 실행한다.

- 정책·아키텍처: `./gradlew --no-daemon :application:policyTest`
- Spring/DB/Flyway/transaction 통합: `./gradlew --no-daemon :application:useCaseTest`
- HTTP 계약: `./gradlew --no-daemon :adapter-in-web:restDocsTest`
- API 계약 생성: `./gradlew --no-daemon generateApiContract`
- API 계약 드리프트: `./gradlew --no-daemon checkApiContract`
- 넓은 백엔드 변경: `./gradlew --no-daemon test` 또는 `./gradlew --no-daemon build`
- 백엔드 실행: `./gradlew --no-daemon :bootstrap:bootRun`
- 프런트 typecheck: `cd frontend && npm run typecheck`
- 프런트 production build: `cd frontend && npm run build`
- 프런트 핵심 E2E: `cd frontend && npm run e2e:smoke`
- 프런트 운영 E2E: `cd frontend && npm run e2e:operations`
- 프런트 기억 E2E: `cd frontend && npm run e2e:memory`
- 프런트 인수인계 E2E: `cd frontend && npm run e2e:handoff`
- 프런트 기록 탐색 E2E: `cd frontend && npm run e2e:records`
- 프런트 반응형 E2E: `cd frontend && npm run e2e:responsive`
- 프런트 전체 E2E: `cd frontend && npm run e2e`
- 실제 Spring/MySQL 파일럿 E2E: `cd frontend && npm run e2e:fullstack`
- 운영 백업 수명주기: `bash ops/tests/backup-cycle-test.sh`
- 파일럿 배포 사전점검·상태 감지: `bash ops/tests/pilot-readiness-test.sh`
- production 이미지 런타임 스모크: `bash ops/tests/production-runtime-smoke.sh`
- 운영 스크립트 문법: `bash -n ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/isolated-recovery-compose.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh`
- 운영 스크립트 정적 분석: `shellcheck -e SC1007,SC2016 ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/isolated-recovery-compose.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh`

테스트 작성 규칙:

- JUnit Platform을 사용한다.
- 모든 테스트 메서드에 한국어 문장형 `@DisplayName`을 붙인다.
- 정책·아키텍처는 `policy`, 통합 흐름은 `usecase`, HTTP 계약은 `restdocs` 태그를 사용한다.
- 커버리지 수치를 채우는 테스트보다 핵심 불변식, 고가치 유스케이스와 외부 직렬화 계약 테스트를 우선한다.
- Testcontainers 계열은 `--no-daemon`으로 실행한다.
- 현재 존재하지 않는 lint나 프런트 unit test 명령을 검증했다고 보고하지 않는다.
- 선택한 Playwright 태그가 실제 테스트와 매칭되는지 확인하며, 0개 테스트 실행을 완료된 검증으로 보고하지 않는다.
- `e2e:fullstack`은 격리된 임시 MySQL과 Vite 개발 proxy를 사용한다. Caddy, TLS와 production image를 검증했다고 확대 해석하지 않는다.
- production runtime smoke는 Caddy 내부 CA의 TLS 종단, production image·profile·빈 DB migration과 폐기 가능한 DB에서 실제 `backup.sh`·`restore.sh`·접근 키 복구 사슬을 검증한다. 공인 DNS·ACME·외부 방화벽·crypt remote 다운로드·실제 운영 데이터와 실기기 동작을 검증했다고 확대 해석하지 않는다.

## 문서 규칙

- `README.md`: 제품 소개, 현재 상태, 기술 스택, 실행법과 문서 index
- `HANDOFF.md`: 현재 진행 작업, 남은 행동과 다음 세션에만 필요한 정보
- `docs/PRD/`: 제품 동작과 HTTP 계약의 source of truth
- `docs/ADR/`: 이미 채택해 장기간 유지할 architecture 결정

`HANDOFF.md`에 완료 이력이나 장기 규칙을 복제하지 않는다. 진행 중 작업이 없으면 `진행 중 작업 없음.` 수준으로 비워 둔다.

소스 변경 시 README, HANDOFF, PRD, ADR와 API 계약 중 실제 영향 문서를 확인한다. 구현되지 않은 기능, route, 상태값과 배포 방식을 완료된 것처럼 기록하지 않는다.

## 변경과 보고

- 한 변경은 하나의 명확한 의도에 집중한다.
- 커밋 prefix는 `Feat:`, `Fix:`, `Refactor:`, `Test:`, `Docs:`, `Chore:`를 사용한다.
- 작업 브랜치는 기본적으로 `codex/` prefix를 사용한다.
- 최종 보고에는 변경한 핵심, 실행한 검증, 실행하지 못한 검증과 문서 반영 여부를 포함한다.
