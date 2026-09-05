# ADR-0004: REST Docs 테스트에서 OpenAPI와 프런트 타입 생성

- 상태: 채택
- 결정일: 2026-07-21

## 배경

BATON은 MockMvc와 Spring REST Docs로 HTTP 요청·응답, 헤더, 상태와 오류 예시를 이미 검증한다. 프런트엔드는 같은 계약을 수기 TypeScript 타입으로 다시 적고 있어 DTO·열거형·필수 헤더가 바뀔 때 두 표현이 어긋날 수 있다.

파일럿에서는 계약의 단일 기준을 유지하면서도 프런트 단독 빌드와 기존 `apiRequest`, `ApiError`, React Query 캐시 키, 내구성 있는 멱등 재시도 정책을 보존해야 한다.

## 결정

다음 테스트 기반 생성 흐름을 채택한다.

```text
MockMvc + Spring REST Docs
  → restdocs-api-spec 리소스 스니펫 0.20.1
  → 결정적 스니펫 정렬
  → 저장소 소유 Gradle 태스크 + restdocs-api-spec OpenAPI 생성기 0.20.1
  → 계약 정규화
  → OpenAPI 3.0.1 YAML
  → openapi-typescript 7.13.0
  → TypeScript 오퍼레이션 타입
```

### 계약의 소유권

- 컨트롤러 동작과 REST Docs 디스크립터가 실행 가능한 HTTP 계약의 원천이다.
- restdocs-api-spec MockMvc 확장은 리소스 스니펫을 만들고, `prepareOpenApiSnippets`가 이를 결정적인 순서와 생성기 호환 검증 메타데이터로 정규화한다.
- 저장소의 캐시 가능한 `OpenApi3ContractTask`는 Gradle 관리 속성으로 스니펫 디렉터리와 출력 파일을 받고 restdocs-api-spec OpenAPI 생성기를 호출해 `adapter-in-web/build/api-spec/openapi3.yaml`을 만든다. 배포된 0.20.1 Gradle 플러그인 태스크는 실행 중 `Task.project`를 호출해 Gradle 10에서 실패할 예정이므로 적용하지 않는다.
- 루트 `normalizeOpenApi`가 재현 가능한 스키마 이름과 순서, 요청 본문 필수성, 형식과 널 허용 계약을 보강한 뒤 `syncOpenApi`가 `docs/api/openapi3.yaml`에 동기화한다.
- openapi-typescript는 추적한 OpenAPI에서 `frontend/src/generated/api.ts`를 생성한다.
- 두 생성 파일은 직접 수정하지 않고 저장소에 추적한다. 프런트 Docker 빌드는 Java·Gradle 파일을 복사하지 않으므로 생성 타입을 커밋해야 독립적으로 재현할 수 있다.

### REST Docs 작성 규칙

- 공개 오퍼레이션마다 camelCase `operationId`와 일치하는 정규 리소스 식별자를 둔다.
- 같은 경로·메서드의 오류 예시는 정규 `operationId`를 접두사로 한 식별자를 사용해 상태별 응답으로 병합한다. 성공과 오류 리소스는 같은 정규 요약·설명을 공유한다.
- 경로 매개변수가 있으면 `RestDocumentationRequestBuilders`로 URI 템플릿을 보존한다.
- 열거형은 `EnumFields`, 원시값 배열은 `itemsType`, 요청 DTO는 `ConstrainedFields`를 사용한다. 중첩 객체와 배열의 부모 디스크립터도 명시해 필수 필드가 생성 스키마에서 빠지지 않게 한다.
- 외부 계약인 `Location`, `Cache-Control` 같은 응답 헤더는 검증문만 두지 않고 `responseHeaders` 디스크립터로도 남긴다.
- 애플리케이션이 정의한 모든 제품 API 응답의 공통 `X-Request-ID`는 공용 MockMvc 검증문과 리소스별 `responseHeaders` 디스크립터로 성공·오류 상태에 빠짐없이 남긴다. 단, `RequestIdFilter` 범위 밖에 두는 공개·캐시 가능 `/.well-known/round-participation-jwks.json`은 이 헤더를 만들지 않으며 REST Docs가 그 예외를 명시적으로 고정한다. Caddy가 먼저 만드는 413·502/503은 테스트 유도 OpenAPI가 아니라 프로덕션 런타임 스모크에서 같은 헤더·엣지 로그 계약을 검증한다.
- `restDocsTest` 실행 전에 스니펫 디렉터리를 비워 삭제된 오퍼레이션의 `resource.json`이 남지 않게 한다.
- OpenAPI 생성 전에 리소스 스니펫을 경로·메서드·`operationId`로 정렬해 운영체제별 파일 순회 차이를 없앤다.

### 정규화 계층

`frontend/scripts/normalize-openapi.mjs`는 도메인 계약을 새로 발명하지 않고 생성기의 표현 한계만 보정한다.

- JSON 요청 본문과 로컬 로그인의 양식 본문을 `required: true`로 명시하되, 본문 자체를 생략할 수 있는 ROUND 참여권 갱신은 예외로 둔다.
- 인증 세션 응답은 널 허용 필드를 가진 단일 객체가 아니라 미인증·인증의 정확한 두 `oneOf` 변형으로 보정한다.
- 경로와 DTO 필드의 UUID, 날짜, 날짜-시간 형식을 반영한다.
- 응답에 항상 존재하지만 `null`일 수 있는 필드를 필수이면서 널 허용으로 표현한다.
- 멱등 키의 길이·패턴과 요청 DTO의 Bean Validation 제약을 스키마에 반영한다.
- 같은 스키마는 내용 해시 기반 컴포넌트 이름으로 중복을 제거하고, 공통 오류는 `ErrorResponse`로 고정한다.
- 배포 주소를 고정하지 않도록 OpenAPI 서버는 동일 출처 `/`를 사용한다.

### 프런트 경계

- openapi-typescript는 정적 타입만 생성한다. 런타임 클라이언트와 React Query 훅은 생성하지 않는다.
- 기능 컴포넌트는 생성 파일을 직접 퍼뜨리지 않고 `frontend/src/features/workspace/types.ts` 퍼사드를 사용한다.
- 요청·응답·헤더와 `ErrorResponse`는 생성 오퍼레이션 타입에서 유도한다.
- URI 템플릿과 HTTP 메서드는 생성된 `paths`가 허용하는 조합으로 제한하고, 경로 매개변수는 템플릿 자리표시자에서 유도한다.
- 폼이 항상 명시적 `null`이나 빈 문자열을 보내야 하는 등 UI 명령이 전송 계약보다 강한 경우 퍼사드에서 그 제약만 좁힌다.
- `fetch` 요청 시간 초과, 오류 정규화, 쿼리 무효화와 멱등 요청 복구는 기존 수기 코드가 소유한다.

### 생성과 드리프트 검사

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

`generateApiContract`는 REST Docs 테스트, OpenAPI 생성·동기화와 TypeScript 생성을 순서대로 실행한다. `checkApiContract`는 새 OpenAPI를 추적 파일과 바이트 단위로 비교한 뒤 openapi-typescript의 `--check`로 TypeScript 생성물이 최신인지 검사한다. 오퍼레이션별 경로·메서드·본문·헤더·상태와 공통 헤더는 실제 MockMvc REST Docs 계약 테스트와 디스크립터가 검증하므로 별도의 수기 오퍼레이션 목록이나 의미 검증기를 중복 관리하지 않는다. Spring Security가 직접 처리하는 로컬 세션·로그아웃도 실제 필터 체인 기반 REST Docs로 생성 OpenAPI에 포함하고, OAuth 시작·콜백 경로만 실제 필터 체인 보안 통합 테스트로 고정한다. 반복 업무 정의 보관·복원은 `PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive`와 `updateRoutineArchive` `operationId`로 고정한다. GitHub Actions 품질 관문도 풀 리퀘스트와 `main` 푸시에서 `build checkApiContract`를 한 Gradle 호출로 실행해 전체 회귀와 같은 계약 검사를 함께 수행한다.

## 결과

### 장점

- 백엔드가 실제 직렬화한 예시와 디스크립터에서 프런트 타입까지 한 흐름으로 이어진다.
- 경로, 필수 헤더, 상태 코드와 열거형 드리프트를 검토 전에 찾을 수 있다.
- 기존 프런트 런타임·캐시·멱등 정책을 바꾸지 않고 수기 전송 DTO를 줄인다.
- 추적한 OpenAPI는 도구와 사람이 함께 읽을 수 있는 계약 기준점이 된다.

### 비용과 한계

- REST Docs 디스크립터의 필수, 널 허용, 열거형과 배열 메타데이터가 부정확하면 생성 타입도 부정확하다.
- restdocs-api-spec의 요청 본문, Jakarta Validation과 널 허용 표현 한계 때문에 작은 정규화 계층을 유지해야 한다.
- 교차 필드 규칙과 정규화 후 중복 금지 같은 도메인 불변식은 JSON Schema만으로 완전히 표현하지 않고 애플리케이션·도메인 테스트가 소유한다.
- OpenAPI의 오류 상태와 예시는 MockMvc로 명시적으로 실행한 경우만 포함되므로, 새 오류 분기를 추가하면 해당 오퍼레이션의 REST Docs 예시도 추가해야 한다.
- 생성된 컴포넌트 스키마 이름은 구현 세부 해시를 포함하므로 프런트는 컴포넌트 이름 대신 안정적인 오퍼레이션 타입을 참조한다.
- 공식 Gradle 플러그인 태스크 대신 작은 저장소 소유 태스크가 생성기 API를 호출하므로 restdocs-api-spec을 올릴 때 태스크 호출 계약과 원시 YAML 결정성을 함께 검증해야 한다.
- Gradle 계약 생성에는 Node 의존성이 필요하고, 새 도구 버전은 Spring Boot·Gradle 조합에서 실제 빌드로 검증해야 한다.

## 대안

### restdocs-api-spec Gradle 플러그인 태스크 유지

설정이 가장 짧지만 최신 0.20.1 태스크가 실행 시점에 Gradle `Project` API를 사용해 Gradle 10에서 실패한다. 경고 억제나 기존 태스크 동작 교체는 미래 호환성을 해결하지 못하거나 플러그인 내부 구현에 결합하므로, 스니펫과 OpenAPI 생성기만 재사용하고 태스크 조정은 저장소가 소유한다.

### springdoc-openapi와 Swagger 애너테이션

런타임 인트로스펙션과 애너테이션으로 문서를 만들 수 있지만 이미 존재하는 REST Docs 계약과 별도 기준이 생긴다. 현재는 테스트에서 실제 HTTP 동작을 검증하는 기준을 유지한다.

### Orval로 클라이언트와 React Query 훅까지 생성

초기 작성량은 줄지만 접근 키·멱등 키와 캐시 키, 오류·재시도 정책을 생성 설정에 맞춰 다시 조립해야 한다. 현재 파일럿에는 정적 타입 생성만 채택한다.

### 수기 OpenAPI 또는 수기 TypeScript DTO 유지

도구 의존성은 적지만 백엔드 테스트와 프런트 타입의 드리프트를 자동으로 막지 못하므로 채택하지 않았다.

## 관련 문서

- [API 명세](../../PRD/0002_api-contract/spec.md)
- [테스트 전략](../0002_test-strategy/adr.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [반복 업무 정의와 회차 실행 분리](../0006_routine-definition-and-round-execution/adr.md)
- [결정과 인수인계의 보관·복원](../0007_reversible-record-archive/adr.md)
- [운영 회차 정정과 보관·복원](../0008_revisable-round-lifecycle/adr.md)
- [구성원 활동 종료와 참조 보존](../0010_reversible-member-lifecycle/adr.md)
- [시즌 종료와 다음 시즌 전환](../0011_season_lifecycle/adr.md)
- [역할 인수인계 전달 생명주기](../0013_role_handoff_lifecycle/adr.md)
