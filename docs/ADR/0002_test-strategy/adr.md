# ADR-0002: 변경 위험에 맞춘 최소 고가치 테스트

- 상태: 채택
- 결정일: 2026-07-20

## 배경

BATON은 작은 실제 스터디에서 빠르게 사용하면서도 역할, 반복 운영과 인수인계 데이터의 신뢰성을 지켜야 한다. 모든 변경마다 전체 테스트만 실행하면 피드백이 느려지고, 테스트 수 자체를 목표로 삼으면 유지 비용이 커진다.

변경이 깨뜨릴 수 있는 경계에 맞춰 가장 작은 검증부터 실행하고, 외부에 보이는 계약과 핵심 흐름에는 더 강한 테스트를 두는 기준이 필요하다.

## 결정

### 테스트 분류

| 분류 | JUnit 태그 | 용도 | 실행 명령 |
| --- | --- | --- | --- |
| 정책·아키텍처 | `policy` | 도메인 규칙과 모듈 의존 경계 | `./gradlew --no-daemon :application:policyTest` |
| 유스케이스 통합 | `usecase` | Spring 조립, DB, Flyway, 트랜잭션과 adapter 협력 | `./gradlew --no-daemon :application:useCaseTest` |
| HTTP 계약 | `restdocs` | 공개 요청·응답과 상태 코드 | `./gradlew --no-daemon :adapter-in-web:restDocsTest` |
| 전체 회귀 | 전체 | 여러 모듈에 걸친 변경 | `./gradlew --no-daemon test` 또는 `./gradlew --no-daemon build` |

`useCaseTest`는 MySQL 8 Testcontainers에서 파일럿 워크스페이스 생성, 워크스페이스·콘텐츠 생성과 접근 키 변경의 멱등성, 생성·복구 비밀 분리, 동시 멱등 요청과 접근 키 변경 충돌, 역할·역할 자료·루틴 정의·회차·실행·결정·바통 저장과 조회 projection을 검증한다. 회차와 역할 자료처럼 기존 schema를 이관하는 변경은 대상 이전 버전까지 적용한 데이터베이스를 최신 migration으로 올리는 전용 테스트도 둔다. 선택한 태스크가 실제 대상 테스트를 실행했는지 항상 확인한다.

`policyTest`는 의존 방향뿐 아니라 DevTools 분리 클래스 로더에서 Spring Data 프록시 생성에 필요한 repository 공개 가시성도 고정한다.

### 테스트 작성 원칙

- 테스트 개수나 커버리지 수치보다 장기적으로 가치 있는 실패를 잡는지 우선한다.
- 모든 테스트 메서드에는 한국어 문장형 `@DisplayName`을 사용한다.
- 정책과 아키텍처 테스트는 `policy`, 통합 흐름은 `usecase`, HTTP 계약은 `restdocs` 태그를 사용한다.
- 단순 framework wiring, getter/setter와 기계적 mapping만 확인하는 저가치 테스트는 추가하지 않는다.
- controller는 HTTP 형태와 변환, application 테스트는 유스케이스와 트랜잭션 경계, domain 테스트는 불변식을 검증한다.
- 데이터베이스 또는 Spring context가 필요한 통합 테스트는 Testcontainers를 사용하고 `--no-daemon`으로 실행한다.
- 시간에 의존하는 코드는 `now()`를 직접 흩뿌리지 않고 `Clock`을 주입한다. 운영 기본 Clock은 UTC다.
- 외부에 노출되는 API 변경은 구현과 REST Docs 테스트, API 계약 문서를 같은 변경에서 갱신한다.

### 기본 빌드 동작

- 모든 서브모듈 테스트는 JUnit Platform을 사용한다.
- `adapter-in-web:test`는 `restdocs` 태그를 제외한다.
- `adapter-in-web:check`는 `restDocsTest`를 별도로 의존한다.
- 따라서 `./gradlew --no-daemon build`에는 현재 REST Docs 계약 테스트가 포함된다.
- `./gradlew --no-daemon generateApiContract`는 REST Docs에서 OpenAPI와 프런트 타입을 생성하고, `checkApiContract`는 추적한 생성물의 드리프트를 검사한다.
- Java compiler는 Spring의 parameter name reflection을 위해 `-parameters`를 사용한다.

### 프런트엔드 기준

현재 프런트엔드에 존재하는 주요 검증 명령은 다음과 같다.

```bash
cd frontend
npm run typecheck
npm run build
npm run e2e:smoke
npm run e2e:operations
npm run e2e:memory
npm run e2e:handoff
npm run e2e:responsive
npm run e2e
npm run e2e:fullstack
```

- TypeScript `strict` 설정을 유지한다.
- UI 동작을 바꾸면 최소한 typecheck와 production build를 실행한다.
- 핵심 작업 공간 탐색, 공유 키 검증·회전, 최근 작업 공간 복구와 역할 생성 멱등 재시도는 `e2e:smoke`, 390px 모바일 작업은 `e2e:responsive`로 확인한다.
- 역할·루틴 수정, 수동 회차 생성과 회차별 반복 업무 완료는 `e2e:operations`, 결정 기록은 `e2e:memory`, 역할 자료 생성의 응답 유실 복구·수정 충돌 최신화·외부 링크·재조회와 바통 항목의 생성 멱등 재시도 및 바통북 흐름은 `e2e:handoff`로 확인한다.
- `e2e` 브라우저 회귀는 테스트별 독립 API fixture로 요청 body, 접근 키 header와 reload 후 서버 projection 복원을 빠르게 검증한다.
- `e2e:fullstack`은 고유 Compose project의 임시 MySQL, 실행 가능한 Spring Boot jar와 Vite 개발 proxy를 실제 브라우저로 잇는다. 빈 DB 온보딩부터 역할 자료, 루틴·회차, 공유 링크를 통한 두 브라우저 완료 상태 동기화와 reload 후 DB 영속성까지 한 핵심 경로만 단일 worker로 검증한다.
- full-stack runner는 기존 로컬·프로덕션 DB를 재사용하지 않고 종료할 때 자신이 만든 container와 volume만 제거한다. 실패 시 Spring, Vite와 MySQL 로그를 별도 artifact 경로에 보존한다.
- 전체 fixture 기반 Playwright 검증은 `e2e`, 전 구간 파일럿 스모크는 `e2e:fullstack`을 사용한다. Chromium이 없으면 먼저 `npm run e2e:install`을 실행한다.
- `e2e:fullstack`은 Caddy, TLS와 production image 실행을 검증하지 않는다. 이 배포 경계는 별도 운영 스모크로 확인한다.
- 선택한 태그가 실제 테스트와 매칭되는지 확인하며, 0개 테스트 실행을 완료된 검증으로 보지 않는다.
- 프런트 단위 테스트와 lint 명령은 아직 구성되지 않았으므로 이 ADR에서 의무 명령으로 선언하지 않는다.

### CI 품질 게이트

`.github/workflows/quality-gate.yml`은 모든 pull request, `main` push와 수동 실행에서 배포 가능한 커밋인지 판정한다. 오래된 같은 ref 실행은 취소하고 각 검증에 timeout을 둔다. path filter는 사용하지 않아 문서만 바뀐 pull request도 필수 상태가 누락되지 않게 한다.

- 백엔드와 API 계약은 Docker가 실제로 사용 가능한지 먼저 확인한 뒤 `build checkApiContract`를 한 Gradle invocation으로 실행한다. 이 조합은 Testcontainers·Flyway 통합 테스트와 REST Docs를 포함하고 같은 task graph 안에서 REST Docs 중복 실행을 피한다.
- 프런트엔드는 `npm run build`로 strict TypeScript와 production bundle을 확인하고 Chromium을 설치한 뒤 전체 Playwright E2E를 실행한다. CI에서는 `test.only`를 거부하고 재시도에서만 성공한 flaky test도 실패로 판정하며, 단일 worker로 실행 특성을 고정한다. 실패한 실행의 report·trace·screenshot은 7일 동안 artifact로 남긴다.
- 전 구간 파일럿 스모크는 Java 21, Node, Docker와 Chromium을 준비한 독립 job에서 `npm run e2e:fullstack`으로 실행한다. fixture 기반 UI 회귀와 분리해 Vite proxy, Spring 보안·HTTP·application 경계, Flyway와 MySQL 사이의 조립 실패를 명확히 드러내고 실패 report와 각 runtime 로그를 7일 동안 보존한다.
- 운영 패키지는 실제 비밀이 아닌 CI 전용 값으로 백업·복구 스크립트 문법과 production Compose를 확인한다. 결정론적 shell fixture로 배포 env 권한·literal 문법·비밀 분리·host 환경 우선순위 제거, 공개 HTTPS health의 성공·실패 종료와 백업 상태의 UTC 교차검증을 고정하고 backup·monitor systemd unit을 정적으로 검증한다. 이어서 고유 project에서 `app`·`web` 이미지를 한 번 build하고 같은 이미지를 `--no-build`로 실행해 Caddy local-CA HTTPS, 정적·SPA 경로, reverse proxy와 보안 header, production profile, Flyway와 MySQL TLS session을 검증한다. 이 runtime smoke는 정상 제품 API의 Spring 요청 ID 보존과 Caddy가 직접 만드는 413·502/503의 edge 요청 ID·access log 상관관계, 운영 키·멱등 키 로그 제거도 실제 컨테이너에서 고정한다. 실패한 Compose 상태·로그·inspect와 응답은 7일 동안 artifact로 남긴다.
- 네 병렬 job의 결과는 기존 계약 workflow의 job 식별자를 유지한 최종 `contract` job으로 집계한다. 원격 ruleset 또는 branch protection은 이 최종 검사를 required로 지정해야 병합을 강제 차단한다.

workflow는 `contents: read` 외 권한과 운영 secret을 사용하지 않는다. 이미지를 registry에 게시하거나 호스트에 배포하는 단계는 공급자와 배포 승인 경계를 결정할 때 별도로 채택한다.

### 변경별 검증 선택

- 한 모듈의 작은 변경은 해당 모듈 또는 해당 태그의 가장 좁은 테스트부터 실행한다.
- DB migration, 트랜잭션, Spring context 변경은 유스케이스 통합 테스트를 실행한다.
- HTTP 경로, DTO, 오류 코드와 상태 변경은 REST Docs 계약 테스트를 실행한다.
- 프런트가 소비하는 HTTP 계약 변경은 `generateApiContract`로 생성물을 갱신한 뒤 `checkApiContract`와 프런트 typecheck를 실행한다.
- 프런트와 백엔드 조립, Vite proxy, runtime 설정 또는 파일럿 핵심 흐름을 바꾸면 `e2e:fullstack`을 실행한다.
- production Compose, Dockerfile, Caddy, production profile 또는 내부 DB TLS 경계를 바꾸면 `bash ops/tests/production-runtime-smoke.sh`를 실행한다.
- 배포 env·Compose wrapper, health·backup freshness 또는 운영 systemd 경계를 바꾸면 `bash ops/tests/pilot-readiness-test.sh`와 해당 unit 정적 검증을 실행한다.
- 모듈 구조와 import 경계 변경은 정책 테스트를 실행한다.
- 공통 설정이나 여러 모듈을 건드린 변경은 마지막에 전체 `build`를 실행한다.

## 결과

### 장점

- 변경 원인과 가까운 테스트에서 빠르게 실패를 확인할 수 있다.
- API 계약과 아키텍처 경계가 실행 가능한 테스트로 남는다.
- 테스트 수를 늘리기 위한 테스트보다 실제 회귀 방지에 집중한다.

### 비용

- 변경자가 어떤 경계가 영향을 받는지 판단해야 한다.
- 테스트 태그를 잘못 붙이면 목적별 태스크에서 누락될 수 있다.
- 현재 프런트엔드에는 단위 테스트와 lint gate가 없다.

## 관련 문서

- [헥사고날 아키텍처 결정](../0001_hexagonal-architecture/adr.md)
- [API 계약 기준선](../../PRD/0002_api-contract/spec.md)
- [테스트 기반 API 계약 생성](../0004_test-derived-api-contract/adr.md)
