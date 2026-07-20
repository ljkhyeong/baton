# BATON

BATON은 사람이 바뀌어도 역할과 운영의 기억이 이어지게 하는 조직 운영 OS다.

스터디, 동아리, 학생회, 학교 위원회, 회사 팀과 TF처럼 여러 사람이 반복해서 함께 일하는 조직을 대상으로 한다. 사람 명단과 할 일만 관리하지 않고 역할의 목적과 책임, 반복 운영, 결정의 이유와 인수인계를 하나의 흐름으로 연결한다.

```text
팀 → 시즌 → 역할과 담당 기간
              ├─ 운영 루틴
              ├─ 결정과 이유
              ├─ 자료와 위험
              └─ 바통북 → 다음 담당자
```

첫 실제 사용처는 사용자가 참여하는 그룹 스터디다. 스터디에서 검증한 뒤 더 큰 학교·회사 조직으로 확장한다.

## 현재 상태

### 그룹 스터디 파일럿

첫 화면에서 팀, 시즌 기간과 구성원을 등록하면 공유 가능한 스터디 작업 공간을 만든다.

- `오늘`: 이번 시즌에 남은 운영 루틴과 역할별 위험 확인
- `역할`: 역할의 목적, 책임, 현재·다음 담당자와 담당 기간 등록
- `운영`: 모임 전·중·후 반복 루틴 등록과 완료 처리
- `기록`: 결정 내용, 이유, 대안과 관련 역할 기록
- `바통`: 역할별 인수인계 항목 등록, 준비 상태와 바통북 미리보기

제품 데이터는 MySQL에 저장하고 React Query를 통해 다시 불러온다. 브라우저에는 팀별 공유 접근 키만 보관하며, 공유 링크를 받은 구성원이 같은 워크스페이스를 함께 사용한다. 공유 키는 소규모 파일럿을 위한 임시 접근 방식이고 최종 계정·초대·권한 모델은 아직 결정하지 않았다.

### 백엔드 MVP

6모듈 Spring Boot 애플리케이션과 다음 최소 기반이 있다.

- `GET /api/v1/system/status`
- 팀·시즌·구성원 온보딩과 공유 키 발급
- 역할·책임·담당 기간, 운영 루틴, 결정과 바통 항목 API
- MySQL 영속화와 Flyway V1 schema
- application 경계의 공유 키 검증과 원문 키 비저장
- 공통 `ErrorResponse`와 입력 오류 처리
- Spring Security 임시 보호 설정
- MySQL, Flyway, Redis와 Spring Session 설정
- Actuator health/info/Prometheus endpoint
- ArchUnit 모듈 경계 테스트
- Spring REST Docs 계약 테스트

현재 HTTP Basic은 개발 기반의 임시 설정이며 최종 인증 방식이 아니다. 운영 배포 방식도 아직 결정하지 않았다.

## 기술 스택

### 백엔드

- Java 21
- Spring Boot 4.0.2
- Gradle Wrapper 9.2.1, Groovy DSL
- Spring MVC, Validation, Security
- Spring Data JPA, MyBatis 3.5.16
- MySQL 8, Flyway
- Redis, Spring Session
- Actuator, Micrometer Prometheus
- JUnit Platform, Testcontainers, ArchUnit, Spring REST Docs

### 프런트엔드

- Node.js 22
- React 19
- TypeScript 5.7, strict mode
- Vite 6
- React Router 7
- TanStack React Query 5
- Bootstrap 5, React Bootstrap, SCSS
- Playwright

라우트와 QueryClient, 공용 API client와 오류 모델을 사용해 팀·시즌 범위의 서버 projection과 mutation을 처리한다. 기존 `localStorage` 데모 데이터 경로는 제거했다.

## 저장소 구조

| 경로 | 책임 |
| --- | --- |
| `domain/` | 엔티티, 값 객체, 정책, 도메인 예외와 핵심 규칙 |
| `application/` | 유스케이스, 서비스, 트랜잭션, `port.in`/`port.out`, 공용 test fixtures |
| `adapter-in-web/` | HTTP 컨트롤러, 요청·응답, 검증, 예외 처리와 웹 보안 |
| `adapter-out-persistence/` | JPA/MyBatis persistence adapter |
| `adapter-out-external/` | Redis, 외부 HTTP와 향후 외부 서비스 adapter |
| `bootstrap/` | 애플리케이션 시작점, 런타임 설정, Flyway와 모듈 조립 |
| `frontend/` | React 웹 UI |
| `docs/PRD/` | 제품과 API의 기준 문서 |
| `docs/ADR/` | 채택한 기술 결정 |

프로덕션 의존 방향은 다음과 같다.

```text
bootstrap → adapter-in-web / adapter-out-* → application → domain
```

세부 규칙은 [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md)에 기록한다.

## 빠른 시작

### 요구사항

- Java 21
- Node.js 22 (`.nvmrc` 제공)
- Docker와 Docker Compose

### MySQL과 Redis 실행

```bash
docker compose up -d mysql redis
```

기본 로컬 포트는 MySQL `3306`, Redis `6379`다.

다른 프로젝트가 기본 포트를 사용 중이면 호스트 포트만 바꿔 함께 실행할 수 있다.

```bash
BATON_MYSQL_PORT=13306 BATON_REDIS_PORT=16379 docker compose up -d mysql redis
```

### 백엔드 실행

```bash
./gradlew --no-daemon :bootstrap:bootRun
```

- API: `http://localhost:8080`
- 상태 API: `http://localhost:8080/api/v1/system/status`
- 헬스 체크: `http://localhost:8080/actuator/health`

기본 포트가 사용 중이면 다음처럼 DB, Redis와 API 포트를 함께 맞춘다.

```bash
DB_URL='jdbc:mysql://localhost:13306/baton?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8' \
REDIS_PORT=16379 BATON_SERVER_PORT=18080 \
./gradlew --no-daemon :bootstrap:bootRun
```

### 프런트엔드 실행

```bash
cd frontend
npm ci
npm run dev
```

- 프런트엔드: `http://127.0.0.1:3000`
- `/api` 요청은 개발 서버에서 `http://127.0.0.1:8080`으로 전달한다.

백엔드를 다른 포트에서 실행했다면 프런트 proxy 대상도 맞춘다.

```bash
BATON_API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev
```

### 첫 파일럿 시작

1. `http://127.0.0.1:3000`에서 팀 이름, 시즌 기간과 구성원을 입력한다.
2. 생성된 작업 공간에서 역할, 반복 루틴, 결정과 바통 항목을 등록한다.
3. 사이드바 또는 모바일 상단의 공유 기능으로 링크를 복사해 스터디 구성원에게 전달한다.
4. 공유 링크의 접근 키는 해당 워크스페이스의 읽기·쓰기 권한과 같으므로 공개 채널에 게시하지 않는다.

접근 키 원문은 생성 시 한 번만 반환되며 서버에는 SHA-256 해시만 저장된다. 키를 잃어버렸을 때 재발급하는 기능은 아직 없으므로 공유 링크를 안전하게 보관한다.

## 검증 명령

### 백엔드

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon test
./gradlew --no-daemon build
```

- `policyTest`: 모듈 경계, Spring Data repository 공개 가시성과 도메인 정책 테스트
- `useCaseTest`: Spring, DB, Flyway와 transaction을 포함하는 통합 흐름 테스트
- `restDocsTest`: 외부 HTTP 계약 테스트
- `build`: 전체 컴파일·테스트와 REST Docs 검증

`useCaseTest`는 MySQL 8 Testcontainers에서 온보딩부터 역할·루틴·결정·바통 저장과 재조회, 접근 키와 충돌 규칙을 검증한다.

### 프런트엔드

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

- `e2e:smoke`: 핵심 작업 공간 탐색
- `e2e:operations`: 반복 업무 완료 흐름
- `e2e:memory`: 결정과 이유 기록 흐름
- `e2e:handoff`: 바통북 미리보기 흐름
- `e2e:responsive`: 390px 모바일 탐색
- `e2e`: 현재 등록된 전체 Playwright 테스트

Chromium이 설치되어 있지 않으면 먼저 `npm run e2e:install`을 실행한다. 프런트엔드 단위 테스트와 lint 명령은 아직 구성하지 않았다.

## 로컬 설정

- 기본 Spring profile: `local`
- 기본 DB: `jdbc:mysql://localhost:3306/baton`
- JPA schema 정책: `ddl-auto: validate`
- Flyway 위치: `bootstrap/src/main/resources/db/migration`
- 서버 기준 시각: UTC `Clock`
- 비밀값과 환경별 접속 정보는 환경 변수로 주입한다.

저장소의 기본 비밀번호는 로컬 개발 편의를 위한 값이다. 운영 인증과 secret 관리 방식은 배포 결정을 내릴 때 함께 확정한다.

## 문서 진입점

- 제품 기준: [PRD-0001](docs/PRD/0001_product-baseline/spec.md)
- API 계약: [PRD-0002](docs/PRD/0002_api-contract/spec.md)
- 백엔드 구조: [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md)
- 테스트 전략: [ADR-0002](docs/ADR/0002_test-strategy/adr.md)
- 저장소 작업 규칙: [AGENTS.md](AGENTS.md)
- 현재 인계 상태: [HANDOFF.md](HANDOFF.md)

## 아직 결정하지 않은 것

- 회원가입, 초대, 소셜 로그인과 세션을 포함한 인증 방식
- 팀·시즌·역할 단위의 세부 권한 모델
- 운영 배포 토폴로지와 공급자
- 과금, 알림 채널과 외부 서비스 연동
- 제품 도메인의 세부 상태값과 보존 정책
- 공유 키 재발급과 폐기 방식

구현보다 문서가 먼저 결정을 가장하지 않도록, 이 항목들은 실제 선택이 이루어질 때 PRD와 ADR을 함께 갱신한다.
