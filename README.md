# BATON

BATON은 사람이 바뀌어도 역할과 운영의 기억이 이어지게 하는 조직 운영 OS다.

스터디, 동아리, 학생회, 학교 위원회, 회사 팀과 TF처럼 여러 사람이 반복해서 함께 일하는 조직을 대상으로 한다. 사람 명단과 할 일만 관리하지 않고 역할의 목적과 책임, 반복 운영, 결정의 이유와 인수인계를 하나의 흐름으로 연결한다.

```text
팀 → 시즌 → 역할과 담당 기간
              ├─ 운영 루틴 정의 → 회차별 실행
              ├─ 결정과 이유
              ├─ 자료와 위험
              └─ 바통북 → 다음 담당자
```

첫 실제 사용처는 사용자가 참여하는 그룹 스터디다. 스터디에서 검증한 뒤 더 큰 학교·회사 조직으로 확장한다.

## 현재 상태

### 그룹 스터디 파일럿

첫 화면에서 팀, 시즌 기간과 구성원을 등록하면 공유 가능한 스터디 작업 공간을 만든다.

- `오늘`: 선택한 운영 회차에 남은 루틴 실행과 역할별 위험 확인
- `역할`: 역할의 목적, 책임, 현재·다음 담당자와 담당 기간 등록·수정
- `운영`: 모임 전·중·후 반복 루틴 정의, 수동 회차 생성과 회차별 완료 처리
- `기록`: 결정 내용, 이유, 대안과 관련 역할 기록
- `바통`: 역할별 인수인계 항목 등록, 준비 상태와 바통북 미리보기

제품 데이터는 MySQL에 저장하고 React Query를 통해 다시 불러온다. 브라우저에는 팀별 공유 접근 키, 최근에 연 워크스페이스의 최소 메타데이터와 응답 유실 복구용 워크스페이스·콘텐츠 생성 및 키 회전 멱등 정보만 보관한다. 워크스페이스·콘텐츠 생성과 키 회전은 복구용 멱등 정보를 브라우저 저장소에 기록하고 다시 읽어 확인한 뒤에만 서버로 전송한다. 공유 링크를 받은 구성원은 같은 워크스페이스를 함께 사용하며, 잘못된 새 링크가 기존의 정상 접근 키를 덮어쓰지 않는다. 공유 키는 소규모 파일럿을 위한 임시 접근 방식이고 최종 계정·초대·권한 모델은 아직 결정하지 않았다.

### 백엔드 MVP

6모듈 Spring Boot 애플리케이션과 다음 최소 기반이 있다.

- `GET /api/v1/system/status`
- 멱등한 팀·시즌·구성원 온보딩과 공유 키 발급
- 응답이 유실되어도 중복 저장 없이 재시도할 수 있는 역할·루틴·회차·결정·바통 항목 생성 API와 역할·루틴 수정 API
- 현재 루틴 정의를 스냅샷하는 수동 시즌 회차와 회차별 독립 실행 상태 API
- 멱등한 공유 키 회전과 별도 파일럿 복구 키를 이용한 분실 복구
- MySQL 영속화와 Flyway migration
- application 경계의 공유 키 검증, 원문 키 비저장과 역할·루틴 정의·회차 실행의 겹친 수정 충돌 처리
- 공통 `ErrorResponse`와 입력 오류 처리
- Spring Security 임시 보호 설정
- MySQL과 Flyway 설정
- Actuator health/info/Prometheus endpoint
- ArchUnit 모듈 경계 테스트
- Spring REST Docs 계약 테스트와 OpenAPI·프런트 타입 자동 생성

현재 HTTP Basic은 개발 기반의 임시 설정이며 최종 인증 방식이 아니다. 첫 파일럿 배포는 Docker Compose와 Caddy를 사용하는 단일 호스트 동일 출처 HTTPS 구성을 제공하지만, 장기 운영 공급자와 확장 토폴로지는 아직 결정하지 않았다.

## 기술 스택

### 백엔드

- Java 21
- Spring Boot 4.0.2
- Gradle Wrapper 9.2.1, Groovy DSL
- Spring MVC, Validation, Security
- Spring Data JPA, MyBatis 3.5.16
- MySQL 8, Flyway
- Actuator, Micrometer Prometheus
- JUnit Platform, Testcontainers, ArchUnit, Spring REST Docs, restdocs-api-spec 0.20.1

### 프런트엔드

- Node.js 22
- React 19
- TypeScript 5.7, strict mode
- Vite 6
- React Router 7
- TanStack React Query 5
- Bootstrap 5, React Bootstrap, SCSS
- Playwright
- openapi-typescript 7.13.0

라우트와 QueryClient, 공용 API client와 오류 모델을 사용해 팀·시즌 범위의 서버 projection과 mutation을 처리한다. 기존 `localStorage` 데모 데이터 경로는 제거했다.

## 저장소 구조

| 경로 | 책임 |
| --- | --- |
| `domain/` | 엔티티, 값 객체, 정책, 도메인 예외와 핵심 규칙 |
| `application/` | 유스케이스, 서비스, 트랜잭션, `port.in`/`port.out`, 공용 test fixtures |
| `adapter-in-web/` | HTTP 컨트롤러, 요청·응답, 검증, 예외 처리와 웹 보안 |
| `adapter-out-persistence/` | JPA/MyBatis persistence adapter |
| `adapter-out-external/` | 외부 HTTP와 향후 외부 서비스 adapter |
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

### MySQL 실행

```bash
docker compose up -d mysql
```

기본 로컬 포트는 MySQL `3306`이며 `127.0.0.1`에만 바인딩되므로 같은 네트워크의 다른 기기에 직접 공개되지 않는다.

다른 프로젝트가 기본 포트를 사용 중이면 호스트 포트만 바꿔 함께 실행할 수 있다.

```bash
BATON_MYSQL_PORT=13306 docker compose up -d mysql
```

### 백엔드 실행

```bash
./gradlew --no-daemon :bootstrap:bootRun
```

- API: `http://localhost:8080`
- 상태 API: `http://localhost:8080/api/v1/system/status`
- 헬스 체크: `http://localhost:8080/actuator/health`

기본 포트가 사용 중이면 다음처럼 DB와 API 포트를 함께 맞춘다.

```bash
DB_URL='jdbc:mysql://localhost:13306/baton?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8' \
BATON_SERVER_PORT=18080 \
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

1. `http://127.0.0.1:3000`에서 팀 이름, 시즌 기간과 구성원을 입력한다. 이름이 같은 구성원은 구분할 별칭을 붙인다. 서버에 생성 코드가 설정되어 있으면 파일럿 생성 코드도 입력한다.
2. 생성된 작업 공간에서 역할과 반복 루틴을 등록·수정하고, 모임 날짜에 맞는 회차를 만든 뒤 회차별 루틴 실행을 완료 처리한다. 결정과 바통 항목도 같은 작업 공간에 등록한다.
3. 사이드바 또는 모바일 상단의 공유 기능으로 링크를 복사해 스터디 구성원에게 전달한다.
4. 공유 링크의 접근 키는 해당 워크스페이스의 읽기·쓰기 권한과 같으므로 공개 채널에 게시하지 않는다.

접근 키 원문은 워크스페이스 생성·키 회전·복구의 최초 응답과 동일 멱등 요청의 응답 유실 복구 때만 반환되며 서버에는 SHA-256 해시만 저장된다. 역할·루틴·회차·결정·바통 항목 생성도 응답을 받지 못하면 브라우저에 보관한 동일 멱등 키로 재시도해 이미 만들어진 항목을 되찾고 중복을 만들지 않는다. 회차를 재시도하면 그 뒤 루틴 정의가 바뀌었더라도 최초 회차와 실행 스냅샷을 돌려준다. 키가 외부에 알려졌다면 워크스페이스의 `키 관리`에서 회전하고 새 공유 링크를 다시 전달한다. 브라우저 저장소가 차단되어 복구용 멱등 키를 안전하게 보관할 수 없으면 생성과 회전을 시작하지 않으므로, 일반 브라우징 모드에서 사이트 저장소를 허용해야 한다. 서버는 이미 사용한 키 변경 멱등 해시를 기억해 더 최신 변경 뒤 폐기된 링크가 과거 요청으로 되살아나지 않게 한다. 모든 구성원이 키를 잃었다면 운영자가 고엔트로피 멱등 키를 생성해 아래 복구 API로 기존 키를 폐기하고 새 키를 발급한다. 응답을 받지 못했다면 멱등 키를 바꾸지 않고 같은 요청으로 재시도한다.

```bash
curl -X POST \
  "https://baton.example.com/api/v1/teams/<team-id>/seasons/<season-id>/access-key/recover" \
  -H "Idempotency-Key: <32~200 character high-entropy value>" \
  -H "X-Baton-Recovery-Key: <production recovery key>"
```

## 첫 파일럿 운영 배포

첫 파일럿은 한 호스트에서 Caddy가 정적 프런트엔드와 `/api`를 같은 HTTPS origin으로 제공하고, Spring 애플리케이션과 MySQL은 Docker 내부 네트워크에서만 통신한다. 세부 결정과 한계는 [ADR-0003](docs/ADR/0003_pilot-self-hosted-deployment/adr.md)에 기록한다.

### 준비와 기동

1. 공개 호스트의 A/AAAA DNS를 배포 서버로 연결하고 80/TCP, 443/TCP·UDP를 허용한다.
2. 예시 설정을 복사한 뒤 모든 placeholder를 서로 다른 고엔트로피 값으로 교체한다.
3. 프로덕션 Compose를 빌드하고 기동한다.

```bash
cp .env.production.example .env.production
chmod 600 .env.production
docker compose --env-file .env.production -f compose.production.yml up -d --build
docker compose --env-file .env.production -f compose.production.yml ps
```

`BATON_HOST`, DB 사용자·비밀번호, `BATON_WORKSPACE_CREATION_KEY`와 `BATON_WORKSPACE_RECOVERY_KEY`가 빠지면 프로덕션 Compose는 설정 단계에서 실패한다. Compose를 거치지 않고 `production` 프로필로 직접 실행해도 두 운영 비밀 중 하나가 비어 있거나 32자보다 짧거나 값이 같으면 애플리케이션이 시작되지 않는다. 프로덕션 프로젝트 이름과 DB volume은 `baton-production`으로 고정되어 로컬 Compose 데이터와 섞이지 않는다. MySQL은 호스트 포트를 열지 않고 애플리케이션과 내부 TLS로 통신한다.

기동 뒤에는 서버 자체 확인으로 끝내지 않고, 스터디 구성원의 두 번째 기기에서 HTTPS 공유 링크를 열어 조회와 변경이 같은 데이터에 반영되는지 확인한다.

### 백업과 복구

```bash
./ops/backup.sh
docker compose --env-file .env.production -f compose.production.yml stop web app
BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE ./ops/restore.sh /absolute/path/to/baton-backup.sql.gz
docker compose --env-file .env.production -f compose.production.yml up -d app web
```

백업은 기본적으로 `ops/backups/`에 권한이 제한된 고유 이름의 압축 SQL로 생성된다. 각 실행은 고유 임시 파일을 원자적으로 완성하므로 동시에 시작되어도 서로 덮어쓰지 않고, 강제 종료 뒤 stale lock 때문에 이후 예약 백업이 막히지 않는다. 실제 파일럿에서는 cron 또는 systemd timer로 매일 실행하고 결과를 같은 호스트 밖의 저장소로 복사한다. 복구 스크립트는 파일이 BATON 핵심 schema marker를 가진 비어 있지 않은 dump인지 먼저 확인하고, 앱과 웹 컨테이너가 모두 `exited` 상태가 아니면 요청을 거부한다. 이후 대상 DB를 비운 뒤 백업 스냅샷만 복원해 백업 이후 생긴 테이블과 데이터가 남지 않게 하고 핵심 테이블을 다시 검증한다. 기존 DB를 교체하는 작업이므로 복구 직전에도 백업하고, 별도 환경 또는 점검 시간에 먼저 복구 리허설을 수행한다.

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

`useCaseTest`는 MySQL 8 Testcontainers에서 멱등한 온보딩과 역할·루틴·회차·결정·바통 생성, 회차 스냅샷과 독립 완료 상태, 접근 키 회전·운영자 복구, 저장·재조회와 동시 충돌 규칙을 검증한다.

### API 계약 생성

REST Docs 계약 테스트를 기준으로 [OpenAPI 3.0.1 문서](docs/api/openapi3.yaml)와 `frontend/src/generated/api.ts`를 생성한다. 생성 파일은 직접 수정하지 않는다.

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

- `generateApiContract`: `restDocsTest → 결정적 snippet 정렬 → OpenAPI 정규화 → openapi-typescript` 전체 흐름을 실행하고 추적할 두 생성 파일을 갱신한다.
- `checkApiContract`: REST Docs에서 다시 만든 OpenAPI와 추적 파일을 비교하고, 14개 operation의 경로·method·본문·헤더·상태 기준선과 프런트 생성 타입 드리프트를 검사한다.

프런트엔드는 생성된 operation 요청·응답·헤더 타입과 `paths`의 URI template·HTTP method 조합을 기존 feature façade에서 사용한다. `apiRequest`, `ApiError`, React Query key와 멱등 재시도 같은 런타임 정책은 생성하지 않고 기존 코드가 계속 소유한다.

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

- `e2e:smoke`: 온보딩, 접근 키·최근 목록 복구와 핵심 작업 공간 탐색
- `e2e:operations`: 역할·루틴 수정, 수동 회차 생성과 회차별 반복 업무 완료 흐름
- `e2e:memory`: 결정과 이유 기록 흐름
- `e2e:handoff`: 바통북 미리보기 흐름
- `e2e:responsive`: 390px 모바일 탐색
- `e2e`: 현재 등록된 전체 Playwright 테스트

Chromium이 설치되어 있지 않으면 먼저 `npm run e2e:install`을 실행한다. 프런트엔드 단위 테스트와 lint 명령은 아직 구성하지 않았다.

### 운영 구성

```bash
bash -n ops/backup.sh ops/restore.sh
docker compose config --quiet
docker compose --env-file .env.production -f compose.production.yml config --quiet
```

Compose 설정 검증은 환경 변수와 YAML 조립을 확인할 뿐 이미지 빌드, TLS 발급, DB migration과 실제 다중 기기 흐름을 대신하지 않는다.

## 로컬 설정

- 기본 Spring profile: `local`
- 기본 DB: `jdbc:mysql://localhost:3306/baton`
- JPA schema 정책: `ddl-auto: validate`
- Flyway 위치: `bootstrap/src/main/resources/db/migration`
- 서버 기준 시각: UTC `Clock`
- 비밀값과 환경별 접속 정보는 환경 변수로 주입한다.
- 프로덕션에서는 MySQL을 Docker 내부 네트워크에만 둔다.

저장소의 기본 비밀번호는 로컬 개발 편의를 위한 값이다. 운영 인증과 secret 관리 방식은 배포 결정을 내릴 때 함께 확정한다.

## 문서 진입점

- 제품 기준: [PRD-0001](docs/PRD/0001_product-baseline/spec.md)
- API 계약: [PRD-0002](docs/PRD/0002_api-contract/spec.md)
- 백엔드 구조: [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md)
- 테스트 전략: [ADR-0002](docs/ADR/0002_test-strategy/adr.md)
- 파일럿 자체 호스팅 배포: [ADR-0003](docs/ADR/0003_pilot-self-hosted-deployment/adr.md)
- 테스트 기반 API 계약 생성: [ADR-0004](docs/ADR/0004_test-derived-api-contract/adr.md)
- 역할·루틴·실행 낙관적 수정 충돌: [ADR-0005](docs/ADR/0005_optimistic-content-updates/adr.md)
- 루틴 정의와 회차 실행 분리: [ADR-0006](docs/ADR/0006_routine-definition-and-round-execution/adr.md)
- 저장소 작업 규칙: [AGENTS.md](AGENTS.md)
- 현재 인계 상태: [HANDOFF.md](HANDOFF.md)

## 아직 결정하지 않은 것

- 회원가입, 초대, 소셜 로그인과 세션을 포함한 인증 방식
- 팀·시즌·역할 단위의 세부 권한 모델
- 장기 운영 공급자, 다중 호스트와 무중단 배포 방식
- 과금, 알림 채널과 외부 서비스 연동
- 제품 도메인의 세부 상태값과 보존 정책
- 파일럿 이후 capability 공유 키를 대체할 계정·초대·복구 방식

구현보다 문서가 먼저 결정을 가장하지 않도록, 이 항목들은 실제 선택이 이루어질 때 PRD와 ADR을 함께 갱신한다.
