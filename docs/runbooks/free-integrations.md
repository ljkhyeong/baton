# 추가 비용 없는 외부 연동

검토 기준: 2026-09-12. 대상은 BATON 기능과 운영 작업의 외부 연동이다. Ubuntu 홈서버·k3s는 실행 환경으로만 전제한다.

## 검토 결과

| 기능 | 사용할 서비스·도구 | 판단과 반영 |
| --- | --- | --- |
| 대한민국 공휴일 | 한국천문연구원 특일 정보 API | 이미 구현되어 있다. 서비스 키를 연결하면 된다. 자체 휴일 계산이나 다른 유료 API를 추가하지 않는다. |
| 외부 캘린더 | CAL의 ICS 구독 → Google·Apple·Outlook | 기존 구독 기능을 사용한다. 갱신 주기는 캘린더 앱이 정하므로 실시간 양방향 동기화로 안내하지 않는다. |
| 작업 공간 공유 | Web Share API | 기기의 공유 창에서 설치된 메신저·메일 앱을 선택한다. 공급자 SDK·계정·서비스 키가 필요 없다. |
| 가입·비밀번호 재설정 메일 | Brevo 무료 SMTP | 기존 Spring Mail 어댑터에 접속 설정만 연결한다. 공급자 전용 HTTP 클라이언트는 추가하지 않는다. |
| 오류 발생 위치 수집 | Sentry React·Spring Boot SDK | 오류 종류·파일·줄만 전송한다. DSN이 없으면 비활성이다. |
| 메일 전달·반송 결과 | Brevo Webhook | 기존 SMTP 발송 ID로 결과를 연결하고 중복 수신을 합친다. 24시간 결과 지표와 경보를 추가했다. |
| 가입·재설정 요청 봇 방지 | Cloudflare Turnstile | 브라우저 위젯과 서버 Siteverify 검증을 연결했다. 기존 IP·이메일 요청 제한을 함께 사용한다. |
| 로그인 | Google OIDC·Naver OAuth2 | 이미 구현되어 있다. 기존 공급자 설정과 콜백을 사용한다. |
| 서비스 장애 감시 | Better Stack 무료 모니터 | 상태 조회·이력·이메일 알림을 외부 서비스가 처리한다. BATON 등록용 API 요청 파일을 추가했다. |
| 공개 인증서 만료 알림 | Blackbox Exporter → Prometheus·Alertmanager | 만료 14일 전부터 선택한 운영 채널로 알린다. 유료 만료 알림이나 인증서 파싱 코드를 추가하지 않는다. |
| 내부 연동 장애 알림 | Prometheus + Alertmanager → Brevo SMTP 또는 Discord | 기존 지표의 경보 규칙과 수신 설정을 사용한다. Discord를 선택하면 SMTP 장애 중에도 운영 알림을 받을 수 있다. |
| 백업 중단 감시 | Better Stack 무료 하트비트 | 새 백업의 원격 검증이 끝나면 완료 신호를 보낸다. 홈서버 정전으로 신호가 끊겨도 외부에서 감지한다. |
| 원격 백업 | Google Drive API를 지원하는 rclone + crypt | 기존 원격 저장 기능을 사용한다. 직접 다운로드·해시 비교하던 코드는 rclone의 `check --download`로 대체했다. |
| 배포 이미지 취약점 검사 | Trivy | 빌드한 로컬 이미지의 HIGH·CRITICAL 취약점을 검사하고 JSON 보고서를 남긴다. GitHub Actions 실행은 추가하지 않는다. |
| 라이브러리·운영 이미지 업데이트 점검 | GitHub Dependabot | 기존 Java·npm·Actions·Dockerfile 점검에 Compose의 MySQL·Caddy를 추가했다. 새 버전 조회와 PR 생성은 GitHub가 처리한다. |
| 자료 URL 점검·인수인계·주간 요약 | WATCH·BATON·BRIEF | 팀 권한·변경 이력과 연결된 제품 기능이다. 무료 모니터의 제한된 슬롯이나 일반 자동화 서비스로 대체하지 않는다. |

공휴일 설정은 [README](../../README.md#무료-공휴일-연동), 캘린더는 [CAL 계약](../PRD/0006_calendar-integration-contract/spec.md)을 따른다. 사용자 업무 알림의 외부 발송은 RELAY가 소유하며 운영 경보는 Alertmanager가 맡는다. 화상회의는 기존 ROUND를 유지한다. 무료 사용량 이후 종량 과금이 발생할 수 있는 외부 TURN·SMS·AI API는 추가하지 않는다.

## 추가 후보와 도입 조건

아래 항목은 구현하지 않은 후보다. 현재 구현과 공식 문서를 대조했으며, 도입 조건이 충족되면 해당 범위부터 진행한다.

| 우선순위 | 후보 | 줄일 수 있는 작업과 도입 조건 |
| --- | --- | --- |
| Drive 자료 사용이 많을 때 | Google Picker | Google의 파일 선택창으로 자료 이름과 링크를 가져온다. [공식 선택창](https://developers.google.com/workspace/drive/picker/guides/overview)을 사용하지만 OAuth 동의·프로젝트 설정이 추가된다. 현재는 링크 입력만으로 충분해 보류한다. |
| 방문·로딩 통계가 필요할 때 | Cloudflare Web Analytics | 방문과 실제 페이지 로딩 성능을 [무료 통계 서비스](https://www.cloudflare.com/web-analytics/)에서 확인한다. 공개 소개 화면부터 검토하며 수집할 경로와 URL·토큰 제외 기준을 먼저 정한다. |

방문 통계는 외부 스크립트와 전송 경로를 추가하므로, 현재 [CSP](../../ops/Caddyfile)를 일괄 완화하거나 로그인·초대·작업 공간 주소를 그대로 수집하지 않는다.

### 추가 검토 근거

- [자료 등록 화면](../../frontend/src/features/workspace/WorkspaceRecordModals.tsx)은 이름과 주소를 입력받는다. Picker는 이 입력을 줄이지만 파일 접근 권한은 Drive가 계속 관리한다. Google 로그인과 파일 접근 동의는 별개이며, BATON 팀 공유만으로 Drive 파일 권한이 생기지는 않는다. Drive API를 함께 사용할 경우 아래 사용 한도 조건을 따른다.

## 오류 수집: Sentry

[Developer 무료 플랜](https://sentry.io/pricing/)의 운영자 1명·월 오류 5,000건 범위로 사용한다.
무료 플랜은 [종량 과금을 지원하지 않는다](https://www.sentry.help/en/articles/13965037-can-i-set-up-an-on-demand-pay-as-you-go-budget-for-my-free-developer-plan).
유료 플랜·자동 결제로 전환하지 않는다. 한도 도달 시 누락될 수 있는 오류는 기존 서버 로그와 상태 감시로 확인한다.

1. 무료 조직에 React와 Spring Boot 프로젝트를 만들고 각각의 공개 DSN을 복사한다. 개인 API 토큰은 BATON에 필요 없다.
2. `.env.production`에 `BATON_SENTRY_DSN`(서버), `BATON_SENTRY_BROWSER_DSN`(브라우저), `BATON_SENTRY_ENVIRONMENT=production`을 설정한다. 두 DSN은 독립적이며 빈 값이면 해당 수집을 끈다.
3. 브라우저 DSN은 **빌드 시 반영**된다. Compose의 `web` 이미지를 다시 빌드한다. k3s에서도 웹 이미지 빌드 인자 `VITE_SENTRY_DSN`·`VITE_SENTRY_ENVIRONMENT`를 사용하고, 서버 DSN은 앱 환경 변수로 주입한다.
4. 공개 HTTPS에서 테스트 오류 1건의 수신과 실제 배포 파일·줄을 확인한다. 오류 메시지·이메일·토큰·현재 화면 주소가 보고에 없는지 확인한다.

React 루트 오류와 브라우저 미처리 오류를 공식 SDK로 수집한다. DSN이 있을 때만 SDK를 불러오며, 로딩 중 발생한 React 오류는 로딩 완료 후 보고한다. 서버는 [Spring Boot 4 SDK](https://github.com/getsentry/sentry-java/tree/main/sentry-spring-boot-4-starter)와 기존 HTTP Observation을 연결해 응답이 확정된 **5xx 예외**를 수집한다. 예상된 4xx 오류는 보내지 않는다. 같은 예외의 중복 제거와 전송은 SDK가 처리한다.

수집 항목은 예외 종류·스택의 파일/줄·배포 환경이다. 예외 메시지, 요청 URL·본문·헤더, 사용자, 탐색 기록, 폼 값, 첨부 파일과 스택 지역 변수는 보내지 않는다. 세션·성능 추적·프로파일링·로그·화면 녹화는 켜지 않는다. 브라우저 오류 위치는 현재 서비스의 `/assets/`와 개발용 `/src/` 파일만 허용하고 쿼리·fragment를 제거한다. 소스맵 업로드는 자동화하지 않아 배포 JS에서는 압축 파일의 위치로 표시될 수 있다.

Caddy의 `connect-src`는 Sentry의 `*.ingest.sentry.io`, `*.ingest.us.sentry.io`, `*.ingest.de.sentry.io` HTTPS 수집 주소를 허용한다. k3s Ingress에서 CSP를 관리하면 같은 허용 목록을 병합한다. 전체 외부 출처를 허용하지 않는다. 비활성화할 때 서버 DSN을 지우고, 브라우저 DSN을 지운 웹 이미지를 다시 배포한다.

## 메일 전달 결과: Brevo Webhook

Brevo [무료 플랜의 outbound webhook](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans)을 사용한다.
메일 발송은 기존 SMTP를 유지하며 상태 조회용 API 키·주기 작업을 추가하지 않는다.

1. V40 마이그레이션과 새 SMTP 어댑터를 배포한다. 이후 발송 메일에는 `X-Mailin-custom: baton-delivery-id:<발송 ID>`가 붙는다. 이전 메일에는 결과 연결이 소급 적용되지 않는다.
2. `openssl rand -hex 32` 결과를 줄바꿈 없이 저장소 밖 0600 파일에 저장한다. 상위 디렉터리는 0700으로 두고 `BATON_BREVO_WEBHOOK_BEARER_TOKEN_FILE`에 절대 경로를 지정한다. 기존 SMTP·다른 연동의 비밀값을 재사용하지 않는다.
3. [Webhook 생성 API](https://developers.brevo.com/reference/create-webhook)로 transactional 웹훅을 등록한다. URL은 `https://b4ton.com/api/v1/integrations/brevo/email-events`, 인증은 [Bearer 방식](https://developers.brevo.com/docs/secured-webhooks)의 `auth.type=bearer`, `auth.token=<전용 토큰>`이다. 등록용 Brevo API 키는 앱에 보관하지 않는다. 단일 이벤트 POST를 사용하며 batch 모드는 사용하지 않는다.
4. 전달·일시 반송·영구 반송·차단·잘못된 주소·발송 오류·지연·스팸 신고 이벤트만 선택한다. 열람·클릭 추적은 연결하지 않는다. 이벤트 선택 이름은 등록 API의 열거형을 따르고, 수신 본문의 값은 아래 계약을 따른다.
5. Brevo SMTP 설정과 토큰 파일을 사전점검한 뒤 `BATON_BREVO_WEBHOOK_ENABLED=true`로 앱을 재시작한다. 실제 테스트 메일의 발송 ID, `delivered` 결과, 중복 재전송 후 기록 한 건을 확인한다. k3s에서는 같은 설정 이름으로 Secret을 연결한다. 전용 토큰 없이도 접근되는 경로를 만들지 않는다.

인증 실패·기본 비활성은 `401`, 잘못된 ID·시각 형식은 `400`, 수신 완료는 본문 없는 `204`다. BATON 발송 ID가 없거나 알려지지 않은 결과·발송 전 ID는 `204`로 무시한다. JSON 본문의 `email`, `subject`, `reason` 등은 저장하지 않는다. 상세 HTTP 계약은 [PRD-0002](../PRD/0002_api-contract/spec.md#brevo-메일-전달-결과)를 따른다.

[Brevo 이벤트](https://developers.brevo.com/docs/transactional-webhooks)의 `ts_event`(UTC Unix 초)를 저장한다. 같은 발송 ID·결과별로 가장 나중 시각 하나를 보존하며, `id`는 웹훅 ID이므로 중복 제거 키로 사용하지 않는다. 결과는 `email_delivery_receipts`에 저장하고 SMTP 아웃박스 상태나 계정 인증 상태를 바꾸지 않는다. `delivered`는 사용자가 읽었거나 가입을 완료했다는 뜻이 아니다. SMTP 재시도도 같은 발송 ID를 쓰므로 이 기록은 시도별 전체 로그가 아니다.

`baton_email_delivery_receipts{event="hard_bounce"}` 등은 **최근 24시간에 해당 결과가 발생한 메일 수**다. 동일 메일에 전달과 반송이 모두 있으면 각 결과에 집계된다. 기존 운영 지표 갱신 상태가 정상이고 영구 반송·차단·주소 오류·발송 오류·스팸 신고가 2분간 보이면 `BatonEmailDeliveryRejected`가 알린다. 일시 반송·지연에는 즉시 경보를 내거나 자동 재발송하지 않는다. 해당 결과가 24시간 창에서 빠지면 경보가 해제된다.

문제 메일의 발송 ID·결과·시각은 운영 DB에서 `email_delivery_receipts`를 조회하고, 자세한 원인은 Brevo 발송 내역과 대조한다. 중단할 때는 공급자 웹훅부터 끈 다음 BATON 수신을 비활성화해 불필요한 재전송을 줄인다. 실제 공급자 수신·HTTPS·경보 메일 검증은 운영 설정 후 진행한다.

## 가입·재설정 봇 방지: Cloudflare Turnstile

가입과 비밀번호 재설정 요청 폼은 Turnstile 사이트 키가 있을 때 위젯을 표시한다. 서버는
[Siteverify API](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/)로 token을 확인하고 `b4ton.com`과 요청별 `action`이 일치할 때만 메일 요청을 처리한다. token은 재사용하지 않으며 요청 실패 뒤 위젯을 초기화한다.

```dotenv
BATON_TURNSTILE_ENABLED=true
BATON_TURNSTILE_SITE_KEY=<공개 사이트 키>
BATON_TURNSTILE_SECRET_KEY_FILE=/srv/baton/secrets/turnstile-secret-key
BATON_TURNSTILE_EXPECTED_HOSTNAME=b4ton.com
```

운영자는 Cloudflare에서 Managed 위젯과 허용 호스트 `b4ton.com`을 만든다. [무료 플랜](https://developers.cloudflare.com/turnstile/plans/)의 위젯을 사용한다. Compose는 저장소 밖 소유자 전용 비밀 파일을 Spring configtree로 전달한다. 키가 없거나 호스트가 `BATON_HOST`와 다르면 사전점검이 실패한다. 기본값은 비활성이다.

향후 k3s에서는 비밀 키를 Kubernetes Secret에서 `BATON_TURNSTILE_SECRET_KEY` 환경변수로 주입하고 나머지 세 설정을 함께 전달한다. `_FILE`은 Compose 래퍼의 입력이며 Spring이 직접 읽는 속성이 아니다.

[Caddy 설정](../../ops/Caddyfile)은 [공식 CSP 기준](https://developers.cloudflare.com/turnstile/reference/content-security-policy/)에 맞춰 `script-src`·`frame-src`에 `https://challenges.cloudflare.com`만 추가 허용한다. SPA 화면 전환을 위해 기본 문서 정책에 적용하며 실제 스크립트는 두 메일 요청 폼에서만 불러온다. ROUND 방 문서의 별도 정책은 유지한다. 다른 Ingress에서도 같은 정책을 적용한다.

코드 검증은 공급자 대역으로 성공·실패·만료·재시도와 메일 미발송을 확인한다. 실제 위젯과 Siteverify, 공개 HTTPS·실제 메일 수신은 운영 키를 연결한 뒤 확인해야 한다.

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

## 기기 앱 공유: Web Share API

작업 공간의 `공유` 버튼을 [Web Share API](https://www.w3.org/TR/web-share/)에 연결했다. 사용자가 기기의 공유 창에서 메신저·메일 등 설치된 앱을 선택한다. 브라우저 표준 기능이므로 BATON에 공급자 SDK·계정·키나 유료 API를 추가하지 않는다.

공개 환경에서는 HTTPS가 필요하며 브라우저·운영체제에 따라 지원 여부와 표시되는 앱이 다르다. 미지원·권한 차단 등은 기존 링크 복사로 전환하고, 복사도 차단되면 주소를 직접 복사하도록 안내한다. 취소하거나 공유 대상 앱이 없으면 복사하지 않는다. 앱 선택만으로 수신자에게 전달됐다고 표시하지 않는다.

공유 주소와 팀 접근 권한은 [제품 기준](../PRD/0001_product-baseline/spec.md#파일럿-시작과-접근복구-원칙)을 따른다. 계정 권한 팀의 링크에는 공유 키가 없으며 로그인한 팀 구성원만 부여된 권한으로 이용할 수 있다. 브라우저 테스트는 API 응답을 대역 처리했다. 실제 iOS·Android에서 앱 선택·취소와 전달된 링크의 접속은 공개 HTTPS에서 확인해야 한다.

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

`verify_ssl`은 접속 시 인증서 유효성을 검사한다. [만료 사전 알림은 유료 플랜](https://betterstack.com/docs/uptime/ssl-certificate-monitor/)이므로 켜지 않는다. 아래 Blackbox Exporter 연동으로 보완한다.

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
- [alertmanager-discord.yml.example](../../ops/integrations/alertmanager-discord.yml.example): SMTP를 거치지 않는 선택 설정이다. 아래 절차로 Discord 웹훅 비밀 파일을 연결한다.

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

실제 수집·장애·해제 알림 수신을 확인한 뒤 `baton-integration-delivery.timer`를 사용 중이라면 해당 예약 검사만 끈다. 수동 `check-integration-delivery.sh`는 초기 점검과 진단에 계속 사용할 수 있다. 이 구성은 같은 홈서버가 중단되면 알릴 수 없으므로 외부 Better Stack 감시는 유지한다.

### 운영 알림: Discord 웹훅

메일 장애나 Brevo 발송 한도 때문에 운영 경보까지 누락되는 상황을 줄이는 선택지다. Discord의 [기본 채널 웹훅](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks)을 직접 사용하므로 유료 봇·중계 서비스나 별도 발송 서버가 필요 없다. BATON 애플리케이션에는 Discord SDK·토큰을 추가하지 않는다.

1. 운영자만 접근하는 Discord 텍스트 채널에서 웹훅을 만든다. 일반 텍스트 채널을 사용하며 별도 스레드 설정이 필요한 포럼·미디어 채널은 사용하지 않는다.
2. 전체 웹훅 URL을 저장소 밖 비밀 파일에 저장한다. 컨테이너에서 `/run/secrets/discord-webhook-url`로 읽기 전용 연결하고 Alertmanager 실행 사용자만 읽을 수 있게 한다. URL 자체가 발송 권한이므로 설정 파일·명령 인자·Git에 직접 넣지 않는다.
3. Discord 예시를 Alertmanager의 `--config.file`에 지정한다. SMTP 예시를 대체하면 운영 경보는 Discord만 사용한다. 메일도 함께 받으려면 기존 `operations-email` 수신자 아래에 예시의 `discord_configs` 블록을 추가한다. 두 설정 파일 전체를 단순히 이어 붙이지 않는다.
4. 공식 `amtool check-config`로 실제 설정을 검사한 뒤 반영한다. 시험 경보 발생·해제의 실제 채널 수신을 확인한다. 웹훅을 바꾸면 비밀 파일을 교체하고 수신을 다시 확인한다. k3s에서는 같은 파일을 Secret 볼륨으로 연결한다.

[Alertmanager 기본 연동](https://prometheus.io/docs/alerting/latest/configuration/#discord_config)이 웹훅 호출과 메시지 길이 제한을 처리한다. 알림에는 조치 필요·해제 건수, 경보 요약과 조치 설명을 표시한다. 전체 라벨·내부 관리 링크·사용자 업무 내용은 메시지에 추가하지 않는다. 경보가 해제됐다는 표시만으로 서비스 전체가 정상이라는 뜻은 아니므로 함께 온 수집 장애 경보도 확인한다.

기존 묶음·재통지 간격을 그대로 사용한다. Discord의 [호출 제한](https://docs.discord.com/developers/topics/rate-limits)이나 채널 장애로 전송이 실패할 수 있으므로 무제한·즉시 전달을 보장하지 않는다. 실제 수신을 확인하기 전에는 기존 운영 알림을 끄지 않는다. 웹훅 파일과 설정을 연결하지 않으면 현재 운영 구성은 바뀌지 않는다.

### 로컬 검증

```bash
bash ops/tests/integration-alerts-test.sh
```

Prometheus 3.14.0의 `promtool`, Alertmanager 0.34.0의 `amtool`, Blackbox Exporter 0.28.0의 `--config.check`로 수집·경보·SMTP·Discord·HTTPS 설정과 장애 시나리오를 검사한다. Docker 이미지가 없으면 최초 실행에 다운로드가 필요하다. 검사는 네트워크가 차단된 임시 컨테이너에서 실행하며 실제 메일·Discord 메시지를 보내지 않는다. CI 품질 게이트에서도 같은 검사를 실행한다. 실행 중인 BATON의 수집과 선택한 채널의 실제 수신은 활성화 전에 별도로 확인해야 한다.

## 공개 인증서 만료 알림: Blackbox Exporter

[Blackbox Exporter](https://github.com/prometheus/blackbox_exporter)는 Apache 2.0 라이선스의 무료 검사 도구다. HTTPS 인증서의 유효기간을 읽고 기존 Prometheus·Alertmanager가 경보와 선택한 채널의 알림을 처리한다.

- [blackbox.yml](../../ops/integrations/blackbox.yml)을 Exporter의 `--config.file`에 지정한다. `baton_https` 모듈은 유효한 인증서·HTTPS·HTTP 200을 요구하고 리디렉션을 따라가지 않는다. IPv4를 우선하며 IPv4 주소가 없으면 IPv6를 사용한다.
- [prometheus.yml](../../ops/integrations/prometheus.yml)의 `baton-https` 작업은 1분마다 `https://b4ton.com/actuator/health`를 검사한다. `blackbox-exporter:9115`는 실제 내부 주소로 바꾼다. Exporter의 `/probe`와 관리 포트는 수집기에서만 접근하게 하고 인터넷에 공개하지 않는다.
- [baton-https-alerts.yml](../../ops/integrations/baton-https-alerts.yml)은 만료까지 **14일 미만인 상태가 5분 지속**되면 알린다. 인증서를 갱신하면 해제한다. 검사 실패·수집 중단·지표 누락이 2분 지속되면 확인 불가를 알린다. 알림 간격·수신자는 기존 Alertmanager 설정을 따른다.

Exporter와 공개 HTTPS 대상이 준비된 뒤 수집을 시작한다. 내부 연동 지표만 먼저 사용할 경우 `baton-https` 작업과 `baton-https-alerts.yml` 참조를 함께 제외한다. 다른 마이크로서비스는 공개 HTTPS 상태 주소를 확인한 뒤 대상에 추가한다. 공유 키·구독 토큰·인증 헤더는 사용하지 않는다.

이 검사는 접속 지점에서 보이는 인증서를 확인한다. Cloudflare 프록시를 사용하면 Cloudflare 인증서를 보므로 **홈서버 원본 인증서까지 검사한 결과가 아니다**. 홈서버 안에서 실행할 때는 NAT 루프백·분할 DNS에 따라 접속 경로가 달라질 수 있다. 같은 홈서버가 중단되면 알림도 멈추므로 외부 Better Stack 감시는 유지한다. 인증서 자동 갱신이나 도메인 등록 갱신은 수행하지 않는다.

위 로컬 검증 명령에 만료 경계·갱신·검사 실패·수집 중단·지표 누락 사례를 포함했다. 실제 인증서 조회와 알림 수신은 미검증이며 공개 환경에서 확인해야 한다. 제공 범위는 연동 설정으로, 홈서버나 k3s를 설치하지 않는다.

## 원격 백업: Google Drive + rclone

Google 계정의 무료 저장 공간은 Gmail·Drive·Photos가 공유하는 [15GB](https://support.google.com/googleone/answer/9004013?hl=en)다. 백업 전용 계정의 남은 공간 안에서 사용하고 저장 공간 업그레이드를 구매하지 않는다.

[Drive API 사용 한도](https://developers.google.com/workspace/drive/api/guides/limits)는 저장 공간과 별개다. 현재 표준 사용은 추가 비용이 없지만 Google은 2026년 후반 한도 초과 과금 도입을 예고했다. 사용하는 OAuth 프로젝트의 적용 한도와 과금 조건을 활성화 전에 확인하고, 유료 한도 확대나 과금 계정 추가는 하지 않는다. API 사용이 항상 무제한 무료라고 전제하지 않는다.

- [rclone Drive 설정](https://rclone.org/drive/)으로 `baton_drive`를 만든다. 가능한 경우 `drive.file` 범위로 백업 도구가 만든 파일에만 접근하게 한다. OAuth 토큰 갱신은 rclone이 처리한다.
- [crypt 원격 저장소](https://rclone.org/crypt/) `baton_crypt`를 만들고 대상은 `baton_drive:baton-backups`로 지정한다. 파일 내용·이름 암호화를 켜고 복호화 비밀을 별도 보관한다.
- 기존 `BATON_RCLONE_REMOTE=baton_crypt:daily`를 그대로 사용한다. `rclone about baton_drive:`로 남은 공간을 확인한다. 원격 백업을 자동 삭제하거나 유료 공간으로 자동 전환하지 않는다.
- 업로드 후와 로컬 보존 기간 만료 시 [rclone check --download](https://rclone.org/commands/rclone_check/)로 해당 덤프·체크섬만 비교한다. 암호화 저장소에서도 복호화한 실제 내용을 비교하며 원격 파일은 수정하지 않는다.

용량 부족·OAuth 해제·암호화 키 유실은 각각 업로드 실패·접근 실패·복구 불가로 이어진다. 파일럿 데이터 투입 전 별도 환경의 복원 훈련은 계속 필요하다.

## 배포 이미지 취약점 검사: Trivy

[Trivy](https://trivy.dev/)는 무료 오픈소스 검사 도구다. [공식 설치 안내](https://trivy.dev/docs/latest/getting-started/installation/)에 따라 로컬 실행 파일을 준비한다. 이 연동은 Trivy `0.74.0`으로 검증했다. 홈서버 설치나 유료 스캔 서비스 가입은 필요하지 않다.

배포할 이미지를 빌드하거나 내려받은 뒤 [검사 스크립트](../../ops/scan-images.sh)에 이미지 이름 또는 ID를 전달한다. 운영 MySQL은 `compose.production.yml`의 고정 다이제스트와 일치하는 이미지를 선택한다.

```bash
./ops/scan-images.sh baton-production-app:latest baton-production-web:latest mysql:8.4.11
```

- 로컬 Docker 이미지만 검사한다. 이미지를 다시 빌드하거나 원격 검사 서버에 업로드하지 않는다. 공개 취약점 DB·Java 인덱스 다운로드에는 네트워크를 사용하며 캐시는 다음 검사에서 재사용한다. 사용 통계 전송은 끈다.
- HIGH·CRITICAL을 대상으로 하며 수정 버전이 없는 취약점도 보고한다. 결과는 `output/security/images.*/`의 이미지별 JSON, 대상 목록과 Trivy 버전에 남고 Git에서는 제외한다.
- 종료 코드 `0`은 해당 기준의 발견 없음, `10`은 취약점 발견, `1`은 이미지·DB 조회 등 검사 실패, `2`는 입력·도구 누락이다. 한 이미지가 실패해도 나머지를 검사하며, 검사 실패를 취약점 없음으로 처리하지 않는다.
- 발견 내용을 확인한 뒤 이미지 업데이트·재빌드 여부를 결정한다. 자동 업데이트나 배포는 하지 않는다. 현재 운영 중인 이미지와 새로 빌드한 이미지가 같은지는 운영자가 확인한다.
- GitHub Actions 작업을 추가하지 않아 실행 시간 과금이 늘지 않는다. 배포 전 로컬 검사로 사용한다. Dependabot은 업데이트 후보를 제안하고 Trivy는 실제 이미지 안의 패키지를 검사한다.

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

이번 변경은 공급자 연동 코드·설정 예시와 검증을 제공한다. 공급자 계정 생성, 자격 증명 발급, 실제 DNS 변경, 공개 HTTPS 감시·메일/Discord 수신·Google Drive 업로드와 내부 지표 수집 활성화는 실행하지 않았다. 해당 항목은 [HANDOFF](../../HANDOFF.md)에서 관리한다.
