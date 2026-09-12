# 추가 비용 없는 외부 연동

검토 기준: 2026-09-12. 대상은 BATON 기능과 운영 작업의 외부 연동이다. Ubuntu 홈서버·k3s는 실행 환경으로만 전제한다.

## 검토 결과

| 기능 | 사용할 서비스·도구 | 판단과 반영 |
| --- | --- | --- |
| 대한민국 공휴일 | 한국천문연구원 특일 정보 API | 이미 구현되어 있다. 서비스 키를 연결하면 된다. 자체 휴일 계산이나 다른 유료 API를 추가하지 않는다. |
| 외부 캘린더 | CAL의 ICS 구독 → Google·Apple·Outlook | 기존 구독 기능을 사용한다. 갱신 주기는 캘린더 앱이 정하므로 실시간 양방향 동기화로 안내하지 않는다. |
| 가입·비밀번호 재설정 메일 | Brevo 무료 SMTP | 기존 Spring Mail 어댑터에 접속 설정만 연결한다. 공급자 전용 HTTP 클라이언트는 추가하지 않는다. |
| 로그인 | Google OIDC·Naver OAuth2 | 이미 구현되어 있다. 기존 공급자 설정과 콜백을 사용한다. |
| 서비스 장애 감시 | Better Stack 무료 모니터 | 상태 조회·이력·이메일 알림을 외부 서비스가 처리한다. BATON 등록용 API 요청 파일을 추가했다. |
| 내부 연동 장애 알림 | Prometheus + Alertmanager → Brevo SMTP | 기존 지표의 경보 규칙과 SMTP 수신 설정을 추가했다. 알림 묶기·재통지·해제는 표준 도구가 처리한다. |
| 백업 중단 감시 | Better Stack 무료 하트비트 | 새 백업의 원격 검증이 끝나면 완료 신호를 보낸다. 홈서버 정전으로 신호가 끊겨도 외부에서 감지한다. |
| 원격 백업 | Google Drive API를 지원하는 rclone + crypt | 기존 원격 저장 기능을 사용한다. 직접 다운로드·해시 비교하던 코드는 rclone의 `check --download`로 대체했다. |
| 라이브러리·운영 이미지 업데이트 점검 | GitHub Dependabot | 기존 Java·npm·Actions·Dockerfile 점검에 Compose의 MySQL·Caddy를 추가했다. 새 버전 조회와 PR 생성은 GitHub가 처리한다. |
| 자료 URL 점검·인수인계·주간 요약 | WATCH·BATON·BRIEF | 팀 권한·변경 이력과 연결된 제품 기능이다. 무료 모니터의 제한된 슬롯이나 일반 자동화 서비스로 대체하지 않는다. |

공휴일 설정은 [README](../../README.md#무료-공휴일-연동), 캘린더는 [CAL 계약](../PRD/0006_calendar-integration-contract/spec.md)을 따른다. 새 알림 채널은 RELAY가 소유하므로 BATON에 별도 발송 경로를 만들지 않는다. 화상회의는 기존 ROUND를 유지한다. 무료 사용량 이후 종량 과금이 발생할 수 있는 외부 TURN·SMS·AI API는 추가하지 않는다.

## 도메인 기준

| 서비스 | 공개 주소 |
| --- | --- |
| BATON | `https://b4ton.com` |
| CAL | `https://cal.b4ton.com` |
| WATCH | `https://watch.b4ton.com` |
| BRIEF | `https://brief.b4ton.com` |
| ROUND | `https://round.b4ton.com` |
| GO | `https://go.b4ton.com` |
| RELAY | `https://relay.b4ton.com` |

공개 주소가 정해져도 내부 관리 API를 함께 공개하지 않는다. BRIEF 사용자 조회·요약 생성은 기존 내부 HTTPS 계약을 유지한다. ROUND의 BATON 참여권·쿠키 흐름도 기존 같은 출처 경로를 유지하며, 서브도메인만 바꿔 연결하지 않는다. 설정 예시는 [프로덕션 환경 파일](../../.env.production.example)에 반영했다.

## 이메일: Brevo SMTP

[Brevo 트랜잭션 메일](https://www.brevo.com/products/transactional-email/)의 무료 한도는 하루 300건이다. 유료 플랜·추가 크레딧을 구매하지 않는다. 한도에 도달하면 발송을 제한하며 다른 유료 공급자로 자동 전환하지 않는다.

1. 무료 계정에서 `b4ton.com`과 발신 주소를 인증한다. 공급자가 제시한 도메인 인증 레코드를 Cloudflare DNS에 등록한다.
2. [SMTP 설정](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP)에서 SMTP 로그인과 **SMTP 키**를 발급한다. REST API 키와 구분한다.
3. SMTP 키를 저장소 밖 `0600` 파일에 저장하고 다음 공개 설정과 파일 경로를 기존 운영 환경에 연결한다.

```dotenv
BATON_EMAIL_VERIFICATION_DELIVERY=smtp
BATON_EMAIL_FROM_ADDRESS=no-reply@b4ton.com
BATON_SMTP_HOST=smtp-relay.brevo.com
BATON_SMTP_PORT=587
BATON_SMTP_USERNAME=<공급자가 발급한 SMTP 로그인>
BATON_SMTP_PASSWORD_FILE=/srv/baton/secrets/smtp-password
```

기존 Compose 설정은 587·STARTTLS·인증서 확인을 사용한다. 다른 실행 환경에서도 같은 Spring Mail 속성과 비밀 파일을 전달한다. 실제 메일 수신을 확인한 뒤 가입·비밀번호 재설정 게이트를 각각 연다. 무료 한도로 발송이 지연될 수 있으므로 기존 아웃박스와 실패 지표는 유지한다.

## 장애 감시: Better Stack

[무료 플랜](https://betterstack.com/pricing)은 모니터·하트비트 합계 10개와 이메일 알림을 제공한다. 우선 BATON 공개 상태 1개와 백업 하트비트 1개를 등록한다. 나머지 서비스는 공개 상태 경로를 확인한 뒤 남은 슬롯에 등록한다. CAL 구독 토큰·공유 링크·로그인 페이지는 상태 검사 대상으로 사용하지 않는다.

### 공개 서비스 상태

[등록 요청 파일](../../ops/integrations/better-stack-monitor.json)은 `https://b4ton.com/actuator/health`, `"status":"UP"`, 3분 주기, HTTPS 인증서 확인과 이메일 알림을 사용한다. 미배포 주소에 불필요한 장애 알림을 보내지 않도록 일시 정지 상태로 등록한다.

UI에서 동일한 설정으로 등록하거나 [공식 모니터 API](https://betterstack.com/docs/uptime/api/create-a-new-monitor/)를 사용한다. API를 쓰는 경우 저장소 밖의 소유자 전용 curl 설정 파일에 `header = "Authorization: Bearer <팀 API 토큰>"`을 넣고 다음 요청을 한 번 실행한다. 같은 모니터가 있으면 새로 만들지 말고 기존 설정을 수정한다.

```bash
curl -q --config /srv/baton/secrets/better-stack-api.curl \
  --fail --silent --show-error \
  --json @ops/integrations/better-stack-monitor.json \
  https://uptime.betterstack.com/api/v2/monitors
```

공개 HTTPS 검증 후 감시를 재개하고 실제 이메일 수신을 확인한다. 기존 GitHub Actions 예약 감시와 중복 운영할 필요는 없다. Better Stack으로 전환을 확인하면 `BATON_EXTERNAL_MONITOR_ENABLED=false`로 기존 예약 검사만 끈다.

### 백업 하트비트

1. Better Stack에서 하트비트를 만든다. 기존 일일 백업은 기대 간격 24시간, 유예 3시간으로 설정한다. 백업 주기를 바꾸면 두 설정도 함께 바꾼다.
2. 발급된 비밀 URL을 `/srv/baton/secrets/backup-heartbeat-url`에 저장한다. 파일은 `0600`, 상위 디렉터리는 `0700`으로 둔다.
3. 기존 백업 환경에 다음 값을 추가한다.

```dotenv
BATON_BACKUP_HEARTBEAT_URL_FILE=/srv/baton/secrets/backup-heartbeat-url
```

`backup-cycle.sh`는 새 덤프 생성·업로드·원격 검증이 모두 성공한 뒤에만 [완료 신호](https://betterstack.com/docs/uptime/cron-and-heartbeat-monitor/)를 보낸다. 기존 대기 백업만 재전송했거나 잠금 획득·백업·원격 검증이 실패하면 보내지 않는다. URL은 프로세스 인자나 로그에 기록하지 않는다. DB 내용과 작업 로그도 외부에 보내지 않는다.

감시 API 장애는 백업 성공 상태를 취소하지 않는다. 로컬에 통보 실패를 남기며, 외부에서는 기한 안에 신호가 없으면 알림을 보낸다. **첫 신호를 받은 뒤부터 감시가 시작**되므로 실제 첫 수신을 확인해야 한다. Kubernetes 작업에서 재사용할 때도 완료 신호는 실제 백업·원격 검증의 마지막 단계에 연결한다. 기존 Compose용 백업 스크립트를 k3s 전용 스크립트로 간주하지 않는다.

## 내부 연동 장애 알림: Prometheus + Alertmanager

CAL·WATCH·BRIEF·이메일 전달은 공개 상태가 `UP`이어도 실패할 수 있다. 기존 Micrometer 지표를 [Prometheus 경보와 Alertmanager](https://prometheus.io/docs/alerting/latest/overview/)에 연결한다. 두 도구는 Apache 2.0 라이선스로 제공되며 자체 실행에 공급자 사용료가 없다. 메일은 위 Brevo 무료 한도를 가입·재설정 메일과 함께 사용한다.

### 연결 설정

- [prometheus.yml](../../ops/integrations/prometheus.yml): 30초마다 BATON 연동 지표를 수집하고 Alertmanager에 경보를 전달한다. 지표를 외부 저장 서비스로 전송하지 않는다.
- [baton-alerts.yml](../../ops/integrations/baton-alerts.yml): 수집·갱신 장애, 전달 실패와 처리 지연을 판단한다. `prometheus.yml`과 같은 디렉터리에 둔다.
- [alertmanager.yml.example](../../ops/integrations/alertmanager.yml.example): SMTP 로그인과 운영자 이메일을 실제 값으로 바꾸고 비밀 파일을 연결한다. [표준 SMTP 설정](https://prometheus.io/docs/alerting/latest/configuration/#file-layout-and-global-settings)으로 STARTTLS를 사용하며 비밀번호 원문은 설정에 넣지 않는다. Alertmanager는 예시의 문자열이나 환경 변수 참조를 자동 치환하지 않으므로 실행 전에 실제 값으로 작성한다.

BATON은 `/actuator/prometheus`를 `127.0.0.1`에서만 허용한다. **수집기는 앱과 같은 네트워크 공간에서 실행**해야 한다. 향후 k3s에서는 같은 Pod의 사이드카가 이 조건을 충족한다. 다른 Pod에서 `app:8080`을 조회하는 설정으로 바꾸면 접근이 거부된다. 수집기와 Alertmanager의 내부 통신을 허용하고 예시의 `alertmanager:9093`을 실제 내부 주소로 지정한다. 지표·관리 화면을 `b4ton.com`이나 서비스 공개 주소에 노출하지 않는다. 이 파일들은 연동 설정이며 서버·k3s 설치 파일은 아니다.

### 경보 기준

| 상태 | 경보 조건 |
| --- | --- |
| 지표 수집 불가 | 수집 실패 또는 대상 누락이 2분 지속 |
| 연동 상태 확인 불가 | 갱신 실패, 갱신 시각 미설정·미래·120초 초과 또는 필수 지표 누락이 2분 지속 |
| 조치 대상 전달 실패 | 영구 실패 항목이 5분 지속 |
| 전달 처리 지연 | 처리 기한을 넘긴 항목이 5분 지속 |

연동 지표는 `calendar`, `calendar_metadata`, `watch`, `brief`, `email`의 실패·처리 지연 각 1개를 요구한다. 연동 종류나 지표를 변경하면 규칙과 테스트를 함께 갱신한다. 지표가 유효하지 않을 때는 과거 건수로 전달 장애를 판단하지 않고 상태 확인 불가를 알린다. `failed` 원시 이력은 사용하지 않으므로 만료된 이메일 인증과 해결된 과거 시즌 이름 실패를 다시 알리지 않는다.

Alertmanager는 같은 앱의 경보를 묶어 최초 30초 대기 후 보내고, 변경 사항은 5분 간격, 같은 장애의 재통지는 12시간 간격으로 처리한다. 경보 조건이 해소되면 해제 알림을 보낸다. 지표 수집 자체가 끊겨도 개별 전달 경보는 해제될 수 있으므로 수집·갱신 경보와 함께 판단한다. 데이터나 작업을 자동 재처리하지 않는다.

실제 수집·장애·해제 메일 수신을 확인한 뒤 `baton-integration-delivery.timer`를 사용 중이라면 해당 예약 검사만 끈다. 수동 `check-integration-delivery.sh`는 초기 점검과 진단에 계속 사용할 수 있다. 이 구성은 같은 홈서버가 중단되면 알릴 수 없으므로 외부 Better Stack 감시는 유지한다.

### 로컬 검증

```bash
bash ops/tests/integration-alerts-test.sh
```

Prometheus 3.14.0의 `promtool`과 Alertmanager 0.34.0의 `amtool`로 수집·경보·SMTP 설정과 장애 시나리오를 검사한다. Docker 이미지가 없으면 최초 실행에 다운로드가 필요하다. 검사는 네트워크가 차단된 임시 컨테이너에서 실행하며 실제 메일을 보내지 않는다. CI 품질 게이트에서도 같은 검사를 실행한다. 실행 중인 BATON의 수집과 실제 이메일 수신은 활성화 전에 별도로 확인해야 한다.

## 원격 백업: Google Drive + rclone

Google 계정의 무료 저장 공간은 Gmail·Drive·Photos가 공유하는 [15GB](https://support.google.com/googleone/answer/9004013?hl=en)다. 백업 전용 계정의 남은 공간 안에서 사용하고 저장 공간 업그레이드를 구매하지 않는다.

- [rclone Drive 설정](https://rclone.org/drive/)으로 `baton_drive`를 만든다. 가능한 경우 `drive.file` 범위로 백업 도구가 만든 파일에만 접근하게 한다. OAuth 토큰 갱신은 rclone이 처리한다.
- [crypt 원격 저장소](https://rclone.org/crypt/) `baton_crypt`를 만들고 대상은 `baton_drive:baton-backups`로 지정한다. 파일 내용·이름 암호화를 켜고 복호화 비밀을 별도 보관한다.
- 기존 `BATON_RCLONE_REMOTE=baton_crypt:daily`를 그대로 사용한다. `rclone about baton_drive:`로 남은 공간을 확인한다. 원격 백업을 자동 삭제하거나 유료 공간으로 자동 전환하지 않는다.
- 업로드 후와 로컬 보존 기간 만료 시 [rclone check --download](https://rclone.org/commands/rclone_check/)로 해당 덤프·체크섬만 비교한다. 암호화 저장소에서도 복호화한 실제 내용을 비교하며 원격 파일은 수정하지 않는다.

용량 부족·OAuth 해제·암호화 키 유실은 각각 업로드 실패·접근 실패·복구 불가로 이어진다. 파일럿 데이터 투입 전 별도 환경의 복원 훈련은 계속 필요하다.

## 운영 이미지 업데이트: GitHub Dependabot

[Dependabot 설정](../../.github/dependabot.yml)에 `docker-compose` 점검을 추가했다. GitHub는 [Dockerfile과 Compose를 별도 대상으로 지원](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories)한다. 기존 `docker` 설정만으로 빠져 있던 다음 파일의 MySQL·Caddy 이미지 버전과 고정 해시를 주 1회 확인한다.

- [개발 DB](../../docker-compose.yml)
- [운영 DB](../../compose.production.yml)
- [전체 스택 테스트 DB](../../compose.fullstack-e2e.yml)
- [ROUND 테스트 DB·Caddy](../../compose.round-fullstack-e2e.yml)

Compose 업데이트는 한 그룹으로 묶고 동시에 열린 PR을 1개로 제한한다. MySQL `8.5` 이상과 Caddy 메이저 버전 변경은 제외한다. MySQL 8.4 LTS·Caddy 2 범위 안의 제안을 기존 CI와 함께 검토하며, DB 상위 계열 전환은 별도 마이그레이션 작업으로 다룬다. 이미지 버전·해시는 이번에 변경하지 않았다.

[일반 GitHub 실행기의 Dependabot 업데이트 작업](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-on-actions)은 포함된 Actions 실행 시간을 사용하지 않는다. 업데이트 PR의 별도 CI에는 [기존 Actions 요금 기준](https://docs.github.com/en/billing/concepts/product-billing/github-actions)이 적용되므로 비공개 저장소에서는 무료 한도와 초과 사용 차단을 유지한다. 유료 대형 실행기를 연결하지 않는다.

설정이 기본 브랜치에 반영된 뒤 첫 Dependabot 실행에서 대상 파일과 업데이트 PR을 확인한다. 설정 검증과 대상 파일 확인은 완료했으며 실제 GitHub 업데이트 작업은 아직 실행하지 않았다. 셸 스크립트의 도구 이미지 버전과 ROUND의 계약·릴리스 고정값은 이 Compose 점검 대상에 포함되지 않는다.

## 활성화 전 남은 항목

이번 변경은 공급자 연동 코드·설정 예시와 검증을 제공한다. 공급자 계정 생성, 자격 증명 발급, 실제 DNS 변경, 공개 HTTPS 감시·메일 수신·Google Drive 업로드와 내부 지표 수집 활성화는 실행하지 않았다. 해당 항목은 [HANDOFF](../../HANDOFF.md)에서 관리한다.
