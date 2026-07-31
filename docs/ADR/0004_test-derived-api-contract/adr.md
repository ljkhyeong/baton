# ADR-0004: REST Docs 테스트에서 OpenAPI와 프런트 타입 생성

- 상태: 채택
- 결정일: 2026-07-21

## 배경

BATON은 MockMvc와 Spring REST Docs로 HTTP 요청·응답, 헤더, 상태와 오류 예시를 이미 검증한다. 프런트엔드는 같은 계약을 수기 TypeScript 타입으로 다시 적고 있어 DTO·enum·필수 헤더가 바뀔 때 두 표현이 어긋날 수 있다.

파일럿에서는 계약의 진실의 원천을 하나로 유지하면서도 프런트 단독 빌드와 기존 `apiRequest`, `ApiError`, React Query cache key, 내구 멱등 재시도 정책을 보존해야 한다.

## 결정

다음 테스트 기반 생성 흐름을 채택한다.

```text
MockMvc + Spring REST Docs
  → restdocs-api-spec 0.20.1
  → deterministic snippet ordering + contract normalization
  → OpenAPI 3.0.1 YAML
  → openapi-typescript 7.13.0
  → TypeScript operation 타입
```

### 계약의 소유권

- 컨트롤러 동작과 REST Docs descriptor가 실행 가능한 HTTP 계약의 원천이다.
- restdocs-api-spec은 `adapter-in-web/build/api-spec/openapi3.yaml`을 만든다. root `normalizeOpenApi`가 재현 가능한 schema 이름과 순서, request body 필수성, 형식과 nullable 계약을 보강한 뒤 `syncOpenApi`가 `docs/api/openapi3.yaml`에 동기화한다.
- openapi-typescript는 추적한 OpenAPI에서 `frontend/src/generated/api.ts`를 생성한다.
- 두 생성 파일은 직접 수정하지 않고 저장소에 추적한다. 프런트 Docker 빌드는 Java·Gradle 파일을 복사하지 않으므로 생성 타입을 커밋해야 독립적으로 재현할 수 있다.

### REST Docs 작성 규칙

- 공개 operation마다 camelCase `operationId`와 일치하는 canonical resource 식별자를 둔다.
- 같은 path·method의 오류 예시는 canonical `operationId`를 prefix로 한 식별자를 사용해 상태별 응답으로 병합한다. 성공과 오류 resource는 같은 canonical summary·description을 공유한다.
- path parameter가 있으면 `RestDocumentationRequestBuilders`로 URI template을 보존한다.
- enum은 `EnumFields`, 원시값 배열은 `itemsType`, request DTO는 `ConstrainedFields`를 사용한다. 중첩 object와 배열의 부모 descriptor도 명시해 필수 필드가 생성 스키마에서 빠지지 않게 한다.
- 외부 계약인 `Location`, `Cache-Control` 같은 응답 헤더는 assertion만 하지 않고 `responseHeaders` descriptor로도 남긴다.
- 애플리케이션이 정의한 모든 제품 API 응답의 공통 `X-Request-ID`는 공용 MockMvc assertion과 resource별 `responseHeaders` descriptor로 성공·오류 상태에 빠짐없이 남기고, 의미 검증기가 각 OpenAPI response를 전부 확인한다. Caddy가 먼저 만드는 413·502/503은 테스트 유도 OpenAPI가 아니라 production runtime smoke에서 같은 헤더·edge log 계약을 검증한다.
- `restDocsTest` 실행 전에 snippet 디렉터리를 비워 삭제된 operation의 `resource.json`이 남지 않게 한다.
- OpenAPI 생성 전에 resource snippet을 path·method·operationId로 정렬해 운영체제별 파일 순회 차이를 없앤다.

### 정규화 계층

`frontend/scripts/normalize-openapi.mjs`는 도메인 계약을 새로 발명하지 않고 생성기의 표현 한계만 보정한다.

- JSON request body를 `required: true`로 명시한다.
- path와 DTO 필드의 UUID, date, date-time format을 반영한다.
- 응답에 항상 존재하지만 null일 수 있는 필드를 required + nullable로 표현한다.
- 멱등 키의 길이·pattern과 request DTO의 Bean Validation 제약을 schema에 반영한다.
- 같은 schema는 내용 hash 기반 component 이름으로 중복을 제거하고, 공통 오류는 `ErrorResponse`로 고정한다.
- 배포 주소를 고정하지 않도록 OpenAPI server는 동일 출처 `/`를 사용한다.
- filter chain과 application 정책으로 검증한 session·운영 key·레거시 workspace
  authorization은 OpenAPI `securitySchemes`로 보강한다. session 또는 레거시 key를
  허용하는 workspace operation은 OR security로 표현하고, access key header와 session
  CSRF header는 선택한 방식에 따라 조건부이므로 optional parameter와 설명으로 남긴다.

### 프런트 경계

- openapi-typescript는 정적 타입만 생성한다. 런타임 client와 React Query hook은 생성하지 않는다.
- feature component는 생성 파일을 직접 퍼뜨리지 않고 `frontend/src/features/workspace/types.ts` façade를 사용한다.
- 요청·응답·헤더와 `ErrorResponse`는 생성 operation 타입에서 유도한다.
- URI template과 HTTP method는 generated `paths`가 허용하는 조합으로 제한하고, path parameter는 template placeholder에서 유도한다.
- 폼이 항상 명시적 `null`이나 빈 문자열을 보내야 하는 등 UI command가 wire 계약보다 강한 경우 façade에서 그 제약만 좁힌다.
- fetch timeout, 오류 정규화, query invalidation과 멱등 요청 복구는 기존 수기 코드가 소유한다.

### 생성과 드리프트 검사

```bash
cd frontend && npm ci && cd ..
./gradlew --no-daemon generateApiContract
./gradlew --no-daemon checkApiContract
```

`generateApiContract`는 REST Docs 테스트, OpenAPI 생성·동기화와 TypeScript 생성을 순서대로 실행한다. `checkApiContract`는 새 OpenAPI를 추적 파일과 비교하고 `validate-openapi.mjs`로 operation 수, 경로·method, 본문, 헤더와 상태 기준선을 검증한 뒤 openapi-typescript의 `--check`로 TypeScript 생성물이 최신인지 검사한다. 현재 기준선은 시즌 수정·종료·다음 시즌 생성·회차 일정 설정, 구성원 추가·이름 수정·활동 상태 변경, 회차·결정·바통 항목의 수정·가역 보관과 역할 바통 준비·전달·수락·취소를 포함한 33개 operation이다. GitHub Actions 품질 게이트도 pull request와 `main` push에서 `build checkApiContract`를 한 Gradle invocation으로 실행해 전체 회귀와 같은 계약 검사를 함께 수행한다.

## 결과

### 장점

- 백엔드가 실제 직렬화한 예시와 descriptor에서 프런트 타입까지 한 흐름으로 이어진다.
- path, 필수 헤더, 상태 코드와 enum 드리프트를 리뷰 전에 찾을 수 있다.
- 기존 프런트 런타임·cache·멱등 정책을 바꾸지 않고 수기 transport DTO를 줄인다.
- 추적한 OpenAPI는 도구와 사람이 함께 읽을 수 있는 계약 기준점이 된다.

### 비용과 한계

- REST Docs descriptor의 required, nullable, enum과 배열 metadata가 부정확하면 생성 타입도 부정확하다.
- restdocs-api-spec의 request body, Jakarta Validation과 nullable 표현 한계 때문에 작은 정규화 계층을 유지해야 한다.
- 교차 필드 규칙과 정규화 후 중복 금지 같은 도메인 불변식은 JSON Schema만으로 완전히 표현하지 않고 application·domain 테스트가 소유한다.
- OpenAPI의 오류 status와 example은 MockMvc로 명시적으로 실행한 경우만 포함되므로, 새 오류 분기를 추가하면 해당 operation의 REST Docs 예시도 추가해야 한다.
- 생성된 component schema 이름은 구현 세부 해시를 포함하므로 프런트는 component 이름 대신 안정적인 operation 타입을 참조한다.
- Gradle 계약 생성에는 Node 의존성이 필요하고, 새 도구 버전은 Spring Boot·Gradle 조합에서 실제 빌드로 검증해야 한다.

## 대안

### springdoc-openapi와 Swagger annotation

런타임 introspection과 annotation으로 문서를 만들 수 있지만 이미 존재하는 REST Docs 계약과 별도 진실의 원천이 생긴다. 현재는 테스트에서 실제 HTTP 동작을 검증하는 기준을 유지한다.

### Orval로 client와 React Query hook까지 생성

초기 작성량은 줄지만 접근 키·멱등 키와 cache key, 오류·재시도 정책을 생성 설정에 맞춰 다시 조립해야 한다. 현재 파일럿에는 정적 타입 생성만 채택한다.

### 수기 OpenAPI 또는 수기 TypeScript DTO 유지

도구 의존성은 적지만 백엔드 테스트와 프런트 타입의 드리프트를 자동으로 막지 못하므로 채택하지 않았다.

## 관련 문서

- [API 계약 기준선](../../PRD/0002_api-contract/spec.md)
- [테스트 전략](../0002_test-strategy/adr.md)
- [헥사고날 아키텍처](../0001_hexagonal-architecture/adr.md)
- [루틴 정의와 회차 실행 분리](../0006_routine-definition-and-round-execution/adr.md)
- [결정과 바통의 가역 보관](../0007_reversible-record-archive/adr.md)
- [운영 회차 정정과 가역 보관](../0008_revisable-round-lifecycle/adr.md)
- [구성원 활동 종료와 참조 보존](../0010_reversible-member-lifecycle/adr.md)
- [시즌 종료와 다음 시즌 전환](../0011_season_lifecycle/adr.md)
- [역할 바통 전달 생명주기](../0013_role_handoff_lifecycle/adr.md)
