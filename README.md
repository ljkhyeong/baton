# BATON

BATON은 사람이 바뀌어도 역할과 운영의 기억이 이어지게 하는 조직 운영 OS다.

스터디, 동아리, 학생회, 학교 위원회, 회사 팀과 TF처럼 여러 사람이 반복해서 함께 일하는 조직을 대상으로 한다. 사람 명단과 할 일만 관리하지 않고 역할의 목적과 책임, 반복 운영, 결정의 이유와 인수인계를 하나의 흐름으로 연결한다.

```text
팀 → 시즌 → 역할과 담당 기간
              ├─ 운영 루틴 정의 → 회차별 실행
              ├─ 결정과 이유
              ├─ 자료와 위험
              └─ 바통북 → 전달 → 다음 담당자 수락
```

첫 실제 사용처는 사용자가 참여하는 그룹 스터디다. 스터디에서 검증한 뒤 더 큰 학교·회사 조직으로 확장한다.

## 현재 상태

### 그룹 스터디 파일럿

첫 화면에서 팀, 시즌 기간과 구성원을 등록하면 공유 가능한 스터디 작업 공간을 만든다.

- `오늘`: 관련 운영 회차의 예정·진행·지연·완료 루틴과 이유·다음 행동을 포함한 조직 연속성 레이더
- `역할`: 현재 팀의 구성원 추가·이름 정정·활동 종료·재활성화, 로그인 계정과 기존 구성원 연결, 현재 시즌 역할의 목적, 책임, 현재·다음 담당자와 담당 기간 등록·수정, 참고 자료 링크 연결
- `운영`: 모임 전·중·후 반복 루틴과 실제 마감 정의, 사용하지 않는 루틴 정의의 가역 보관·복원, 주간·격주 자동 일정, 수동 회차 생성·정정, 모든 회차의 보관·복원과 회차별 완료 처리
- `기록`: 결정 내용, 이유, 대안과 관련 역할 등록·수정, 가역 보관·복원과 보관함
- `바통`: 역할별 인수인계 항목 등록·수정·완료와 가역 보관·복원, 다음 담당 기간을 정한 바통 준비, 누락 경고 확인 뒤 전달, 다음 담당자의 수락 또는 현재 담당자의 취소
- `탐색`: 현재 선택한 시즌의 결정·바통 항목·역할 자료 통합 검색, 종류·역할·활성/보관 상태·기간 필터와 원본 화면 이동
- `시즌`: 팀의 과거·현재 시즌 전환, 이름·기간 수정, 명시적 종료와 선택한 역할·루틴으로 다음 시즌 시작

작은 보조 문구와 경고는 밝은 화면에서 읽을 수 있는 대비를 유지하고, 키보드 초점 표시는 밝은 본문과 어두운 탐색 영역 모두에서 구분된다. 보관함 요약, 바통 탭 패널과 시각적으로 감춘 확인란도 키보드 위치를 화면에 표시한다.

제품 데이터는 MySQL에 저장하고 React Query를 통해 다시 불러온다. 활동 중·활동 종료 구성원과 루틴 정의·회차·결정·바통 항목의 활성·보관 기록, 역할 바통의 준비·전달·수락·취소 이력, 팀의 서버 권위 시즌 목록과 현재 기록에서 파생한 조직 연속성 신호는 같은 워크스페이스 프로젝션에 포함된다. 루틴 정의를 보관해도 과거 회차는 생성 당시 실행 스냅샷을 계속 표시하고 완료 처리할 수 있다. 탐색 화면은 별도 검색 API나 별도 캐시 없이 현재 선택한 시즌 프로젝션의 결정·바통 항목·역할 자료에서 파생하며, 종료 시즌은 해당 시즌으로 전환한 뒤 같은 방식으로 검색한다. 연속성 레이더는 활동 상태를 포함한 담당자·후임 공백, 역할 준비 부족, 반복 지연, 시작하지 않았거나 전달·수락이 남은 바통과 담당 기간 사이 공백을 서버 `Clock`과 시즌 시간대로 계산하고 이유와 다음 행동을 함께 반환한다. 프런트엔드는 구성원의 `deactivatedAt`을 새 담당자·작성자 선택 가능성에, 루틴 정의와 기록의 `archivedAt`을 일반 화면과 보관함 구분에, 시즌의 `endedAt`을 읽기 전용 경계에 사용한다. 종료 시즌의 기록은 계속 조회할 수 있지만 일반 콘텐츠 변경은 서버와 UI에서 모두 막는다. 열린 워크스페이스는 전경에서 10초마다 최신 내용을 확인하고 창 포커스·네트워크 복구 때 즉시 다시 조회하며, 마지막 화면 갱신 시각과 수동 새로고침을 제공한다. 일시적인 재조회 실패에는 기존 내용을 유지하지만 접근 키가 폐기된 `403`은 접근 오류 화면으로 전환한다. 연결 실패, 응답 지연과 해석할 수 없는 서버 응답은 각각 재시도할 수 있는 한국어 안내로 표시하고, 서버 5xx 오류에는 운영자에게 전달할 요청 ID를 함께 보여 준다. 서버의 커넥션 획득·행 잠금·쿼리에 전파되는 트랜잭션 제한은 프런트의 10초 요청 시간 초과보다 짧은 순서로 두어 브라우저가 결과를 포기한 뒤 변경이 늦게 반영될 가능성을 줄인다. 오늘 날짜, 시즌 진행률, 종료 안내와 실제 마감은 브라우저 위치와 관계없이 시즌의 IANA 시간대를 기준으로 계산한다. 기존·최초 시즌의 기본값은 `Asia/Seoul`이며 다음 시즌은 원본 시간대를 이어 받되 회차 일정은 새로 설정한다. 브라우저에는 팀별 공유 접근 키, 최근에 연 워크스페이스의 최소 메타데이터와 응답 유실 복구용 워크스페이스·콘텐츠·다음 시즌 생성 및 키 회전 멱등 정보만 보관한다. 워크스페이스·콘텐츠·다음 시즌 생성과 키 회전은 복구용 멱등 정보를 브라우저 저장소에 예외 없이 기록한 뒤에만 서버로 전송한다. 생성 요청은 같은 브라우저의 탭 사이에서 진행 중 요청을 직렬화하고, 경쟁한 탭은 요청을 보내지 않은 채 먼저 시작한 탭의 결과 확인을 안내한다. 온보딩은 완료 여부를 확인하지 못한 생성 요청을 목록으로 보여 주고 저장된 입력과 같은 멱등 키로 결과를 다시 확인하며, 기존 공유 링크로 결과를 확인한 요청은 경고 뒤 개별 복구 기록만 폐기할 수 있다. 공유 링크를 받은 구성원은 같은 워크스페이스를 함께 사용하며, 잘못된 새 링크가 기존의 정상 접근 키를 덮어쓰지 않는다. 공유 키는 소규모 파일럿의 워크스페이스 접근 권한으로 남고, 공급자 중립 계정과 서버 세션은 로그인 신원, 기존 구성원 연결과 ROUND 참여권에 사용한다. 공유 키를 대체할 초대·세부 권한 모델은 아직 결정하지 않았으며, 기존 역할 바통의 구성원 확인도 공유 키 안의 명의 선언이므로 실제 로그인 신원을 인증한 감사 증거는 아니다.

멱등 저널의 실패는 같은 요청 재확인, 새 요청 가능, 기존 결과 확인 후 새 요청 가능으로 구분한다. 결정적 종료와 콘텐츠 생성·접근 키 변경 성공 뒤에는 저장된 스냅샷 삭제를 시도하고, Web Storage가 예외를 던져 삭제하지 못하면 새 멱등 키 요청으로 넘어가기 전에 완료 기록 정리를 요구한다. 온보딩 복구 스냅샷이 다른 탭에서 바뀌거나 동일 재처리 결과가 만료된 경우에도 기존 결과 확인 없이 새 요청으로 자동 전환하지 않는다. 접근 키 변경도 같은 팀의 다른 탭과 저널 생성부터 서버 결과 확인·정리까지 직렬화하며, 이 안전 잠금을 지원하지 않는 브라우저에서는 회전을 시작하지 않는다.

### 백엔드 MVP

6모듈 Spring Boot 애플리케이션과 다음 최소 기반이 있다.

- `GET /api/v1/system/status`
- 멱등한 팀·시즌·구성원 온보딩과 공유 키 발급
- 응답이 유실되어도 중복 저장 없이 재시도할 수 있는 기존 팀 구성원·역할·루틴·회차·결정·바통 항목·역할 자료 생성 API와 구성원 이름·활동 상태, 역할·루틴·회차·자료·결정·바통 항목 수정 API
- 역할과 분리된 `PREPARING → TRANSFERRED → ACCEPTED` 또는 `CANCELLED` 이력, 전달 준비도 스냅샷·경고 확인과 수락 시 역할 담당자·기간을 원자적으로 바꾸는 역할 바통 API
- 루틴 정의·회차·결정·바통 항목의 영구 삭제 없는 가역 보관·복원 API
- 활성 루틴 정의와 실제 마감만 스냅샷하는 수동·자동 시즌 회차, 보관 전 실행을 유지하는 회차별 독립 실행과 예정·진행·지연·완료 상태 API
- 현재 역할·회차·바통 기록에서 이유와 다음 행동을 계산하는 조직 연속성 레이더 프로젝션
- 시즌 IANA 시간대, 주간·격주 단일 일정, `0..30`일 선행 생성과 중복 없는 스케줄러 재실행, 활성 루틴이 없을 때 발생 커서만 전진하고 빈 자동 회차를 만들지 않는 처리
- 시즌 이름·기간 수정, 명시적 종료·재개와 선택한 역할·활성 루틴 정의를 새 UUID 스냅샷으로 복사하는 멱등한 다음 시즌 시작 API
- 멱등한 공유 키 회전과 별도 파일럿 복구 키를 이용한 분실 복구
- MySQL 영속화와 Flyway 마이그레이션
- 역할 자료·시즌 트랜잭션과 함께 저장하는 WATCH 모니터 아웃박스, 커밋 이후 전용 스케줄러의 임대·재시도 전달, 시작 시 운영 실패 복구와 동시 변경을 되돌리지 않는 수렴형 조정 기반
- 별도 Bearer로 보호한 `POST /api/v1/internal/resource-health-events`, 이벤트 ID별 원자적 불변 인박스와 신규·동일 재전송의 `202` 접수증, 같은 ID의 다른 봉투 `409` 처리
- 애플리케이션 경계의 공유 키 검증, 원문 키 비저장과 구성원·역할·루틴 정의·시즌 회차·회차 실행·역할 자료·결정·바통 항목·역할 바통의 겹친 수정 충돌 처리
- 공통 `ErrorResponse`, MVC 입력 오류와 안전한 내부 오류 처리
- 모든 제품 API 응답의 서버 생성 `X-Request-ID`와 Spring·Caddy 경계별 5xx 로그 상관관계
- 공유 키 제품 API와 명시적 공개 경로에는 기본 거부를 적용하고, 계정 인증에는 동일 출처 서버 세션과 CSRF를 적용하는 Spring Security 경계
- Google OIDC·Naver OAuth2·자체 이메일 검증을 공급자 중립 계정으로 수용하는 동일 출처 서버 세션과 CSRF 경계
- 기존 구성원을 계정에 명시적으로 연결하고 팀별 현재 연결을 조회하는 전환 계약, ROUND 방 매핑과 짧은 수명의 RS256 참여권·공개 JWK
- MySQL과 Flyway 설정
- Actuator 상태·정보·Prometheus 엔드포인트
- ArchUnit 모듈 경계 테스트
- Spring REST Docs 계약 테스트와 OpenAPI·프런트 타입 자동 생성

현재 파일럿은 계정 세션과 기존 워크스페이스 접근 권한을 분리한다. 로그인 신원·기존 구성원 연결·ROUND 참여권은 계정 세션으로, 기존 제품 경로는 애플리케이션의 공유 키 검증으로, WATCH 내부 이벤트 경로는 별도 Bearer로 보호한다. 계정 초대·세부 권한과 공유 키 폐기 방식은 아직 결정하지 않았다. 첫 파일럿 배포는 Docker Compose와 Caddy를 사용하는 단일 호스트 동일 출처 HTTPS 구성을 제공하지만, 장기 운영 공급자와 확장 토폴로지는 아직 결정하지 않았다.

### 연관 마이크로서비스 경계

BATON 본체는 조직·시즌·역할·운영 기록과 최종 접근 권한을 소유한다. 다음 서비스는 각각 독립 저장소·런타임·배포 단위를 유지하며 현재 BATON 본체와의 운영 연동은 아직 완료되지 않았다.

- `BATON RELAY`: BATON 이벤트의 영속 수신·중복 제거, 구독·채널 연결과 전달 작업 생명주기를 소유한다. 현재 인박스·중복 제거·구독·연결 영속화와 전달 작업 생성까지 구현됐고, 실제 채널 공급자 호출과 재시도 전달 작업자는 아직 구현되지 않았다. BATON 본체는 이 전달 기능을 중복 구현하지 않는다.
- `BATON WATCH`: 역할 자료 URL 스냅샷의 비동기 상태 점검, SSRF 방어, 임대·시도·결과·현재 건강 상태와 상태 변경 이벤트 전달을 소유한다. BATON은 감시 적격 자료 변경과 시즌 생명주기를 불변 트랜잭셔널 아웃박스에 기록하고 기능을 활성화한 뒤 커밋 이후 WATCH 모니터로 전달·재조정한다. WATCH가 최소 한 번 전달 방식으로 보낸 이벤트는 별도 인증의 트랜잭셔널 인박스에 원자적으로 수신하지만, 실제 공개 스테이징의 WATCH→BATON 전달·동일 재전송과 운영 활성화, BATON 상태 프로젝션·UI는 아직 완료하지 않았다.
- `ROUND`: WebRTC 방·피어·시그널링과 TURN 자격 증명 발급을 소유한다. BATON은 AccountMembership과 서버 권위 방 매핑을 바탕으로 짧은 수명의 참여권을 발급한다. 선택 실행 교차 서비스 테스트는 실제 BATON 서명자와 ROUND `bootJar` 사이의 발급자·단일 수신자·JWK 회전과 TURN·WebSocket 방 경계를 검증한다. 기본 전 구간 테스트는 테스트 전용 자체 이메일 계정의 실제 브라우저 로컬 세션에서 기존 구성원을 연결하고 방 매핑·참여권 쿠키·공개 JWK와 서명까지 검증한다. 별도 선택 실행 경계 테스트는 로컬 사설 CA의 테스트 전용 Caddy와 기존 ROUND 웹·시그널링 이미지를 연결해 같은 브라우저의 `Secure` 쿠키로 TURN 자격 증명을 받고 WSS 방에 입장하는 공개 경로를 검증한다. 프로덕션 Caddy·Compose에는 선택 실행 런타임과 자격 증명 최소 전달 경계를 반영했으며, 실제 릴리스 다이제스트·외부 coturn을 사용한 공개 스테이징 검증은 남아 있다.
- `BATON GO`: 공개 링크 코드의 시간·폐기와 BATON·ROUND 신뢰 대상 라우팅을 소유한다. 워크스페이스와 방의 최종 접근 권한은 각 소유 서비스가 계속 판단한다.

서비스끼리 영속 저장소나 JPA 엔티티를 공유하지 않는다. WATCH 첫 양방향 연동 계약은 PRD-0004,
ADR-0015와 ADR-0016에 채택했다. CAL은 불변 안정 계약 `1.0.0`, 회차·마감 생산자 직렬화와 원본 변경
트랜잭션의 불변 아웃박스 적재, 임대 기반 HTTP 전달과 기존 데이터 보정까지 구현했다. 운영 활성화 전에는
PRD-0006 순서에 따라 캡처·보정을 먼저 확인하고 연동 지표와 CAL 실제 피드를 점검한 뒤 전달을 켠다.

## 기술 스택

### 백엔드

- Java 21
- Spring Boot 4.0.7
- Gradle Wrapper 9.2.1, Groovy DSL
- Spring MVC, Spring Validation, Spring Security
- Spring Data JPA
- MySQL 8, Flyway
- Actuator, Micrometer Prometheus
- JUnit Platform, Testcontainers, ArchUnit, Spring REST Docs, restdocs-api-spec 0.20.1

### 프런트엔드

- Node.js 22
- React 19
- TypeScript 5.7, 엄격 모드
- Vite 6
- React Router 7
- TanStack React Query 5
- SCSS
- Playwright
- openapi-typescript 7.13.0

라우트와 QueryClient, 공용 API 클라이언트와 오류 모델을 사용해 팀·시즌 범위의 서버 프로젝션과 변경을 처리한다. 기존 `localStorage` 데모 데이터 경로는 제거했다.

## 저장소 구조

| 경로 | 책임 |
| --- | --- |
| `domain/` | 엔티티, 값 객체, 정책, 도메인 예외와 핵심 규칙 |
| `application/` | 유스케이스, 서비스, 트랜잭션과 `port.in`/`port.out` |
| `adapter-in-web/` | HTTP 컨트롤러, 요청·응답, 검증, 예외 처리와 웹 보안 |
| `adapter-out-persistence/` | JPA 저장소와 JDBC 영속성 어댑터 |
| `adapter-out-external/` | 외부 HTTP와 향후 외부 서비스 어댑터 |
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

백엔드를 다른 포트에서 실행했다면 프런트 프록시 대상도 맞춘다.

```bash
BATON_API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev
```

### 첫 파일럿 시작

1. `http://127.0.0.1:3000`에서 팀 이름, 시즌 기간과 구성원을 입력한다. 이름이 같은 구성원은 구분할 별칭을 붙인다. 서버에 생성 코드가 설정되어 있으면 파일럿 생성 코드도 입력한다.
2. 생성된 작업 공간의 역할 화면에서 구성원을 추가하고 이름·활동 상태를 관리한다. 활동 종료 구성원은 기존 역할·결정에 남지만 새 담당자와 작성자 선택에서는 제외된다. 역할과 반복 루틴·실제 마감을 등록·수정하며, 역할 상세에 함께 사용할 문서 링크를 연결한다. 오늘 화면의 조직 연속성 레이더에서 담당자·후임 공백, 준비 부족과 반복 지연의 이유와 다음 행동을 확인한다. 운영 화면에서 시즌 시간대와 주간·격주 일정을 켜 회차를 미리 자동 생성하거나 필요할 때 수동 회차를 만든다. 수동 회차는 이름·날짜를 정정하고 모든 활성 회차의 루틴 실행을 완료 처리한다. 더 이상 반복하지 않는 루틴 정의는 보관함으로 옮겨 새 수동·자동 회차와 다음 시즌에서 제외하되, 보관 전 회차의 실행은 계속 완료 처리한다. 정의를 복원하면 다음 회차부터 다시 포함된다. 모든 정의를 보관한 동안 자동 일정은 빈 회차를 만들지 않고 발생 커서를 전진시키며 화면은 `활성 루틴 대기 중`으로 안내한다. 현재 운영에서 치울 회차·결정·바통 항목은 보관했다가 필요할 때 기존 실행 기록과 상태 그대로 복원한다. 탐색 화면에서는 결정의 이유와 관련 역할, 바통 항목과 자료를 검색하고 역할·상태·시즌 시간대 기준 기간으로 좁힌다. 역할을 교대할 때는 바통 화면에서 다음 담당자와 담당 기간을 정해 준비하고, 누락 경고를 확인해 전달한 뒤 다음 담당자 명의로 수락한다. 시즌이 끝나면 열린 역할 바통을 먼저 수락하거나 취소하고 상단 시즌 전환에서 다음 시즌을 시작해 이어 갈 역할과 활성 루틴만 고른다. 담당자·담당 기간, 회차 일정과 실행 상태는 새 시즌에서 다시 정하며, 과거 시즌은 읽기 전용으로 계속 조회하고 해당 시즌 안에서 탐색한다.
3. 사이드바 또는 모바일 상단의 공유 기능으로 링크를 복사해 스터디 구성원에게 전달한다.
4. 공유 링크의 접근 키는 해당 워크스페이스의 읽기·쓰기 권한과 같으므로 공개 채널에 게시하지 않는다.

접근 키 원문은 워크스페이스 생성·키 회전·복구의 최초 응답과 동일 멱등 요청의 응답 유실 복구 때만 반환되며 서버에는 SHA-256 해시만 저장된다. 기존 팀 구성원·역할·루틴·회차·결정·바통 항목·역할 자료 생성과 역할 바통 준비도 응답을 받지 못하면 브라우저에 보관한 동일 멱등 키로 재시도해 이미 만들어진 항목을 되찾고 중복을 만들지 않는다. 회차 생성 재시도는 최초 회차와 실행 스냅샷의 식별자를 유지하면서, 이후 정정·보관 또는 완료 변경이 있었다면 그 현재 표현을 돌려준다. 자료 URL은 사용자 정보가 없는 `http` 또는 `https` 전체 주소만 허용하며 BATON 서버가 링크 대상의 내용이나 신뢰성을 확인하지 않는다. 키가 외부에 알려졌다면 워크스페이스의 `키 관리`에서 회전하고 새 공유 링크를 다시 전달한다. 브라우저 저장소가 차단되어 복구용 멱등 키를 안전하게 보관할 수 없으면 워크스페이스·콘텐츠 생성과 키 회전을 시작하지 않는다. 탭 사이의 생성 요청 직렬화를 지원하지 않는 브라우저에서는 워크스페이스와 콘텐츠 생성을 시작하지 않는다. 일반 브라우징 모드에서 사이트 저장소를 허용하고 최신 브라우저를 사용해야 한다. 서버는 이미 사용한 키 변경 멱등 해시를 기억해 더 최신 변경 뒤 폐기된 링크가 과거 요청으로 되살아나지 않게 한다. 모든 구성원이 키를 잃었다면 운영자가 고엔트로피 멱등 키를 생성해 아래 복구 API로 기존 키를 폐기하고 새 키를 발급한다. 응답을 받지 못했다면 멱등 키를 바꾸지 않고 같은 요청으로 재시도한다.

```bash
curl -X POST \
  "https://baton.example.com/api/v1/teams/<team-id>/seasons/<season-id>/access-key/recover" \
  -H "Idempotency-Key: <32~200자의 고엔트로피 값>" \
  -H "X-Baton-Recovery-Key: <프로덕션 복구 키>"
```

## 첫 파일럿 운영 배포

첫 파일럿은 한 호스트에서 Caddy가 정적 프런트엔드와 `/api`를 같은 HTTPS 출처로 제공하고, Spring 애플리케이션과 MySQL은 Docker 내부 네트워크에서만 통신한다. 기본 배포는 [ADR-0003](docs/ADR/0003_pilot-self-hosted-deployment/adr.md), 선택 실행 ROUND 런타임과 외부 coturn 경계는 [ADR-0018](docs/ADR/0018_round-production-runtime/adr.md)에 기록한다.

### 준비와 기동

1. 공개 호스트의 A/AAAA DNS를 배포 서버로 연결하고 80/TCP, 443/TCP·UDP를 허용한다. Cloudflare DNS를 쓰는 첫 파일럿은 레코드를 `DNS only`로 둔다. 주황색 프록시를 켜려면 Cloudflare 공식 IP 대역만 신뢰하는 클라이언트 IP 복원과 원본 직접 접근 차단을 함께 구성해야 하며, 그렇지 않으면 인증 요청률 제한이 사용자 대신 Cloudflare 경계 IP를 본다.
2. 예시 설정을 복사한 뒤 호스트·DB 식별자를 실제 값으로 바꾸고 기본 네 비밀값을 서로 다른 고엔트로피 값으로 생성한다. WATCH 방향별 연동을 활성화하면 각 전용 토큰도 기존 비밀값과 모두 다르게 생성한다.
3. 첫 사전점검 전에 소유자 전용 비밀·상태 디렉터리, 생명주기 잠금과 항상 필요한 이메일 아웃박스 암호화 키를 만든다. 계정 인증이나 ROUND를 활성화할 때는 해당 원문 비밀도 이 디렉터리에 만들고 절대 경로만 환경 설정에 기록한다.
4. 준비가 끝난 같은 설정 파일로 사전점검을 통과한 뒤 프로덕션 Compose를 빌드하고 기동한다.

```bash
command -v git
command -v openssl
command -v docker
docker compose version
cp .env.production.example .env.production
chmod 600 .env.production
sudo install -d -m 0700 -o "$USER" -g "$(id -gn)" /srv/baton/secrets
sudo install -d -m 0700 -o "$USER" -g "$(id -gn)" /srv/baton/state
install -m 0600 /dev/null /srv/baton/state/production-lifecycle.lock
umask 077
openssl rand -base64 32 | tr -d '\n' \
  > /srv/baton/secrets/email-outbox-encryption-key.base64
# 기본 네 비밀값과 활성화할 WATCH 방향별 토큰은 이 명령을 각각 다시 실행해 독립적으로 생성한다.
openssl rand -hex 32
# .env.production의 호스트, DB 식별자, 기본 비밀값과 사용할 기능 설정을 채운다.
./ops/preflight-production.sh
./ops/production-compose.sh up -d --build
./ops/production-compose.sh ps
```

운영 환경 설정은 주석과 검증기가 허용한 단순한 `KEY=VALUE`만 사용한다. 따옴표, 공백, `$` 보간과 포트 게시 재정의를 넣지 않는다. 공통 검증기는 파일이 현재 사용자 소유의 일반 파일이고 그룹·기타 권한이나 Git 추적이 없는지, 공개 DNS 형식과 DB 식별자, 32~200자의 서로 다른 URL 안전 비밀값을 검사한다. `preflight-production.sh`는 이 검증에 Docker 데몬·Compose v2와 최종 Compose 조립 확인을 더한다. ROUND 런타임이 활성화되면 정확한 다이제스트 이미지를 가져와 두 이미지의 릴리스 리비전·태그 객체가 일치하고 웹 이미지가 BATON 모드인지도 미리 확인한다. 실제 `up`, `create`, `pull` 경계에서는 `production-compose.sh`가 생명주기 잠금을 잡은 뒤 잠금 디렉터리의 보호된 `0600` 스냅샷으로 환경 설정을 동결·재검증하고 같은 이미지 가져오기·증명을 다시 통과시킨다. 증명과 Compose는 이 스냅샷만 사용하고 종료 시 제거하므로 사전점검 결과나 실행 중 바뀐 원본 환경 설정을 다시 읽지 않는다. DNS가 실제 호스트를 가리키는지, 외부 80/443 접근, 공인 인증서 발급과 호스트 디스크 여유까지 증명하지는 않는다.

`production-compose.sh`는 모든 명령 직전에 같은 환경 설정 검증기를 다시 실행하고, 현재 셸의 충돌 가능한 배포·Compose 경계 변수를 명시적으로 제거하며, `baton-production` 프로젝트와 저장소의 프로덕션 Compose를 고정한다. Docker 엔드포인트도 환경이나 현재 컨텍스트가 아니라 Linux 로컬 `unix:///var/run/docker.sock`으로 고정한다. 서비스 생명주기를 바꾸거나 프로덕션 이미지를 가져오는 명령은 환경 설정·체크아웃·호출 UID와 무관하게 미리 준비한 `/srv/baton/state/production-lifecycle.lock` 아이노드의 `flock`을 잡고 `restore.sh`와 상호 배제한다. 잠금 상위 디렉터리와 파일은 운영 사용자만 접근하도록 각각 `0700`, `0600`이어야 한다. `up`과 `create`는 호출자가 일부 서비스만 지정해도 현재 게이트의 전체 서비스 집합을 `--remove-orphans`와 함께 수렴시킨다. 반대로 설정·게이트·오버레이를 재조립하지 않는 `start`, `restart`, `pause`, `unpause`, `watch`, `up --watch`와 대화형 메뉴는 거부하고 `COMPOSE_MENU=false`로 고정한다. 독립 실행 `build`도 허용하지 않으며 배포 빌드는 전체 수렴 경계인 `up --build`로만 수행한다. 운영에 필요한 Compose 명령만 명시적 허용 목록으로 허용하며 `run`, `attach`, 모델 변환, 이미지 게시, 확장, 데이터 볼륨 삭제, 호출자의 파일·프로필·프로젝트 변경과 고아 컨테이너·재생성 우회 옵션도 거부한다. `config`는 비밀값을 출력하지 않는 정확한 `--quiet`만 허용한다. 따라서 사전점검 뒤 환경 설정의 내용·권한·Git 추적 상태가 잘못 바뀌면 다음 Compose 명령이 안전하게 실패한다. 다른 절대 경로의 환경 설정을 쓸 때는 `./ops/preflight-production.sh /absolute/path/to/env`로 먼저 검사하고, 모든 Compose 명령에 `BATON_PRODUCTION_ENV_FILE=/absolute/path/to/env`를 지정한다. `BATON_HOST`, DB 사용자·비밀번호, `BATON_WORKSPACE_CREATION_KEY`와 `BATON_WORKSPACE_RECOVERY_KEY`가 빠지면 프로덕션 Compose는 설정 단계에서 실패한다. Compose를 거치지 않고 직접 실행해도 설정한 두 운영 비밀은 32~200자의 URL 안전 ASCII여야 하며, `production`에서는 두 값이 모두 있고 서로 달라야 애플리케이션이 시작된다. 프로덕션 프로젝트 이름과 DB 볼륨은 `baton-production`으로 고정되어 로컬 Compose 데이터와 섞이지 않는다. MySQL은 호스트 포트를 열지 않고 애플리케이션과 내부 TLS로 통신한다.

### 계정 인증과 ROUND 운영 설정

`.env.production`에는 Google·Naver 클라이언트 ID, SMTP 호스트·사용자 이름, JWK `kid` 같은 공개 설정과 비밀 파일 경로만 둔다. `ops/validate-production-auth-secrets.sh`는 OAuth 두 공급자가 함께 완성됐는지, 자체 가입 게이트가 열린 경우 STARTTLS SMTP 설정이 완전한지, 단일 값 비밀이 줄바꿈 없는 소유자 전용 파일인지, 이메일 아웃박스 키가 표준 Base64로 정확히 32바이트인지, ROUND RSA 키가 2048비트 이상이며 비공개·공개 쌍이 일치하는지를 확인한다. 아웃박스 키는 가입 기능을 닫은 프로덕션에서도 항상 필요하며 재시작·배포 뒤에도 같은 값을 유지한다. 별도 비밀번호 관리자나 복구 매체에 함께 보관하고 미발송 아웃박스가 남은 상태에서 임의 교체하지 않는다. 비밀 상위 디렉터리는 `0700`, 각 파일은 `0600` 또는 더 엄격하게 두고 저장소 밖에 둔다. BATON 애플리케이션의 단일 원문 값은 래퍼가 짧게 환경 기반 Compose 비밀 소스로 전달하고 컨테이너에는 UID/GID 10001의 파일로 재구성한다. ROUND TURN 원문은 환경에 복사하지 않고 검증한 호스트 파일을 파일 기반 비밀의 읽기 전용 바인드로 직접 마운트하며, 래퍼가 두 ROUND 컨테이너의 비루트 UID/GID를 해당 파일 소유자와 일치시킨다. 로컬 Compose의 파일 소스는 별도 `0400` 파일을 구체화하지 않으므로 컨테이너에서도 호스트의 소유자 전용 모드를 그대로 사용한다. 단일 값은 Spring 설정 트리에서 읽고 ROUND 비공개 키는 `/run/baton-keys` 밖으로 전달하지 않는다. 원문을 `.env.production`에 복사하거나 `docker compose`를 래퍼 없이 직접 실행하지 않는다. 다음 명령에서는 실제로 활성화할 ROUND 기능에 해당하는 비밀만 생성한다.

```bash
umask 077
# ROUND 런타임을 활성화할 때 외부 coturn에도 같은 값을 안전하게 전달한다.
openssl rand -hex 32 | tr -d '\n' \
  > /srv/baton/secrets/round-turn-shared-secret
chmod 0600 /srv/baton/secrets/round-turn-shared-secret
# ROUND 참여권을 활성화할 때만 서명 키 쌍을 만든다.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
  -out /srv/baton/secrets/round-current-private.pem
openssl pkey -in /srv/baton/secrets/round-current-private.pem -pubout \
  -out /srv/baton/secrets/round-current-public.pem
chmod 0600 \
  /srv/baton/secrets/round-current-private.pem \
  /srv/baton/secrets/round-current-public.pem
```

Google 리디렉션 URI는 `https://<BATON_HOST>/login/oauth2/code/google`, Naver 콜백은 `https://<BATON_HOST>/login/oauth2/code/naver`로 공급자 콘솔에 정확히 등록한다. 두 공급자를 모두 준비한 뒤 `BATON_AUTH_OAUTH2_ENABLED=true`로 바꾼다. 자체 이메일은 `delivery=smtp` 상태에서 시작 시 SMTP 연결을 먼저 검증하고 마지막에 `BATON_AUTH_LOCAL_REGISTRATION_ENABLED=true`로 연다. 서버의 인증 기능 응답과 화면은 이 게이트를 그대로 반영하므로, 게이트가 닫힌 동안 기존 이메일 로그인은 유지하면서 새 계정 만들기만 숨긴다. SMTP는 587/TCP, 인증, STARTTLS 필수, 서버 신원 검증과 2초 연결·읽기·쓰기 시간 초과로 고정된다. 실제 수신함에서 프래그먼트 토큰 링크와 비밀번호 설정까지 확인한다.

서버 세션은 30분, 메모리 단일 인스턴스다. 래퍼는 `scale`과 `--scale`을 거부하며 애플리케이션 재시작은 모든 로그인을 종료한다. 다중 복제본 전에 공유 세션 저장소를 먼저 결정한다.

ROUND 런타임은 참여권 서명자와 별도 게이트로 배포한다. 먼저 외부 coturn의 UDP·TCP·TLS 엔드포인트와 같은 64자리 16진수 공유 비밀 사본, `round-baton-web`·`round-signaling` 릴리스의 정확한 다이제스트와 40자 소스 리비전을 준비한다. `.env.production`에 이 값들을 넣고 `BATON_ROUND_RUNTIME_ENABLED=true`, `BATON_ROUND_PARTICIPATION_GRANT_ENABLED=false`로 사전점검과 `up -d --build`를 실행하면 런타임만 비공개로 배포된다. Caddy 외에는 호스트 포트가 없고, 두 ROUND 서비스는 서로 분리된 내부 네트워크에서 Caddy에만 연결된다. 시그널링은 BATON 공개 JWK를 `https://<BATON_HOST>/.well-known/round-participation-jwks.json`으로 읽고 TURN 비밀만 소유자 전용 호스트 파일의 읽기 전용 바인드로 설정 트리에 받으며 BATON RSA 비공개 키·DB·세션 비밀은 받지 않는다.

비공개 배포에서 `./ops/production-compose.sh ps`, 내부 상태와 `/room/<room-id>` 정적 응답을 확인한 뒤 서명 키를 구성하고 참여권 게이트를 연다. Caddy는 갱신을 BATON에 남기고 공개 시그널링·TURN만 ROUND 내부 경로로 재작성하며, 원본 `Cookie` 헤더에 정확한 철자의 `__Secure-round_access`가 하나일 때만 그 쿠키를 ROUND 업스트림에 전달한다. 중복이나 대소문자 변형은 업스트림 전에 `401`·`no-store`로 거부한다. 방 범위의 세 경로는 커밋 고정 Caddy 요청률 제한 모듈로 클라이언트 IP당 1분 120회로 제한된다. 공개 `/actuator/health`는 BATON 애플리케이션·DB만 나타내므로 ROUND 컨테이너 상태와 실제 coturn 할당은 별도 확인한다.

비활성화는 참여권과 런타임 게이트를 함께 닫고 `./ops/production-compose.sh up -d --build`를 다시 실행하는 즉시 차단 절차다. 참여권 게이트를 닫으면 갱신뿐 아니라 공개 JWK도 닫히므로 기존 참여권의 300초 만료를 기다리는 점진적 종료로 해석하지 않는다. 래퍼는 호출자가 일부 서비스만 지정해도 `mysql`, `app`, `web`과 활성 ROUND 서비스를 모두 수렴시키고, 고정 오버레이를 제외한 전환에서는 `--remove-orphans`로 기존 ROUND 컨테이너를 제거한다. `stop`, `down`, `logs`, `ps`는 게이트가 닫힌 뒤에도 이전 오버레이 서비스를 관리할 수 있다. coturn의 공인 IP·3478/5349·중계 포트·TLS 인증서와 실제 할당·미디어 중계는 이 Compose 밖의 별도 ROUND 운영 단위와 외부 탐침이 소유한다.

ROUND 키 회전은 두 번의 명시적 배포로 수행한다. 먼저 이전 키로 계속 서명하면서 새 공개 키를 이전 슬롯에 넣어 JWK에 선게시하고 60초 캐시 갱신보다 길게 기다린다. 그다음 새 비공개·공개 키를 현재 키로, 이전 공개 키를 이전 키로 바꿔 발급을 전환한다. 마지막 이전 참여권 발급 뒤 300초 수명, 60초 시간 편차와 60초 JWK 캐시를 합친 최소 420초가 지난 후에만 이전 키를 제거한다. 각 단계에서 공개 JWK가 예상 두 `kid`만 포함하고 비공개 RSA 필드가 없는지, 갱신으로 받은 쿠키가 TURN·WebSocket 입장까지 같은 `sub`로 동작하는지 확인한다.

기동 뒤에는 서버 자체 확인으로 끝내지 않고, 스터디 구성원의 두 번째 기기에서 HTTPS 공유 링크를 열어 조회와 변경이 같은 데이터에 반영되는지 확인한다.

### 백업과 복구

```bash
./ops/backup.sh
./ops/verify-backup.sh --require-checksum /absolute/path/to/baton-backup.sql.gz
# 아래 systemd 타이머를 사용 중이라면 복구 전에 예약 실행도 멈춘다.
systemctl --user stop baton-backup.timer baton-backup.service
./ops/production-compose.sh stop round-signaling round-web web app
BATON_BACKUP_STATE_DIR=/absolute/path/to/baton-backup-state \
  BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
  ./ops/restore.sh /absolute/path/to/baton-backup.sql.gz
# restore가 출력한 팀별 최신 대표 시즌을 확인한다.
cat /absolute/path/to/baton-backup-state/last-restore-recovery-targets.tsv
./ops/production-compose.sh up -d
# 각 줄의 팀 ID와 시즌 ID에 새 멱등 키를 사용해 운영자 복구 API를 호출한다.
# 응답을 확인할 때까지 같은 restore_idempotency_key를 보관하고, 다음 팀에는 새 값을 만든다.
restore_idempotency_key="$(openssl rand -hex 32)"
curl -X POST \
  "https://<BATON_HOST>/api/v1/teams/<team-id>/seasons/<season-id>/access-key/recover" \
  -H "Idempotency-Key: $restore_idempotency_key" \
  -H "X-Baton-Recovery-Key: <프로덕션 복구 키>"
# 응답의 accessKey로 다음 형식의 새 링크를 만들고 구성원에게 다시 전달한다.
# https://<BATON_HOST>/teams/<team-id>/seasons/<season-id>#accessKey=<new-access-key>
# 기존 링크의 403과 새 링크의 조회·변경을 확인한 뒤 새 보안 상태를 백업한다.
./ops/backup.sh
systemctl --user start baton-backup.service
systemctl --user start baton-backup.timer
```

백업은 기본적으로 `ops/backups/`에 권한이 제한된 고유 이름의 압축 SQL과 필수 SHA-256 보조 파일로 생성된다. `backup.sh`는 gzip과 BATON 핵심 스키마 표식을 확인하고 보조 파일을 먼저 원자적으로 게시한 뒤 덤프 본문을 마지막에 공개하므로, 강제 종료가 다음 예약 주기를 막는 불완전 본문을 남기지 않는다. `restore.sh`는 보조 파일 검증을 자체적으로 강제하고 예약 백업 잠금과 프로덕션 Compose 생명주기 잠금을 모두 잡는다. 또한 애플리케이션·웹과 잔존 ROUND 웹·시그널링 컨테이너가 모두 `exited` 상태가 아니면 요청을 거부하고 대상 DB를 비운 뒤 백업 스냅샷만 복원한다.

복원한 스냅샷의 접근 키 상태는 현재 시점의 폐기 이력을 증명할 수 없으므로 `restore.sh`는 공개 전에 모든 팀의 접근 키 해시를 발급한 적 없는 무작위 값으로 교체한다. 현재 스키마에서는 마지막 키 변경 멱등 표식을 비우고 팀 버전도 함께 올리되, 이미 사용한 키 변경·워크스페이스 생성·콘텐츠 생성 멱등 이력은 과거 요청을 새 요청으로 되살리지 않도록 보존한다. 모든 팀이 무효화됐고 팀마다 복구에 사용할 최신 대표 시즌이 하나씩 있는지 확인한 뒤에만 성공하며, 대상은 권한이 제한된 `last-restore-recovery-targets.tsv`에 기록한다. 따라서 복원 뒤에는 기존 공유 링크가 전부 `403`이 되고, 운영자가 각 팀을 서로 다른 새 멱등 키로 복구해 받은 접근 키로 새 링크를 다시 배포해야 한다. 응답이 유실되면 해당 팀에는 같은 멱등 키로 재시도한다. 스냅샷의 출처나 운영 비밀 노출 여부가 의심되면 `.env.production`의 생성 키와 복구 키도 새 값으로 교체한다.

기존 DB를 교체하고 모든 공유 링크를 폐기하는 작업이므로 복구 직전에도 백업하고, 실제 데이터를 넣기 전 별도 환경에서 복구·팀별 키 재발급·옛 링크 거부까지 리허설한다.

CI의 `production-runtime-smoke.sh`는 실제 운영 데이터를 사용하지 않는 폐기 가능한 MySQL에서 원본 `backup.sh`와 `restore.sh`를 실행한다. 두 팀과 최신 대표 시즌을 스냅샷으로 되돌리고, 모든 과거 키의 `403`, 팀별 운영자 복구와 멱등 동일 재처리, 새 키의 조회·변경, 복구 완료 상태의 재백업까지 자동 검증한다. 같은 Docker 데몬에 `baton-production` 리소스가 있으면 파괴적 리허설을 시작하지 않는다. 이 자동화는 rclone 암호화 원격 저장소 자격, 외부 저장소 다운로드와 별도 호스트 가져오기를 대신하지 않으므로 실제 파일럿 전·월간 별도 환경 리허설은 계속 수행한다.

### 매일 암호화 외부 백업

외부 저장소 공급자는 고정하지 않고 rclone `crypt` 원격 저장소를 사용한다. 일반 공급자 원격 저장소 위에 BATON 전용 경로를 감싼 암호화 원격 저장소를 만들고, 암호화 설정 파일·암호·솔트는 그 원격 저장소와 다른 비밀번호 관리자 또는 오프라인 매체에도 보관한다. 원격 저장소 이름은 환경 변수 재정의를 정확히 검사할 수 있도록 영문·숫자·밑줄만 사용한다(예: `baton_crypt`). rclone 1.64 이상이 필요하며, 일반 원격 저장소이거나 `no_data_encryption=true`인 암호화 원격 저장소를 지정하면 자동화는 업로드 전에 실패한다.

운영 호스트에 `rclone`과 `flock`을 설치한 뒤 절대 경로로 전용 환경 파일을 준비한다.

```bash
mkdir -p ~/.config/baton ~/.config/systemd/user
cp ops/backup.env.example ~/.config/baton/backup.env
chmod 600 ~/.config/baton/backup.env
cp ops/systemd/baton-backup.service ops/systemd/baton-backup.timer ~/.config/systemd/user/
```

`~/.config/baton/backup.env`의 `BATON_REPO_ROOT`, `BATON_PRODUCTION_ENV_FILE`, 백업·상태 디렉터리와 `BATON_RCLONE_REMOTE`를 실제 절대 경로로 바꾼다. 이 Compose 환경 설정 경로는 수동 기동·백업·복구가 공통으로 사용한다. systemd `EnvironmentFile`은 `~`, `$HOME`과 명령 치환을 확장하지 않는다. 상태 디렉터리는 실행 시 `0700`으로 제한되며 예약 백업과 수동 복원이 같은 잠금을 사용한다. OAuth 기반 원격 저장소가 설정 토큰을 갱신할 수 있으므로 rclone 설정은 서비스 사용자만 읽고 쓸 수 있게 `0600`으로 둔다. 설정 자체를 암호화했다면 `RCLONE_PASSWORD_COMMAND`에는 암호를 비대화형으로 출력하는 절대 경로 명령을 지정하고 그 복구 수단도 별도로 보관한다.

```bash
sudo loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemctl --user enable --now baton-backup.timer
systemctl --user start baton-backup.service
systemctl --user list-timers --all baton-backup.timer
journalctl --user -u baton-backup.service -n 100 --no-pager
BATON_BACKUP_STATE_DIR=/absolute/path/to/baton-backup-state ./ops/check-backup-freshness.sh
```

타이머는 매일 `03:15 Asia/Seoul`부터 최대 15분 안에 실행하고, 호스트가 꺼져 놓친 실행은 다음 기동 뒤 보충한다. 실패한 서비스는 15분 간격으로 다시 시작하되 시작률을 1시간에 네 번으로 제한한다. 주기는 커널 `flock`으로 겹침을 막아 프로세스 종료 뒤 오래된 잠금이 남지 않는다.

각 주기는 이전에 업로드하지 못한 로컬 백업을 먼저 재시도하되 이 단계만으로 최신성이나 로컬 보존 상태를 바꾸지 않고, 이어서 새 덤프를 만든다. 모든 덤프와 필수 SHA-256 보조 파일을 `--immutable`로 암호화 원격 저장소에 게시한 뒤, 같은 원격 저장소를 통해 본문과 보조 파일을 다시 읽어 해시를 비교한다. 완전히 검증한 파일에는 로컬 완료 표식을 남겨 이후 주기에는 다시 전송하지 않는다. 새 스냅샷까지 외부 검증된 뒤 상태 파일에 체크섬이 이름과 결합한 UTC 스냅샷 시각·이름·해시와 검증 시각을 기록한다. 14일이 지난 로컬 파일은 삭제 직전에 원격 본문과 보조 파일을 다시 검증하고, 최신 세 개는 항상 남긴다. BATON 스크립트는 원격 백업을 삭제하지 않으므로 원격 버전 관리·객체 잠금·생명주기는 공급자에서 별도로 설정한다.

외부 백업을 복구할 때는 덤프와 같은 이름의 `.sha256`을 함께 암호화 원격 저장소에서 내려받고 먼저 검증한다.

```bash
rclone copyto baton_crypt:daily/baton-YYYYMMDDTHHMMSSZ-id.sql.gz /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz
rclone copyto baton_crypt:daily/baton-YYYYMMDDTHHMMSSZ-id.sql.gz.sha256 /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz.sha256
./ops/verify-backup.sh --require-checksum /secure/path/baton-YYYYMMDDTHHMMSSZ-id.sql.gz
```

최신성은 마지막 작업 시각이 아니라 외부에서 검증된 최신 DB 스냅샷의 나이를 본다. 그래도 실제 가져오기 성공을 뜻하지는 않으므로 월 1회 다른 환경에서 복원 리허설을 수행해 rclone 복호화 자격, 다운로드와 MySQL 가져오기까지 함께 검증한다.

### 서비스와 백업 상태 감지

호스트 로컬 점검은 서비스와 백업의 주기가 다르므로 별도 타이머로 운영한다. 범위가 좁은 모니터 환경 파일에는 저장소·백업 상태의 절대 경로, 공개 상태 URL과 시간 초과·최신성 기준만 넣고 rclone 자격이나 운영 비밀은 넣지 않는다. 호스트에 `curl`이 있어야 하며 예시를 복사한 뒤 세 자리표시자를 실제 값으로 바꾼다.

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

서비스 점검은 5분마다 공개 `https://.../actuator/health`를 리디렉션 없이 기본 CA 검증과 TLS 1.2 이상으로 호출한다. HTTP 200의 종합 `UP` 응답이어야 성공하므로 DNS, 공인 TLS, Caddy, Spring과 DB 상태 경계를 함께 지난다. 백업 점검은 1시간마다 마지막 암호화 원격 저장소 재읽기 검증 상태를 읽고 파일명 UTC 시각·에포크·검증 시각의 일치와 36시간 이내 최신성을 확인한다. 둘 다 자동 복구나 Compose 재시작은 하지 않고 실패 종료와 로그를 남긴다.

CAL·WATCH 전달 상태는 외부에 공개하지 않는 애플리케이션 컨테이너의 Prometheus 지표로 확인한다. 다음 명령은 검증된 프로덕션 Compose 경계 안에서 해당 지표만 읽는다.

```bash
./ops/show-integration-metrics.sh
```

`baton_integration_delivery_items`는 `integration=calendar|watch`, `status=pending|processing|failed`별 현재 아웃박스 항목 수를, `baton_integration_delivery_oldest_pending_age_seconds`는 가장 오래된 대기 시간, `baton_integration_delivery_last_success_time_seconds`는 마지막 전달 완료 시각을 나타낸다. WATCH 인박스는 아직 처리 완료 상태를 소유하지 않으므로 `baton_integration_watch_inbox_items`와 마지막 접수 시각만 제공한다. `baton_integration_metrics_refresh_success`가 `0`이면 나머지 값은 마지막 정상 갱신 스냅샷이며, 지표는 최대 30초 간격으로 갱신된다. 실패 행의 자동 재처리나 삭제는 이 명령이 수행하지 않는다.

이 타이머들은 같은 호스트에서 실행되므로 전원·커널·전체 네트워크 장애 때 검사와 로그도 함께 멈추며 알림을 보내지 않는다. 첫 외부 관측 경계로 기본 비활성화된 GitHub Actions `외부 상태 감시`를 제공한다. 실제 배포와 워크플로가 `main`에 반영된 뒤 공개 URL을 저장소 변수에 넣고 수동 실행이 성공하는지 먼저 확인한 다음 예약 검사를 켠다.

```bash
gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body false
gh variable set BATON_HEALTH_URL --body 'https://study.example.com/actuator/health'
gh workflow run external-health.yml
gh run list --workflow external-health.yml --event workflow_dispatch --limit 1
gh run watch "$(gh run list --workflow external-health.yml --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body true
```

`gh run list`에 방금 요청한 실행이 나타난 뒤 `watch`를 실행한다. 수동 실행에서 다른 URL을 일회성으로 확인하려면 `gh workflow run external-health.yml -f health_url=https://study.example.com/actuator/health`를 사용한다. 예약 검사는 비공개 저장소 실행기 사용량을 제한하기 위해 매시 17분에 한 번만 실행하며, `BATON_EXTERNAL_MONITOR_ENABLED`가 정확히 `true`일 때만 실행기를 시작한다. 각 실행은 순간적인 외부 네트워크 실패를 걸러내려고 30초 간격으로 최대 두 번 확인한다. 중지할 때는 `gh variable set BATON_EXTERNAL_MONITOR_ENABLED --body false`로 되돌린다.

예약 검사를 켜는 계정은 GitHub Actions의 이메일 또는 웹 실패 알림을 활성화하고 첫 예약 실행과 알림 수신 책임자를 확인한다. 예약 알림은 워크플로를 처음 만든 사용자에게 연결되고, 이후 예약 설정을 수정하거나 워크플로를 다시 활성화한 사용자로 바뀔 수 있으므로 개인 한 명에게 영구적인 호출 책임을 고정한 것으로 보지 않는다.

이 센티널은 GitHub 인프라에서 같은 엄격한 HTTPS 상태 검사를 실행하므로 호스트 전체 장애도 관측할 수 있지만, GitHub 예약 실행은 지연되거나 누락될 수 있고 워크플로 자체가 호출·SMS 같은 별도 알림 채널을 보장하지 않는다. 실제 파일럿에서 더 짧은 감지 시간이나 독립적인 호출이 필요하면 다른 네트워크의 가동 시간 공급자를 같은 URL과 알림 채널에 추가한다. NAT 루프백이나 분할 DNS 환경에서는 호스트 로컬 검사만 실패할 수 있으므로 외부 관측과 함께 판단한다. 상태 검사 성공도 프런트 자산, 공유 링크 쓰기와 실기기 동기화까지 증명하지 않으며, 백업 최신성 성공도 현재 원격 객체의 재검증이나 가져오기 성공을 뜻하지 않는다.

## 검증 명령

### 백엔드

```bash
./gradlew --no-daemon :application:policyTest
./gradlew --no-daemon :application:useCaseTest
./gradlew --no-daemon :adapter-in-web:restDocsTest
./gradlew --no-daemon test
./gradlew --no-daemon build
ROUND_REPOSITORY_ROOT=/absolute/path/to/round \
  bash ops/tests/round-consumer-contract.sh
```

- `policyTest`: 모듈 경계, Spring Data 저장소 공개 가시성과 도메인 정책 테스트
- `useCaseTest`: Spring, DB, Flyway와 트랜잭션을 포함하는 통합 흐름 테스트
- `restDocsTest`: 외부 HTTP 계약 테스트
- `build`: 전체 컴파일·테스트와 REST Docs 검증
- `round-consumer-contract.sh`: BATON의 실제 RS256 서명자·JWK를 현재 ROUND 시그널링 `bootJar`에 연결해 올바른 방의 TURN·WebSocket 수락, 다른 방·발급자·수신자·`kid`·만료 참여권 거부, 키 선게시·새 `kid` 즉시 재조회·이전 키 중첩과 반복되는 알 수 없는 `kid`의 JWK 갱신 제한을 검증하는 선택 실행 교차 서비스 테스트

교차 서비스 테스트는 기본 `test`·`build`에 외부 저장소를 암묵적으로 결합하지 않는다. `ROUND_REPOSITORY_ROOT`를 생략하면 BATON과 같은 상위 디렉터리의 `webRTC`를 사용하며, 이미 빌드한 JAR를 재사용하려면 `ROUND_SIGNALING_JAR` 절대 경로만 지정한다. 두 값은 동시에 사용할 수 없고 실행 로그에는 실제 검증한 JAR와 저장소를 사용한 경우 Git 리비전·변경 상태가 남는다. 이 경계는 실제 BATON 서명자와 ROUND의 Nimbus JWK 디코더·키 회전·캐시 누락·갱신 제한·쿠키·방 결속을 검증하며, ROUND의 고정 시각 Nimbus 소스 테스트가 JVM 캐시의 60초 만료와 30초 구간당 소스 접근 상한을 별도로 고정한다. BATON 세션·AccountMembership·공개 Caddy TLS 경로와 실제 SMTP 가입은 포함하지 않는다.

`useCaseTest`는 MySQL 8 Testcontainers에서 멱등한 온보딩과 기존 팀 구성원·시즌·역할·역할 자료·루틴·회차·결정·바통 항목·역할 바통 생성, 구성원 이름·활동 상태와 시즌·루틴 정의·회차·결정·바통 정정·보관·복원, 역할 바통 전달·수락·취소, 다음 시즌 역할·활성 루틴 복사, 활성 정의만 사용하는 수동·자동 회차와 실제 마감 스냅샷·독립 완료 상태, 활성 정의가 없는 자동 발생의 커서 전진과 빈 회차 미생성, 접근 키 회전·운영자 복구, 저장·재조회와 동시 충돌 규칙을 검증한다. 실제 행 잠금이 설정한 제한을 넘으면 애그리거트별 충돌로 실패하고 트랜잭션이 롤백되어 나중에 변경이 반영되지 않는지도 확인한다. 역할 자료는 V5 데이터가 있는 DB를 V6로, 결정·바통 항목은 기존 데이터가 있는 DB를 V7로, 기존 회차는 활성 상태와 버전 `0`을 가진 V8로 올리는 이관을 검증한다. 구성원 생성 마이그레이션은 기존 V8 데이터를 보존하면서 V9의 팀별 이름 유일성과 구성원 멱등 작업 제약을 확인하고, 구성원 생명주기 마이그레이션은 V9의 역할·결정 참조를 보존하면서 V10의 활동 상태와 버전 초기값을 확인한다. 시즌 생명주기 마이그레이션은 기존 다중 시즌의 역할·바통 항목·자료 스냅샷과 참조·멱등 결과를 보존하면서 V11의 시즌·역할·루틴 계보와 활성 시즌·같은 시즌 참조 제약을 확인한다. 회차 자동화 마이그레이션은 V11의 시즌·루틴·회차·실행을 보존하면서 V12의 기본 시간대, `null` 허용 일정·마감과 예정 발생일 유일 제약을 확인한다. 역할 바통 마이그레이션은 V12 데이터를 V13으로 올려 기존 역할·구성원·멱등 기록을 보존하고 역할 바통의 복합 참조, 상태·스냅샷 제약과 역할당 열린 이력 유일성을 확인한다. 기록 탐색 생성 시각 마이그레이션은 V13 데이터를 V14로 올리면서 기존 바통 항목과 역할 자료를 보존하고, 알 수 없는 기존 생성 시각을 `null`로 유지하는지 확인한다.

루틴 정의 보관 마이그레이션은 V14의 정의와 실행 계보를 V15에서도 보존하고 기존 정의를 활성 상태인 `archived_at = null`로 유지하는지 확인한다.

WATCH 아웃박스 마이그레이션은 V15의 역할 자료와 시즌 데이터를 보존하면서 V16에 빈 불변 아웃박스, 소스 리비전, 임대·재시도와 완료·실패 제약을 추가하는지 확인한다. 기존 자료의 모니터 스냅샷은 환경별 소스 이름공간을 마이그레이션에서 추측하지 않고 런타임 수렴형 조정으로 생성한다.

WATCH 상태 이벤트 인박스 마이그레이션은 V16 데이터를 보존하면서 V17에 `RoleResource` FK가 없는 빈 불변 인박스를 추가하는지 확인한다. 수신 통합 테스트는 신규·동일 재전송과 같은 ID의 다른 봉투 충돌을 한 트랜잭션에서 판정하고, 서로 다른 이벤트를 순서와 소스 리비전에 관계없이 모두 보존하며 `changedAt`의 나노초 정밀도를 유지하는지 검증한다.

### API 계약 생성

REST Docs 계약 테스트를 기준으로 [OpenAPI 3.0.1 문서](docs/api/openapi3.yaml)와 `frontend/src/generated/api.ts`를 생성한다. 생성 파일은 직접 수정하지 않는다.

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

- `generateApiContract`: `restDocsTest → 결정적 스니펫 정렬 → OpenAPI 정규화 → openapi-typescript` 전체 흐름을 실행하고 추적할 두 생성 파일을 갱신한다.
- `checkApiContract`: REST Docs에서 다시 만든 OpenAPI를 추적 파일과 바이트 단위로 비교하고, `openapi-typescript --check`로 프런트 생성 타입의 드리프트를 검사한다. OpenAPI 오퍼레이션별 경로·메서드·본문·헤더·상태는 실제 MockMvc REST Docs 계약 테스트와 디스크립터가 소유한다.

Spring Security가 직접 처리하는 로컬 세션·로그아웃은 실제 필터 체인 기반 REST Docs로 생성 OpenAPI에 포함하고, OAuth 시작·콜백 라우트만 실제 필터 체인 보안 통합 테스트를 계약 기준으로 유지한다.

프런트엔드는 생성된 OpenAPI 오퍼레이션 요청·응답·헤더 타입과 `paths`의 URI 템플릿·HTTP 메서드 조합을 기존 기능 파사드에서 사용한다. `apiRequest`, `ApiError`, React Query 키와 멱등 재시도 같은 런타임 정책은 생성하지 않고 기존 코드가 계속 소유한다.

### 프런트엔드

```bash
cd frontend
npm run typecheck
npm run build
npm run e2e:smoke
npm run e2e:operations
npm run e2e:memory
npm run e2e:handoff
npm run e2e:records
npm run e2e:responsive
npm run e2e
npm run e2e:fullstack
ROUND_REPOSITORY_ROOT=/absolute/path/to/round npm run e2e:round-edge
```

- `e2e:smoke`: 온보딩, 접근 키·최근 목록 복구와 핵심 작업 공간 탐색
- `e2e:operations`: 역할·루틴과 실제 마감 수정, 자동 일정 설정, 수동 회차 생성과 회차별 반복 업무 완료 흐름
- `e2e:memory`: 결정과 이유 기록 흐름
- `e2e:handoff`: 역할 자료 생성의 응답 유실 복구, 수정 충돌 최신화, 새 창 열기·재조회, 바통 항목·바통북 미리보기와 역할 바통 준비·경고 확인·전달·수락·새로고침 보존 흐름
- `e2e:records`: 결정·바통·자료 통합 검색, 역할·상태·기간 필터, 시각 미상 처리, 검색 조건 유지와 원본 화면 이동을 데스크톱·390px 모바일에서 확인
- `e2e:responsive`: 390px 모바일 탐색
- `e2e`: 독립 API 픽스처를 사용하는 전체 Playwright 회귀 테스트
- `e2e:fullstack`: 임시 MySQL에서 실제 Spring Boot와 Vite를 띄우고 빈 DB 온보딩, 기존 팀 구성원 추가, 역할 자료, 루틴·회차, 두 브라우저 동기화와 새로고침 후 영속성을 확인한다. 테스트 전용 로컬 계정과 폐기 가능한 RSA 키로 실제 로그인 세션, AccountMembership 연결, 서버 권위 방 매핑, 참여권 쿠키의 속성·RS256 서명·클레임·300초 수명과 공개 JWK를 함께 검증한다. 세션 ID 회전은 실제 Spring Security 필터 체인을 사용하는 `AuthSecurityTest`가 검증한다.
- `e2e:round-edge`: 명시한 ROUND 저장소의 기존 `baton-web-runtime`·`signaling-runtime` 이미지를 테스트 전용 Caddy, 로컬 사설 CA와 임시 MySQL에 연결한다. 실제 HTTPS 브라우저 세션에서 구성원 연결·방 매핑·참여권 재발급을 거쳐 공개 TURN 자격 증명 엔드포인트와 WSS 방 입장, 내부 TURN 경로 비노출을 확인한다. 프록시는 ROUND 업스트림에 참여 쿠키만 전달하고 BATON 세션·`Authorization`·워크스페이스 자격 증명은 제거하도록 구성한다.

Chromium이 설치되어 있지 않으면 먼저 `npm run e2e:install`을 실행한다. `e2e:fullstack`은 Docker, Java 21과 OpenSSL도 필요하며, 고유 Compose 프로젝트와 임시 MySQL 볼륨·RSA 키를 만들었다가 종료 시 함께 제거한다. 합성 로컬 자격 증명은 실행기가 추가한 테스트 전용 Flyway 위치에만 있고 운영 마이그레이션과 기존 로컬·프로덕션 DB에는 들어가지 않는다. 이 명령은 실제 브라우저와 Vite 개발 프록시까지 검증하지만 Caddy, TLS, 프로덕션 이미지와 ROUND TURN·WebSocket 런타임을 대신하지 않는다. 프런트엔드 단위 테스트와 린트 명령은 아직 구성하지 않았다.

`e2e:round-edge`는 시스템 신뢰 저장소나 호스트 파일을 바꾸지 않고 폐기 가능한 CA·종단 인증서와 ROUND JVM 전용 신뢰 저장소를 만든다. 공개 포트는 루프백의 테스트 전용 Caddy 하나뿐이며 종료할 때 컨테이너·네트워크·볼륨과 임시 키를 제거한다. 이 게이트는 TLS 종단, 외부 `/round/rooms/{roomId}` 재작성, HTTPS JWK 조회, TURN 자격 증명 발급과 WSS `room.join`을 검증하지만 공인 DNS·ACME, 프로덕션 이미지·프로덕션 Caddy, 실제 coturn 할당·미디어 중계, 외부 OAuth·SMTP와 배포 키 회전을 대신하지 않는다.

### 운영 구성

```bash
bash ops/check-shell-scripts.sh
bash ops/tests/backup-cycle-test.sh
bash ops/tests/pilot-readiness-test.sh
bash ops/tests/production-runtime-smoke.sh
systemd-analyze verify ops/systemd/baton-backup.service ops/systemd/baton-backup.timer ops/systemd/baton-service-health.service ops/systemd/baton-service-health.timer ops/systemd/baton-backup-freshness.service ops/systemd/baton-backup-freshness.timer
./ops/production-compose.sh config --quiet
./ops/preflight-production.sh
```

`production-runtime-smoke.sh`는 실제 프로덕션 `app`·`web` 이미지와 프로덕션 ROUND 오버레이를 조립한 뒤 고유 Compose 프로젝트와 폐기 가능한 MySQL·Caddy 볼륨을 사용한다. ROUND 이미지만 파일에 부여한 Linux 세분화 권한을 제거한 테스트 소유 Caddy 모의 서버로 바꾸고 프로덕션의 비루트 사용자, 읽기 전용 루트 파일 시스템, 정확한 세분화 권한 집합, 네트워크와 Spring 구성 트리 비밀 마운트를 그대로 검증한다. 실제 ROUND 릴리스 이미지의 다이제스트·레이블 출처는 사전점검과 래퍼의 잠금 내부 배포 경계가, 진입점 호환성은 공개 스테이징 기동이 별도로 확인한다. 먼저 DB 설정이 없는 `app` 이미지가 컨텍스트와 Flyway 구성 전에 전용 오류로 종료되는지 확인하고, Caddy 내부 CA HTTPS, 정적 프런트엔드와 SPA 대체 경로, 상태 검사·제품 API 역방향 프록시와 보안 헤더, 유효한 CI 전용 키를 사용한 프로덕션 프로필 기동, 실행 중인 Flyway·MySQL TLS 연결을 확인한다. ROUND 런타임을 닫은 기본 상태에서는 공개 방 UI·`signal`·TURN과 내부 ROUND 경로가 `404`·`no-store`로 수렴하고, 활성 상태에서는 정확한 재작성·자격 증명 허용 목록·쿠키 중복/대소문자 변형 `401`·미디어/WSS 헤더를 확인한다. 맞춤 Caddy의 방 범위 사전 요청률 제한이 `429`를 반환하는지도 검증한다. 정상 제품 API의 Spring 요청 ID 보존뿐 아니라 Caddy가 직접 만드는 1MB 초과 `413`과 업스트림 중지 `502/503`에도 별도 요청 ID가 있고 같은 ID를 접근 로그에서 찾을 수 있으며 운영 키와 멱등 키는 그 로그에서 제거되는지도 확인한다.

같은 실행에서 원본 백업·복구 스크립트를 격리 경계 안에 복사하고 테스트 전용 Compose 심으로 고유 프로젝트만 연결한다. 실제 `mysqldump`·체크섬·DB 삭제 및 가져오기를 거쳐 백업 이후 센티널 제거, 팀별 최신 대표 시즌 TSV와 `0600` 권한, 최초·회전 키의 `403`, 과거 생성·회전 멱등 동일 재처리 만료, 잘못된 복구 키 거부, 팀별 새 키와 멱등 동일 재처리·팀 간 격리, 새 키의 조회·변경과 재백업을 확인한다. `app`·`web`을 멈춘 뒤 ROUND 웹과 시그널링을 각각 실행해 실제 `restore.sh`가 DB 변경 전 거부하고 센티널을 보존하는지도 검증한다. 심은 실행 토큰, Docker 데몬·컨텍스트, 사용자 정의 레이블, 전용 DB 볼륨·이름과 중지된 `app`·`web`을 매 명령마다 다시 검사한다. 실패 산출물에는 컨테이너 환경 변수를 저장하지 않고 보호 값이 발견된 런타임 로그도 남기지 않는다. 마지막에는 소유 레이블을 확인한 자신만의 컨테이너·네트워크·볼륨·이미지를 제거한다. 컨테이너의 80·443 포트만 `127.0.0.1`의 임시 호스트 포트에 게시하며 `app`과 MySQL 포트는 게시하지 않는다. 호스트에는 Docker, `flock`, OpenSSL이 필요하다.

이 스모크의 로컬 인증서는 TLS 종단을 검증하지만 공인 DNS·ACME 발급과 브라우저 신뢰 체인, 외부 방화벽, HTTP/3, 실제 운영 비밀과 실기기 공유 흐름을 대신하지 않는다. Compose 설정 검증만 실행한 경우에는 환경 변수와 YAML 조립만 확인된다.

### 자동 품질 게이트

GitHub Actions의 `품질 게이트`는 모든 풀 리퀘스트, `main` 푸시와 수동 실행에서 다음 네 경계를 병렬로 검증한다.

- 전체 백엔드 회귀와 API 계약 드리프트: `./gradlew --no-daemon build checkApiContract`
- 프런트 프로덕션 빌드와 독립 API 픽스처 기반 전체 Playwright E2E
- 실제 브라우저, Vite 프록시, Spring Boot, Flyway와 격리된 MySQL을 잇는 파일럿 전 구간 스모크
- 백업 생성·검증·암호화 원격 실패·보존 수명주기, 배포 사전점검·상태 감지, systemd 유닛, 프로덕션 Compose 조립과 `app`·`web` 이미지 빌드·런타임 스모크

네 경계가 모두 성공해야 최종 `contract` 검사가 성공한다. 원격 저장소의 규칙 집합 또는 분기 보호에서 이 검사를 필수로 지정하면 실패한 커밋의 병합을 차단할 수 있다. 이 게이트는 실제 운영 비밀을 사용하거나 이미지를 게시·배포하지 않는다. 프로덕션 이미지의 DB 설정 누락 시 폐쇄형 실패, 로컬 CA TLS 종단, 빈 DB 마이그레이션과 현재 스키마의 복원 키 무효화 SQL은 검증하지만 공인 DNS·ACME·외부 네트워크·실제 운영 데이터 전체 복원과 팀별 새 링크 배포는 배포 후 별도로 확인한다.

## 로컬 설정

- 기본 Spring 프로필: `local`
- 기본 DB: `jdbc:mysql://localhost:3306/baton`
- 로컬 DB 기본 주소와 `baton/password` 계정은 `application-local.yml`에서만 제공한다. `production` 프로필은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 모두 명시하고 MySQL JDBC TLS를 강제하지 않거나 Hikari/JNDI/Flyway 전용 연결 설정으로 검증된 주 DataSource를 우회하면 시작을 거절한다.
- JPA 스키마 정책: `ddl-auto: validate`
- Flyway 위치: `bootstrap/src/main/resources/db/migration`
- 서버 기준 시각: UTC `Clock`
- 시즌 달력·모임·마감 기준: 시즌별 IANA `timeZone`
- 자동 회차 폴링: 기본 `PT1M`, Spring 직접 실행 시 `BATON_ROUND_AUTOMATION_POLL_INTERVAL`로 재정의
- CAL 스냅샷 캡처·기존 데이터 보정·전달: 모두 기본 비활성화다. 전달 작업자는 원본별 이전 미종결
  행보다 다음 행을 먼저 보내지 않고, 한 번에 한 건을 1분 임대로 처리한다. 활성화하려면
  `BATON_CAL_BASE_URL`에 경로가 없는 HTTPS 출처, `BATON_CAL_BEARER_TOKEN`에 32~200자의 URL 안전
  ASCII를 설정한다. 먼저 전달을 끈 채 `BATON_CAL_CAPTURE_ENABLED=true`와
  `BATON_CAL_BACKFILL_ENABLED=true`로 한 번 기동해 보정 완료 로그를 확인한다. 이후 보정은 다시
  `false`로 닫고 캡처를 유지한 채 `BATON_CAL_DELIVERY_ENABLED=true`로 전환한다. 보정은 100개
  페이지와 회차별 짧은 트랜잭션을 사용하며 같은 상태로 재실행해도 새 행을 만들지 않는다.
  기본 연결 시간 제한은 `PT2S`, 읽기 시간 제한은 `PT5S`, 전달 간격은 `PT10S`이며 두 시간 제한의
  합은 45초를 넘을 수 없다. `401`·`403`은 아웃박스를 실패로 확정하지 않고 자격 증명 교체 뒤 같은
  행을 재시도한다. 로컬 교차 서비스 검증은 `./ops/tests/calendar-consumer-contract.sh`로
  CAL 안정 계약 `1.0.0` 컨테이너와 실제 BATON 클라이언트를 연결한다. 운영 활성화 전에는 기존 문자열의
  NFC·제어 문자 적합성을 점검한다.
- WATCH 모니터 동기화: 기본 비활성화. 활성화하려면 `BATON_WATCH_ENABLED=true`, 경로가 없는 HTTPS 출처인 `BATON_WATCH_BASE_URL`, 32~200자의 URL 안전 ASCII인 `BATON_WATCH_BEARER_TOKEN`과 환경마다 고정된 `BATON_WATCH_SOURCE_NAMESPACE`를 설정한다. HTTP 기본 URL은 Bearer 토큰 보호를 위해 기동 단계에서 거부한다. 기본 시간 제한은 연결 `PT2S`, 읽기 `PT5S`이고 합은 45초를 넘을 수 없다. 디스패처는 전용 스케줄러에서 한 번에 한 건을 1분 임대로 처리하며 10초 간격, 최초 수렴형 조정은 10초 뒤, 이후에는 6시간 간격이다. 소스 이름공간은 기존 아웃박스와 다르면 시작을 거부한다. 점검을 완전히 중단하려면 연결을 유지한 채 `BATON_WATCH_MONITORING_ENABLED=false`로 배포해 `INACTIVE` 전달을 끝낸 다음 `BATON_WATCH_ENABLED=false`로 전환한다.
- WATCH 상태 이벤트 수신: 기본 비활성화. 활성화하려면 `BATON_WATCH_EVENT_RECEIVER_ENABLED=true`, 위와 같은 환경의 `BATON_WATCH_SOURCE_NAMESPACE`와 32~200자의 URL 안전 ASCII `BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN`을 설정한다. 수신 토큰은 외부 전송 WATCH 토큰과 그 밖의 운영 비밀값과 달라야 한다. 저장소 구현과 로컬 런타임 스모크는 실제 공개 HTTPS 콜백, 응답 유실 뒤 동일 재전송과 운영 활성화를 대신하지 않는다.
- 비밀값과 환경별 접속 정보는 환경 변수로 주입한다.
- 프로덕션에서는 MySQL을 Docker 내부 네트워크에만 둔다.

저장소의 기본 비밀번호는 로컬 개발 편의를 위한 값이다. 파일럿 운영은 소유자 전용 비밀 파일과 Spring 구성 트리를 사용하며, 관리형 비밀·키 서비스 전환은 장기 배포 토폴로지와 함께 결정한다.

## 문서 진입점

- 제품 기준: [PRD-0001](docs/PRD/0001_product-baseline/spec.md)
- API 계약: [PRD-0002](docs/PRD/0002_api-contract/spec.md)
- 제품 개발 우선순위: [PRD-0003](docs/PRD/0003_product-roadmap/spec.md)
- BATON–WATCH 역할 자료 감시 계약: [PRD-0004](docs/PRD/0004_watch-integration-contract/spec.md)
- 계정 인증과 ROUND 참여권 계약: [PRD-0005](docs/PRD/0005_account-and-round-authentication/spec.md)
- BATON–CAL 일정 스냅샷 생산 계약: [PRD-0006](docs/PRD/0006_calendar-integration-contract/spec.md)
- 백엔드 구조: [ADR-0001](docs/ADR/0001_hexagonal-architecture/adr.md)
- 테스트 전략: [ADR-0002](docs/ADR/0002_test-strategy/adr.md)
- 파일럿 자체 호스팅 배포: [ADR-0003](docs/ADR/0003_pilot-self-hosted-deployment/adr.md)
- 테스트 기반 API 계약 생성: [ADR-0004](docs/ADR/0004_test-derived-api-contract/adr.md)
- 공유 콘텐츠의 낙관적 수정 충돌: [ADR-0005](docs/ADR/0005_optimistic-content-updates/adr.md)
- 루틴 정의와 회차 실행 분리: [ADR-0006](docs/ADR/0006_routine-definition-and-round-execution/adr.md)
- 결정과 바통의 가역 보관: [ADR-0007](docs/ADR/0007_reversible-record-archive/adr.md)
- 운영 회차 정정과 가역 보관: [ADR-0008](docs/ADR/0008_revisable-round-lifecycle/adr.md)
- 서버 요청 시간 예산: [ADR-0009](docs/ADR/0009_server-request-time-budget/adr.md)
- 구성원 활동 종료와 참조 보존: [ADR-0010](docs/ADR/0010_reversible-member-lifecycle/adr.md)
- 시즌 종료와 다음 시즌 전환: [ADR-0011](docs/ADR/0011_season_lifecycle/adr.md)
- 시즌 시간대와 수렴형 회차·마감 자동화: [ADR-0012](docs/ADR/0012_round_schedule_and_deadline_automation/adr.md)
- 역할 바통 전달 생명주기: [ADR-0013](docs/ADR/0013_role_handoff_lifecycle/adr.md)
- 반복 루틴 정의의 가역 보관: [ADR-0014](docs/ADR/0014_reversible-routine-archive/adr.md)
- WATCH 트랜잭셔널 아웃박스와 수렴형 동기화: [ADR-0015](docs/ADR/0015_watch-transactional-outbox/adr.md)
- WATCH 상태 변경 이벤트 트랜잭셔널 인박스: [ADR-0016](docs/ADR/0016_watch-health-event-transactional-inbox/adr.md)
- 계정 식별성과 동일 출처 세션: [ADR-0017](docs/ADR/0017_account-identity-and-session/adr.md)
- ROUND 프로덕션 런타임 통합: [ADR-0018](docs/ADR/0018_round-production-runtime/adr.md)
- BATON CAL 일정 스냅샷 생산자 경계: [ADR-0019](docs/ADR/0019_calendar_snapshot_producer/adr.md)
- 저장소 작업 규칙: [AGENTS.md](AGENTS.md)
- 현재 인계 상태: [HANDOFF.md](HANDOFF.md)

## 아직 결정하지 않은 것

- 계정 초대·탈퇴·비밀번호 재설정, 추가 인증 기반 신원 연결·병합과 기존 세션 강제 만료
- 공유 워크스페이스 capability(권한 증표)를 대체할 팀·시즌·역할 단위 세부 권한과 감사 모델
- 장기 운영 공급자, 다중 호스트와 무중단 배포 방식
- 정식 가동 시간 공급자와 호출·SMS 같은 독립 알림 채널
- 결정·바통 이외 제품 도메인의 세부 상태값과 영구 삭제·보존 기간 정책
- 파일럿 이후 capability(권한 증표)인 공유 키를 폐기하는 초대·복구 전환 방식

구현보다 문서가 먼저 결정을 가장하지 않도록, 이 항목들은 실제 선택이 이루어질 때 PRD와 ADR을 함께 갱신한다.
