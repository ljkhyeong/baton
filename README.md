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
- `역할`: 역할의 목적, 책임, 현재·다음 담당자와 담당 기간 등록·수정, 참고 자료 링크 연결
- `운영`: 모임 전·중·후 반복 루틴 정의, 수동 회차 생성·정정·보관·복원과 회차별 완료 처리
- `기록`: 결정 내용, 이유, 대안과 관련 역할 등록·수정, 가역 보관·복원과 보관함
- `바통`: 역할별 인수인계 항목 등록·수정·완료, 가역 보관·복원과 바통북·보관함

작은 보조 문구와 경고는 밝은 화면에서 읽을 수 있는 대비를 유지하고, 키보드 focus 표시는 밝은 본문과 어두운 navigation 모두에서 구분된다. 보관함 summary, 바통 tabpanel과 시각적으로 감춘 checkbox도 키보드 위치를 화면에 표시한다.

제품 데이터는 MySQL에 저장하고 React Query를 통해 다시 불러온다. 회차·결정·바통의 활성·보관 기록은 같은 workspace projection에 포함되며 프런트엔드는 `archivedAt`을 기준으로 일반 화면과 보관함을 나눈다. 열린 워크스페이스는 전경에서 10초마다 최신 내용을 확인하고 창 포커스·네트워크 복구 때 즉시 다시 조회하며, 마지막 화면 갱신 시각과 수동 새로고침을 제공한다. 일시적인 재조회 실패에는 기존 내용을 유지하지만 접근 키가 폐기된 `403`은 접근 오류 화면으로 전환한다. 연결 실패, 응답 지연과 해석할 수 없는 서버 응답은 각각 재시도할 수 있는 한국어 안내로 표시한다. 파일럿의 오늘 날짜, 시즌 진행률과 종료 안내는 브라우저 위치와 관계없이 `Asia/Seoul` 달력 날짜를 기준으로 계산하며, 팀·시즌별 시간대와 실제 마감 시각은 후속 범위다. 브라우저에는 팀별 공유 접근 키, 최근에 연 워크스페이스의 최소 메타데이터와 응답 유실 복구용 워크스페이스·콘텐츠 생성 및 키 회전 멱등 정보만 보관한다. 워크스페이스·콘텐츠 생성과 키 회전은 복구용 멱등 정보를 브라우저 저장소에 기록하고 다시 읽어 확인한 뒤에만 서버로 전송한다. 워크스페이스와 콘텐츠 생성은 같은 브라우저의 탭 사이에서 진행 중 요청을 직렬화하고, 경쟁한 탭은 요청을 보내지 않은 채 먼저 시작한 탭의 결과 확인을 안내한다. 온보딩은 완료 여부를 확인하지 못한 생성 요청을 목록으로 보여 주고 저장된 입력과 같은 멱등 키로 결과를 다시 확인하며, 기존 공유 링크로 결과를 확인한 요청은 경고 뒤 개별 복구 기록만 폐기할 수 있다. 공유 링크를 받은 구성원은 같은 워크스페이스를 함께 사용하며, 잘못된 새 링크가 기존의 정상 접근 키를 덮어쓰지 않는다. 공유 키는 소규모 파일럿을 위한 임시 접근 방식이고 최종 계정·초대·권한 모델은 아직 결정하지 않았다.

멱등 journal의 실패는 같은 요청 재확인, 새 요청 가능, 기존 결과 확인 후 새 요청 가능으로 구분한다. 결정적 종료와 콘텐츠 생성·접근 키 변경 성공 뒤에는 저장된 snapshot이 그대로인지 확인해 정리하고, 삭제와 `null` tombstone 기록이 모두 실패하면 새 멱등 키 요청으로 넘어가기 전에 완료 기록 정리를 요구한다. 온보딩 복구 snapshot이 다른 탭에서 바뀌거나 재생 결과가 만료된 경우에도 기존 결과 확인 없이 새 요청으로 자동 전환하지 않는다. 접근 키 변경도 같은 팀의 다른 탭과 journal 생성부터 서버 결과 확인·정리까지 직렬화하며, 이 안전 잠금을 지원하지 않는 브라우저에서는 회전을 시작하지 않는다.

### 백엔드 MVP

6모듈 Spring Boot 애플리케이션과 다음 최소 기반이 있다.

- `GET /api/v1/system/status`
- 멱등한 팀·시즌·구성원 온보딩과 공유 키 발급
- 응답이 유실되어도 중복 저장 없이 재시도할 수 있는 역할·루틴·회차·결정·바통 항목·역할 자료 생성 API와 역할·루틴·회차·자료·결정·바통 수정 API
- 회차·결정·바통의 영구 삭제 없는 가역 보관·복원 API
- 현재 루틴 정의를 스냅샷하는 수동 시즌 회차와 회차별 독립 실행 상태 API
- 멱등한 공유 키 회전과 별도 파일럿 복구 키를 이용한 분실 복구
- MySQL 영속화와 Flyway migration
- application 경계의 공유 키 검증, 원문 키 비저장과 역할·루틴 정의·시즌 회차·회차 실행·역할 자료·결정·바통의 겹친 수정 충돌 처리
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
- Spring Boot 4.0.7
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
2. 생성된 작업 공간에서 역할과 반복 루틴을 등록·수정하고, 역할 상세에 함께 사용할 문서 링크를 연결한다. 모임 날짜에 맞는 회차를 만들고 이름·날짜를 정정하며 회차별 루틴 실행을 완료 처리한다. 현재 운영에서 치울 회차·결정·바통 항목은 보관했다가 필요할 때 기존 실행 기록과 상태 그대로 복원한다.
3. 사이드바 또는 모바일 상단의 공유 기능으로 링크를 복사해 스터디 구성원에게 전달한다.
4. 공유 링크의 접근 키는 해당 워크스페이스의 읽기·쓰기 권한과 같으므로 공개 채널에 게시하지 않는다.

접근 키 원문은 워크스페이스 생성·키 회전·복구의 최초 응답과 동일 멱등 요청의 응답 유실 복구 때만 반환되며 서버에는 SHA-256 해시만 저장된다. 역할·루틴·회차·결정·바통 항목·역할 자료 생성도 응답을 받지 못하면 브라우저에 보관한 동일 멱등 키로 재시도해 이미 만들어진 항목을 되찾고 중복을 만들지 않는다. 회차 생성 재시도는 최초 회차와 실행 스냅샷의 식별자를 유지하면서, 이후 정정·보관 또는 완료 변경이 있었다면 그 현재 표현을 돌려준다. 자료 URL은 사용자 정보가 없는 `http` 또는 `https` 전체 주소만 허용하며 BATON 서버가 링크 대상의 내용이나 신뢰성을 확인하지 않는다. 키가 외부에 알려졌다면 워크스페이스의 `키 관리`에서 회전하고 새 공유 링크를 다시 전달한다. 브라우저 저장소가 차단되어 복구용 멱등 키를 안전하게 보관할 수 없으면 워크스페이스·콘텐츠 생성과 키 회전을 시작하지 않는다. 탭 사이의 생성 요청 직렬화를 지원하지 않는 브라우저에서는 워크스페이스와 콘텐츠 생성을 시작하지 않는다. 일반 브라우징 모드에서 사이트 저장소를 허용하고 최신 브라우저를 사용해야 한다. 서버는 이미 사용한 키 변경 멱등 해시를 기억해 더 최신 변경 뒤 폐기된 링크가 과거 요청으로 되살아나지 않게 한다. 모든 구성원이 키를 잃었다면 운영자가 고엔트로피 멱등 키를 생성해 아래 복구 API로 기존 키를 폐기하고 새 키를 발급한다. 응답을 받지 못했다면 멱등 키를 바꾸지 않고 같은 요청으로 재시도한다.

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
2. 예시 설정을 복사한 뒤 호스트·DB 식별자를 실제 값으로 바꾸고 네 비밀값을 서로 다른 고엔트로피 값으로 생성한다.
3. 사전점검을 통과한 같은 설정 파일로 프로덕션 Compose를 빌드하고 기동한다.

```bash
command -v git
command -v openssl
command -v docker
docker compose version
cp .env.production.example .env.production
chmod 600 .env.production
# 네 비밀값은 이 명령을 각각 다시 실행해 독립적으로 생성한다.
openssl rand -hex 32
./ops/preflight-production.sh
./ops/production-compose.sh up -d --build
./ops/production-compose.sh ps
```

운영 env는 주석과 일곱 개의 단순한 `KEY=VALUE`만 허용한다. 따옴표, 공백, `$` 보간과 port publish override를 넣지 않는다. 공통 validator는 파일이 현재 사용자 소유의 일반 파일이고 group·other 권한이나 Git 추적이 없는지, 공개 DNS 형식과 DB 식별자, 32~200자의 서로 다른 URL-safe 비밀값을 검사한다. `preflight-production.sh`는 이 검증에 Docker daemon·Compose v2와 최종 Compose 조립 확인을 더한다. DNS가 실제 호스트를 가리키는지, 외부 80/443 접근, 공인 인증서 발급과 host 디스크 여유까지 증명하지는 않는다.

`production-compose.sh`는 모든 명령 직전에 같은 env validator를 다시 실행하고, 현재 셸의 충돌 가능한 배포·Compose 경계 변수를 명시적으로 제거하며, `baton-production` 프로젝트와 저장소의 production Compose를 고정한다. 따라서 사전점검 뒤 env의 내용·권한·Git 추적 상태가 잘못 바뀌면 다음 Compose 명령이 fail-closed한다. 다른 절대 경로의 env를 쓸 때는 `./ops/preflight-production.sh /absolute/path/to/env`로 먼저 검사하고, 모든 Compose 명령에 `BATON_PRODUCTION_ENV_FILE=/absolute/path/to/env`를 지정한다. `BATON_HOST`, DB 사용자·비밀번호, `BATON_WORKSPACE_CREATION_KEY`와 `BATON_WORKSPACE_RECOVERY_KEY`가 빠지면 프로덕션 Compose는 설정 단계에서 실패한다. Compose를 거치지 않고 `production` 프로필로 직접 실행해도 두 운영 비밀 중 하나가 비어 있거나 32자보다 짧거나 값이 같으면 애플리케이션이 시작되지 않는다. 프로덕션 프로젝트 이름과 DB volume은 `baton-production`으로 고정되어 로컬 Compose 데이터와 섞이지 않는다. MySQL은 호스트 포트를 열지 않고 애플리케이션과 내부 TLS로 통신한다.

기동 뒤에는 서버 자체 확인으로 끝내지 않고, 스터디 구성원의 두 번째 기기에서 HTTPS 공유 링크를 열어 조회와 변경이 같은 데이터에 반영되는지 확인한다.

### 백업과 복구

```bash
./ops/backup.sh
./ops/verify-backup.sh --require-checksum /absolute/path/to/baton-backup.sql.gz
# 아래 systemd timer를 사용 중이라면 복구 전에 예약 실행도 멈춘다.
systemctl --user stop baton-backup.timer baton-backup.service
./ops/production-compose.sh stop web app
BATON_BACKUP_STATE_DIR=/absolute/path/to/baton-backup-state \
  BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
  ./ops/restore.sh /absolute/path/to/baton-backup.sql.gz
./ops/production-compose.sh up -d app web
# 복구 확인 뒤 새 백업을 만들고 timer를 다시 시작한다.
systemctl --user start baton-backup.service
systemctl --user start baton-backup.timer
```

백업은 기본적으로 `ops/backups/`에 권한이 제한된 고유 이름의 압축 SQL과 필수 SHA-256 sidecar로 생성된다. `backup.sh`는 gzip과 BATON 핵심 schema marker를 확인하고 sidecar를 먼저 원자적으로 게시한 뒤 dump 본문을 마지막에 공개하므로, 강제 종료가 다음 예약 주기를 막는 불완전 본문을 남기지 않는다. `restore.sh`는 sidecar 검증을 자체적으로 강제하고 예약 백업과 같은 `flock`을 잡는다. 또한 앱과 웹 컨테이너가 모두 `exited` 상태가 아니면 요청을 거부하고 대상 DB를 비운 뒤 백업 스냅샷만 복원한다. 기존 DB를 교체하는 작업이므로 복구 직전에도 백업하고, 실제 데이터를 넣기 전 별도 환경에서 복구 리허설을 수행한다.

### 매일 암호화 외부 백업

외부 저장소 공급자는 고정하지 않고 rclone `crypt` remote를 사용한다. 일반 provider remote 위에 BATON 전용 경로를 감싼 crypt remote를 만들고, crypt 설정 파일·암호·salt는 그 remote와 다른 비밀번호 관리자 또는 오프라인 매체에도 보관한다. remote 이름은 환경 변수 override를 정확히 검사할 수 있도록 영문·숫자·밑줄만 사용한다(예: `baton_crypt`). rclone 1.64 이상이 필요하며, 일반 remote이거나 `no_data_encryption=true`인 crypt remote를 지정하면 자동화는 업로드 전에 실패한다.

운영 호스트에 `rclone`과 `flock`을 설치한 뒤 절대 경로로 전용 환경 파일을 준비한다.

```bash
mkdir -p ~/.config/baton ~/.config/systemd/user
cp ops/backup.env.example ~/.config/baton/backup.env
chmod 600 ~/.config/baton/backup.env
cp ops/systemd/baton-backup.service ops/systemd/baton-backup.timer ~/.config/systemd/user/
```

`~/.config/baton/backup.env`의 `BATON_REPO_ROOT`, `BATON_PRODUCTION_ENV_FILE`, 백업·상태 디렉터리와 `BATON_RCLONE_REMOTE`를 실제 절대 경로로 바꾼다. 이 Compose env 경로는 수동 기동·백업·복구가 공통으로 사용한다. systemd EnvironmentFile은 `~`, `$HOME`과 명령 치환을 확장하지 않는다. 상태 디렉터리는 실행 시 `0700`으로 제한되며 예약 백업과 수동 복원이 같은 lock을 사용한다. OAuth 기반 remote가 config token을 갱신할 수 있으므로 rclone config는 서비스 사용자만 읽고 쓸 수 있게 `0600`으로 둔다. config 자체를 암호화했다면 `RCLONE_PASSWORD_COMMAND`에는 암호를 비대화형으로 출력하는 절대 경로 명령을 지정하고 그 복구 수단도 별도로 보관한다.

```bash
sudo loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemctl --user enable --now baton-backup.timer
systemctl --user start baton-backup.service
systemctl --user list-timers --all baton-backup.timer
journalctl --user -u baton-backup.service -n 100 --no-pager
BATON_BACKUP_STATE_DIR=/absolute/path/to/baton-backup-state ./ops/check-backup-freshness.sh
```

timer는 매일 `03:15 Asia/Seoul`부터 최대 15분 안에 실행하고, 호스트가 꺼져 놓친 실행은 다음 기동 뒤 보충한다. 실패한 서비스는 15분 간격으로 다시 시작하되 시작률을 1시간에 네 번으로 제한한다. 주기는 kernel `flock`으로 겹침을 막아 프로세스 종료 뒤 stale lock이 남지 않는다.

각 주기는 이전에 업로드하지 못한 로컬 백업을 먼저 재시도하되 이 단계만으로 freshness나 로컬 보존 상태를 바꾸지 않고, 이어서 새 dump를 만든다. 모든 dump와 필수 SHA-256 sidecar를 `--immutable`로 crypt remote에 게시한 뒤, 같은 remote를 통해 본문과 sidecar를 다시 읽어 hash를 비교한다. 완전히 검증한 파일에는 로컬 완료 marker를 남겨 이후 주기에는 다시 전송하지 않는다. 새 snapshot까지 외부 검증된 뒤 상태 파일에 checksum이 이름과 결합한 UTC snapshot 시각·이름·hash와 검증 시각을 기록한다. 14일이 지난 로컬 파일은 삭제 직전에 원격 본문과 sidecar를 다시 검증하고, 최신 세 개는 항상 남긴다. BATON 스크립트는 원격 백업을 삭제하지 않으므로 원격 versioning·object lock·lifecycle은 공급자에서 별도로 설정한다.

외부 백업을 복구할 때는 dump와 같은 이름의 `.sha256`을 함께 crypt remote에서 내려받고 먼저 검증한다.

```bash
rclone copyto baton_crypt:daily/baton-YYYYMMDDTHHMMSSZ-id.sql.gz /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz
rclone copyto baton_crypt:daily/baton-YYYYMMDDTHHMMSSZ-id.sql.gz.sha256 /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz.sha256
./ops/verify-backup.sh --require-checksum /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz
```

freshness는 마지막 작업 시각이 아니라 외부에서 검증된 최신 DB snapshot의 나이를 본다. 그래도 실제 import 성공을 뜻하지는 않으므로 월 1회 다른 환경에서 restore 리허설을 수행해 rclone 복호화 자격, 다운로드와 MySQL import까지 함께 검증한다.

### 서비스와 백업 상태 감지

호스트 로컬 점검은 서비스와 백업의 주기가 다르므로 별도 timer로 운영한다. 좁은 monitor 환경 파일에는 저장소·백업 상태의 절대 경로, 공개 health URL과 timeout·freshness 기준만 넣고 rclone 자격이나 운영 비밀은 넣지 않는다. 호스트에 `curl`이 있어야 하며 예시를 복사한 뒤 세 placeholder를 실제 값으로 바꾼다.

```bash
command -v curl
mkdir -p ~/.config/baton ~/.config/systemd/user
cp ops/monitor.env.example ~/.config/baton/monitor.env
chmod 600 ~/.config/baton/monitor.env
cp \
  ops/systemd/baton-service-health.service \
  ops/systemd/baton-service-health.timer \
  ops/systemd/baton-backup-freshness.service \
  ops/systemd/baton-backup-freshness.timer \
  ~/.config/systemd/user/
sudo loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemctl --user enable --now baton-service-health.timer baton-backup-freshness.timer
systemctl --user start baton-service-health.service baton-backup-freshness.service
systemctl --user list-timers --all 'baton-*'
journalctl --user -u baton-service-health.service -u baton-backup-freshness.service -n 100 --no-pager
```

서비스 점검은 5분마다 공개 `https://.../actuator/health`를 redirect 없이 기본 CA 검증과 TLS 1.2 이상으로 호출한다. HTTP 200의 aggregate `UP` 응답이어야 성공하므로 DNS, 공인 TLS, Caddy, Spring과 DB health 경계를 함께 지난다. 백업 점검은 1시간마다 마지막 crypt remote readback 검증 상태를 읽고 파일명 UTC 시각·epoch·검증 시각의 일치와 36시간 이내 freshness를 확인한다. 둘 다 자동 복구나 Compose 재시작은 하지 않고 실패 종료와 journal을 남긴다.

이 timer들은 같은 호스트에서 실행되므로 전원·커널·전체 네트워크 장애 때 검사와 journal도 함께 멈추며 알림을 보내지 않는다. 첫 외부 관측 경계로 기본 비활성화된 GitHub Actions `External health sentinel`을 제공한다. 실제 배포와 워크플로가 `main`에 반영된 뒤 공개 URL을 저장소 변수에 넣고 수동 실행이 성공하는지 먼저 확인한 다음 예약 검사를 켠다.

```bash
gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body false
gh variable set BATON_HEALTH_URL --body 'https://study.example.com/actuator/health'
gh workflow run external-health.yml
gh run list --workflow external-health.yml --event workflow_dispatch --limit 1
gh run watch "$(gh run list --workflow external-health.yml --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body true
```

`gh run list`에 방금 요청한 실행이 나타난 뒤 `watch`를 실행한다. 수동 실행에서 다른 URL을 일회성으로 확인하려면 `gh workflow run external-health.yml -f health_url=https://study.example.com/actuator/health`를 사용한다. 예약 검사는 private 저장소 runner 사용량을 제한하기 위해 매시 17분에 한 번만 실행하며, `BATON_EXTERNAL_MONITOR_ENABLED`가 정확히 `true`일 때만 runner를 시작한다. 각 실행은 순간적인 외부 network 실패를 걸러내려고 30초 간격으로 최대 두 번 확인한다. 중지할 때는 `gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body false`로 되돌린다.

예약 검사를 켜는 계정은 GitHub의 Actions email 또는 web 실패 알림을 활성화하고 첫 예약 실행과 알림 수신 책임자를 확인한다. 예약 알림은 워크플로를 처음 만든 사용자에게 연결되고, 이후 cron을 수정하거나 워크플로를 다시 활성화한 사용자로 바뀔 수 있으므로 개인 한 명에게 영구적인 호출 책임을 고정한 것으로 보지 않는다.

이 센티널은 GitHub 인프라에서 같은 엄격한 HTTPS health 검사를 실행하므로 호스트 전체 장애도 관측할 수 있지만, GitHub 예약 실행은 지연되거나 누락될 수 있고 워크플로 자체가 호출·SMS 같은 별도 알림 채널을 보장하지 않는다. 실제 파일럿에서 더 짧은 감지 시간이나 독립적인 호출이 필요하면 다른 네트워크의 uptime provider를 같은 URL과 알림 채널에 추가한다. NAT loopback이나 split DNS 환경에서는 호스트 로컬 검사만 실패할 수 있으므로 외부 관측과 함께 판단한다. health 성공도 프런트 자산, 공유 링크 쓰기와 실기기 동기화까지 증명하지 않으며, 백업 freshness 성공도 현재 원격 객체의 재검증이나 import 성공을 뜻하지 않는다.

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

`useCaseTest`는 MySQL 8 Testcontainers에서 멱등한 온보딩과 역할·역할 자료·루틴·회차·결정·바통 생성, 회차·결정·바통 정정과 보관·복원, 회차 스냅샷과 독립 완료 상태, 접근 키 회전·운영자 복구, 저장·재조회와 동시 충돌 규칙을 검증한다. 역할 자료는 V5 데이터가 있는 DB를 V6로, 결정·바통은 기존 데이터가 있는 DB를 V7로, 기존 회차는 활성 상태와 버전 `0`을 가진 V8로 올리는 이관도 검증한다.

### API 계약 생성

REST Docs 계약 테스트를 기준으로 [OpenAPI 3.0.1 문서](docs/api/openapi3.yaml)와 `frontend/src/generated/api.ts`를 생성한다. 생성 파일은 직접 수정하지 않는다.

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

- `generateApiContract`: `restDocsTest → 결정적 snippet 정렬 → OpenAPI 정규화 → openapi-typescript` 전체 흐름을 실행하고 추적할 두 생성 파일을 갱신한다.
- `checkApiContract`: REST Docs에서 다시 만든 OpenAPI와 추적 파일을 비교하고, 22개 operation의 경로·method·본문·헤더·상태 기준선과 프런트 생성 타입 드리프트를 검사한다.

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
npm run e2e:fullstack
```

- `e2e:smoke`: 온보딩, 접근 키·최근 목록 복구와 핵심 작업 공간 탐색
- `e2e:operations`: 역할·루틴 수정, 수동 회차 생성과 회차별 반복 업무 완료 흐름
- `e2e:memory`: 결정과 이유 기록 흐름
- `e2e:handoff`: 역할 자료 생성의 응답 유실 복구, 수정 충돌 최신화, 새 창 열기·재조회와 바통 항목 및 바통북 미리보기 흐름
- `e2e:responsive`: 390px 모바일 탐색
- `e2e`: 독립 API fixture를 사용하는 전체 Playwright 회귀 테스트
- `e2e:fullstack`: 임시 MySQL에서 실제 Spring Boot와 Vite를 띄우고 빈 DB 온보딩, 역할 자료, 루틴·회차, 두 브라우저 동기화와 새로고침 후 영속성을 확인하는 파일럿 스모크

Chromium이 설치되어 있지 않으면 먼저 `npm run e2e:install`을 실행한다. `e2e:fullstack`은 Docker와 Java 21도 필요하며, 고유 Compose project와 임시 MySQL volume을 만들었다가 종료 시 함께 제거한다. 기존 로컬·프로덕션 DB는 사용하지 않는다. 이 명령은 Vite 개발 proxy까지 검증하지만 Caddy, TLS와 production image 실행을 대신하지 않는다. 프런트엔드 단위 테스트와 lint 명령은 아직 구성하지 않았다.

### 운영 구성

```bash
bash -n ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh
shellcheck -e SC1007,SC2016 ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh
bash ops/tests/backup-cycle-test.sh
bash ops/tests/pilot-readiness-test.sh
bash ops/tests/production-runtime-smoke.sh
systemd-analyze verify ops/systemd/baton-backup.service ops/systemd/baton-backup.timer ops/systemd/baton-service-health.service ops/systemd/baton-service-health.timer ops/systemd/baton-backup-freshness.service ops/systemd/baton-backup-freshness.timer
docker compose config --quiet
./ops/preflight-production.sh
```

`production-runtime-smoke.sh`는 실제 production app·web 이미지를 빌드한 뒤 고유 Compose project와 폐기 가능한 MySQL·Caddy volume을 사용한다. Caddy 내부 CA HTTPS, 정적 프런트엔드와 SPA fallback, health·제품 API reverse proxy와 보안 header, 유효한 CI 전용 키를 사용한 production profile 기동, 실행 중인 Flyway·MySQL TLS 연결을 확인하고 자신이 만든 container·volume·image를 종료 시 제거한다. container 80·443만 `127.0.0.1`의 임시 host port에 게시하며 app과 MySQL port는 게시하지 않는다.

이 스모크의 로컬 인증서는 TLS 종단을 검증하지만 공인 DNS·ACME 발급과 브라우저 trust chain, 외부 방화벽, HTTP/3, 실제 운영 비밀과 실기기 공유 흐름을 대신하지 않는다. Compose 설정 검증만 실행한 경우에는 환경 변수와 YAML 조립만 확인된다.

### 자동 품질 게이트

GitHub Actions의 `Quality gate`는 모든 pull request, `main` push와 수동 실행에서 다음 네 경계를 병렬로 검증한다.

- 전체 백엔드 회귀와 API 계약 드리프트: `./gradlew --no-daemon build checkApiContract`
- 프런트 production build와 독립 API fixture 기반 전체 Playwright E2E
- 실제 브라우저, Vite proxy, Spring Boot, Flyway와 격리된 MySQL을 잇는 파일럿 전 구간 스모크
- 백업 생성·검증·암호화 원격 실패·보존 수명주기, 배포 사전점검·상태 감지, systemd unit, production Compose 조립과 `app`·`web` 이미지 build·runtime smoke

네 경계가 모두 성공해야 최종 `contract` 검사가 성공한다. 원격 저장소의 ruleset 또는 branch protection에서 이 검사를 required로 지정하면 실패한 커밋의 병합을 차단할 수 있다. 이 게이트는 실제 운영 비밀을 사용하거나 이미지를 게시·배포하지 않는다. production image의 local-CA TLS 종단과 빈 DB migration은 검증하지만 공인 DNS·ACME·외부 네트워크·실제 운영 데이터 migration과 실기기 흐름은 배포 후 별도로 확인한다.

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
- 제품 개발 우선순위: [PRD-0003](docs/PRD/0003_product-roadmap/spec.md)
- 백엔드 구조: [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md)
- 테스트 전략: [ADR-0002](docs/ADR/0002_test-strategy/adr.md)
- 파일럿 자체 호스팅 배포: [ADR-0003](docs/ADR/0003_pilot-self-hosted-deployment/adr.md)
- 테스트 기반 API 계약 생성: [ADR-0004](docs/ADR/0004_test-derived-api-contract/adr.md)
- 공유 콘텐츠의 낙관적 수정 충돌: [ADR-0005](docs/ADR/0005_optimistic-content-updates/adr.md)
- 루틴 정의와 회차 실행 분리: [ADR-0006](docs/ADR/0006_routine-definition-and-round-execution/adr.md)
- 결정과 바통의 가역 보관: [ADR-0007](docs/ADR/0007_reversible-record-archive/adr.md)
- 운영 회차 정정과 가역 보관: [ADR-0008](docs/ADR/0008_revisable-round-lifecycle/adr.md)
- 저장소 작업 규칙: [AGENTS.md](AGENTS.md)
- 현재 인계 상태: [HANDOFF.md](HANDOFF.md)

## 아직 결정하지 않은 것

- 회원가입, 초대, 소셜 로그인과 세션을 포함한 인증 방식
- 팀·시즌·역할 단위의 세부 권한 모델
- 장기 운영 공급자, 다중 호스트와 무중단 배포 방식
- 정식 uptime 공급자와 호출·SMS 같은 독립 알림 채널
- 결정·바통 이외 제품 도메인의 세부 상태값과 영구 삭제·보존 기간 정책
- 파일럿 이후 capability 공유 키를 대체할 계정·초대·복구 방식

구현보다 문서가 먼저 결정을 가장하지 않도록, 이 항목들은 실제 선택이 이루어질 때 PRD와 ADR을 함께 갱신한다.
