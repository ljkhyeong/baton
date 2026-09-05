# ADR-0001: 6모듈 헥사고날 아키텍처

- 상태: 채택
- 결정일: 2026-07-20

## 배경

BATON은 프런트엔드 프로토타입에서 실제 데이터를 저장하는 웹 서비스로 확장한다. 스터디에서 시작하지만 학교 조직과 회사 팀까지 적용하려면 HTTP, 데이터베이스와 외부 연동의 세부 구현이 핵심 제품 규칙을 침범하지 않아야 한다.

초기부터 지나치게 많은 서비스를 분리하는 대신, 하나의 Spring Boot 애플리케이션 안에서 코드 경계를 명확히 유지하는 멀티모듈 구조가 필요하다.

## 결정

백엔드는 Java 21, Spring Boot 4.0 계열과 Gradle 9.2.1 기반의 6모듈 구조를 사용한다.

```text
bootstrap
 ├─ adapter-in-web
 ├─ adapter-out-persistence
 ├─ adapter-out-external
 ├─ application
 └─ domain

adapter-in-web ─────────┐
adapter-out-persistence ├─> application ─> domain
adapter-out-external ───┘
```

### 모듈 책임

| 모듈 | 책임 |
| --- | --- |
| `domain` | 엔티티, 값 객체, 정책, 도메인 예외와 핵심 규칙 |
| `application` | 유스케이스, 서비스, 트랜잭션 경계, `port.in`/`port.out` |
| `adapter-in-web` | HTTP 컨트롤러, 요청·응답 DTO, 검증, 예외 변환과 웹 보안 설정 |
| `adapter-out-persistence` | JPA 저장소와 영속성 포트 구현 |
| `adapter-out-external` | 외부 HTTP와 향후 외부 서비스 포트 구현 |
| `bootstrap` | `@SpringBootApplication`, 런타임 설정, Flyway와 전체 모듈 조립 |

프런트엔드는 별도 `frontend/` 애플리케이션으로 유지한다. 라우트와 최상위 공급자는 `src/app`, 라우트 단위 화면은 `src/pages`, 기능 UI와 상태는 `src/features`, 공용 API·타입·UI·유틸리티는 `src/shared`가 소유한다.

### 의존 규칙

- `domain`은 `application`, 어댑터와 `bootstrap`을 참조하지 않는다.
- `application`은 어댑터와 `bootstrap`을 참조하지 않는다.
- 인바운드 웹 어댑터는 아웃바운드 어댑터와 `port.out`을 직접 참조하지 않고 `port.in`을 호출한다.
- 아웃바운드 어댑터끼리 직접 결합하지 않는다.
- `bootstrap`은 구성 루트로서 모든 모듈을 조립할 수 있다.
- 테스트 조립을 위한 `testImplementation` 의존성은 프로덕션 의존 방향과 구분한다.

이 규칙은 `application` 모듈의 `LayerDependencyPolicyTest`로 검사한다.

### 패키지 기준

Java 패키지 루트는 `com.personal.baton`이다.

```text
com.personal.baton.domain.<feature>
com.personal.baton.application.<feature>.port.in
com.personal.baton.application.<feature>.port.out
com.personal.baton.adapter.in.web.<feature>
com.personal.baton.adapter.out.persistence.<feature>
com.personal.baton.adapter.out.external.<feature>
com.personal.baton.bootstrap.<concern>
```

### 영속성 결합

현재 `domain`은 `jakarta.persistence-api`를 `api` 의존성으로 허용한다. 초기 서비스의 모델과 JPA 매핑을 가깝게 유지해 구현 비용을 줄이기 위한 선택이다. 이 결정 때문에 도메인이 완전한 프레임워크 독립 모델은 아니지만, 저장소와 데이터 접근 구현은 계속 영속성 어댑터가 소유한다.

## 결과

### 장점

- 제품 규칙을 HTTP, DB와 외부 공급자 변경에서 격리할 수 있다.
- 기능의 진입점과 출력 의존성이 포트로 드러난다.
- 단일 배포 단위를 유지하면서 모듈 경계를 자동 검사할 수 있다.
- 테스트에서 애플리케이션 흐름을 실제 어댑터와 조립하기 쉽다.

### 비용

- 작은 기능도 포트와 어댑터를 나누는 파일 비용이 생긴다.
- 테스트용 조립 의존성과 프로덕션 의존성을 구분해서 읽어야 한다.
- JPA 애너테이션을 도메인에 허용한 절충을 의식적으로 관리해야 한다.

## 미결정 및 비범위

- 인증과 권한 방식은 이 ADR에서 결정하지 않는다.
- 첫 파일럿 배포 토폴로지는 ADR-0003에서 결정한다. 장기 클라우드 공급자와 확장 구조는 이 ADR에서 결정하지 않는다.
- 마이크로서비스 분리는 현재 목표가 아니다.
- 팀·시즌·역할의 세부 애그리거트와 상태값은 제품 흐름을 구현할 때 별도로 결정한다.

## 검증

```bash
./gradlew --no-daemon :application:policyTest
```

## 관련 문서

- [제품 명세](../../PRD/0001_product-baseline/spec.md)
- [테스트 전략](../0002_test-strategy/adr.md)
- [첫 파일럿 자체 호스팅 배포](../0003_pilot-self-hosted-deployment/adr.md)
