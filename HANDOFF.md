# HANDOFF

- 파일럿 배포 사전점검·상태 감지와 기본 비활성화된 `External health sentinel` 구현·정적 검증은 완료했지만 실제 공개 URL의 저장소 변수는 설정하지 않았다.
- 워크플로가 `main`에 반영된 뒤 README 순서대로 실제 URL의 수동 성공을 확인하고 예약 검사를 활성화한다.
- 첫 예약 실행과 담당 계정의 GitHub Actions 실패 알림 수신은 아직 검증하지 않았다.
- 실제 파일럿 데이터를 넣기 전 crypt remote에서 dump와 sidecar를 내려받아 별도 환경에 import하고, `last-restore-recovery-targets.tsv`를 따른 팀별 새 키 발급·이전 링크 `403`·복구 완료 재백업까지 자동 품질 게이트 밖의 실제 자격으로 확인해야 한다.
- 첫 그룹 스터디 실사용에서 조직 연속성 레이더가 놓칠 뻔한 책임이나 인수인계 공백을 한 번 이상 미리 발견하는지 확인하고 오탐과 행동 문구를 기록해야 한다.
- 첫 그룹 스터디에서 과거 결정의 결과·이유·관련 역할을 탐색 화면에서 짧은 흐름으로 다시 찾을 수 있는지 확인하고, 놓친 검색어·필터와 V14 이전 시각 미상 안내의 이해도를 기록해야 한다.
- 실제 public HTTPS staging에서 WATCH가 보낸 최초 health-change event와 응답 유실 뒤 같은 `eventId` 재전송이 BATON inbox 한 건으로 수렴하고 WATCH delivery backlog가 비는지 아직 검증하지 않았다.
- outbound monitor token과 event receiver token을 서로 다르게 배포하고 양쪽 로그에 인증값이 남지 않는지 확인한 뒤에만 `BATON_WATCH_EVENT_RECEIVER_ENABLED`와 WATCH callback 전달을 운영에서 활성화한다.
- 실제 Google·Naver·SMTP credential과 public HTTPS origin에서 세 로그인 흐름, callback 로그 비노출, 이메일 수신·검증과 session cookie 속성을 확인한 뒤에만 계정 인증 gate를 운영에서 활성화한다.
- 로컬에서는 `e2e:fullstack`이 실제 local session → AccountMembership claim → authoritative room mapping → participation refresh와 cookie·JWK 서명의 producer 경계를, 교차서비스 테스트가 BATON signer·JWK → ROUND TURN·WebSocket의 consumer 경계를 각각 고정한다. 다음 과제는 public HTTPS staging의 Caddy·ROUND route에서 두 경계를 연결해 같은 Account `sub`의 refresh → TURN → WebSocket 입장과 실제 배포 key 회전을 검증하는 것이다.
