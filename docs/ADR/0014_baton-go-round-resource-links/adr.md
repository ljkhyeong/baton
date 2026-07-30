# ADR-0014: 검증된 역할 자료 클릭을 BATON GO를 거쳐 ROUND로 연결

- 상태: 채택
- 결정일: 2026-07-30

## 배경

BATON의 역할 자료는 사용자가 입력한 `http` 또는 `https` 전체 주소를 저장하고 브라우저가
직접 연다. 이 모델은 일반 문서에는 적합하지만, ROUND 회의 주소를 짧은 링크로 공유하거나
만료 정책을 적용할 책임까지 BATON에 넣으면 조직 권한과 링크 수명주기 경계가 섞인다.

BATON GO는 코드, 활성 시간, 만료, 폐기와 신뢰 대상 라우팅을 소유한다. BATON은
workspace 접근 키, 팀·시즌과 역할 자료 소속을 계속 소유하고, ROUND는 실제 room 입장과
미디어 세션 권한을 소유한다.

## 결정

역할 자료를 클릭할 때 다음 순서로 navigation URL을 해석한다.

1. 브라우저는 BATON에 `X-Baton-Access-Key`, 클릭 intent의 canonical UUID
   `Idempotency-Key`와 짧은 만료 시각을 보낸다.
2. BATON application은 기존 workspace 조회 유스케이스로 접근 키와
   `teamId`·`seasonId`·`resourceId` 소속을 확인한다.
3. 일반 외부 자료 또는 GO 연동 비활성 상태이면 저장된 자료 URL을 `DIRECT`로 반환한다.
4. 자료 URL이 설정된 ROUND public origin과 정확히 같고, query·fragment·userinfo가
   없으며 canonical `/room/{roomId}` 경로이면 BATON이 GO 관리 API를 호출한다.
5. GO에는 `targetSystem=ROUND`, 검증한 상대 room 경로, `purpose=MEETING_ENTRY`,
   만료 시각과 동일한 멱등 키만 보낸다. BATON 접근 키와 원래 전체 URL은 보내지 않는다.
6. BATON은 GO 응답의 대상·목적·만료·폐기 상태가 요청과 같은지 확인하고, short URL이
   설정된 GO public origin의 canonical `/l/{code}`인지 검증한 뒤 `BATON_GO`
   navigation URL로 브라우저에 돌려준다.

GO 호출은 역할 자료 생성·수정 transaction 또는 workspace projection 조회 transaction
안에서 실행하지 않는다. 기존 조회 유스케이스가 반환되어 transaction이 끝난 다음 별도
application service가 outbound port를 호출한다.

GO 링크 만료는 클릭 시점부터 최대 15분으로 제한한다. 같은 클릭 intent를 재시도할 때는
동일한 UUID와 만료 시각을 사용한다. 공개 short URL, 관리 credential과 BATON 접근 키는
로그에 남기지 않는다.

## 결과

- 일반 자료 링크의 기존 동작과 가용성은 GO 장애와 분리된다.
- ROUND 회의 링크만 명시적인 allowlist와 짧은 만료 정책을 사용한다.
- short URL은 ROUND room 위치를 가리키는 locator일 뿐 입장 credential이 아니다.
- GO가 활성화된 ROUND 링크 발급에 실패하면 BATON은 원본으로 조용히 우회하지 않고
  안정적인 gateway 오류를 반환한다.
- 클릭마다 GO 링크 행이 생기므로 만료 데이터 정리와 링크 폐기 UI는 후속 운영 범위다.

## 검토한 대안

### 모든 외부 URL을 GO에 전달

GO의 신뢰 대상 allowlist를 무력화하고 open redirect 책임을 확대하므로 채택하지 않았다.

### 역할 자료 저장 transaction에서 미리 short URL 생성

원격 장애가 BATON 저장을 막고 두 서비스 사이의 부분 성공을 만들기 때문에 채택하지
않았다.

### BATON 접근 키를 GO target이나 query에 포함

브라우저 기록, proxy와 애플리케이션 로그로 credential이 유출될 수 있으므로 금지한다.
