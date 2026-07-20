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
| 정책·아키텍처 | `policy` | 도메인 규칙과 모듈 의존 경계 | `./gradlew :application:policyTest` |
| 유스케이스 통합 | `usecase` | Spring 조립, DB, Flyway, 트랜잭션과 adapter 협력 | `./gradlew --no-daemon :application:useCaseTest` |
| HTTP 계약 | `restdocs` | 공개 요청·응답과 상태 코드 | `./gradlew --no-daemon :adapter-in-web:restDocsTest` |
| 전체 회귀 | 전체 | 여러 모듈에 걸친 변경 | `./gradlew test` 또는 `./gradlew build` |

`useCaseTest` 태스크는 현재 존재하지만 제품 유스케이스 통합 테스트는 아직 추가되지 않았다. 존재하지 않는 테스트 기반이나 실행 명령을 문서상 완료된 것으로 간주하지 않는다.

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
- 따라서 `./gradlew build`에는 현재 REST Docs 계약 테스트가 포함된다.
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
```

- TypeScript `strict` 설정을 유지한다.
- UI 동작을 바꾸면 최소한 typecheck와 production build를 실행한다.
- 핵심 작업 공간 탐색은 `e2e:smoke`, 390px 모바일 탐색은 `e2e:responsive`로 확인한다.
- 반복 업무 완료, 결정 기록과 바통북 흐름은 각각 `e2e:operations`, `e2e:memory`, `e2e:handoff`로 확인한다.
- 전체 Playwright 검증은 `e2e`를 사용한다. Chromium이 없으면 먼저 `npm run e2e:install`을 실행한다.
- 선택한 태그가 실제 테스트와 매칭되는지 확인하며, 0개 테스트 실행을 완료된 검증으로 보지 않는다.
- 프런트 단위 테스트와 lint 명령은 아직 구성되지 않았으므로 이 ADR에서 의무 명령으로 선언하지 않는다.

### 변경별 검증 선택

- 한 모듈의 작은 변경은 해당 모듈 또는 해당 태그의 가장 좁은 테스트부터 실행한다.
- DB migration, 트랜잭션, Spring context 변경은 유스케이스 통합 테스트를 실행한다.
- HTTP 경로, DTO, 오류 코드와 상태 변경은 REST Docs 계약 테스트를 실행한다.
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
