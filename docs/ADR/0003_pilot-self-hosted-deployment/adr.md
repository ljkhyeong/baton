# ADR-0003: 첫 파일럿 단일 호스트 동일 출처 HTTPS 배포

- 상태: 채택
- 결정일: 2026-07-21

## 배경

BATON의 첫 실제 사용자는 한 그룹 스터디의 구성원들이다. 개발용 Vite와 `bootRun`을 각자 실행하는 방식으로는 공유 링크가 다른 기기의 `localhost`를 가리키고, 프로세스 재시작·TLS·데이터 백업을 보장할 수 없다.

초기 파일럿은 운영 인력이 적고 트래픽도 작다. 따라서 클라우드 공급자나 다중 호스트 구조를 먼저 고정하기보다, 한 서버에서 재현 가능하게 실행하면서 브라우저와 API를 같은 HTTPS origin으로 제공하는 최소 운영 경계가 필요하다.

## 결정

첫 파일럿은 `compose.production.yml`을 사용하는 단일 호스트 Docker Compose 배포를 채택한다.

```text
인터넷
  │ 80/443
  ▼
Caddy ── 정적 React 애플리케이션
  │ /api/*, /actuator/health
  ▼
Spring Boot
  │ Docker 내부 data network
  ▼
MySQL
```

### 동일 출처와 TLS

- Caddy가 `BATON_HOST`의 인증서를 자동 관리하고 정적 프런트엔드를 제공한다.
- `/api/*`와 공개 health 경로만 Spring 애플리케이션으로 reverse proxy한다.
- 프런트엔드와 제품 API는 같은 origin을 사용하므로 개발 proxy 주소나 사용자 기기의 `localhost`가 공유 링크에 포함되지 않는다.
- Caddy는 CSP, HSTS, referrer, MIME sniffing과 브라우저 권한 관련 최소 보안 헤더를 설정한다.

### 네트워크와 런타임

- 호스트에는 Caddy의 80/443 포트만 공개한다.
- Spring 애플리케이션 포트와 MySQL 포트는 호스트에 게시하지 않는다.
- MySQL은 외부 연결이 없는 내부 `data` network에 두고 애플리케이션만 두 네트워크를 연결한다. JDBC 연결도 TLS를 요구한다. 현재 `sslMode=REQUIRED`는 전송 암호화를 강제하지만 서버 CA와 host identity 검증까지 제공하지는 않는다.
- 프로덕션 Compose 프로젝트와 DB volume 이름을 고정해 같은 저장소의 로컬 Compose 데이터와 재사용되지 않게 한다.
- 컨테이너에는 restart policy, 제한된 로그 크기, 애플리케이션 healthcheck와 graceful stop 시간을 둔다.
- MySQL healthcheck는 애플리케이션 DB 계정과 TLS로 `SELECT 1`이 성공해야 준비 완료로 판정한다.
- 현재 파일럿은 서버 세션을 사용하지 않으므로 Redis와 Spring Session 의존성을 두지 않는다.
- Caddy는 제품 API와 health 요청 본문을 1MB로 제한한다.
- Spring이 만든 제품 API 응답의 `X-Request-ID`는 Caddy가 보존하고 최종 응답 헤더로 access log에 기록한다. 1MB 제한과 upstream 장애처럼 Caddy가 직접 만드는 제품 API 오류에는 Caddy가 자체 UUID를 누락된 헤더에만 채우며 access log의 내장 `uuid`도 같은 값을 사용한다.
- Caddy access log는 운영 문의에 필요한 edge 요청 ID를 남기되 `X-Baton-Access-Key`, 생성·복구 키, `Idempotency-Key`와 외부 `X-Request-ID` 필드를 제거한다. 애플리케이션 오류의 상세와 stack trace는 Spring 로그에만 남는다.

### 설정과 생성 경계

- 도메인, DB 자격 증명, `BATON_WORKSPACE_CREATION_KEY`와 `BATON_WORKSPACE_RECOVERY_KEY`는 추적하지 않는 `.env.production`에서 주입한다. OAuth client secret, SMTP password, 안정적인 email outbox AES-256-GCM key와 ROUND PEM은 env에 원문을 넣지 않고 저장소 밖 owner-only 파일의 절대 경로만 기록한다.
- 예시 환경 파일은 실제 비밀값을 제공하지 않는다. `ops/validate-production-env.sh`는 owner-only 일반 파일과 Git 비추적, literal allowlist, 공개 DNS 형식, DB 식별자와 독립 생성한 32~200자 URL-safe 비밀 정책을 소유한다. 전용 auth validator는 Google·Naver 동시 완성, local-registration과 SMTP의 fail-closed 관계, scalar secret 파일 경계, RSA 크기·쌍·`kid`를 검증한다. 배포 사전점검은 이 검증에 Linux 로컬 Docker socket·Compose v2와 최종 조립 확인을 더한다. DNS 전파, 외부 port 접근, 공인 인증서 발급과 host 용량은 이 정적 점검의 보장 범위가 아니다.
- `ops/production-compose.sh`는 모든 명령 직전에 공통 env validator를 다시 실행하고, 현재 shell의 충돌 가능한 배포·Compose·Docker·BuildKit 경계 변수를 명시적으로 제거하며, `unix:///var/run/docker.sock`, `baton-production` project와 production Compose를 고정한다. 검증한 새 credential은 환경 source Compose secret에서 UID/GID 10001의 `0400` container 파일로 재구성하며 app 환경이나 image build context에 넣지 않는다. 사전점검 이후 잘못 변경된 env나 secret file은 다음 Compose 호출에서 거부한다. 수동 기동뿐 아니라 백업과 복구도 이 경계를 공유한다.
- 개발용 DB 주소와 계정 기본값은 `local` Spring profile에만 둔다. 프로덕션 Compose는 필수 값이 비어 있으면 설정 단계에서 실패하고, `production` Spring profile도 config data를 읽은 직후 애플리케이션 context와 Flyway를 구성하기 전에 명시적인 MySQL JDBC 주소·비 root 사용자·32~200자 URL-safe 비밀번호를 검증한다. JDBC 주소는 속성 없는 단일 `host[:port]/database`만 허용하고 query에는 `sslMode=REQUIRED`, `VERIFY_CA` 또는 `VERIFY_IDENTITY` 중 하나를 정확히 한 번 지정해야 한다. fragment·중복·host별 속성이나 Hikari/JNDI·Flyway 대체 연결 속성으로 실제 TLS 설정과 검증 결과가 달라지는 구성을 거절하고, Flyway도 검증된 주 DataSource만 사용하게 한다. 이 조건이 없으면 진입 경로와 무관하게 DB에 접속하기 전에 시작을 거절한다. 설정한 두 운영 비밀은 모든 프로필에서 32~200자의 URL-safe ASCII여야 하며, `production`에서는 두 값이 모두 있고 서로 달라야 시작한다.
- 생성 키는 공개된 생성 API를 파일럿 운영자에게 제한한다. 별도의 복구 키는 모든 구성원이 워크스페이스 접근 키를 잃었을 때만 사용하며 두 값을 서로 다르게 생성한다.
- 최종 계정·초대·권한 모델은 이 결정에 포함하지 않는다.

### 백업과 복구

- `ops/backup.sh`는 컨테이너 내부 root 자격과 `--single-transaction`, `--hex-blob`을 사용해 일관된 MySQL dump를 호스트의 권한 제한 압축 파일로 만든다. 비밀번호는 프로세스 인자에 넣지 않으며, 실행별 고유 임시 파일과 원자적 이동으로 동시 실행의 덮어쓰기를 막는다. gzip과 BATON 핵심 schema marker를 검증하고 필수 SHA-256 sidecar를 먼저 게시한 뒤 dump 본문을 마지막에 공개해 crash 중 불완전 본문이 동기화 glob을 막지 않게 한다.
- `ops/verify-backup.sh`는 gzip·schema marker와 SHA-256 sidecar를 공통 검증한다. `ops/restore.sh`는 sidecar를 필수로 요구하고 예약 백업과 같은 `flock`을 잡으며, 명시적인 확인 환경 변수와 절대 경로를 요구한다. 앱과 웹 컨테이너가 모두 `exited` 상태가 아니면 paused/restarting 상태를 포함해 복구를 차단한다. 오프라인 복구는 대상 DB를 drop/recreate한 뒤 덤프를 주입해 백업 이후 추가된 테이블과 데이터까지 제거하고 핵심 테이블을 다시 확인한다.
- 과거 스냅샷은 복원 시점까지의 접근 키 폐기 상태를 포함하지 않을 수 있으므로, `restore.sh`는 성공을 알리기 전에 모든 팀의 `access_key_hash`를 팀별 CSPRNG 값으로 교체한다. V2 이상 schema에서는 같은 transaction에서 마지막 키 변경 멱등 marker를 `NULL`로 만들고 `version`을 증가시킨다. 이미 사용한 키 변경 이력과 워크스페이스·콘텐츠 생성 멱등 이력은 과거 요청을 새 요청으로 되살리지 않도록 보존한다. V1 schema는 접근 키만 먼저 무효화하고 이후 애플리케이션 기동 때 Flyway가 nullable marker와 version을 추가한다.
- 무효화한 팀 수, 저장 해시 형식과 남은 최신 marker를 검증하고 각 팀에 복구용 최신 대표 시즌이 하나씩 없으면 restore를 실패시킨다. 대표 시즌은 시작일과 UUID 내림차순의 첫 행으로 고른다. 성공한 복구 대상은 서비스 사용자만 읽는 backup state의 `last-restore-recovery-targets.tsv`에 기록한다. 복원 뒤 모든 기존 공유 링크는 폐기되며, 운영자는 각 팀을 새 멱등 키로 복구한 뒤 새 링크를 다시 배포하고 그 상태를 새로 백업해야 한다.
- `ops/backup-cycle.sh`는 systemd user timer의 진입점이다. `0700` 상태 디렉터리의 동일 lock을 복원과 공유하고 open file descriptor의 `flock`으로 주기 전체를 직렬화하므로, 파일은 남아도 프로세스 종료 뒤 실제 lock은 자동 해제된다. 이전 주기의 미업로드 파일을 먼저 재시도하되 그 재시도만으로 freshness와 로컬 보존 상태를 바꾸지 않고, 이어서 새 dump를 생성한다.
- `ops/sync-backups.sh`는 rclone 1.64 이상에서 영문·숫자·밑줄 이름의 공급자 독립 `crypt` remote만 허용하며 config와 환경의 `no_data_encryption` override도 거부한다. 각 dump와 필수 SHA-256 sidecar를 `copyto --immutable`로 올리고 같은 crypt 경로로 다시 읽은 hash가 로컬과 일치해야 전체 원격 성공으로 판정한다. 완전히 검증한 파일에는 hash와 remote를 가진 로컬 완료 marker를 원자적으로 기록해 이후 주기에는 미완료 파일만 재시도한다. 일반 remote, 기존 원격 객체 불일치, 업로드·재다운로드 실패에는 완료 marker, snapshot 성공 상태와 보존 정리를 갱신하지 않는다.
- 완전히 검증된 원격 성공 뒤 14일이 지난 로컬 dump와 sidecar를 정리하되 최신 세 세트는 항상 보존한다. 삭제 직전에는 해당 원격 본문과 sidecar를 다시 읽어 hash를 재검증하며, BATON은 원격 객체를 삭제하지 않는다. 원격 보존 기간, versioning, lifecycle과 object lock은 선택한 공급자의 전용 BATON 경계에서 결정한다.
- user timer는 매일 `03:15 Asia/Seoul`부터 최대 15분 안에 실행하고 `Persistent=true`로 놓친 실행을 기동 뒤 보충한다. 서비스 실패는 15분 간격으로 재시도하되 시작률을 1시간에 네 번으로 제한하며 journal에 남긴다. 무로그인 기동에는 해당 사용자의 linger가 필요하다.
- checksum에 결합된 UTC 파일명에서 외부 검증 최신 snapshot 시각을 구하고 이름·hash·검증 시각과 함께 기록해 36시간 freshness check를 제공하지만 실제 import 성공을 대신하지 않는다. crypt config·암호·salt와 provider 자격은 해당 crypt remote와 다른 복구 경계에도 보관하고, 실제 데이터 투입 전과 이후 월 1회 다른 환경에서 다운로드·복호화·restore 리허설을 수행한다.
- 자동 품질 게이트의 복구 리허설은 운영 wrapper에 project override를 추가하지 않는다. 대신 현재 `backup.sh`·`restore.sh`·검증 SQL을 owner-only 임시 경계에 그대로 복사하고, test-only Compose shim이 고유 project·run token·동일 Docker daemon/context·custom label·전용 volume과 DB 이름·중지된 app/web을 매 명령에서 확인한 뒤 폐기 가능한 DB에만 연결한다. 같은 daemon에 `baton-production` resource가 있거나 예상 이름의 기존 volume·network·image가 있으면 시작 전에 거부한다. 이 리허설은 실제 dump/import와 키 복구 사슬을 증명하지만 crypt remote 다운로드와 별도 호스트 복구를 대신하지 않는다.

### 운영 상태 감지

- `baton-service-health.timer`는 기동 2분 뒤부터 5분마다 공개 HTTPS health를 호출한다. redirect와 신뢰되지 않은 인증서를 허용하지 않고 TLS 1.2 이상, HTTP 200과 aggregate `UP`을 요구해 DNS·공인 TLS·Caddy·Spring·DB health 경계를 관통한다.
- `baton-backup-freshness.timer`는 기동 3분 뒤부터 1시간마다 외부 readback 검증 성공 상태를 확인한다. 상태 파일은 service user 소유의 일반 파일이어야 하고 snapshot 파일명 UTC 시각과 저장 epoch, 검증 시각의 순서, remote 형식과 36시간 경계를 모두 만족해야 한다.
- 두 oneshot service는 자동으로 Compose를 재시작하거나 데이터를 바꾸지 않는다. 성공은 0, 실패는 non-zero로 끝나고 stdout·stderr를 journal에 남긴다. health와 freshness는 장애 특성과 주기가 달라 각 unit의 failed 상태와 향후 알림 연동 지점을 분리한다.
- 동일 호스트 timer는 host 전원·kernel·전체 network 장애를 감지하거나 알릴 수 없다. 기본 비활성화된 GitHub Actions `External health sentinel`을 첫 외부 관측 경계로 두고, 배포 뒤 저장소 변수 `BATON_HEALTH_URL`의 수동 검사를 통과한 경우에만 `BATON_EXTERNAL_MONITOR_ENABLED=true`로 예약 검사를 켠다. private 저장소 runner 사용량을 제한하려고 기본 branch에서 매시 17분에 한 번 실행하며 로컬 검사와 같은 엄격한 public HTTPS health 검사를 30초 간격으로 최대 두 번 수행한다.
- GitHub 예약 실행은 지연되거나 누락될 수 있고 실패 알림의 수신자는 workflow 생성·cron 수정·재활성화 주체와 개인 Actions 알림 설정에 의존한다. 이 워크플로는 독립적인 호출·SMS 알림을 제공하지 않으므로 보조 센티널이다. 더 짧은 감지 시간이나 독립 알림이 필요하면 다른 network의 uptime provider와 채널을 같은 public health URL에 추가한다. NAT loopback·split DNS에서는 로컬 검사만 실패할 수 있다.
- health 성공은 정적 프런트엔드·공유 링크 쓰기와 실기기 동기화를, freshness 성공은 remote 객체의 현재 재검증이나 실제 MySQL import를 보장하지 않는다.

## 결과

### 장점

- 실제 공유 URL, TLS와 API origin을 하나의 배포 단위로 재현할 수 있다.
- DB가 인터넷이나 LAN에 직접 노출되지 않는다.
- 작은 파일럿에 현재 불필요한 세션 저장소와 다중 서비스 운영 비용을 줄인다.
- 백업과 복구의 실행 경로가 저장소에 남는다.

### 비용과 한계

- 단일 호스트 장애 시 서비스가 중단되므로 외부 백업과 호스트 모니터링이 필요하다.
- 호스트 로컬 상태 감지는 실패를 journal에 남기고 GitHub 센티널은 host 전체 장애를 보조 관측하지만, 예약 실행의 지연·누락과 독립적인 사용자 호출을 해결하지 않으므로 필요한 감지 시간에 맞는 외부 monitor·알림 연결이 별도로 필요하다.
- 외부 백업은 systemd·flock·rclone과 서비스 사용자의 Docker socket 접근에 의존한다. rootful Docker의 docker group은 사실상 root 권한이며 이 자동화가 별도 권한 격리를 제공하지 않는다.
- crypt remote 설정과 복호화 자격을 잃으면 원격 객체가 정상이어도 복구할 수 없다. freshness 성공은 MySQL import 성공을 증명하지 않는다.
- 복구는 의도적으로 모든 공유 링크를 폐기하므로 팀별 접근 키 재발급과 구성원에게 새 링크를 전달하는 운영 시간이 필요하다.
- 자동 무중단 배포, 다중 인스턴스와 DB 고가용성을 제공하지 않는다.
- Caddy의 자동 인증서를 위해 올바른 공개 DNS와 80/443 접근이 필요하다.
- 내부 MySQL 연결은 암호화하지만 CA 검증과 host identity 검증을 추가하려면 별도의 CA 배포·회전 결정이 필요하다.
- 품질 게이트는 검증용 이미지를 build하지만 registry 게시와 호스트 배포는 수동이며 공급자별 IaC는 포함하지 않는다.

## 대안

### 개발 서버를 LAN에 직접 공개

구성은 단순하지만 TLS, origin, 프로세스 복구와 데이터스토어 노출을 안정적으로 통제하기 어려워 채택하지 않았다.

### 초기부터 관리형 클라우드와 Kubernetes 사용

확장성과 관리 기능은 좋지만 첫 스터디 파일럿의 트래픽과 운영 인력에 비해 복잡도가 크고 제품 검증보다 인프라 결정을 앞세우므로 보류했다.

## 검증

```bash
bash -n ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/validate-production-auth-secrets.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/isolated-recovery-compose.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh
shellcheck -e SC1007,SC2016 ops/backup.sh ops/backup-cycle.sh ops/check-backup-freshness.sh ops/check-service-health.sh ops/preflight-production.sh ops/production-compose.sh ops/restore.sh ops/sync-backups.sh ops/validate-production-env.sh ops/validate-production-auth-secrets.sh ops/verify-backup.sh ops/tests/backup-cycle-test.sh ops/tests/isolated-recovery-compose.sh ops/tests/pilot-readiness-test.sh ops/tests/production-runtime-smoke.sh
bash ops/tests/backup-cycle-test.sh
bash ops/tests/pilot-readiness-test.sh
bash ops/tests/production-runtime-smoke.sh
systemd-analyze verify ops/systemd/baton-backup.service ops/systemd/baton-backup.timer ops/systemd/baton-service-health.service ops/systemd/baton-service-health.timer ops/systemd/baton-backup-freshness.service ops/systemd/baton-backup-freshness.timer
systemd-analyze calendar '*-*-* 03:15:00 Asia/Seoul'
./ops/preflight-production.sh
./ops/production-compose.sh up -d --build
```

GitHub Actions 품질 게이트는 pull request와 `main` push에서 백업 성공·schema 실패·non-crypt 거부·원격 sidecar 실패·업로드 성공 뒤 로컬 보존 흐름을 확인한다. 별도 shell fixture는 production env 권한·literal 문법·비밀 분리, ambient 환경 제거, HTTPS health의 성공·실패 종료와 백업 UTC 상태 교차검증을 고정하고 backup·monitor systemd unit 문법을 확인한다. 복원 fixture는 현재 schema와 V1 schema 분기, 팀 수와 키 무효화 수 불일치 거부, 팀별 복구 대상 기록을 확인한다. production package job은 고유 project에서 이미지를 build한 뒤 DB 설정이 없는 app 이미지가 context와 Flyway 구성 전에 실패하는지 먼저 확인한다. 이어서 폐기 가능한 volume으로 같은 이미지를 실행해 Caddy 내부 CA의 TLS 종단, 정적 화면·SPA fallback, health·제품 API proxy와 보안 header, 유효한 CI 전용 키를 사용한 production profile 기동, 빈 DB Flyway migration, host에 게시되지 않은 app·MySQL port와 암호화된 JDBC session을 실제 운영 비밀 없이 검증한다. 제품 API의 정상 Spring 요청 ID 보존과 대용량 413·중지된 upstream 502/503의 Caddy 요청 ID·access log 상관관계, 운영 키·멱등 키 로그 제거도 실제 edge에서 확인한다. 이어서 두 실제 workspace와 후속 시즌을 만들고 원본 `backup.sh`·`restore.sh`로 snapshot rollback, 팀별 최신 복구 대상, 모든 과거 키 폐기와 replay tombstone, 운영자 복구·새 키 조회와 변경·재백업을 검증한다. 실패 상태는 환경 변수를 제외한 inspect와 보호 값이 없는 로그만 artifact로 보존하고, 소유 label을 확인한 스모크 전용 container·network·volume·image만 제거한다. 외부 health 센티널은 코드 품질 게이트와 분리하며, 명시적으로 활성화한 배포 URL의 현재 도달성을 예약 검사한다.

이 검증은 이미지를 게시하거나 실제 원격 저장소·호스트에 배포하지 않는다. shell fixture는 실제 DNS·공인 CA·timer 실행을 대신하지 않고 unit 정적 검증도 linger·journal·실패 후 다음 주기 회복을 증명하지 않는다. Caddy 내부 CA는 공인 DNS·ACME와 브라우저 trust chain을 대신하지 않으며, 외부 80/443 방화벽, HTTP/3, 실제 crypt remote 업로드, timer 재기동, 기존 운영 데이터 migration과 실기기 공유 동작은 운영 환경에서 별도로 확인한다. 실제 배포 뒤에는 `/actuator/health`와 서로 다른 두 기기의 공유 링크 조회·변경을 확인한다. 예약 센티널은 `BATON_EXTERNAL_MONITOR_ENABLED`가 없거나 `false`이면 skipped 상태다. 수동 실행과 활성화된 예약 실행은 health URL이 없거나 유효하지 않으면 fail-closed하며, 실제 URL을 사용한 수동 성공과 첫 예약 실행의 정시성·알림 전달은 운영 환경에서 별도로 확인한다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
