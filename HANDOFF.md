# HANDOFF

- `codex/manager-integration-identity-20260730`에서 역할 바통 `V13`과 GO·ROUND 역할
  자료 링크 흐름을 한 기준선으로 합치고, 공급자 중립적인 `UserAccount`와
  `MemberIdentityBinding`을 `V14`로 추가했다.
- 사용자 계정과 팀 구성원 결속은 기존 roster를 보존하고 같은 팀에서 한 계정당 구성원
  하나, 구성원당 계정 하나만 허용한다. 동일 결속 재시도는 최초 `boundAt`을 유지하고,
  활동 종료 구성원과 다른 계정·구성원 재결속은 거절한다.
- 아직 로그인·세션·초대 HTTP endpoint는 열지 않았다. 현재 공유 접근 키나 요청 header로
  `AuthenticatedAccount`를 만들면 사칭 경계가 되므로, 최초 OIDC 공급자와 기존 팀의
  일회성 member invite·owner bootstrap을 결정한 뒤 web adapter를 연결해야 한다.
- 2026-07-30에 격리 MySQL과 BATON, BATON GO, ROUND web을 실제로 띄우고 Chromium에서
  `역할 자료 클릭 → BATON GO 302 → ROUND 초대 화면 → 입장 준비 → 프리조인` 흐름을
  확인했다. ROUND signaling은 일부러 기동하지 않았고 프리조인까지 `/signal` 요청은
  0건이었다.
- 브라우저 trace에서 BATON GO와 ROUND navigation 요청에는 BATON 접근 키,
  `Authorization`, `Referer`, query, fragment가 없었다. GO 응답은 `Cache-Control:
  no-store`, `Referrer-Policy: no-referrer`를 유지했다.
- 같은 BATON GO 생성 intent를 재생하면 `201 → 200`과 동일한 22자 short URL을
  반환했고, 활성 중에는 ROUND room으로 `302`, 만료 뒤에는 `410`을 반환했다. 임시 DB에는
  공개 코드와 멱등성 키 대신 각각 64자 해시만 저장됐다.
- 프런트는 같은 클릭의 실패 재시도에 UUID와 만료 시각을 재사용한다. ROUND의 BATON
  인증형 room-aware signaling/TURN endpoint와 자동 signaling 재연결 직전 비동기 갱신
  hook은 `codex/round-grant-refresh-20260730`에 구현했다. GO URL에는 이후에도 BATON 접근
  키나 ROUND join ticket을 넣지 않는다.
- BATON 참여권 발급기는 아직 구현하지 않는다. 현재 팀 전체 공유 키만으로는
  ROUND JWT의 실제 사용자 `sub`, membership과 `host|participant`를 증명할 수 없다. 팀
  공통 subject는 세 번째 참가자를 막고, 매번 임의 subject는 사용자별 연결 제한과 감사를
  우회한다.
- 다음 구현 경계는 범용 OIDC + BATON 내부 계정 UUID + MySQL opaque session을 채택할지,
  최초 공급자와 기존 팀 bootstrap을 확정하는 것이다. 그 결정 뒤 일회성 구성원 초대,
  BATON grant API·RS256/JWKS·same-origin edge와 ROUND web의 최초 입장·TURN 갱신
  provider를 함께 구현하고 실제 `grant → TURN → WSS`를 검증한다. 권한 행렬 전에는
  `participant`만 발급하고 `host`는 보류한다.
- 파일럿 배포 사전점검·상태 감지와 기본 비활성화된 `External health sentinel` 구현·정적 검증은 완료했지만 실제 공개 URL의 저장소 변수는 설정하지 않았다.
- 워크플로가 `main`에 반영된 뒤 README 순서대로 실제 URL의 수동 성공을 확인하고 예약 검사를 활성화한다.
- 첫 예약 실행과 담당 계정의 GitHub Actions 실패 알림 수신은 아직 검증하지 않았다.
- 첫 실제 복구 리허설에서 `restore.sh`의 팀별 공유 키 무효화, `last-restore-recovery-targets.tsv`를 이용한 새 키 발급과 이전 링크의 `403`을 확인해야 한다.
