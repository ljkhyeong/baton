# PRD-0002: BATON API 계약 기준선

- 상태: 초기 기준선
- 작성일: 2026-07-20
- 적용 범위: 현재 구현된 HTTP API와 향후 `/api/v1` 계약의 공통 규칙

## 1. 목적

이 문서는 프런트엔드와 백엔드 사이에서 유지해야 할 HTTP 계약을 정의한다. 구현되지 않은 제품 API를 미리 발명하지 않고, 현재 실행 가능한 계약과 향후 API가 따라야 할 최소 규칙만 기록한다.

## 2. 공통 규칙

- 제품 API 기본 경로는 `/api/v1`이다.
- 요청과 응답 본문은 별도 표기가 없으면 JSON을 사용한다.
- 날짜와 시각은 의미가 불분명한 문자열 대신 ISO 8601 형식을 사용한다.
- 서버 시각은 UTC를 기준으로 생성하고 응답에는 오프셋 또는 `Z`를 포함한다.
- 식별자는 클라이언트가 형식이나 정렬 의미를 추론하지 않는 불투명한 값으로 다룬다.
- HTTP DTO와 application 결과 타입을 분리한다.
- 컨트롤러는 요청 검증과 변환을 담당하고 업무 규칙은 application 또는 domain에 둔다.
- 기존 필드의 의미를 바꾸거나 제거하는 변경은 새 버전 또는 명시적 호환 전략 없이 진행하지 않는다.

페이지네이션 형식, 멱등키, 낙관적 잠금 표현은 이를 필요로 하는 실제 API가 설계될 때 확정한다.

## 3. 현재 구현된 제품 API

### 시스템 상태 조회

```http
GET /api/v1/system/status
```

- 인증: 필요 없음
- 요청 본문: 없음
- 성공 상태: `200 OK`

응답 예시:

```json
{
  "service": "baton",
  "checkedAt": "2026-07-20T12:00:00Z"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `service` | string | 서비스 식별자. 현재 값은 `baton`이다. |
| `checkedAt` | string | 서버가 상태를 확인한 UTC 시각이다. |

이 계약은 `SystemStatusRestDocsTest`가 검증하고 REST Docs 스니펫을 생성한다.

## 4. 운영 상태 엔드포인트

```http
GET /actuator/health
```

이 경로는 Spring Boot Actuator 운영 엔드포인트이며 제품 API가 아니다. 현재 인증 없이 접근할 수 있다. 응답 세부 구조는 제품 DTO 계약이 아니라 Actuator 설정을 따른다.

## 5. 오류 응답

명시적으로 처리하는 입력 오류는 다음 형태를 사용한다.

```json
{
  "code": "INVALID_INPUT",
  "message": "name: 비어 있을 수 없습니다"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `code` | string | 클라이언트가 분기할 수 있는 안정적인 오류 코드다. |
| `message` | string | 사용자가 이해할 수 있는 오류 설명이다. |

현재 `MethodArgumentNotValidException`과 `IllegalArgumentException`은 `400 Bad Request`와 `INVALID_INPUT`으로 변환된다. 그 밖의 예외를 같은 형태로 정규화하는 정책은 아직 구현되지 않았으므로 모든 오류가 이 형태라고 가정하지 않는다.

새 제품 API를 추가할 때는 다음을 함께 결정한다.

- 오류 코드와 HTTP 상태
- 필드 단위 검증 실패 표현이 필요한지
- 존재하지 않는 리소스, 충돌, 권한 실패의 경계
- 로그에 남길 내부 정보와 응답에 공개할 정보의 분리

## 6. 인증과 권한

최종 인증 방식은 미결정이다.

현재 Spring Security 설정은 다음 두 경로만 공개하고 나머지 요청에 HTTP Basic 인증을 요구한다.

- `/actuator/health`
- `/api/v1/system/status`

이는 개발 기반의 임시 설정이다. 쿠키 세션, Bearer 토큰, 소셜 로그인, 조직 초대 방식 중 무엇을 채택할지는 별도 결정 전까지 공개 계약으로 간주하지 않는다. 새 클라이언트 코드가 HTTP Basic을 최종 제품 계약으로 전제해서는 안 된다.

## 7. 아직 계약이 없는 제품 영역

다음 영역은 제품 기준선에는 포함되지만 HTTP 경로, 요청·응답 DTO와 상태값이 아직 확정되지 않았다.

- 팀과 시즌
- 구성원과 역할 배정
- 역할의 책임과 자료
- 운영 루틴과 회차별 실행
- 결정 기록
- 위험 신호
- 바통북과 인수인계

이 영역의 API를 추가할 때는 구현, 이 문서와 REST Docs 계약 테스트를 같은 변경에서 갱신한다.

## 8. 계약 검증

현재 존재하는 계약 검증 명령은 다음과 같다.

```bash
./gradlew --no-daemon :adapter-in-web:restDocsTest
```

일반 웹 모듈 테스트에서는 `restdocs` 태그를 제외하고, `check`가 `restDocsTest`를 별도로 실행한다. 따라서 전체 빌드에도 현재 REST Docs 계약 검증이 포함된다.

```bash
./gradlew build
```

## 9. 관련 문서

- [제품 기준선](../0001_product-baseline/spec.md)
- [테스트 전략](../../ADR/0002_test-strategy/adr.md)
