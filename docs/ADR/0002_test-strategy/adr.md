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
| 유스케이스 통합 | `usecase` | Spring 조립, DB, Flyway, 트랜잭션과 어댑터 협력 | `./gradlew --no-daemon :application:useCaseTest` |
| HTTP 계약 | `restdocs` | 공개 요청·응답과 상태 코드 | `./gradlew --no-daemon :adapter-in-web:restDocsTest` |
| ROUND 소비자 계약 | `crossservice` | BATON 서명기·JWK 회전과 외부 ROUND 시그널링 런타임 호환성 | `ROUND_REPOSITORY_ROOT=/absolute/path/to/round bash ops/tests/round-consumer-contract.sh` |
| CAL 소비자 계약 | `calendar-crossservice` | BATON 일정·시즌 이름·복구 완료 직렬화와 외부 CAL 사전 릴리스 호환성 | `BATON_CAL_REPOSITORY_ROOT=/absolute/path/to/baton-cal-contracts-v1.1.0-rc.1 bash ops/tests/calendar-consumer-contract.sh` |
| 전체 회귀 | 전체 | 여러 모듈에 걸친 변경 | `./gradlew --no-daemon test` 또는 `./gradlew --no-daemon build` |

`useCaseTest`는 MySQL 8 Testcontainers에서 파일럿 워크스페이스 생성, 워크스페이스·구성원을 포함한 콘텐츠 생성과 접근 키 변경의 멱등성, 생성·복구 비밀 분리, 동시 멱등 요청과 접근 키 변경 충돌, 구성원·시즌·역할·역할 자료·반복 업무 정의·회차·실행·결정·인수인계 항목·역할 인수인계 저장과 조회 프로젝션을 검증한다. Flyway 변경은 대상 이전 버전까지 적용한 대표 데이터를 최신 마이그레이션으로 올린 뒤 데이터·참조·제약·인덱스 같은 실제 이관 사후조건을 확인하는 전용 테스트가 소유한다. 개별 버전의 제품·연동 기대값은 [제품 명세](../../PRD/0001_product-baseline/spec.md), [WATCH 연동 계약](../../PRD/0004_watch-integration-contract/spec.md), [CAL 연동 계약](../../PRD/0006_calendar-integration-contract/spec.md)과 관련 ADR에 두며 이 문서에는 반복해 열거하지 않는다. 선택한 태스크가 실제 대상 테스트를 실행했는지 항상 확인한다.

`policyTest`는 의존 방향뿐 아니라 DevTools 분리 클래스 로더에서 Spring Data 프록시 생성에 필요한 저장소 공개 가시성도 고정한다.

### 테스트 작성 원칙

- 테스트 개수나 커버리지 수치보다 장기적으로 가치 있는 실패를 잡는지 우선한다.
- 모든 테스트 메서드에는 한국어 문장형 `@DisplayName`을 사용한다.
- 정책과 아키텍처 테스트는 `policy`, 통합 흐름은 `usecase`, HTTP 계약은 `restdocs` 태그를 사용한다.
- 단순 프레임워크 연결, 접근자 메서드와 기계적 매핑만 확인하는 저가치 테스트는 추가하지 않는다.
- 컨트롤러는 HTTP 형태와 변환, 애플리케이션 테스트는 유스케이스와 트랜잭션 경계, 도메인 테스트는 불변식을 검증한다.
- 데이터베이스 또는 Spring 컨텍스트가 필요한 통합 테스트는 Testcontainers를 사용하고 `--no-daemon`으로 실행한다.
- 시간에 의존하는 코드는 `now()`를 직접 흩뿌리지 않고 `Clock`을 주입한다. 운영 기본 Clock은 UTC다.
- 운영 `Clock`은 MySQL `DATETIME(6)` 저장 정밀도에 맞춘 마이크로초 틱을 사용해 저장 전 응답과 재조회 결과가 달라지지 않게 한다.
- 외부에 노출되는 API 변경은 구현과 REST Docs 테스트, API 계약 문서를 같은 변경에서 갱신한다.

### 기본 빌드 동작

- 모든 서브모듈 테스트는 JUnit Platform을 사용한다.
- `adapter-out-external:test`는 외부 런타임을 요구하는 `crossservice`와 `calendar-crossservice` 태그를 제외한다. 전용 `roundConsumerContractTest`는 명시한 signaling bootJar로, `calendarConsumerContractTest`는 `BATON_CAL_LIVE_BASE_URL`과 `BATON_CAL_LIVE_BEARER_TOKEN`을 제공했을 때만 실행한다.
- `adapter-in-web:test`는 `restdocs` 태그를 제외한다.
- `adapter-in-web:check`는 `restDocsTest`를 별도로 의존한다.
- 따라서 `./gradlew --no-daemon build`에는 현재 REST Docs 계약 테스트가 포함된다.
- `./gradlew --no-daemon generateApiContract`는 REST Docs에서 OpenAPI와 프런트 타입을 생성하고, `checkApiContract`는 추적한 생성물의 드리프트를 검사한다.
- Java 컴파일러는 Spring의 매개변수 이름 리플렉션을 위해 `-parameters`를 사용한다.

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
npm run e2e:records
npm run e2e:responsive
npm run e2e
npm run e2e:fullstack
```

- TypeScript `strict` 설정을 유지한다.
- `npm run typecheck`는 프로덕션 소스와 Playwright 설정·테스트를 함께 검사하고, `npm run build`는 프로덕션 소스 검사와 Vite 번들을 소유한다.
- UI 동작을 바꾸면 최소한 타입 검사와 프로덕션 빌드를 실행한다.
- 핵심 작업 공간·시즌 탐색, 공유 키 검증·회전, 최근 작업 공간 복구와 기존 팀 구성원·역할 생성 멱등 재시도는 `e2e:smoke`, 390px 모바일 작업은 `e2e:responsive`로 확인한다.
- 핵심 흐름은 아니지만 브라우저 엔진 차이를 직접 확인해야 하는 `Headers`, 요청 취소, Web Storage와 네이티브 dialog 대표 사례는 `@webkit`으로 표시한다. 같은 API의 모든 경곗값을 WebKit에서 반복하지 않고 BATON이 실제 사용하는 경로 한 건만 유지한다.
- 역할·반복 업무 수정, 반복 업무 정의 보관·복원과 과거 실행 보존, 수동 회차 생성과 회차별 반복 업무 완료, 종료 시즌 읽기 전용은 `e2e:operations`, 결정 기록은 `e2e:memory`, 역할 자료 생성의 응답 유실 복구·수정 충돌 최신화·보관·복원·외부 링크·재조회, 인수인계 항목·다음 시즌 생성의 멱등 재시도와 역할 인수인계 준비·경고 확인·전달·수락·새로고침 보존은 `e2e:handoff`로 확인한다. 결정·인수인계·자료의 통합 검색, 역할·상태·기간 필터, 시각 미상 처리, 검색 조건 유지와 원본 화면 이동은 `e2e:records`가 데스크톱과 390px 모바일에서 확인한다.
- `e2e` 브라우저 회귀는 테스트별 독립 API 픽스처로 요청 본문, 접근 키 헤더와 새로고침 후 서버 프로젝션 복원을 빠르게 검증한다.
- `e2e:fullstack`은 고유 Compose 프로젝트의 임시 MySQL, 실행 가능한 Spring Boot JAR와 Vite 개발 프록시를 실제 브라우저로 잇는다. 빈 DB 온보딩부터 기존 팀 구성원 추가, 역할 자료, 반복 업무·회차, 공유 링크를 통한 두 브라우저 완료 상태 동기화, 다음 시즌 역할·반복 업무 복사와 원본 시즌 읽기 전용 보존, 새로고침 후 DB 영속성까지 한 핵심 경로만 단일 워커로 검증한다.
- 전 구간 실행기는 기존 로컬·프로덕션 DB를 재사용하지 않고 종료할 때 자신이 만든 컨테이너와 볼륨만 제거한다. 실패 시 Spring, Vite와 MySQL 로그를 별도 산출물 경로에 보존한다.
- 전체 픽스처 기반 Playwright 검증은 `e2e`, 전 구간 파일럿 스모크는 `e2e:fullstack`을 사용한다. 전체 흐름은 Chromium, 390px 모바일 흐름은 Chromium, 핵심 스모크·반응형 흐름과 `@webkit` 대표 사례는 Safari 호환 WebKit에서도 실행한다. 브라우저 엔진이 없으면 먼저 `npm run e2e:install`을 실행한다.
- `e2e:fullstack`은 Caddy, TLS와 프로덕션 이미지 실행을 검증하지 않는다. 이 배포 경계는 별도 운영 스모크로 확인한다.
- 선택한 태그가 실제 테스트와 매칭되는지 확인하며, 0개 테스트 실행을 완료된 검증으로 보지 않는다.
- 프런트 단위 테스트와 린트 명령은 아직 구성되지 않았으므로 이 ADR에서 의무 명령으로 선언하지 않는다.

### CI 품질 관문

`.github/workflows/quality-gate.yml`은 모든 풀 리퀘스트, `main` 푸시와 수동 실행에서 배포 가능한 커밋인지 판정한다. 오래된 같은 참조의 실행은 취소하고 각 검증에 시간 제한을 둔다. 경로 필터는 사용하지 않아 문서만 바뀐 풀 리퀘스트도 필수 상태가 누락되지 않게 한다.

- 백엔드와 API 계약은 Docker가 실제로 사용 가능한지 먼저 확인한 뒤 `build checkApiContract`를 한 Gradle 호출로 실행한다. 이 조합은 Testcontainers·Flyway 통합 테스트와 REST Docs를 포함하고 같은 태스크 그래프 안에서 REST Docs 중복 실행을 피한다.
- 프런트엔드는 `npm run typecheck`로 프로덕션 소스와 Playwright 코드를 엄격하게 검사하고 `npm run build`로 프로덕션 번들을 확인한 뒤 Chromium·WebKit에서 Playwright E2E를 실행한다. Chromium은 전체 흐름과 390px 모바일 흐름, WebKit은 핵심 스모크·반응형 흐름과 브라우저 API 대표 사례를 소유한다. CI에서는 `test.only`를 거부하고 재시도에서만 성공한 불안정 테스트도 실패로 판정한다. 같은 실행기에서 워커 2개를 사용하고, 테스트별 진행 결과를 로그에 남긴다. 전체 작업 제한은 브라우저 설치와 전체 E2E 실행 시간을 포함해 40분이며, 개별 테스트 제한은 30초를 유지한다. 실패한 실행의 보고서·추적·화면 캡처는 7일 동안 산출물로 남긴다.
- 전 구간 파일럿 스모크는 Java 21, Node, Docker와 Chromium을 준비한 독립 작업에서 `npm run e2e:fullstack`으로 실행한다. 픽스처 기반 UI 회귀와 분리해 Vite 프록시, Spring 보안·HTTP·애플리케이션 경계, Flyway와 MySQL 사이의 조립 실패를 명확히 드러내고 실패 보고서와 각 런타임 로그를 7일 동안 보존한다.
- 운영 패키지는 단일 `ops/check-shell-scripts.sh`가 Git 인덱스의 추적 파일과 작업 트리의 무시되지 않은 신규 파일에서 모든 `ops/**/*.sh`를 모아 각각 `bash -n`으로 해석하고 같은 집합에 ShellCheck를 적용한 뒤, 실제 비밀이 아닌 CI 전용 값으로 백업·복구와 프로덕션 Compose를 확인한다. 결정론적 셸 픽스처로 배포 환경 파일 권한·리터럴 문법·비밀 분리·호스트 환경 우선순위 제거, 공개 HTTPS 서비스 상태의 성공·실패 종료와 백업 상태의 UTC 교차검증을 고정하고 백업·모니터 systemd 유닛을 정적으로 검증한다. 이어서 고유 프로젝트에서 `app`·`web` 이미지를 한 번 빌드하고 같은 이미지를 `--no-build`로 실행해 Caddy 로컬 CA HTTPS, 정적·SPA 경로, 역방향 프록시와 보안 헤더, 프로덕션 프로필, Flyway와 MySQL TLS 세션을 검증한다. 이 런타임 스모크는 정상 제품 API의 Spring 요청 ID 보존과 Caddy가 직접 만드는 413·502/503의 엣지 요청 ID·접근 로그 상관관계, 운영 키·멱등 키 로그 제거를 고정한다. 같은 폐기 가능 DB에서 원본 `backup.sh`·`restore.sh`로 실제 덤프·체크섬·삭제/가져오기를 수행하고, 스냅샷 롤백, 팀별 최신 복구 대상, 모든 과거 키의 `403`, 생성·키 변경 동일 재처리 만료, 운영자 복구와 팀별 새 키의 조회·변경·재백업까지 확인한다. 파괴 경계는 원본 운영 래퍼를 변경하지 않고 실행 토큰·데몬/컨텍스트·사용자 정의 레이블·전용 볼륨과 DB 이름·중지된 `app`/`web`을 다시 확인하는 테스트 전용 심이 소유한다. 실패 산출물은 컨테이너 환경 변수를 제외하며 보호 값이 발견된 런타임 로그도 보존하지 않는다.
- 네 병렬 작업의 결과는 기존 계약 워크플로의 작업 식별자를 유지한 최종 `contract` 작업으로 집계한다. 원격 규칙 집합 또는 브랜치 보호는 이 최종 검사를 필수로 지정해야 병합을 강제 차단한다.

워크플로는 `contents: read` 외 권한과 운영 비밀을 사용하지 않는다. 이미지를 레지스트리에 게시하거나 호스트에 배포하는 단계는 공급자와 배포 승인 경계를 결정할 때 별도로 채택한다.

### 변경별 검증 선택

- 한 모듈의 작은 변경은 해당 모듈 또는 해당 태그의 가장 좁은 테스트부터 실행한다.
- DB 마이그레이션, 트랜잭션, Spring 컨텍스트 변경은 유스케이스 통합 테스트를 실행한다.
- HTTP 경로, DTO, 오류 코드와 상태 변경은 REST Docs 계약 테스트를 실행한다.
- 프런트가 소비하는 HTTP 계약 변경은 `generateApiContract checkApiContract`를 한 Gradle 호출로 실행하고 프런트 타입을 검사한다. 계약 생성에 전체 REST Docs 실행이 포함된다.
- 프런트와 백엔드 조립, Vite 프록시, 런타임 설정 또는 파일럿 핵심 흐름을 바꾸면 `e2e:fullstack`을 실행한다.
- 프로덕션 Compose, Dockerfile, Caddy, 프로덕션 프로필 또는 내부 DB TLS 경계를 바꾸면 `bash ops/tests/production-runtime-smoke.sh`를 실행한다.
- ROUND 참여권 서명기·JWK·클레임 또는 소비자 인증 계약을 바꾸면 `bash ops/tests/round-consumer-contract.sh`를 실행한다.
- CAL 일정 스냅샷 DTO·직렬화·개정과 상태 의미, CAL 응답 분류 또는 고정한 안정 계약 버전을 바꾸면 `bash ops/tests/calendar-consumer-contract.sh`를 실행한다.
- 배포 환경·Compose 래퍼, 서비스 상태·백업 최신성 또는 운영 systemd 경계를 바꾸면 `bash ops/tests/pilot-readiness-test.sh`와 해당 유닛 정적 검증을 실행한다.
- 모듈 구조와 임포트 경계 변경은 정책 테스트를 실행한다.
- 공통 빌드·런타임 설정이나 여러 모듈에 걸친 동작을 바꾸면 마지막에 전체 `build`를 실행한다. 변경 파일 수나 문구 수정만으로 전체 검증을 선택하지 않는다.

### 로컬 검증 순서

1. 변경한 동작과 필요한 테스트를 먼저 정한다. 실행할 검증에 필요한 도구만 확인한다. Docker 연결 실패, 미설치 브라우저, `flock` 부재 같은 환경 문제는 테스트 실패와 구분하고, 원인이 해결되기 전에는 같은 검증을 재시도하지 않는다.
2. 관련 소스와 테스트 수정을 마친 뒤 실행한다. 화면 문구는 `src`뿐 아니라 테스트·공통 helper·fullstack의 기존 기대값도 검색한다. 사용자 입력 예시까지 일괄 치환하지 않는다.
3. 공통 진입 흐름을 바꿨다면 대표 테스트를 Chromium에서 먼저 확인한다. 개별 오류는 해당 파일·제목 또는 클래스부터 실행하고, 실패하면 로그를 확인해 원인을 고친다. `clean`이나 `--rerun-tasks`는 빌드 산출물 문제를 확인했을 때만 사용한다.
4. 빠른 확인이 통과하면 위 변경별 기준에 맞는 범위와 브라우저를 검증한다. 실행 중에는 검증 대상 소스를 수정하지 않는다. 같은 체크아웃의 Playwright 실행은 포트 3100과 결과 파일을 공유하므로 한 명령 안에서 워커를 사용하고, Gradle 작업도 같은 빌드 디렉터리에서 겹쳐 실행하지 않는다.
5. 실패 수정 후에는 해당 테스트부터 재실행한다. 실패 조기 종료로 실행하지 못한 테스트는 여전히 미검증이며, 필요한 나머지 범위를 이어서 확인한다. 이미 통과한 범위는 소스·테스트·설정·의존성·환경 변화가 영향을 줄 때만 다시 실행한다. 로컬 선별 검증은 CI 필수 검사를 대체하지 않는다.

검증 종료 시 `output/verification/latest.md`에 **작업 경로·브랜치·커밋과 미커밋 변경, 명령·선택 범위, 성공/실패/제외 개수, 로그 위치, 미검증 범위·환경 제약**을 짧게 남긴다. 유효한 기존 결과는 유지하고 새 검증과 무효가 된 범위만 갱신한다. 실행 중 작업을 넘길 때는 실행 ID도 남긴다. 다음 세션은 기록과 현재 변경을 비교하며, 커밋 번호가 같다는 이유만으로 통과를 재사용하지 않는다. 이 파일은 로컬 기록으로 Git에서 제외한다. 해결이 필요한 작업만 `HANDOFF.md`에 남긴다.

## 결과

### 장점

- 변경 원인과 가까운 테스트에서 빠르게 실패를 확인할 수 있다.
- API 계약과 아키텍처 경계가 실행 가능한 테스트로 남는다.
- 테스트 수를 늘리기 위한 테스트보다 실제 회귀 방지에 집중한다.

### 비용

- 변경자가 어떤 경계가 영향을 받는지 판단해야 한다.
- 테스트 태그를 잘못 붙이면 목적별 태스크에서 누락될 수 있다.
- 현재 프런트엔드에는 단위 테스트와 린트 관문이 없다.

## 관련 문서

- [헥사고날 아키텍처 결정](../0001_hexagonal-architecture/adr.md)
- [API 명세](../../PRD/0002_api-contract/spec.md)
- [테스트 기반 API 계약 생성](../0004_test-derived-api-contract/adr.md)
- [시즌 종료와 다음 시즌 전환](../0011_season_lifecycle/adr.md)
- [역할 인수인계 전달 생명주기](../0013_role_handoff_lifecycle/adr.md)
- [BATON CAL 일정 스냅샷 생산자 경계](../0019_calendar_snapshot_producer/adr.md)
