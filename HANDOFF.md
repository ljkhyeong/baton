# 인수인계

## BRIEF 연결의 남은 작업

- 공개 이벤트 전달 origin은 `https://brief.b4ton.com`으로 정했다. 서버는 미구축 상태이며
  설정 예시만 반영했다. 내부 조회·생성 대상은 기존 `brief-service:8443`을 유지한다. 같은
  호스트에 배치할 때는 두 공개 Caddy의 기본 80·443 포트가 충돌하므로 앞단 통합이 먼저다.
  DNS·공인 인증서·배포 완료를 뜻하지 않으며 서버 위치·접속 방법·IP가 필요하다.
- `codex/brief-attention-view`에서 분리한 `codex/brief-edition-carryover-ui` 작업 브랜치다.
  BRIEF 선정 규칙 v2·주간 해소 상세 응답과 내부 서비스 경로를 먼저 반영한 뒤 BATON을
  적용해야 한다. 서비스 인증서·비밀·비공개 HTTPS 준비 후 실제 로그인 사용자 화면에서
  해소 상세·비교·생성, 특정 생성본 링크의 로그인 복귀와 인쇄·PDF 저장을 확인한다.
  현재 브라우저 검증은 API 대역이며 운영체제 인쇄 창·실기기 저장과 공인 스테이징 검증은 남아 있다.
- 이벤트 계약 핀은 `2.0.0-rc.4`를 유지한다. 공인 HTTPS 스테이징 검증 전에는 안정 버전으로
  승격하지 않는다. BRIEF의 선택적 loopback 지표와 BATON의 기존 전달 진단 명령을 실제
  수집·경보 시스템에 연결하는 작업도 남아 있다.
- 제품 동작은 [PRD-0009](docs/PRD/0009_brief-current-attention/spec.md)와
  [PRD-0010](docs/PRD/0010_brief-navigation-and-readiness/spec.md)을 기준으로 확인한다.

## CAL 시즌 이름 연결의 남은 작업

- 전체 일정·필요한 취소·시즌 이름의 재전달 대상을 나타내는 매니페스트와 완료 신호를 정해야 한다.
  이름의 `REPLAY` 준비 완료나 대기 행 수만으로 CAL 복구 모드를 해제해서는 안 된다.
- 정식 채택 전 CAL 후보를 불변 사전 릴리스로 게시하고 검증된 자산으로 계약 핀을 갱신해야 한다.
  현재 안정 핀은 `1.0.0`이며 `BATON_CAL_SEASON_METADATA_ENABLED=false`와 보정 모드 `OFF`를
  유지한다. CAL V7 배포 확인 뒤 PRD-0006의 보정·복원 순서를 운영 환경에서 검증해야 한다.
  실제 캘린더 앱의 이름 갱신도 남아 있다.

## 다음 실행 순서

1. 실제 공개 URL을 `BATON_HEALTH_URL`에 설정하고 `외부 상태 감시` 워크플로의 수동 실행을 먼저 통과시킨다. 이후 `BATON_EXTERNAL_MONITOR_ENABLED=true`로 예약 검사를 켜고 첫 예약 실행과 담당 계정의 GitHub Actions 실패 알림 수신을 확인한다.
2. 실제 파일럿 데이터를 넣기 전에 암호화 원격 저장소의 덤프와 보조 파일을 별도 환경에 복구한다. `last-restore-recovery-targets.tsv`에 따라 팀별 새 키 발급, 이전 링크의 `403` 응답과 복구 완료 뒤 재백업까지 실제 자격 증명으로 확인한다.
3. CAL 전용 Bearer를 소유자 전용 파일에 저장하고 `BATON_CAL_BEARER_TOKEN_FILE`에 절대 경로를 설정해 프로덕션 사전점검을 통과시킨다. 기존 문자열의 NFC·제어 문자 적합성을 점검한 뒤 PRD-0006 순서대로 CAL 캡처와 보정을 먼저 활성화한다. `./ops/check-integration-delivery.sh`, `./ops/show-integration-metrics.sh`와 DB 상태가 정상이면 전달을 켜고 실제 시즌 피드를 확인한다.
4. BRIEF 전용 Bearer 파일과 HTTPS origin을 준비하고 전달을 끈 상태에서 재조정 주기를 설정해 초기 신호와 아웃박스를 확인한다. 이후 전달을 켜 `./ops/check-integration-delivery.sh`와 `integration="brief"` 공통 지표가 정상인지 확인하고, 재시도·동일 이벤트 재전달·심각도 변경·해소가 실제 BRIEF 관심 항목에 수렴하는지 확인한 뒤 새 token과 직전 token의 중첩 교체를 검증한다.
5. 외부 전송용 WATCH 모니터 토큰과 이벤트 수신기 토큰을 서로 다르게 배포하고 로그에 인증값이 남지 않는지 확인한다. 실제 공개 HTTPS에서 최초 상태 변경 이벤트와 응답 유실 뒤 같은 `eventId` 재전송이 BATON 인박스 한 건으로 수렴하고 WATCH 전달 적체가 비는지 확인한 뒤에만 운영 수신을 활성화한다.
6. 실제 Google·Naver·SMTP 자격 증명과 공개 HTTPS 출처에서 세 로그인 흐름, 콜백 로그 비노출, 이메일 수신·검증과 세션 쿠키 속성을 확인한다. `integration="email"`의 조치 대상 실패가 `0`이고 `./ops/check-integration-delivery.sh`가 성공하는지 확인한 뒤 계정 인증 게이트를 활성화한다.
7. 실제 릴리스 다이제스트와 외부 coturn을 공개 HTTPS 스테이징에 배포해 UDP·TCP·TLS 할당과 미디어 중계, 실제 OAuth 계정의 동일 `sub` 입장과 키 중첩 회전을 확인한다.
8. 5번의 WATCH 공개 스테이징 검증이 끝난 뒤에만 이벤트 순서·조정 정책을 확정하고 WATCH 상태 프로젝션과 UI를 구현한다.

## 파일럿 관찰

- 첫 그룹 스터디에서 조직 연속성 레이더가 놓칠 뻔한 책임이나 인수인계 공백을 한 번 이상 미리 발견하는지 확인하고 오탐과 행동 문구를 기록한다.
- 과거 결정의 결과·이유·관련 역할을 탐색 화면에서 짧은 흐름으로 다시 찾을 수 있는지 확인하고, 놓친 검색어·필터와 V14 이전 시각 미상 안내의 이해도를 기록한다.
