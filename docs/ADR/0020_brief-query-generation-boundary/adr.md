# ADR-0020: BATON이 BRIEF 조회 권한과 내구성 있는 생성 실행을 소유한다

- 상태: 채택
- 결정일: 2026-08-29

## 배경

BRIEF는 BATON 연속성 이벤트에서 관심 항목과 불변 에디션을 만든다. 사용자가 BRIEF를 직접
호출하면 BATON의 계정·팀·시즌 권한을 우회하고, BRIEF에 같은 사용자 모델을 복제하게 된다.
에디션 생성도 단순 HTTP 재시도로 구현하면 어떤 BATON 이벤트 전달 경계를 반영하려 했는지와
응답 유실 뒤 같은 의도인지 판단할 근거가 없다.

## 결정

- BATON 사용자 API가 활동 중인 계정 세션과 팀 멤버십을 확인한다. 계정 권한을 사용하는
  팀은 기존 팀 권한을, 공유 키 방식의 팀은 워크스페이스 접근 키를 검사한다.
- BATON은 BRIEF 불변 에디션과 `ETag`를 변형하지 않고 중계한다. 식별자 단건과 비교는
  양쪽 에디션의 팀·시즌을 확인한 뒤 제공한다.
- 현재 업무명·이동 대상과 생성 준비 상태는 별도 BATON 조회로 제공하고 불변 본문에 합치지 않는다.
- 저장 이후 추가 전달 여부도 같은 에디션에 성공 확인한 BATON 전달 경계와 outbox에서
  조회한다. BRIEF 수신 순번과 비교하거나 불변 에디션 내용을 최신 여부 판정으로 덮어쓰지 않는다.
- BATON이 시즌 시간대의 현재 월요일과 완료된 BRIEF outbox `deliveryWatermark`를 정한다.
- 생성 의도는 `(team, season, weekStart, zoneId, deliveryWatermark)`의 V27 실행 기록으로
  보존하고 1분 lease와 fencing token으로 최소 한 번 호출을 제어한다.
- 외부 호출은 MySQL 트랜잭션 밖에서 수행하고, 현재 lease token으로만 완료·실패를 기록한다.
- 이벤트 수신 token과 다른 서비스 Bearer, 비공개 HTTPS Caddy와 BATON truststore를 사용한다.
- BRIEF의 scheduler·대상 registry·사용자 인증을 추가하지 않는다.

## 결과

### 장점

- 사용자 권한과 서비스 데이터 소유권이 기존 BATON·BRIEF 경계를 유지한다.
- 응답 유실과 중복 요청에서도 같은 생성 의도를 재사용할 수 있다.
- 에디션이 어떤 BATON 전달 완료 경계를 기준으로 생성됐는지 운영 근거가 남는다.
- 이벤트 쓰기 자격과 조회·생성 자격이 분리되고 사설망에서도 Bearer를 암호화해 전송한다.

### 비용과 한계

- V27 실행 테이블, lease 회수와 결과 분류를 운영해야 한다.
- 사용자 생성 요청 전에 BRIEF outbox가 모두 전달돼야 하므로 일시적으로 `409`가 발생할 수 있다.
- 현재는 사용자 요청 재시도만 실행을 다시 claim하며 별도 운영자 재처리 API나 scheduler가 없다.
- 로컬 선택 실행 테스트로 실제 두 서비스 HTTPS 조립과 저장 상태 되돌리기 방식의 응답 유실
  재시도를 검증했지만, 실제 TCP 응답 절단과 공인 원격 스테이징은 별도로 확인해야 한다.

## 대안

- 브라우저가 BRIEF 직접 호출: BATON 권한 우회와 사용자 모델 복제가 필요해 채택하지 않았다.
- BRIEF가 생성 시점과 대상을 스케줄링: BATON의 권위 있는 시즌·운영 시점 소유권과 충돌해
  채택하지 않았다.
- HTTP 요청마다 새 생성 호출: 응답 유실 뒤 의도와 전달 경계를 식별할 수 없어 채택하지 않았다.
- BATON이 에디션을 재조합해 캐시: BRIEF 불변성·`ETag`와 감사 근거를 이중 소유하므로 채택하지
  않았다.

## 관련 문서

- [BATON 경유 BRIEF 에디션 조회와 생성](../../PRD/0008_brief-edition-query-and-generation/spec.md)
- [계정 식별성과 동일 출처 세션](../0017_account-identity-and-session/adr.md)
- [BRIEF 연속성 신호 생산 계약](../../PRD/0007_brief-continuity-signal-producer/spec.md)
