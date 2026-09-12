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
| 백업 중단 감시 | Better Stack 무료 하트비트 | 새 백업의 원격 검증이 끝나면 완료 신호를 보낸다. 홈서버 정전으로 신호가 끊겨도 외부에서 감지한다. |
| 원격 백업 | Google Drive API를 지원하는 rclone + crypt | 기존 원격 저장 기능을 사용한다. 직접 다운로드·해시 비교하던 코드는 rclone의 `check --download`로 대체했다. |
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

## 원격 백업: Google Drive + rclone

Google 계정의 무료 저장 공간은 Gmail·Drive·Photos가 공유하는 [15GB](https://support.google.com/googleone/answer/9004013?hl=en)다. 백업 전용 계정의 남은 공간 안에서 사용하고 저장 공간 업그레이드를 구매하지 않는다.

- [rclone Drive 설정](https://rclone.org/drive/)으로 `baton_drive`를 만든다. 가능한 경우 `drive.file` 범위로 백업 도구가 만든 파일에만 접근하게 한다. OAuth 토큰 갱신은 rclone이 처리한다.
- [crypt 원격 저장소](https://rclone.org/crypt/) `baton_crypt`를 만들고 대상은 `baton_drive:baton-backups`로 지정한다. 파일 내용·이름 암호화를 켜고 복호화 비밀을 별도 보관한다.
- 기존 `BATON_RCLONE_REMOTE=baton_crypt:daily`를 그대로 사용한다. `rclone about baton_drive:`로 남은 공간을 확인한다. 원격 백업을 자동 삭제하거나 유료 공간으로 자동 전환하지 않는다.
- 업로드 후와 로컬 보존 기간 만료 시 [rclone check --download](https://rclone.org/commands/rclone_check/)로 해당 덤프·체크섬만 비교한다. 암호화 저장소에서도 복호화한 실제 내용을 비교하며 원격 파일은 수정하지 않는다.

용량 부족·OAuth 해제·암호화 키 유실은 각각 업로드 실패·접근 실패·복구 불가로 이어진다. 파일럿 데이터 투입 전 별도 환경의 복원 훈련은 계속 필요하다.

## 활성화 전 남은 항목

이번 변경은 공급자 연동 코드·설정 예시와 검증을 제공한다. 공급자 계정 생성, 자격 증명 발급, 실제 DNS 변경, 공개 HTTPS 감시·메일 수신·Google Drive 업로드는 실행하지 않았다. 해당 항목은 [HANDOFF](../../HANDOFF.md)에서 관리한다.
