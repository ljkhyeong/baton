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
- MySQL은 외부 연결이 없는 내부 `data` network에 두고 애플리케이션만 두 네트워크를 연결한다. JDBC 연결도 TLS를 요구한다.
- 프로덕션 Compose 프로젝트와 DB volume 이름을 고정해 같은 저장소의 로컬 Compose 데이터와 재사용되지 않게 한다.
- 컨테이너에는 restart policy, 제한된 로그 크기, 애플리케이션 healthcheck와 graceful stop 시간을 둔다.
- 현재 파일럿은 서버 세션을 사용하지 않으므로 Redis와 Spring Session 의존성을 두지 않는다.
- Caddy는 제품 API와 health 요청 본문을 1MB로 제한한다.

### 설정과 생성 경계

- 도메인, DB 자격 증명, `BATON_WORKSPACE_CREATION_KEY`와 `BATON_WORKSPACE_RECOVERY_KEY`는 추적하지 않는 `.env.production`에서 주입한다.
- 예시 환경 파일은 실제 비밀값을 제공하지 않으며, 프로덕션 Compose는 필수 값이 비어 있으면 설정 단계에서 실패한다. `production` Spring 프로필도 두 운영 비밀 중 하나가 비어 있거나 32자보다 짧거나 값이 같으면 시작을 거절한다.
- 생성 키는 공개된 생성 API를 파일럿 운영자에게 제한한다. 별도의 복구 키는 모든 구성원이 워크스페이스 접근 키를 잃었을 때만 사용하며 두 값을 서로 다르게 생성한다.
- 최종 계정·초대·권한 모델은 이 결정에 포함하지 않는다.

### 백업과 복구

- `ops/backup.sh`는 컨테이너 내부 root 자격과 `--single-transaction`, `--hex-blob`을 사용해 일관된 MySQL dump를 호스트의 권한 제한 압축 파일로 만든다. 비밀번호는 프로세스 인자에 넣지 않으며, 실행별 고유 임시 파일과 원자적 이동으로 동시 실행의 덮어쓰기를 막는다. 프로세스 수명과 분리된 lock 파일은 강제 종료 뒤 예약 백업을 영구 차단할 수 있어 사용하지 않는다.
- 운영자는 백업을 매일 자동 실행하고 결과를 배포 호스트 밖으로 복제한다.
- `ops/restore.sh`는 명시적인 확인 환경 변수, 절대 경로, 비어 있지 않은 SQL과 BATON 핵심 schema marker 검증 없이는 실행을 거부한다. 앱과 웹 컨테이너가 모두 `exited`가 아니면 paused/restarting 상태를 포함해 복구를 차단한다. 오프라인 복구는 대상 DB를 drop/recreate한 뒤 덤프를 주입해 백업 이후 추가된 테이블과 데이터까지 제거하고 핵심 테이블을 다시 확인한다.
- 실제 데이터 투입 전 별도 환경에서 복구 리허설을 한 번 이상 수행한다.

## 결과

### 장점

- 실제 공유 URL, TLS와 API origin을 하나의 배포 단위로 재현할 수 있다.
- DB가 인터넷이나 LAN에 직접 노출되지 않는다.
- 작은 파일럿에 현재 불필요한 세션 저장소와 다중 서비스 운영 비용을 줄인다.
- 백업과 복구의 실행 경로가 저장소에 남는다.

### 비용과 한계

- 단일 호스트 장애 시 서비스가 중단되므로 외부 백업과 호스트 모니터링이 필요하다.
- 자동 무중단 배포, 다중 인스턴스와 DB 고가용성을 제공하지 않는다.
- Caddy의 자동 인증서를 위해 올바른 공개 DNS와 80/443 접근이 필요하다.
- 이미지 빌드와 배포는 현재 수동 명령이며 공급자별 IaC는 포함하지 않는다.

## 대안

### 개발 서버를 LAN에 직접 공개

구성은 단순하지만 TLS, origin, 프로세스 복구와 데이터스토어 노출을 안정적으로 통제하기 어려워 채택하지 않았다.

### 초기부터 관리형 클라우드와 Kubernetes 사용

확장성과 관리 기능은 좋지만 첫 스터디 파일럿의 트래픽과 운영 인력에 비해 복잡도가 크고 제품 검증보다 인프라 결정을 앞세우므로 보류했다.

## 검증

```bash
bash -n ops/backup.sh ops/restore.sh
docker compose --env-file .env.production -f compose.production.yml config --quiet
docker compose --env-file .env.production -f compose.production.yml up -d --build
```

마지막 명령 뒤에는 `/actuator/health`와 서로 다른 두 기기의 공유 링크 조회·변경을 확인한다. Compose 설정 검증만으로 실제 TLS와 다중 기기 동작을 검증했다고 간주하지 않는다.

## 관련 문서

- [제품 기준선](../../PRD/0001_product-baseline/spec.md)
- [API 계약](../../PRD/0002_api-contract/spec.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
