# 남은 작업

## 공통 운영 준비

- 실제 배포할 BATON·웹·MySQL 이미지에 [Trivy 검사](docs/runbooks/free-integrations.md#배포-이미지-취약점-검사-trivy)를 실행하고 HIGH·CRITICAL 발견 항목을 조치한다. 로컬 테스트 이미지 검사 결과를 운영 이미지 검사 결과로 대신하지 않는다.

- [무료 외부 연동](docs/runbooks/free-integrations.md)에 따라 Brevo 무료 SMTP·Better Stack 공개 상태/백업 하트비트·Google Drive crypt 원격 저장소의 계정과 비밀값을 준비한다. `b4ton.com`의 실제 HTTPS·메일 수신·첫 하트비트·원격 업로드/복원을 확인한다.
- 외부 상태 감시는 Better Stack 또는 기존 GitHub Actions 중 하나를 선택한다. GitHub Actions를 사용하면 `BATON_HEALTH_URL=https://b4ton.com/actuator/health`로 수동 성공을 확인한 뒤 예약 검사를 켠다. Better Stack 전환 후에는 `BATON_EXTERNAL_MONITOR_ENABLED=false`로 기존 예약 검사를 끈다.
- [내부 연동 장애 알림](docs/runbooks/free-integrations.md#내부-연동-장애-알림-prometheus--alertmanager)의 수집기를 앱과 같은 네트워크 공간에 연결하고 Alertmanager 내부 주소·SMTP 비밀·운영자 수신 주소를 준비한다. 실제 지표 수집과 장애·해제 메일을 확인한 뒤 중복되는 `baton-integration-delivery.timer`를 끈다. 외부 Better Stack 감시는 유지한다.
- [공개 인증서 만료 알림](docs/runbooks/free-integrations.md#공개-인증서-만료-알림-blackbox-exporter)의 Blackbox Exporter 내부 주소와 설정을 연결한다. 실제 만료일 수집·경보·갱신 후 해제 메일을 확인한다. Cloudflare 프록시 사용 시 원본 인증서는 별도로 확인한다. Exporter 준비 전에는 `baton-https` 작업과 해당 경보 파일을 함께 제외한다.
- Dependabot 설정이 기본 브랜치에 반영되면 첫 `docker-compose` 점검에서 [대상 파일](docs/runbooks/free-integrations.md#운영-이미지-업데이트-github-dependabot)과 그룹 PR을 확인한다. 이미지 변경 제안은 기존 CI를 통과한 뒤 검토하며, 비공개 저장소의 Actions 초과 사용 차단을 확인한다.
- 파일럿 데이터를 넣기 전에 암호화 원격 백업을 별도 환경에 복구한다. `last-restore-recovery-targets.tsv`의 팀별로 새 키를 발급하고 이전 링크의 `403`, 새 링크의 접근과 복구 후 재백업을 확인한다.
- 아래 기능별 공개 HTTPS·실기기 검증을 마친 뒤 운영을 활성화한다. 로컬 검증만으로 완료 처리하지 않는다.

## BRIEF

- 공개 이벤트 전달 주소는 `https://brief.b4ton.com`이다. 서버 위치·접속 방법·IP·공인 HTTPS를 준비해야 한다. 같은 호스트를 사용하면 Caddy의 80·443 포트를 먼저 통합한다.
- BRIEF 선정 규칙 v2와 주간 해결 상세 API를 먼저 배포한 뒤 BATON을 적용한다. 조회·생성은 내부 `brief-service:8443`과 별도 서비스 인증을 사용하며 공개 Caddy에는 조회 경로를 추가하지 않는다.
- 전용 Bearer 토큰 파일, HTTPS 원본 주소와 truststore를 준비한다. 전달을 끈 채 재조정 작업을 한 번 실행해 초기 점검 이벤트와 아웃박스를 확인한 뒤 전달을 켠다. `./ops/check-integration-delivery.sh`와 `integration="brief"` 지표에서 전달 상태를 확인한다.
- 실제 HTTPS에서 장애 재시도·동일 이벤트 재전달·심각도 변경·해결 반영을 확인한다. 새 토큰과 직전 토큰을 함께 허용하는 교체 기간도 검증한다. 전달 진단·지표는 실제 수집·경보 시스템에 연결한다.
- 로그인한 사용자로 점검·해결 상세, 요약 조회·생성·재사용·비교, 종료 시즌 조회, 특정 요약 링크의 로그인 복귀와 인쇄·PDF 저장을 확인한다.
- 이 검증 전에는 계약 핀 `2.0.0-rc.4`를 안정 버전으로 올리지 않는다. 기준은 [업무 점검](docs/PRD/0009_brief-current-attention/spec.md)과 [주간 요약 탐색·공유·출력](docs/PRD/0010_brief-navigation-and-readiness/spec.md)이다.

## CAL

- 전용 Bearer를 소유자 전용 파일에 저장하고 `BATON_CAL_BEARER_TOKEN_FILE`에 절대 경로를 설정한다. 운영 사전점검과 기존 문자열의 NFC·제어 문자 검사를 통과한 뒤 [PRD-0006](docs/PRD/0006_calendar-integration-contract/spec.md) 순서로 캡처·보정·전달을 활성화한다.
- 계약 `1.1.0-rc.1`의 자산·증명은 확인했지만 운영 안정 기준은 정식 승격 전까지 `1.0.0`이다. CAL V7 배포를 확인하기 전까지 `BATON_CAL_SEASON_METADATA_ENABLED=false`, 보정 모드 `OFF`를 유지한다.
- 실제 시즌 피드와 캘린더 앱의 이름·일정 변경을 확인한다. 전달을 켜기 전에 `./ops/check-integration-delivery.sh`, `./ops/show-integration-metrics.sh`와 DB 상태를 확인한다.
- 복원 훈련에서는 같은 복구 ID로 준비·전달을 실행한다. 최신 아웃박스의 전달과 CAL `COMPLETED`를 확인한 뒤 복구 모드를 해제한다. 이름 `REPLAY` 준비나 대기 행 수만으로 완료 처리하지 않는다.
- 개인 구독은 `1.1.0-rc.2` 개발 소스로만 검증했다. `contracts/baton-cal/candidate/source.properties`의 후보 해시를 공식 릴리스 자산·증명과 대조한 뒤 계약을 채택한다. 그전에는 구독 기본 비활성을 유지한다.
- 팀 권한과 개인 구독을 함께 배포할 때는 V34·V35가 필요하다. 공개 HTTPS·실제 앱에서 등록·변경·취소·이름 갱신과 본인 구독 해제를 확인한다.
- 앱별 안내 링크와 실제 구독 등록은 Google·Apple·Outlook 계정에서 확인한다. 브라우저 대역 검증에는 외부 계정 등록이 포함되지 않는다.

## WATCH

- 모니터 전송 토큰과 이벤트 수신기 토큰을 서로 다르게 배포한다. 로그에 인증값이 남지 않는지 확인한다.
- 공개 HTTPS에서 최초 상태 변경 전달과 응답 유실 뒤 같은 `eventId`의 재전송을 검증한다. BATON 인박스에는 한 건만 저장되고 WATCH 전달 대기는 해소돼야 한다. 확인 후 운영 수신을 활성화한다.
- WATCH `3a04e7b` 이상과 BATON 상태 조회·재점검 API를 배포한 뒤 실제 HTTPS에서 정상·오래된 결과·연결 장애·재점검 `429`를 확인한다. 상태는 이벤트 인박스가 아닌 WATCH 현재 조회를 사용한다. [PRD-0004](docs/PRD/0004_watch-integration-contract/spec.md)
- 조회 분산·중복 요청 합류, 실패 원인·횟수, 편집 제한 중 상태 조회, 점검 제외·동기화 대기를 확인한다. 내부 오류로 최근 시도만 갱신돼도 5분 이상 지난 판정이 유효해지지 않아야 한다.
- 역할 이동·오프라인·조회 실패 뒤 재점검 대기시간과 접수·새 결과 안내를 확인한다. 새 요청·URL·계정 변경 시 이전 상태가 섞이지 않아야 한다. 스크린리더가 수동 조회와 상태 변경을 알리고 같은 상태의 자동 조회는 반복해 읽지 않는지 확인한다.
- WATCH 독립 복원은 WATCH 저장소의 `docs/runbooks/baton-snapshot-recovery.md`에 따라 BATON 최신 아웃박스를 내보내 대조하고 같은 리비전으로 재전송한다. 더 높은 원격 리비전은 자동으로 바꾸지 않는다.
- 2026-09-05 `watch-staging.b4ton.com`은 네트워크 제한 밖에서도 DNS 조회에 실패했다. 실제 BATON 주소·시험 자료·인증 설정을 준비해 공개 통합 검증을 완료한다.

## 로그인·계정 보안

- [Turnstile 설정](docs/runbooks/free-integrations.md#가입재설정-봇-방지-cloudflare-turnstile)에 따라 Managed 위젯·허용 호스트·사이트 키·비밀 키를 준비한다. 공개 HTTPS에서 가입·재설정 토큰의 성공·만료·재사용·공급자 장애와 실제 메일 수신을 확인한 뒤 활성화한다. k3s Ingress에도 문서의 CSP를 적용한다.
- 실제 Google·Naver·SMTP 자격 증명과 공개 HTTPS에서 로그인 세 가지, 콜백 로그 비노출, 이메일 수신·인증과 세션 쿠키를 확인한다. `integration="email"`의 조치 대상 실패가 `0`이고 전달 점검이 성공한 뒤 계정 인증 기능을 활성화한다.
- V30 배포 후 비밀번호 재설정 메일 수신, 새 비밀번호 로그인과 두 브라우저의 기존 세션 거부를 확인한다. 성공 후 `BATON_AUTH_PASSWORD_RESET_ENABLED=true`로 가입 기능과 별도 활성화한다. 재설정으로 공유 키·발급된 ROUND 참여권은 폐기되지 않는다.
- `/account`에서 잘못된 현재 비밀번호는 기존 비밀번호·세션을 유지하는지 확인한다. 올바른 비밀번호 변경과 모든 기기 로그아웃은 현재 브라우저를 로그아웃하고 다른 세션을 다음 요청에서 거부해야 한다. 같은 기기의 공유 키는 유지돼야 한다.
- 테스트 계정 비활성화에서 마지막 관리자 거부, 모든 기존 세션 거부, 자체 이메일·소셜 재로그인 차단과 개인 CAL 구독의 실제 해지를 확인한다. 공유 키와 조직 기록은 보존한다.

## 팀 권한 전환·복원

- 공개 HTTPS에서 운영자 복구 키로 첫 관리자를 지정한다. 두 계정·두 기기로 초대 수락·권한 회수·기존 공유 링크 거부·본인 명의 인수인계 확인을 검증한 뒤 팀을 전환한다. 기존 팀은 자동 전환하지 않는다.
- 계정 권한 팀은 자동 복원 완료를 지원하지 않는다. `restore.sh`는 DB를 불러온 뒤 완료 처리를 중단하고 권한·초대 데이터를 유지한다. 서버 공개 전에 격리 환경에서 백업 이후 권한 회수·초대 취소 이력과 대조한다.

## ROUND

- 실제 릴리스 다이제스트와 외부 coturn을 공개 HTTPS 스테이징에 배포한다. UDP·TCP·TLS 중계, 실제 OAuth 계정의 동일 `sub` 입장과 서명 키 중첩 교체를 검증한다.

## 추가 기능

- 공개 HTTPS의 iOS·Android에서 작업 공간 `공유`의 앱 선택·취소와 전달된 링크의 접속을 확인한다. 공유 키 팀은 현재 키가 유지되고, 계정 권한 팀은 로그인·팀 권한이 적용돼야 한다. 현재는 브라우저 API 대역 검증이며 외부 앱의 실제 수신은 확인하지 않았다. [지원 조건](docs/runbooks/free-integrations.md#기기-앱-공유-web-share-api)
- 무료 공휴일 연동은 공공데이터포털 ‘한국천문연구원_특일 정보’ 활용 신청과 일반 인증키(Decoding) 파일이 필요하다. [README](README.md#무료-공휴일-연동)에 따라 설정하고 HTTPS에서 공휴일 이름·월 전환·장애 표시를 확인한다. 현재는 XML 대역 검증만 했으며 기본 비활성이다.
- V36~V39 배포 전에 기존 결정·자료·계정 데이터 보존을 확인한다. 수정 이력은 배포 이후부터 남으며 과거 수정은 소급하지 않는다.
- 공개 HTTPS의 두 기기에서 내 팀 전환·모든 팀의 내 할 일·권한 회수, 자료 재확인 날짜·오늘 목록, 개인 알림 설정·읽음 유지와 모든 시즌 검색을 확인한다.
- 스터디·팀 운영 템플릿 선택과 본문 초안 복구를 확인한다. 결정·자료·체크리스트 초안은 같은 탭에서 새로고침 후 복구되고 계정 전환 시 섞이지 않아야 한다.

## 파일럿 관찰

- 첫 그룹 스터디에서 ‘조치할 항목’이 담당자 공백·업무 누락을 미리 알려 주는지 관찰한다. 잘못 표시된 항목과 이해하기 어려운 안내를 기록한다.
- 결정 내용·이유·관련 역할을 검색해 다시 찾을 수 있는지 확인한다. 찾지 못한 검색어·필터와 작성 시각 미상 안내의 이해도를 기록한다.
