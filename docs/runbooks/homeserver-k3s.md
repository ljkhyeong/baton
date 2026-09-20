# 16GB 홈서버: BATON·CAL·ROUND·포트폴리오 RAG 배포

기존 happyGallery가 실행 중인 단일 노드 k3s에 추가하는 수동 배포 절차다. **서버에 명령을 실행하는 사람은 운영자이며, 이 문서를 작성하면서 서버를 변경하거나 실제 배포를 검증하지 않았다.**

기준일: 2026-09-20. BATON은 현재 체크아웃, CAL·ROUND·포트폴리오는 같은 개발 PC의 저장소 설정을 확인했다. 배포 시 각 저장소의 커밋을 기록하고 해당 리비전의 CI 결과를 확인한다. 버전이 달라지면 환경변수·계약도 비교한다.

확인한 리비전: BATON `c566410b2ae0`, CAL `817720de44ba`, ROUND `bdf63eb2a4ec`, 포트폴리오 `de2b8ddc8573`. 이 리비전의 설정을 바탕으로 작성했으며 운영 릴리스가 자동 선정된 것은 아니다.

진행 순서: **서버 확인 → DNS·인증서 → 이미지 빌드 → 비밀값 → 배포 파일 생성 → BATON → CAL·ROUND → RAG 검색 → 공개 접속 확인 → OpenAI 활성화 → 백업·운영**.

## 1. 최종 구성과 운영 기준

- 기존 k3s·Traefik·Prometheus·Grafana를 재사용한다. happyGallery 리소스는 변경하지 않는다.
- `baton` 네임스페이스: BATON 웹·앱·MySQL, CAL·PostgreSQL·공개 프록시, 내부 TLS 프록시, ROUND 웹·시그널링·coturn.
- `portfolio` 네임스페이스: 검색 API·Elasticsearch·공개 프록시. 포트폴리오 정적 웹은 현재 호스팅을 유지한다.
- AI 답변은 **OpenAI API**를 사용한다. Ollama는 설치하지 않는다. API 사용료는 별도이며 ChatGPT 구독과 별개다.
- 모든 애플리케이션은 복제본 1개다. 업데이트 시 잠깐 중단될 수 있고, BATON 재시작 시 재로그인이 필요할 수 있다. ROUND 재시작은 진행 중 통화를 끊는다.
- WATCH·BRIEF·GO·RELAY·RabbitMQ는 배포하지 않는다.
- 이 문서는 신규 빈 DB 기준이다. 기존 운영 데이터가 있다면 먼저 해당 서비스의 백업·복구 절차로 이관한다.

| 주소 | 용도 | 공개 범위 |
| --- | --- | --- |
| `https://b4ton.com` | BATON, ROUND `/room/*`·`/round/rooms/*` | 기존 Caddy 경로 정책 |
| `https://cal.b4ton.com` | 개인 캘린더 구독 | `/calendars/v1/`의 GET·HEAD만 |
| `https://rag.b4ton.com` | 포트폴리오 검색·답변 | `/api/v1/knowledge/search`, `/api/v1/knowledge/answers` |
| `turn.b4ton.com` | 통화 미디어 중계 | TURN 전용 포트 |

`rag.b4ton.com`은 이 문서의 권장 이름이다. 변경하려면 아래 설정과 프런트엔드 주소를 함께 바꾼다. **ROUND는 현재 BATON 인증 쿠키·CSP 계약을 유지하기 위해 `round.b4ton.com`으로 분리하지 않는다.** 별도 도메인 분리는 애플리케이션 변경이 필요한 후속 작업이다.

```text
인터넷 → 공유기 80/443 → 기존 Traefik
                         ├─ 기존 happyGallery
                         ├─ BATON Caddy → BATON 앱 → MySQL
                         │             └─ ROUND 웹·시그널링
                         ├─ CAL 공개 프록시 → CAL → PostgreSQL
                         └─ RAG 프록시 → 검색 API → Elasticsearch
                                                └─ OpenAI API
BATON 앱 → 내부 TLS 프록시 → CAL 내부 API
ROUND 시그널링 → 내부 TLS 프록시 → BATON 공개 JWK
브라우저 ↔ 공유기 TURN 포트 ↔ coturn
```

### 메모리 예산

아래는 작은 공개 문서 집합·소규모 사용을 위한 **시작 설정**이다. 측정된 수용량이 아니다.

| 컨테이너 | requests | limits | JVM 힙 |
| --- | ---: | ---: | ---: |
| BATON 앱 | 512Mi | 1Gi | 512Mi |
| BATON 웹 | 64Mi | 128Mi | — |
| MySQL | 512Mi | 1536Mi | — |
| CAL | 384Mi | 768Mi | 256Mi |
| PostgreSQL | 256Mi | 512Mi | — |
| CAL 공개·내부 TLS 프록시 | 각 32Mi | 각 128Mi | — |
| ROUND 웹 | 32Mi | 128Mi | — |
| ROUND 시그널링 | 256Mi | 512Mi | 256Mi |
| coturn | 64Mi | 256Mi | — |
| RAG API | 384Mi | 768Mi | 384Mi |
| Elasticsearch | 1Gi | 2Gi | 768Mi |
| RAG 프록시 | 32Mi | 128Mi | — |

추가 컨테이너 limits 합계는 약 **7.9GiB**다. happyGallery 기본 설정의 약 4.75GiB와 합쳐 약 12.6GiB이므로 OS·k3s·파일 캐시·순간 부하 여유가 필요하다. Kubernetes 노드 `allocatable`은 물리 RAM보다 작다. 메모리가 부족하면 RAG부터 중단하고 원인을 확인한다. 남은 RAM을 전부 힙에 할당하지 않는다.

## 2. 사전 확인 — 홈서버

명령은 Bash 기준이다. 이미 사용 중인 k3s 관리 계정에서 실행한다. `kubectl` 권한이 없으면 기존 happyGallery 작업 때 쓰던 `sudo k3s kubectl`을 사용한다. kubeconfig를 공개하거나 권한을 전체 사용자에게 열지 않는다.

```bash
kubectl config current-context
kubectl get nodes -o wide
kubectl describe node
kubectl top nodes
kubectl top pods -A --sort-by=memory
kubectl get ingress -A
kubectl -n kube-system get deployment traefik -o wide
kubectl -n kube-system get pods --show-labels
kubectl get storageclass local-path-retain -o yaml
kubectl get crd middlewares.traefik.io
kubectl get namespaces
free -h
df -h
uname -m
```

확인할 사항:

1. 노드 Ready, 기존 happyGallery 정상. 기존 DB 백업과 복구 방법을 확보한다.
2. Traefik의 Service/Pod가 사용하는 포트·라벨을 확인한다. 아래 예시는 `kube-system`, `app.kubernetes.io/name=traefik`이다.
3. `local-path-retain`의 provisioner가 `rancher.io/local-path`, reclaimPolicy가 `Retain`인지 확인한다. 없으면 happyGallery의 `deploy/k3s/base/storage-class.yaml`을 먼저 검토해서 적용한다. 기존 StorageClass 설정은 덮어쓰지 않는다.
4. 새 DB·색인용 SSD 여유 공간을 확보한다. 예시는 MySQL 20Gi, PostgreSQL 10Gi, Elasticsearch 10Gi를 요청한다. local-path의 요청 크기는 디스크 사용량을 강제로 제한하는 쿼터가 아니므로 실제 디스크도 감시한다.
5. 아래 이름의 기존 리소스가 있으면 신규 설치 명령을 중단하고 기존 구성과 비교한다. 특히 Secret을 새로 생성하면 DB 비밀번호·CAL 구독 세대가 어긋날 수 있다.

```bash
kubectl get namespace baton portfolio
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.podCIDR}{"\n"}{end}'
```

현재 사용량만 보지 말고 happyGallery에 평소 요청이 있는 시간대도 확인한다. `kubectl top`이 없다면 기존 metrics-server 상태부터 확인한다.

## 3. DNS·인증서·포트 준비

Cloudflare에 `b4ton.com`, `cal.b4ton.com`, `rag.b4ton.com`, `turn.b4ton.com`을 같은 공인 IP로 등록한다. 첫 검증은 **DNS only**로 진행한다. 특히 TURN은 계속 DNS only다. 사용하지 않는 AAAA 레코드가 있으면 잘못된 IPv6 경로가 생기지 않도록 정리한다.

| 공유기 → 홈서버 | 프로토콜 | 용도 |
| --- | --- | --- |
| 80 → 80, 443 → 443 | TCP | 기존 Traefik 유지 |
| 3478 → 3478 | UDP·TCP | STUN/TURN |
| 5349 → 5349 | TCP | TURN TLS |
| 49160–49259 → 같은 포트 | UDP | TURN 미디어 중계 |

Ubuntu 방화벽에도 같은 TURN 포트를 허용한다. 사용 중인 방화벽 도구의 기존 규칙에 추가하며, 방화벽 전체 초기화나 k3s 네트워크 규칙 변경은 하지 않는다. 이 문서의 coturn은 `hostNetwork`를 사용하므로 Kubernetes NetworkPolicy만으로 접근을 제한할 수 없다.

인증서는 **브라우저와 JVM이 신뢰하는 공개 CA 인증서**를 사용한다. Cloudflare Origin CA 인증서는 DNS only 브라우저 접속과 JVM 내부 TLS 연결에 그대로 사용할 수 없다. `*.b4ton.com`만으로는 루트 `b4ton.com`을 포함하지 않는다.

이후 명령은 운영자 전용 디렉터리에 다음 파일이 있다고 가정한다. 같은 SAN 인증서를 여러 경로에 복사해도 된다.

```text
~/baton-deploy/
  tls/baton/{fullchain.pem,privkey.pem}
  tls/cal/{fullchain.pem,privkey.pem}
  tls/rag/{fullchain.pem,privkey.pem}
  tls/turn/{fullchain.pem,privkey.pem}
```

각 인증서의 호스트·만료·개인 키 짝을 확인한다. 기존 cert-manager를 쓰고 있다면 해당 네임스페이스에 Certificate를 만들어 아래 Secret 이름으로 발급해도 된다. 이후의 수동 Secret 생성 명령과 중복 실행하지 않는다.

## 4. 이미지 준비 — 개발 PC

홈서버에서 빌드를 병렬로 실행하지 않는다. 다음 네 저장소를 같은 상위 디렉터리에 준비한다. 실제 checkout 리비전을 기록하고 미커밋 변경이 없는지 확인한다.

- `manager`: BATON
- `baton-cal`: CAL
- `webRTC`: ROUND
- `portfolio`: 포트폴리오

다음 예시는 개발 PC에 Docker Buildx, Node 22, CAL 빌드용 JDK 25와 Gradle wrapper 실행 환경이 준비된 경우다. BATON·ROUND·RAG 런타임은 Java 21이고 **현재 CAL은 Java 25**다.

```bash
export SRC_ROOT="$HOME/devProject/personal"
export RELEASE="$(date +%Y%m%d-%H%M%S)"
# 서버가 x86_64이면 amd64, aarch64이면 arm64
export PLATFORM=linux/amd64
export OUT="$HOME/baton-release-$RELEASE"
mkdir -p "$OUT"
for repo in manager baton-cal webRTC portfolio; do
  git -C "$SRC_ROOT/$repo" status --short
  git -C "$SRC_ROOT/$repo" rev-parse HEAD >> "$OUT/revisions.txt"
done

# BATON–CAL 공식 계약 검증. Docker 실행 가능 환경에서 수행한다.
(cd "$SRC_ROOT/manager" && BATON_CAL_CONTRACT_VERSION=1.1.0-rc.2 bash ops/tests/calendar-consumer-contract.sh)

# 계약 검증의 고정 CAL 이미지와 지금 빌드할 CAL 리비전이 다르면,
# 해당 CAL 리비전의 계약 검사/CI도 확인한 뒤 계속한다.
docker buildx build --platform "$PLATFORM" --load -t "baton-app:$RELEASE" -f "$SRC_ROOT/manager/Dockerfile" "$SRC_ROOT/manager"
docker buildx build --platform "$PLATFORM" --load -t "baton-web:$RELEASE" -f "$SRC_ROOT/manager/frontend/Dockerfile" "$SRC_ROOT/manager"
(cd "$SRC_ROOT/baton-cal" && ./gradlew --no-daemon bootBuildImage --imageName="baton-cal:$RELEASE" --imagePlatform="$PLATFORM")
docker buildx build --platform "$PLATFORM" --load --target baton-web-runtime --build-arg VITE_STUN_URLS=stun:turn.b4ton.com:3478 -t "round-web:$RELEASE" "$SRC_ROOT/webRTC"
docker buildx build --platform "$PLATFORM" --load --target signaling-runtime -t "round-signaling:$RELEASE" "$SRC_ROOT/webRTC"
(cd "$SRC_ROOT/portfolio" && npm ci && npm run knowledge:generate)
docker buildx build --platform "$PLATFORM" --load -t "knowledge-api:$RELEASE" -f "$SRC_ROOT/portfolio/knowledge-api/Dockerfile" "$SRC_ROOT/portfolio"
docker buildx build --platform "$PLATFORM" --load -t "knowledge-es:$RELEASE" -f "$SRC_ROOT/portfolio/knowledge-api/elasticsearch.Dockerfile" "$SRC_ROOT/portfolio"

docker image save -o "$OUT/images.tar" \
  "baton-app:$RELEASE" "baton-web:$RELEASE" "baton-cal:$RELEASE" \
  "round-web:$RELEASE" "round-signaling:$RELEASE" \
  "knowledge-api:$RELEASE" "knowledge-es:$RELEASE"
cp "$SRC_ROOT/manager/ops/Caddyfile" "$OUT/BatonCaddyfile.source"
cp "$SRC_ROOT/baton-cal/operations/nginx/calendar.conf.template" "$OUT/CalNginx.source"
cp "$SRC_ROOT/webRTC/ops/coturn/turnserver.conf.example" "$OUT/turnserver.conf.source"
```

빌드 실패를 무시하고 진행하지 않는다. 이미지 이름을 `latest`로 바꾸거나 같은 태그를 다른 내용으로 덮어쓰지 않는다. 서로 다른 CPU 아키텍처에서 Buildpacks가 실패하면 대상 플랫폼을 지원하는 빌드 환경에서 CAL만 다시 만든다. [Spring Boot 이미지 빌드 옵션](https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html)

전송 전 `docker image inspect <이미지> --format '{{.Os}}/{{.Architecture}} {{.Id}}'`로 모두 서버 아키텍처와 일치하는지 확인한다. `images.tar`와 `OUT`의 나머지 파일을 홈서버 `~/baton-deploy/release/`에 복사한다. 이미지 archive에는 운영 비밀값을 넣지 않는다.

## 5. 작업 폴더·네임스페이스·비밀값 — 홈서버

이후 명령은 **같은 Bash 세션**, `~/baton-deploy`에서 실행한다. 접속을 다시 열면 `cd`와 공개 설정을 다시 읽는다. 실제 값이 있는 작업 폴더는 Git에 넣지 않는다.

```bash
umask 077
mkdir -p "$HOME/baton-deploy"/{release,secrets,tls,manifests}
cd "$HOME/baton-deploy"
sudo k3s ctr images import release/images.tar

cat > settings.env <<'ENV'
RELEASE=위에서_만든_릴리스_태그
NODE_NAME=실제_k3s_노드_이름
LAN_IP=실제_홈서버_사설_IP
PUBLIC_IP=실제_공인_IP
POD_CIDR=10.42.0.0/24
TRAEFIK_NAMESPACE=kube-system
MONITOR_NAMESPACE=happygallery
PORTFOLIO_ORIGIN=https://ljkportfolio.netlify.app
RAG_HOST=rag.b4ton.com
ENV
# settings.env를 실제 값으로 수정한 뒤 실행
set -a
source settings.env
set +a

kubectl create namespace baton
kubectl create namespace portfolio
for item in baton cal turn; do
  kubectl -n baton create secret tls "$item-tls" \
    --cert="tls/$item/fullchain.pem" --key="tls/$item/privkey.pem"
done
kubectl -n portfolio create secret tls rag-tls \
  --cert=tls/rag/fullchain.pem --key=tls/rag/privkey.pem
```

처음 한 번만 비밀값을 생성한다. 아래 생성기를 재실행하면 비밀값이 바뀌므로 폴더가 비어 있는지 먼저 확인한다.

```bash
python3 - <<'PY'
from pathlib import Path
import secrets, uuid
p = Path('secrets')
if any(p.iterdir()):
    raise SystemExit('기존 secrets 폴더가 있으므로 새 키를 만들지 않습니다.')
for name in ('DB_PASSWORD','MYSQL_ROOT_PASSWORD','DATABASE_PASSWORD',
             'BATON_WORKSPACE_CREATION_KEY','BATON_WORKSPACE_RECOVERY_KEY',
             'BATON_CAL_INTERNAL_TOKEN','TURN_COTURN_SECRET','KNOWLEDGE_SYNC_KEY'):
    (p/name).write_text(secrets.token_urlsafe(48))
(p/'BATON_CAL_SUBSCRIPTION_GENERATION').write_text(str(uuid.uuid4()))
PY
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/current-private.pem
openssl pkey -in secrets/current-private.pem -pubout -out secrets/current-public.pem
kubectl -n baton create secret generic baton-secret \
  --from-file=DB_PASSWORD=secrets/DB_PASSWORD \
  --from-file=BATON_WORKSPACE_CREATION_KEY=secrets/BATON_WORKSPACE_CREATION_KEY \
  --from-file=BATON_WORKSPACE_RECOVERY_KEY=secrets/BATON_WORKSPACE_RECOVERY_KEY \
  --from-file=BATON_CAL_BEARER_TOKEN=secrets/BATON_CAL_INTERNAL_TOKEN
kubectl -n baton create secret generic mysql-secret \
  --from-file=MYSQL_PASSWORD=secrets/DB_PASSWORD \
  --from-file=MYSQL_ROOT_PASSWORD=secrets/MYSQL_ROOT_PASSWORD
kubectl -n baton create secret generic cal-secret \
  --from-file=DATABASE_PASSWORD=secrets/DATABASE_PASSWORD \
  --from-file=BATON_CAL_INTERNAL_TOKEN=secrets/BATON_CAL_INTERNAL_TOKEN \
  --from-file=BATON_CAL_SUBSCRIPTION_GENERATION=secrets/BATON_CAL_SUBSCRIPTION_GENERATION
kubectl -n baton create secret generic postgres-secret --from-file=POSTGRES_PASSWORD=secrets/DATABASE_PASSWORD
kubectl -n baton create secret generic round-keys --from-file=secrets/current-private.pem --from-file=secrets/current-public.pem
kubectl -n baton create secret generic round-secret --from-file=TURN_COTURN_SECRET=secrets/TURN_COTURN_SECRET
kubectl -n portfolio create secret generic knowledge-secret --from-file=KNOWLEDGE_SYNC_KEY=secrets/KNOWLEDGE_SYNC_KEY
```

### BATON 로그인 준비

본 절차는 SMTP를 추가하지 않고 Google OAuth로 로그인한다. Google Cloud에서 웹 애플리케이션 OAuth 클라이언트를 준비한다. 승인된 리디렉션 URI는 `https://b4ton.com/login/oauth2/code/google`이다. 동의 화면이 테스트 상태면 로그인할 계정을 테스트 사용자로 등록한다.

`secrets/google.env`를 편집기로 작성한다. 값은 따옴표 없이 기록하고 셸에서 `source`하지 않는다.

```dotenv
BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=실제_클라이언트_ID
BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET=실제_클라이언트_비밀값
```

```bash
kubectl -n baton create secret generic google-oauth --from-env-file=secrets/google.env
```

운영자 생성 키·복구 키는 브라우저 공개 설정이나 프런트엔드 빌드에 넣지 않는다.

## 6. 내부 TLS 서비스 주소 확보

CAL 내부 API를 인터넷에 노출하지 않으면서 BATON의 HTTPS 요구사항을 유지한다. 내부 프록시는 준비한 공개 CA 인증서를 제시한다. BATON Pod 안에서만 `cal.b4ton.com`을, ROUND Pod 안에서만 `b4ton.com`을 이 프록시의 ClusterIP로 연결한다. 클러스터 전체 DNS나 happyGallery DNS는 수정하지 않는다.

```bash
kubectl -n baton create service clusterip internal-tls --tcp=8443:8443
export INTERNAL_TLS_IP="$(kubectl -n baton get svc internal-tls -o jsonpath='{.spec.clusterIP}')"
```

서비스를 삭제해 ClusterIP가 바뀌면 해당 Pod의 hostAliases도 갱신하고 재시작해야 한다. 아래 생성기가 같은 Service의 selector를 설정한다.

## 7. 배포 파일 생성

아래 Python은 Kubernetes가 읽을 수 있는 JSON 매니페스트와 프록시 설정을 **로컬 파일로만 생성**한다. 아직 배포하지 않는다. Python 표준 라이브러리만 사용한다. 중복 YAML을 줄이기 위한 문서 내 생성기이며 실제 생성 결과를 다음 절에서 확인한다.

```bash
cat > render.py <<'PY'
import json, os, re
from pathlib import Path
out = Path('manifests')
out.mkdir(exist_ok=True)
rev = os.environ['RELEASE']
node = os.environ['NODE_NAME']
ip = os.environ['INTERNAL_TLS_IP']
rag = os.environ['RAG_HOST']
origin = os.environ['PORTFOLIO_ORIGIN']
traefik_ns = os.environ['TRAEFIK_NAMESPACE']
monitor_ns = os.environ['MONITOR_NAMESPACE']
pod_cidr = os.environ['POD_CIDR']

def save(name, items):
    (out/(name+'.json')).write_text(json.dumps({'apiVersion':'v1','kind':'List','items':items}, indent=2))
def obj(kind, ns, name, spec=None, **fields):
    api = {'Deployment':'apps/v1','StatefulSet':'apps/v1','NetworkPolicy':'networking.k8s.io/v1','Ingress':'networking.k8s.io/v1'}.get(kind,'v1')
    x = dict(apiVersion=api,kind=kind,metadata=dict(name=name,namespace=ns),**fields)
    if spec is not None: x['spec']=spec
    return x

def service(ns, name, port, target=None):
    return obj('Service',ns,name,dict(selector={'app':name},ports=[dict(name='http',port=port,targetPort=target or port)]))
def mount_config(c,p,name,path,secret=False):
    c.setdefault('volumeMounts',[]).append(dict(name=name,mountPath=path,readOnly=True))
    p.setdefault('volumes',[]).append(dict(name=name,**({'secret':{'secretName':name,'defaultMode':288}} if secret else {'configMap':{'name':name}})))
def workload(ns,name,image,port,request,limit,env=None,secret_names=(),health=None,health_port=None,storage=None,mounts=(),extra=None):
    c=dict(name=name,image=image,imagePullPolicy='IfNotPresent',ports=[dict(containerPort=port)],
           resources={'requests':{'cpu':'50m','memory':request},'limits':{'cpu':'1','memory':limit}},
           env=[dict(name=k,value=str(v)) for k,v in (env or {}).items()])
    if secret_names: c['envFrom']=[{'secretRef':{'name':s}} for s in secret_names]
    if health:
        probe={'httpGet':{'path':health,'port':health_port or port},'timeoutSeconds':5,'periodSeconds':10}
        c['startupProbe']={**probe,'failureThreshold':60}
        c['readinessProbe']={**probe,'failureThreshold':3}
    p=dict(containers=[c],terminationGracePeriodSeconds=40,nodeSelector={'kubernetes.io/hostname':node})
    for m in mounts: mount_config(c,p,*m)
    if extra: extra(c,p)
    spec=dict(replicas=1,selector={'matchLabels':{'app':name}},template={'metadata':{'labels':{'app':name}},'spec':p})
    kind='Deployment'
    if storage:
        kind='StatefulSet'
        size,path=storage
        spec['serviceName']=name
        spec['volumeClaimTemplates']=[{'metadata':{'name':'data'},'spec':{'accessModes':['ReadWriteOnce'],'storageClassName':'local-path-retain','resources':{'requests':{'storage':size}}}}]
        c.setdefault('volumeMounts',[]).append({'name':'data','mountPath':path})
    else: spec['strategy']={'type':'Recreate'}
    return [service(ns,name,port),obj(kind,ns,name,spec)]
def cm(ns,name,data): return obj('ConfigMap',ns,name,data=data)
def alias(host):
    def apply(c,p): p['hostAliases']=[{'ip':ip,'hostnames':[host]}]
    return apply

def from_app(ns, names):
    return {'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':ns}},'podSelector':{'matchExpressions':[{'key':'app','operator':'In','values':names}]}}
traefik={'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':traefik_ns}},'podSelector':{'matchLabels':{'app.kubernetes.io/name':'traefik'}}}
monitor={'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':monitor_ns}}}
def allow(ns,name,sources,ports):
    return obj('NetworkPolicy',ns,'allow-'+name,{'podSelector':{'matchLabels':{'app':name}},'policyTypes':['Ingress'],'ingress':[{'from':sources,'ports':[{'protocol':'TCP','port':p} for p in ports]}]})
policies=[]
for ns in ('baton','portfolio'):
    policies.append(obj('NetworkPolicy',ns,'default-deny-ingress',{'podSelector':{},'policyTypes':['Ingress'],'ingress':[]}))
for name,sources,ports in [
 ('web',[traefik],[8080]),('app',[from_app('baton',['web','internal-tls'])],[8080]),
 ('mysql',[from_app('baton',['app'])],[3306]),('postgres',[from_app('baton',['cal'])],[5432]),
 ('cal',[from_app('baton',['cal-public','internal-tls'])],[8080]),
 ('cal-public',[traefik],[8080]),('internal-tls',[from_app('baton',['app','round-signaling'])],[8443]),
 ('round-web',[from_app('baton',['web'])],[8080]),('round-signaling',[from_app('baton',['web'])],[8787])]:
    policies.append(allow('baton',name,sources,ports))
for name,sources,ports in [('rag-web',[traefik],[8080]),('knowledge-api',[from_app('portfolio',['rag-web'])],[8080]),('elasticsearch',[from_app('portfolio',['knowledge-api'])],[9200])]:
    policies.append(allow('portfolio',name,sources,ports))
for ns,name,port in [('baton','app',8080),('baton','cal',8081),('baton','round-signaling',8787),('portfolio','knowledge-api',9091)]:
    policy=allow(ns,name,[monitor],[port]); policy['metadata']['name']+='-monitor'; policies.append(policy)
save('00-network',policies)

mysql=workload('baton','mysql','mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb',3306,'512Mi','1536Mi',
 {'MYSQL_DATABASE':'baton','MYSQL_USER':'baton','TZ':'UTC'},['mysql-secret'],storage=('20Gi','/var/lib/mysql'))
mysql[1]['spec']['template']['spec']['containers'][0]['args']=['--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--default-time-zone=+00:00','--innodb-buffer-pool-size=512M','--max-connections=50']
postgres=workload('baton','postgres','postgres:18.6-alpine',5432,'256Mi','512Mi',{'POSTGRES_DB':'baton_cal','POSTGRES_USER':'baton_cal'},['postgres-secret'],storage=('10Gi','/var/lib/postgresql'))
postgres[1]['spec']['template']['spec']['containers'][0]['args']=['-c','shared_buffers=128MB','-c','max_connections=30']
save('10-databases',mysql+postgres)

def app_extra(c,p):
    alias('cal.b4ton.com')(c,p)
    p['securityContext']={'fsGroup':10001}
    c['envFrom'].append({'configMapRef':{'name':'baton-flags'}})
app_env={'SPRING_PROFILES_ACTIVE':'production','DB_URL':'jdbc:mysql://mysql:3306/baton?sslMode=REQUIRED&allowPublicKeyRetrieval=false&serverTimezone=UTC&characterEncoding=UTF-8','DB_USERNAME':'baton','SERVER_FORWARD_HEADERS_STRATEGY':'FRAMEWORK','SERVER_SERVLET_SESSION_TIMEOUT':'PT30M','BATON_PUBLIC_BASE_URL':'https://b4ton.com','JAVA_TOOL_OPTIONS':'-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError','BATON_CAL_BASE_URL':'https://cal.b4ton.com:8443','BATON_ROUND_PARTICIPATION_GRANT_ISSUER':'https://b4ton.com','BATON_ROUND_PARTICIPATION_GRANT_AUDIENCE':'round','BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID':'homeserver-v1','BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_PATH':'/run/baton-keys/current-private.pem','BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_PATH':'/run/baton-keys/current-public.pem','TZ':'UTC'}
save('20-baton',workload('baton','app','baton-app:'+rev,8080,'512Mi','1Gi',app_env,['baton-secret','google-oauth'],health='/actuator/health',mounts=[('round-keys','/run/baton-keys',True)],extra=app_extra))

# 검증된 BATON Caddy 경로를 보존하고 내부 HTTP 수신·신뢰 프록시만 조정한다.
src=Path('release/BatonCaddyfile.source').read_text()
assert src.count('{$BATON_HOST} {')==1
src=src.replace('{$BATON_HOST} {',':8080 {',1)
src=src.replace('request_header -X-Forwarded-*','request_header -X-Forwarded-Host\n\trequest_header -X-Forwarded-Proto\n\trequest_header -X-Forwarded-Port')
src=src.replace('{http.request.remote.host}','{http.request.client_ip}').replace('key {remote_host}','key {client_ip}')
# 공개 Actuator 경로를 닫고 상태는 내부에서 확인한다.
src=src.replace('\n:8080 {','\n:8080 {\n\thandle /actuator/* {\n\t\trespond 404\n\t}\n',1)
head='{\n auto_https off\n admin off\n servers {\n  trusted_proxies static '+pod_cidr+'\n  trusted_proxies_strict\n  client_ip_headers X-Forwarded-For\n }\n}\n'
save('21-web',[cm('baton','web-config',{'Caddyfile':head+src})]+workload('baton','web','baton-web:'+rev,8080,'64Mi','128Mi',{'BATON_HOST':'b4ton.com','BATON_ROUND_RUNTIME_ENABLED':'true'},health='/',mounts=[('web-config','/etc/caddy',False)]))

calenv={'SPRING_PROFILES_ACTIVE':'prod','DATABASE_URL':'jdbc:postgresql://postgres:5432/baton_cal','DATABASE_USERNAME':'baton_cal','DATABASE_MAXIMUM_POOL_SIZE':'5','BATON_CAL_PUBLIC_BASE_URL':'https://cal.b4ton.com','BATON_CAL_RECOVERY_MODE':'false','JAVA_TOOL_OPTIONS':'-Xmx256m -XX:+ExitOnOutOfMemoryError','BPL_JVM_THREAD_COUNT':'50'}
save('30-cal',workload('baton','cal','baton-cal:'+rev,8080,'384Mi','768Mi',calenv,['cal-secret'],health='/actuator/health/readiness',health_port=8081))
ng=Path('release/CalNginx.source').read_text()
ng=ng.replace('${CAL_REQUEST_RATE}','10r/s').replace('${CAL_REQUEST_BURST}','20').replace('${CAL_CONNECTION_LIMIT}','20')
ng=ng.replace('resolver 127.0.0.11 valid=10s ipv6=off;','').replace('server app:8080 resolve;','server cal:8080;')
start=ng.index('server {\n    listen 80;'); end=ng.index('server {\n    listen 443 ssl;')
ng=ng[:start]+ng[end:]
ng=re.sub(r'^    ssl_.*;\n','',ng,flags=re.M).replace('listen 443 ssl;','listen 8080;\n    access_log off;\n    error_log /dev/stderr crit;')
ng=ng.replace('listen 8080;', 'listen 8080;\n    set_real_ip_from '+pod_cidr+';\n    real_ip_header X-Forwarded-For;\n    real_ip_recursive on;')
nginx='nginx:1.31.4-alpine-slim@sha256:1870de6d59aafee152589b64404556d2535922cdd998e6dac1c4888c938ed8f9'
save('31-cal-public',[cm('baton','cal-nginx',{'default.conf':ng})]+workload('baton','cal-public',nginx,8080,'32Mi','128Mi',mounts=[('cal-nginx','/etc/nginx/conf.d',False)]))
caddy='caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648'
internal='''{\n auto_https off\n admin off\n}\nhttps://cal.b4ton.com:8443 {
 tls /tls/cal/tls.crt /tls/cal/tls.key
 handle /internal/api/v1/* {\n  reverse_proxy cal:8080\n }
 respond 404
}
https://b4ton.com:8443 {
 tls /tls/baton/tls.crt /tls/baton/tls.key
 handle /.well-known/round-participation-jwks.json {\n  reverse_proxy app:8080\n }
 respond 404
}
'''
save('32-internal-tls',[cm('baton','internal-caddy',{'Caddyfile':internal})]+workload('baton','internal-tls',caddy,8443,'32Mi','128Mi',mounts=[('internal-caddy','/etc/caddy',False),('cal-tls','/tls/cal',True),('baton-tls','/tls/baton',True)]))
roundenv={'SPRING_PROFILES_ACTIVE':'production','HOST':'0.0.0.0','PORT':'8787','ROUND_AUTH_MODE':'baton','ROUND_AUTH_COOKIE_NAME':'__Secure-round_access','ROUND_AUTH_ISSUER':'https://b4ton.com','ROUND_AUTH_AUDIENCE':'round','ROUND_AUTH_JWK_SET_URI':'https://b4ton.com:8443/.well-known/round-participation-jwks.json','ROUND_AUTH_MAX_GRANT_LIFETIME_SECONDS':'300','ALLOWED_ORIGINS':'https://b4ton.com','TURN_PROVIDER':'coturn','TURN_COTURN_URLS':'turn:turn.b4ton.com:3478?transport=udp,turn:turn.b4ton.com:3478?transport=tcp,turns:turn.b4ton.com:5349?transport=tcp','MAX_ROOM_SIZE':'6','MAX_SIGNALING_CONNECTIONS':'100','JAVA_TOOL_OPTIONS':'-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError'}
save('40-round',workload('baton','round-web','round-web:'+rev,8080,'32Mi','128Mi',health='/healthz')+workload('baton','round-signaling','round-signaling:'+rev,8787,'256Mi','512Mi',roundenv,['round-secret'],health='/actuator/health/readiness',extra=alias('b4ton.com')))

# coturn의 기존 차단 대역·인증·중계 제한을 유지한다.
turn=Path('release/turnserver.conf.source').read_text()
turn=turn.replace('192.168.0.10',os.environ['LAN_IP']).replace('203.0.113.10',os.environ['PUBLIC_IP'])
turn=turn.replace('static-auth-secret=','static-auth-secret='+Path('secrets/TURN_COTURN_SECRET').read_text().strip())
turn=turn.replace('/etc/coturn/tls/fullchain.pem','/tls/tls.crt').replace('/etc/coturn/tls/privkey.pem','/tls/tls.key')
Path('secrets/turnserver.conf').write_text(turn)
def turn_extra(c,p):
    p.update(hostNetwork=True,dnsPolicy='ClusterFirstWithHostNet')
    c['args']=['-c','/etc/coturn/turnserver.conf']
    c['ports']=[{'containerPort':3478,'protocol':'UDP'},{'containerPort':3478,'protocol':'TCP'},{'containerPort':5349,'protocol':'TCP'}]
    c['securityContext']={'runAsUser':0,'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}
# COTURN_IMAGE에는 다음 절에서 확인한 공식 이미지의 고정 다이제스트를 지정한다.
save('41-turn',workload('baton','coturn',os.environ['COTURN_IMAGE'],3478,'64Mi','256Mi',mounts=[('coturn-config','/etc/coturn',True),('turn-tls','/tls',True)],extra=turn_extra)[1:])

def es_extra(c,p): p['securityContext']={'fsGroup':1000}
save('50-es',workload('portfolio','elasticsearch','knowledge-es:'+rev,9200,'1Gi','2Gi',{'discovery.type':'single-node','xpack.security.enabled':'false','ES_JAVA_OPTS':'-Xms768m -Xmx768m'},health='/_cluster/health',storage=('10Gi','/usr/share/elasticsearch/data'),extra=es_extra))
def knowledge_extra(c,p): c['envFrom'].append({'configMapRef':{'name':'knowledge-flags'}})
kenv={'ELASTICSEARCH_URL':'http://elasticsearch:9200','KNOWLEDGE_SOURCE_LOCATION':'classpath:knowledge/portfolio.json','KNOWLEDGE_CORS_ALLOWED_ORIGINS':origin,'MANAGEMENT_SERVER_PORT':'9091','MANAGEMENT_SERVER_ADDRESS':'0.0.0.0','AI_TRUST_PROXY_HEADERS':'true','AI_GLOBAL_ANSWERS_PER_MINUTE':'5','AI_CLIENT_ANSWERS_PER_MINUTE':'2','AI_GLOBAL_SEARCHES_PER_MINUTE':'60','AI_CLIENT_SEARCHES_PER_MINUTE':'10','AI_RETRY_MAX_ATTEMPTS':'1','OPENAI_SDK_MAX_RETRIES':'0','OPENAI_MAX_COMPLETION_TOKENS':'2000','OPENAI_REQUEST_TIMEOUT':'30s','OPENAI_CHAT_MODEL':'gpt-5-mini','OPENAI_EMBEDDING_MODEL':'text-embedding-3-large','OPENAI_EMBEDDING_DIMENSIONS':'1024','JAVA_TOOL_OPTIONS':'-Xms64m -Xmx384m -XX:+ExitOnOutOfMemoryError'}
save('51-knowledge',workload('portfolio','knowledge-api','knowledge-api:'+rev,8080,'384Mi','768Mi',kenv,['knowledge-secret'],health='/actuator/health/readiness',health_port=9091,extra=knowledge_extra))
ragconf=head+''':8080 {
 header {\n  X-Content-Type-Options nosniff\n  Referrer-Policy no-referrer\n  Cache-Control no-store\n }
 @api path /api/v1/knowledge/search /api/v1/knowledge/answers
 handle @api {
  request_body {\n   max_size 32KB\n  }
  reverse_proxy knowledge-api:8080 {
   header_up -Forwarded
   header_up -X-Real-IP
   header_up -X-Knowledge-Sync-Key
   header_up X-Forwarded-For {http.request.client_ip}
   header_up X-Forwarded-Proto https
  }
 }
 respond 404
}
'''
save('52-rag-web',[cm('portfolio','rag-caddy',{'Caddyfile':ragconf})]+workload('portfolio','rag-web',caddy,8080,'32Mi','128Mi',mounts=[('rag-caddy','/etc/caddy',False)]))

# TLS 진입점 로그에 구독 토큰과 OAuth code를 남기지 않는다.
# 아래 Ingress annotation은 Traefik 3.3 이상을 전제로 한다.
def ingress(ns,name,host,svc,secret,path='/'):
    x=obj('Ingress',ns,name,{'ingressClassName':'traefik','tls':[{'hosts':[host],'secretName':secret}], 'rules':[{'host':host,'http':{'paths':[{'path':path,'pathType':'Prefix','backend':{'service':{'name':svc,'port':{'number':8080}}}}]}}]})
    x['metadata']['annotations']={'traefik.ingress.kubernetes.io/router.entrypoints':'websecure','traefik.ingress.kubernetes.io/router.observability.accesslogs':'false'}
    return x
save('60-ingress',[ingress('baton','baton','b4ton.com','web','baton-tls'),ingress('baton','cal','cal.b4ton.com','cal-public','cal-tls','/calendars/v1/'),ingress('portfolio','rag',rag,'rag-web','rag-tls')])
PY
```

### 생성·검토

coturn은 공식 이미지를 한 번 조회한 뒤 **다이제스트로 고정**한다. 아래 `latest`는 다이제스트 조회용이며 매니페스트에는 사용하지 않는다. 운영 리비전을 따로 선정했다면 해당 버전을 pull한다.

```bash
sudo k3s ctr images pull docker.io/coturn/coturn:latest
export COTURN_IMAGE="$(sudo k3s ctr images list | awk '$1=="docker.io/coturn/coturn:latest" {print "docker.io/coturn/coturn@" $3}')"
test -n "$COTURN_IMAGE"
python3 render.py
kubectl -n baton create secret generic coturn-config --from-file=turnserver.conf=secrets/turnserver.conf
```

비밀값 없는 `manifests/`는 운영 기록에 보관한다. 원본 리비전·이미지 다이제스트·공개 설정을 함께 기록한다. 실제 비밀값과 `turnserver.conf`는 접근 제한·암호화된 별도 백업으로 보관한다.

이 문서의 `Secret`은 env 또는 볼륨으로 직접 연결한다. Compose 전용 `_FILE` 래퍼는 사용하지 않는다. Kubernetes Secret의 base64는 암호화가 아니므로 kubeconfig와 백업의 접근 권한을 제한한다.

```bash
kubectl apply --dry-run=server -f manifests/00-network.json
kubectl apply --dry-run=server -f manifests/10-databases.json
# 나머지 매니페스트도 같은 방식으로 서버 스키마를 확인한다.
for file in manifests/*.json; do
  kubectl apply --dry-run=server -f "$file" || break
done
```

하나라도 실패하면 이후 배포를 진행하지 않는다. `dry-run` 성공은 이미지 기동·TLS·통화 성공을 뜻하지 않는다. 특히 다음을 읽고 확인한다.

- 내부 TLS 주소의 hostAliases가 6절의 ClusterIP와 같다.
- Google OAuth, RSA 키, CAL 토큰·구독 세대가 비어 있지 않다.
- Pod CIDR과 Traefik 라벨이 실제 서버와 같다. 프록시 신뢰 설정은 해당 NetworkPolicy와 함께 적용한다.
- Traefik 3.3 이상에서 접근 로그 비활성 annotation을 지원한다. 지원하지 않으면 해당 라우트의 민감 경로 로그 제외를 설정한 뒤 공개한다. 기존 happyGallery의 로그 설정을 통째로 덮어쓰지 않는다.
- Cloudflare 프록시를 나중에 켜면 원본 IP 신뢰 설정을 다시 검토한다. 현재 설정은 DNS only와 기존 Traefik을 전제로 한다.
- Elasticsearch는 인증을 끈 대신 ClusterIP·NetworkPolicy로 검색 API에서만 접근한다. 9200을 Ingress·NodePort·hostPort로 공개하지 않는다.

[Traefik 라우트별 로그 설정](https://doc.traefik.io/traefik/reference/routing-configuration/http/routing/observability/), [Kubernetes 자원 제한](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/).

## 8. DB와 BATON부터 배포

초기 기능 설정 파일을 만든다. 이후 기능 활성화도 이 파일을 수정해서 반영한다.

```bash
cat > baton-flags.env <<'ENV'
BATON_CAL_CAPTURE_ENABLED=false
BATON_CAL_BACKFILL_ENABLED=false
BATON_CAL_DELIVERY_ENABLED=false
BATON_CAL_SUBSCRIPTIONS_ENABLED=false
BATON_CAL_SEASON_METADATA_ENABLED=false
BATON_CAL_SEASON_METADATA_MAINTENANCE=OFF
BATON_ROUND_PARTICIPATION_GRANT_ENABLED=false
BATON_WATCH_ENABLED=false
BATON_WATCH_EVENT_RECEIVER_ENABLED=false
BATON_BRIEF_DELIVERY_ENABLED=false
BATON_AUTH_LOCAL_REGISTRATION_ENABLED=false
BATON_AUTH_PASSWORD_RESET_ENABLED=false
BATON_EMAIL_VERIFICATION_DELIVERY=disabled
ENV
kubectl -n baton create configmap baton-flags --from-env-file=baton-flags.env
kubectl apply -f manifests/00-network.json
kubectl apply -f manifests/10-databases.json
kubectl -n baton rollout status statefulset/mysql --timeout=5m
kubectl -n baton rollout status statefulset/postgres --timeout=5m
kubectl -n baton exec statefulset/mysql -- sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" --ssl-mode=REQUIRED "$MYSQL_DATABASE" -e "SELECT 1"'
kubectl -n baton exec statefulset/postgres -- sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
kubectl apply -f manifests/20-baton.json
kubectl -n baton rollout status deployment/app --timeout=10m
kubectl apply -f manifests/21-web.json
kubectl -n baton rollout status deployment/web --timeout=5m
kubectl -n baton logs deployment/app --tail=100
```

MySQL·PostgreSQL StatefulSet의 시작만으로 DB 준비 완료를 판단하지 않는다. 위 쿼리와 CAL의 이후 readiness까지 성공해야 한다. 비밀번호 불일치가 생기면 초기화된 PVC의 비밀번호를 확인한다. Secret만 바꿔도 기존 DB 비밀번호가 바뀌지는 않는다.

BATON 로그에서 Flyway 성공·기동 완료를 확인한다. `ddl-auto`를 `update`로 바꾸거나 TLS 검증을 끄면서 오류를 우회하지 않는다.

첫 접근은 11절의 Ingress 적용 후 가능하다. 지금은 상태와 로그만 확인한다.

## 9. CAL·ROUND·TURN 배포

```bash
kubectl apply -f manifests/30-cal.json
kubectl -n baton rollout status deployment/cal --timeout=10m
kubectl apply -f manifests/31-cal-public.json
kubectl apply -f manifests/32-internal-tls.json
kubectl -n baton rollout status deployment/cal-public --timeout=5m
kubectl -n baton rollout status deployment/internal-tls --timeout=5m
kubectl apply -f manifests/40-round.json
kubectl apply -f manifests/41-turn.json
kubectl -n baton rollout status deployment/round-web --timeout=5m
kubectl -n baton rollout status deployment/round-signaling --timeout=5m
kubectl -n baton rollout status deployment/coturn --timeout=5m
kubectl -n baton logs deployment/coturn --tail=100
```

TURN 로그에서 사설 IP 바인딩·TLS 인증서 로드·인증 설정 오류가 없는지 확인한다. 이 컨테이너에는 HTTP 상태 확인을 붙이지 않았다. Pod Running만으로 TURN 할당 성공을 판단하지 않는다. 실제 외부 망 테스트는 12절에서 수행한다.

공인 IP가 바뀌면 DNS뿐 아니라 coturn의 `external-ip`도 변경해야 한다. `settings.env` 수정 → `render.py` 실행 → coturn Secret 갱신 → coturn 재시작 순서다. [coturn NAT·포트 설정](https://github.com/coturn/coturn/blob/master/docker/coturn/turnserver.conf)

### CAL 내부 HTTPS 확인·활성화

```bash
kubectl -n baton exec deployment/app -- wget -S -O /dev/null \
  https://cal.b4ton.com:8443/internal/api/v1/subscriptions/00000000-0000-0000-0000-000000000000
```

인증 토큰 없이 보낸 요청이므로 `401` 또는 `403` 실패가 정상이다. **인증서 오류·이름 해석 실패·연결 거부는 정상으로 처리하지 않는다.** 잘못된 토큰에 대한 거절과 실제 토큰을 사용하는 다음 전달 검증을 구분한다.

1. `baton-flags.env`에서 `BATON_CAL_CAPTURE_ENABLED=true`, `BATON_CAL_BACKFILL_ENABLED=true`로 바꾼다. 전달·구독은 아직 false다.
2. 아래 명령으로 반영하고 `app` 로그에서 보정 성공을 확인한다. 빈 DB여도 같은 순서로 진행한다.
3. 보정 성공 후 `BATON_CAL_BACKFILL_ENABLED=false`, `BATON_CAL_DELIVERY_ENABLED=true`로 변경하고 다시 반영한다.
4. 12절에서 일정 생성·변경과 아웃박스 전달 성공을 확인한 뒤 `BATON_CAL_SUBSCRIPTIONS_ENABLED=true`로 바꾼다.
5. 시즌 이름 연동·복구 모드는 이번 첫 배포에서 켜지 않는다. 필요한 경우 [CAL 계약의 활성화·복구 절차](../PRD/0006_calendar-integration-contract/spec.md)를 따른다.

```bash
kubectl -n baton create configmap baton-flags --from-env-file=baton-flags.env --dry-run=client -o yaml | kubectl apply -f -
kubectl -n baton rollout restart deployment/app
kubectl -n baton rollout status deployment/app --timeout=10m
kubectl -n baton logs deployment/app --tail=150
```

마지막으로 `BATON_ROUND_PARTICIPATION_GRANT_ENABLED=true`를 같은 파일에 반영하고 위 명령을 실행한다. 기존 ROUND 서명 키를 교체하면서 활성화하지 않는다. 서명 키 공개 조회는 12절에서 확인한다.

## 10. RAG 검색 배포 — AI 호출 전

Elasticsearch 8.19의 mmap 요구사항을 확인한다. 현재 값이 더 크면 낮추지 않는다.

```bash
sysctl vm.max_map_count
# 1048576보다 작을 때만 적용. 기존 다른 서비스 설정은 삭제하지 않는다.
if [ "$(sysctl -n vm.max_map_count)" -lt 1048576 ]; then
  printf 'vm.max_map_count=1048576\n' | sudo tee /etc/sysctl.d/90-portfolio-elasticsearch.conf >/dev/null
  sudo sysctl -p /etc/sysctl.d/90-portfolio-elasticsearch.conf
fi
```

[Elasticsearch 8.19 Docker 운영 설정](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/docker.html).

```bash
cat > knowledge-flags.env <<'ENV'
SPRING_PROFILES_ACTIVE=homeserver
AI_PROFILE=disabled
ELASTICSEARCH_INDEX=portfolio-knowledge-disabled-v3
KNOWLEDGE_SYNC_ON_STARTUP=true
KNOWLEDGE_TURNSTILE_ENABLED=false
ENV
kubectl -n portfolio create configmap knowledge-flags --from-env-file=knowledge-flags.env
kubectl apply -f manifests/50-es.json
kubectl -n portfolio rollout status statefulset/elasticsearch --timeout=10m
kubectl apply -f manifests/51-knowledge.json
kubectl -n portfolio rollout status deployment/knowledge-api --timeout=10m
kubectl apply -f manifests/52-rag-web.json
kubectl -n portfolio rollout status deployment/rag-web --timeout=5m
kubectl -n portfolio logs deployment/knowledge-api --tail=100
```

Elasticsearch 1노드의 `yellow`는 replica 부족으로 발생할 수 있다. `red`를 정상으로 취급하지 않는다. API readiness는 Elasticsearch 연결·상태를 확인하지만 자료의 최신 여부까지 증명하지 않는다.

## 11. 공개 경로와 HTTPS 연결

기존 80 → HTTPS 리디렉션이 Traefik 전체에 설정되어 있으면 유지한다. 없다면 아래처럼 **새 도메인에만** 리디렉션을 추가한다. happyGallery의 마이크·카메라 차단 헤더 미들웨어를 BATON에 복사하지 않는다. ROUND 통화가 막힐 수 있다.

```bash
kubectl apply -f manifests/60-ingress.json
python3 - <<'PY'
import json
from pathlib import Path
x=json.loads(Path('manifests/60-ingress.json').read_text())['items']
items=[]
for ns in ('baton','portfolio'):
    items.append({'apiVersion':'traefik.io/v1alpha1','kind':'Middleware','metadata':{'name':'redirect-https','namespace':ns},'spec':{'redirectScheme':{'scheme':'https','permanent':True}}})
for i in x:
    ns=i['metadata']['namespace']
    i['metadata']['name']+='-http'
    i['metadata']['annotations']={'traefik.ingress.kubernetes.io/router.entrypoints':'web','traefik.ingress.kubernetes.io/router.middlewares':ns+'-redirect-https@kubernetescrd','traefik.ingress.kubernetes.io/router.observability.accesslogs':'false'}
    i['spec'].pop('tls')
    items.append(i)
Path('manifests/61-http.json').write_text(json.dumps({'apiVersion':'v1','kind':'List','items':items},indent=2))
PY
kubectl apply -f manifests/61-http.json
curl -I https://b4ton.com
curl -I "https://$RAG_HOST/api/v1/knowledge/search"
curl -I https://cal.b4ton.com/internal/api/v1/subscriptions
curl -I https://b4ton.com/actuator/prometheus
```

BATON은 정상 페이지, 검색 API의 HEAD는 구현에 따라 `405`일 수 있다. CAL 내부 API와 BATON Actuator는 공개 주소에서 `404`여야 한다. TLS 검사에 `-k`를 쓰지 않는다. 인증서 이름 불일치·만료·체인 누락은 먼저 고친다.

```bash
curl --fail-with-body "https://$RAG_HOST/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  --data '{"query":"BATON 인수인계","limit":5}'
```

검색 결과가 있어야 한다. 이 단계의 AI 답변은 비활성 상태다. Elasticsearch 9200·RAG 관리 포트 9091·CAL 관리 포트 8081은 인터넷에 공개하지 않는다.

## 12. BATON·CAL·ROUND 실사용 확인

### BATON

1. Google 로그인 후 계정 화면이 열린다.
2. 운영자 생성 키로 첫 팀을 만든다. 생성·복구 키는 요청이 필요한 운영자만 사용한다.
3. `팀 초대·권한 관리`에서 복구 키로 첫 관리자를 지정한다. 이후 일반 계정 권한으로 사용한다.
4. 시즌·업무·모임을 생성하고 새로고침 후 유지되는지 확인한다.
5. 다른 계정의 팀 접근 제한과 로그아웃을 확인한다.

### CAL

1. 테스트 모임의 날짜를 바꾸고 캘린더 전달 로그·지표를 확인한다.
2. `baton_integration_delivery_items{integration="calendar"}`의 pending·processing이 처리 후 0으로 줄고 failed가 0인지 확인한다. 실패가 남으면 토큰·TLS·CAL 계약부터 확인한다.
3. 전달 확인 후 9절의 구독 활성화를 완료한다.
4. 오늘 화면에서 `내 캘린더에 추가`로 주소를 발급한다. 실제 Apple·Google·Outlook 중 하나에 URL 구독으로 등록한다.
5. 일정 변경 반영과 구독 해지 후 기존 URL 접근 거절을 확인한다. 외부 캘린더 앱의 갱신 주기는 즉시가 아닐 수 있다.
6. 토큰이 포함된 구독 URL을 터미널 출력·스크린샷·접근 로그에 남기지 않는다. Traefik·CAL 프록시 로그에 경로 원문이 기록되지 않는지 확인한다.

### ROUND

```bash
curl --fail-with-body https://b4ton.com/.well-known/round-participation-jwks.json
```

공개 RSA JWK에 `kid=homeserver-v1`이 있고 개인 키 필드가 없어야 한다.

1. BATON에서 권한 있는 스터디룸을 열어 ROUND로 진입한다. ROUND 독립 실행 모드의 호스트 토큰을 사용하지 않는다.
2. Wi-Fi 노트북과 휴대전화 모바일 데이터처럼 **서로 다른 망**의 두 기기로 접속한다.
3. 음성·영상·화면 공유·재접속을 확인한다. 무권한 방 접근은 거절되어야 한다.
4. 일반 연결 성공만으로 TURN을 통과했다고 판단하지 않는다. 별도 테스트용 ROUND 웹 이미지를 `VITE_ICE_TRANSPORT_POLICY=relay`로 빌드해 적용한 뒤 브라우저 WebRTC 진단의 선택 candidate pair가 `relay`인지 확인한다. 테스트 후 원래 이미지로 되돌린다.
5. UDP 차단 망에서 TURN TCP/TLS도 확인한다. 이 구성의 TURN TLS는 5349다. 외부 네트워크가 443만 허용하면 동작을 보장하지 않는다. 기존 HTTPS 443을 coturn에 넘기지 않는다.

TURN 실패 시 인증 비밀값 일치 → 공인/사설 IP 매핑 → 공유기·Ubuntu 포트 → 인증서 순서로 확인한다. 서버 LAN 안에서만 테스트하면 잘못된 NAT 설정을 놓칠 수 있다.

## 13. OpenAI·Turnstile 활성화

### 선택 이유와 비용

이 서버에서는 Ollama 대신 OpenAI가 적합하다. 로컬 기본 모델 `qwen3:8b`·`bge-m3`를 추가하면 모델 상주 메모리와 추론 CPU가 필요하다. 이미 여러 JVM과 Elasticsearch가 있으므로 홈서버는 검색만 담당하고 답변·임베딩은 외부 API로 처리한다.

현재 프로젝트 기본값 `gpt-5-mini`, `text-embedding-3-large` 1024차원을 유지한다. 최신 모델로 임의 변경하지 않는다. 모델·차원을 바꾸려면 검색 품질과 새 인덱스 재색인을 함께 검토한다. 가격은 배포 시 [GPT-5 mini 모델 페이지](https://developers.openai.com/api/docs/models/gpt-5-mini)와 [API 가격표](https://developers.openai.com/api/docs/pricing)를 확인한다.

비용은 답변뿐 아니라 **최초 문서 임베딩·검색 질의 임베딩·갱신 색인**에도 발생한다. 문서 검색만 해도 OpenAI 프로필의 벡터 검색은 API를 사용할 수 있다.

1. OpenAI에서 이 서비스 전용 프로젝트·API 키를 만든다. 다른 서비스 키를 공유하지 않는다.
2. 사용량 알림과 계정에서 제공하는 지출 제한을 설정한다. 알림만 설정한 것을 자동 차단으로 오해하지 않는다. 활성 계정의 제한 동작을 확인한다.
3. 아래 설정은 답변 전역 5회/분·사용자 IP별 2회/분, 검색 전역 60회/분·IP별 10회/분으로 시작한다. 이것은 **월 비용 상한이 아니다**. 공개 직후 사용량을 확인하고 필요하면 더 낮춘다.
4. API 키를 브라우저의 `VITE_*`에 넣지 않는다. 외부 요청에 공개 문서 근거와 사용자 질문이 전달됨을 포트폴리오에서 안내한다.

### Turnstile 준비

Cloudflare Turnstile에 **포트폴리오 웹의 호스트**를 등록한다. 예를 들어 현재 웹이 `ljkportfolio.netlify.app`이면 그 호스트다. API 호스트 `rag.b4ton.com`과 혼동하지 않는다.

- 사이트 키: 포트폴리오 웹의 `VITE_KNOWLEDGE_TURNSTILE_SITE_KEY`
- 비밀 키: 검색 API의 `KNOWLEDGE_TURNSTILE_SECRET_KEY`
- 서버는 `action=knowledge_answer`와 허용 hostname을 확인한다.

다음 파일을 편집기로 작성한다. `source`하거나 터미널에 출력하지 않는다.

```dotenv
# secrets/openai.env
OPENAI_API_KEY=실제_OpenAI_API_키
KNOWLEDGE_TURNSTILE_SECRET_KEY=실제_Turnstile_비밀_키
```

```bash
kubectl -n portfolio create secret generic knowledge-secret \
  --from-file=KNOWLEDGE_SYNC_KEY=secrets/KNOWLEDGE_SYNC_KEY \
  --from-env-file=secrets/openai.env --dry-run=client -o yaml | kubectl apply -f -
cat > knowledge-flags.env <<'ENV'
SPRING_PROFILES_ACTIVE=homeserver,openai
AI_PROFILE=openai
ELASTICSEARCH_INDEX=portfolio-knowledge-openai-v3
KNOWLEDGE_SYNC_ON_STARTUP=false
KNOWLEDGE_TURNSTILE_ENABLED=true
KNOWLEDGE_TURNSTILE_EXPECTED_HOSTNAMES=ljkportfolio.netlify.app
ENV
# 실제 포트폴리오 호스트가 다르면 위 EXPECTED_HOSTNAMES를 수정한다.
kubectl -n portfolio create configmap knowledge-flags --from-env-file=knowledge-flags.env --dry-run=client -o yaml | kubectl apply -f -
kubectl -n portfolio rollout restart deployment/knowledge-api
kubectl -n portfolio rollout status deployment/knowledge-api --timeout=10m
```

이제 **새 OpenAI 인덱스**에 최초 색인을 수행한다. 기존 disabled 인덱스의 이름만 바꿔 재사용하지 않는다.

서버의 별도 터미널에서 관리용 연결을 연다. 바인딩은 127.0.0.1로 유지한다.

```bash
kubectl -n portfolio port-forward deployment/knowledge-api 18080:8080
```

원래 서버 터미널에서 실행한다. 색인 요청은 OpenAI 임베딩 요금이 발생하며 문서 수에 따라 수 분 걸릴 수 있다.

```bash
printf 'X-Knowledge-Sync-Key: %s\n' "$(cat secrets/KNOWLEDGE_SYNC_KEY)" > secrets/knowledge-header
curl --fail-with-body --max-time 900 -X POST \
  -H @secrets/knowledge-header http://127.0.0.1:18080/internal/v1/knowledge/sync
curl --fail-with-body -H @secrets/knowledge-header \
  http://127.0.0.1:18080/internal/v1/knowledge/status
```

`upToDate=true`인지 확인한다. 타임아웃이면 동기화 상태부터 확인하고 중복 실행하지 않는다. `409`는 진행 중인 동기화를 뜻한다. 실패한 색인은 원인을 해결한 뒤 같은 자료로 재실행한다.

### 프런트엔드 연결 — 현재 포트폴리오 호스팅

현재 정적 웹 호스팅의 빌드 환경에 다음 두 값을 설정하고 웹을 다시 빌드·배포한다. 저장소의 포트폴리오 자료도 API 이미지와 같은 리비전을 사용한다.

```dotenv
VITE_KNOWLEDGE_API_BASE_URL=https://rag.b4ton.com
VITE_KNOWLEDGE_TURNSTILE_SITE_KEY=실제_공개_사이트_키
```

확인 사항:

1. 브라우저에서 검색 결과와 출처 링크가 보인다.
2. Turnstile 검증 후 질문하면 근거를 포함한 `GENERATED` 답변이 보인다.
3. 관련 자료가 없으면 `INSUFFICIENT_EVIDENCE`가 될 수 있다. 무조건 답변을 만들도록 제한을 풀지 않는다.
4. Turnstile 없는 공개 답변 요청은 거절된다. 내부 동기화 키를 공개 프록시에 보내도 우회되지 않아야 한다.
5. OpenAI 장애·사용량 제한 때 검색 결과를 유지하고 답변 불가를 안내한다.
6. 서로 다른 사용자 IP의 호출 제한이 독립적으로 동작하고, 위조 `X-Forwarded-For`로 제한을 우회할 수 없는지 확인한다. 이번 구성은 RAG 프록시가 헤더를 한 값으로 덮어쓰고 API 접근을 해당 프록시로 제한한다.

작업이 끝나면 `port-forward`를 Ctrl+C로 종료한다.

## 14. 모니터링·인증서 갱신

별도 Prometheus·Grafana·Alloy를 추가하지 않는다. 기존 수집기에 다음 대상을 **추가**한다. 기존 수집 설정을 덮어쓰지 않는다.

| 대상 | 주소 |
| --- | --- |
| BATON | `app.baton.svc.cluster.local:8080/actuator/prometheus` |
| CAL | `cal-metrics.baton.svc.cluster.local:8081/actuator/prometheus` |
| ROUND | `round-signaling.baton.svc.cluster.local:8787/actuator/prometheus` |
| RAG | Pod 9091의 `/actuator/prometheus` — 아래 관리 Service 추가 |

```bash
kubectl -n portfolio expose deployment knowledge-api --name=knowledge-metrics --port=9091 --target-port=9091
kubectl -n baton expose deployment cal --name=cal-metrics --port=8081 --target-port=8081
```

CAL 관리 수집 주소는 실제로 `cal-metrics.baton.svc.cluster.local:8081`, RAG는 `knowledge-metrics.portfolio.svc.cluster.local:9091`을 사용한다. 기존 `cal` Service는 업무 포트만 열고 있다. `MONITOR_NAMESPACE`와 기존 Prometheus egress 정책도 확인한다.

관찰 대상은 메모리·OOMKilled·재시작·디스크 여유·HTTP 5xx·CAL 적체·TURN 할당 실패·RAG 검색/답변 실패·OpenAI 실제 사용료다. 애플리케이션 메트릭만으로 공급자의 최종 청구액을 계산하지 않는다.

인증서 갱신 시:

1. 기존 갱신 도구가 새 인증서와 개인 키를 정상 생성했는지 확인한다.
2. 수동 Secret 사용 시 5절의 `create secret tls`에 `--dry-run=client -o yaml | kubectl apply -f -`를 붙여 갱신한다.
3. Traefik은 Secret 변경을 반영한다. **내부 Caddy와 coturn은 Secret 갱신만으로 인증서를 다시 읽었다고 가정하지 말고 재시작한다.** coturn 재시작은 통화 없는 시간에 진행한다.
4. 외부 HTTPS와 앱→CAL 내부 TLS·ROUND JWK·TURN TLS를 다시 확인한다. 만료 전에 갱신됐는지 모니터링한다.

## 15. 백업·업데이트·복구

### 백업

서버 디스크 하나의 PVC는 백업이 아니다. DB dump·CAL 구독 세대·서명 키·운영 설정을 서버 밖의 보유 장치에도 암호화해서 보관한다. 같은 서버의 다른 폴더만으로는 디스크 장애에 대비할 수 없다.

```bash
mkdir -p backups
BACKUP_ID="$(date +%Y%m%d-%H%M%S)"
kubectl -n baton exec statefulset/mysql -- sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF baton' \
  > "backups/baton-$BACKUP_ID.sql"
kubectl -n baton exec statefulset/postgres -- sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > "backups/cal-$BACKUP_ID.dump"
test -s "backups/baton-$BACKUP_ID.sql"
test -s "backups/cal-$BACKUP_ID.dump"
```

각 명령의 종료 성공을 확인한다. 파일이 존재한다는 것만으로 백업이 성공한 것은 아니다. 단일 트랜잭션 dump는 테이블 데이터의 일관성을 위한 것이며 서비스 간 동시 시점 백업을 만들지는 않는다. 이 두 dump는 서로 다른 시점의 데이터이므로 CAL 재전달·복구 검증이 필요하다. DB 업그레이드 중에는 백업하지 않는다.

Elasticsearch는 공개 문서 JSON과 같은 모델·차원·청크 설정을 보관하면 재색인할 수 있다. 따라서 첫 운영에서는 DB처럼 별도 snapshot 저장소를 추가하지 않는다. 대신 원본 이미지·공개 자료 리비전·인덱스 설정을 보관한다. 재색인에는 시간과 OpenAI 임베딩 비용이 발생한다.

주기적으로 **별도 네임스페이스·새 PVC**에 DB를 복원해 실제 조회까지 확인한다. 현재 PVC를 지우고 복원 연습을 하지 않는다. CAL 과거 복원에는 구독 세대·복구 모드·BATON 최신 상태 재전달이 필요하므로 [CAL 복구 절차](../PRD/0006_calendar-integration-contract/spec.md)와 CAL 저장소 운영 문서를 함께 따른다. dump만 덮어쓴 뒤 공개하지 않는다.

### 업데이트

1. 현재 이미지·설정·스키마 버전과 백업을 기록한다.
2. 새 커밋으로 새 태그의 이미지를 빌드하고 아키텍처·CI를 확인한다.
3. `images.tar`를 새로 import한다. 기존 태그를 재사용하지 않는다.
4. 이전 `manifests/`를 보관하고 `RELEASE`를 변경해 생성한다. Secret·CAL 구독 세대·RSA 키는 새로 만들지 않는다.
5. 변경한 서비스의 매니페스트만 적용한다. `Recreate`이므로 서비스별 짧은 중단을 알리고 한 번에 하나씩 배포한다.
6. DB 마이그레이션이 있으면 애플리케이션 시작 전에 해당 변경의 구버전 호환성을 확인한다.
7. RAG 자료가 바뀌었으면 13절 내부 동기화를 한 번 실행하고 `upToDate=true`를 확인한다.
8. 12·13절에서 변경한 기능을 다시 확인한다.

### 장애 시 되돌리기

- 코드만 바뀌고 DB·계약이 이전 이미지와 호환되면 이전 매니페스트를 적용한다. `kubectl rollout undo`만으로 Secret·ConfigMap은 되돌아가지 않는다.
- DB 변경이 이전 코드와 호환되지 않으면 단순 이미지 롤백을 하지 않는다. 쓰기 중단 → 백업 복원 → 호환 이미지·설정 적용 → CAL 복구 순서를 따른다.
- 메모리가 부족하면 `kubectl -n portfolio scale deployment knowledge-api rag-web --replicas=0` 후 `kubectl -n portfolio scale statefulset elasticsearch --replicas=0`으로 RAG부터 내린다. PVC는 삭제하지 않는다. 이후 같은 리소스를 replicas=1로 올리고 검색·색인을 확인한다.
- OpenAI 비용·공급자 장애로 외부 AI 호출을 중단하려면 `SPRING_PROFILES_ACTIVE=homeserver`, `AI_PROFILE=disabled`, `ELASTICSEARCH_INDEX=portfolio-knowledge-disabled-v3`로 되돌려 API를 재시작한다. 답변뿐 아니라 질의 임베딩도 중단된다. disabled 인덱스를 최신 자료로 동기화하고 웹의 안내 상태를 확인한다.
- `kubectl delete namespace`, `kubectl delete pvc`, `docker compose down -v`는 업데이트·일반 복구 명령이 아니다.

## 16. 배포 완료 기준

- [ ] happyGallery가 배포 전과 동일하게 동작한다.
- [ ] BATON Google 로그인·권한·업무 저장이 정상이다.
- [ ] CAL 내부 API가 공개되지 않고 실제 구독·변경·해지가 동작한다.
- [ ] ROUND 두 외부 망 통화와 relay 강제 TURN 테스트가 성공한다.
- [ ] 포트폴리오 검색·Turnstile·OpenAI 근거 답변이 정상이다.
- [ ] OpenAI 사용량·지출 설정을 확인했고 비밀 키가 브라우저에 없다.
- [ ] 접근 로그에 OAuth code·캘린더 토큰·참여권 쿠키가 남지 않는다.
- [ ] 메모리 여유가 있고 OOMKilled·지속 재시작·디스크 부족이 없다.
- [ ] 인증서 갱신·백업·별도 복원 확인을 담당할 절차가 있다.

## 문서 검증 범위

작성 시 Bash 코드 블록 문법, Python 생성기 실행, 생성 리소스 50개의 kubeconform strict 검사, 문서 상대 링크를 확인했다. Caddy의 BATON·RAG·내부 TLS 설정과 Nginx 공개 CAL 설정은 포트를 열지 않는 로컬 임시 컨테이너에서 검사했다. Nginx 문법 검사는 로컬 1.31.3 이미지로 수행했으며 배포 예시의 1.31.4 이미지 기동 확인을 대신하지 않는다. 내부 TLS 문법 검사에는 운영 인증서 대신 임시 인증서를 사용했다.

실제 k3s 적용, DB 초기화·복원, 공개 인증서 체인, 외부 망 TURN 통화, OAuth·OpenAI·Turnstile 공급자 연동과 부하 검증은 실행하지 않았다. 위 단계별 확인과 배포 완료 기준을 운영 환경에서 통과해야 배포 완료다.

## 참고

- [BATON 운영 Compose](../../compose.production.yml), [ROUND 연동 Compose](../../compose.round.production.yml), [기존 Caddy 정책](../../ops/Caddyfile)
- [ROUND 운영 경계](../ADR/0018_round-production-runtime/adr.md), [외부 연동 운영 안내](free-integrations.md)
- [K3s 네트워크](https://docs.k3s.io/networking/networking-services), [로컬 스토리지](https://docs.k3s.io/add-ons/storage)
- 다른 저장소 기준 파일: CAL `compose.operations.yml`·`operations/nginx/calendar.conf.template`, ROUND `ops/baton.env.example`·`ops/coturn/turnserver.conf.example`, 포트폴리오 `knowledge-api/README.md`·`knowledge-api/.env.homeserver.example`.
