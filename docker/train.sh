#!/usr/bin/env bash
# Mock 이미지를 만든다 — 기반 이미지를 띄워 JVM AOT 캐시(JEP 483 · 514 · 515)를 학습하고, 그 이미지에 캐시를 얹는다.
# be 의 docker/aot-training/train.sh 와 같은 순서다. CI(_image.yml)와 로컬이 같은 명령을 쓴다.
#
#   ./gradlew bootJar && bash docker/train.sh
#
# 1) 기반 이미지(docker/Dockerfile, 캐시 없음)와 마이그레이션 이미지(db/)를 만든다.
# 2) 빈 MySQL 을 띄우고 마이그레이션 이미지로 표를 만든다 — 운영과 같은 길이다.
# 3) 기반 이미지를 띄워 대표 요청을 넣고 정상 종료한다. 캐시는 -XX:AOTCacheOutput 으로 JVM 이 끝날 때 쓰인다.
#    Mock 은 ddl-auto: validate 로 뜨므로, 마이그레이션이 만든 표가 엔티티와 다르면 여기서 기동이 멈춘다.
#    요청의 응답 코드도 확인한다 — 올릴 이미지가 실제로 등록 · 조회 · 취소를 하는지 여기서 본다.
# 4) 학습한 그 이미지에 캐시를 얹는다(docker/Dockerfile.aot → FINAL_IMAGE). 다시 빌드한 이미지에 얹으면 안 된다.
# 5) 검증: 최종 이미지를 그 ENTRYPOINT 에 -XX:AOTMode=on 을 더해 띄운다 — JDK · 캐시 경로 · 모듈 구성이 어긋나 캐시를 받지 못하면
#    JVM 이 실패한다(운영의 기본 auto 는 조용히 캐시 없이 뜬다). 운영이 켜는 OTel 에이전트를 붙여 한 번 더 띄운다.
#    기반 이미지를 띄운 시간과 함께 표로 남긴다(SUMMARY, 없으면 표준 출력).
#
# 학습 환경은 이번 실행에서만 쓰고 버린다. 호스트 포트 · 볼륨을 쓰지 않고, DB 비밀번호는 매번 새로 만든다.
#
# 환경 변수
#   FINAL_IMAGE     최종 이미지 이름(기본 nova-mock:local)
#   MIGRATION_IMAGE 마이그레이션 이미지 이름(기본 nova-mock-migration:local). 여기서 db/ 로 만든다
#   TRAIN_CPUS · TRAIN_MEMORY   학습 · 측정 컨테이너 자원(기본 1 · 2g — 운영 태스크와 같다). 정확성 조건이 아니라 측정 조건이다
#   ROUNDS          요청 학습에서 작업자 하나가 도는 횟수(기본 150, 작업자 4)
#   SUMMARY         검증 표를 덧붙일 파일(CI 는 $GITHUB_STEP_SUMMARY)
#   KEEP=1          끝나도 컨테이너를 지우지 않는다(디버깅)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROJECT=nova-mock-aot
BASE=$PROJECT:base
FINAL_IMAGE=${FINAL_IMAGE:-nova-mock:local}
MIGRATION_IMAGE=${MIGRATION_IMAGE:-nova-mock-migration:local}
TRAIN_CPUS=${TRAIN_CPUS:-1}
TRAIN_MEMORY=${TRAIN_MEMORY:-2g}
ROUNDS=${ROUNDS:-150}
SUMMARY=${SUMMARY:-/dev/stdout}
PORT=8081
# 헬스 체크를 내놓는 관리 포트. 앱 기본값(MockDefaults)과 같다
MANAGEMENT_PORT=9081
# 학습에 더하는 모듈. Dockerfile.aot 의 ENTRYPOINT 와 같아야 캐시가 받아진다 — 이유는 그 파일에
AOT_MODULES=--add-modules=java.instrument
MYSQL_IMAGE='mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242'
CURL_IMAGE='curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6'
DB_PASSWORD=$(openssl rand -hex 16)
WORK=$(mktemp -d)

log() { echo "[aot] $*" >&2; }
now() { python3 -c 'import time; print(time.time())'; }

cleanup() {
  local rc=$?
  if [ "${KEEP:-}" != 1 ]; then
    for c in $(docker ps -aq --filter "label=$PROJECT"); do docker rm -f "$c" > /dev/null 2>&1 || true; done
    rm -rf "$WORK"
  else
    log "남겨 둠: 컨테이너(label=$PROJECT) · 작업 디렉터리 $WORK"
  fi
  exit $rc
}
trap cleanup EXIT

fail_with_logs() {
  log "$1"
  docker logs --tail 80 "$2" >&2 2>&1 || true
  exit 1
}

jars=()
for f in "$ROOT"/build/libs/*.jar; do [[ $f == *-plain.jar ]] || [ ! -e "$f" ] || jars+=("$f"); done
[ "${#jars[@]}" -eq 1 ] || { echo "build/libs 의 실행 jar 가 하나가 아니다(${#jars[@]}개). ./gradlew clean bootJar 로 다시 만든다" >&2; exit 1; }

# ── 1) 이미지 ────────────────────────────────────────────────────
# 출처 증명(provenance)을 붙이면 태그가 매니페스트 하나가 아니라 목록을 가리킨다. 배포는 태스크가 받은 이미지의 digest 를
# 올린 digest 와 맞춰 보므로 매니페스트 하나로 둔다.
build() { docker buildx build --quiet --load --provenance=false --platform linux/amd64 "$@" > /dev/null; }
build -f "$ROOT/docker/Dockerfile" -t "$BASE" "$ROOT"
build -t "$MIGRATION_IMAGE" "$ROOT/db"
[ "$(docker run --rm --entrypoint id "$BASE" -u)" != 0 ] || { echo "Mock 이미지가 루트로 돈다" >&2; exit 1; }

# ── 2) DB ────────────────────────────────────────────────────────
# 나머지 컨테이너는 모두 이 컨테이너의 네트워크를 함께 쓴다(127.0.0.1). 호스트 포트를 열지 않고, 한 번에 Mock 하나만 띄운다.
mysql=$(docker run -d --label "$PROJECT" \
  -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" -e MYSQL_DATABASE=external_mock \
  -e MYSQL_USER=nova -e MYSQL_PASSWORD="$DB_PASSWORD" \
  "$MYSQL_IMAGE" --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --transaction-isolation=READ-COMMITTED)
ready=false
for _ in $(seq 90); do
  if docker exec -e MYSQL_PWD="$DB_PASSWORD" "$mysql" mysql --protocol=TCP -h127.0.0.1 -unova external_mock -e 'SELECT 1' > /dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[ "$ready" = true ] || fail_with_logs "MySQL 이 뜨지 않았다" "$mysql"

docker run --rm --label "$PROJECT" --platform linux/amd64 --network "container:$mysql" \
  -e FLYWAY_URL='jdbc:mysql://127.0.0.1:3306/external_mock?allowPublicKeyRetrieval=true&sslMode=DISABLED' \
  -e FLYWAY_USER=nova -e FLYWAY_PASSWORD="$DB_PASSWORD" "$MIGRATION_IMAGE" > "$WORK/flyway.log" 2>&1 \
  || { cat "$WORK/flyway.log" >&2; echo "마이그레이션 실패" >&2; exit 1; }

# 인프라가 넣는 설정과 같은 모양이다 (nova-infra application/mock/application.yml). 접속 주소 · 계정만 학습용이다.
APP_JSON=$(jq -nc --argjson port "$PORT" '{
  spring: {
    threads: {virtual: {enabled: true}},
    datasource: {hikari: {"transaction-isolation": "TRANSACTION_READ_COMMITTED", "maximum-pool-size": 30,
                          "data-source-properties": {useLocalSessionState: true}}},
    jpa: {hibernate: {"ddl-auto": "validate"}, properties: {hibernate: {jdbc: {time_zone: "UTC"}}}, "open-in-view": false}
  },
  server: {port: $port}
}')

# Mock 컨테이너를 띄운다. 나머지 인자는 docker run 에 그대로 넘긴다(이미지 · 명령 포함).
#   app_container <이름> <-d · --rm> docker run 인자…
app_container() {
  local name=$1 how=$2
  shift 2
  docker run "$how" --name "$name" --label "$PROJECT" --platform linux/amd64 --network "container:$mysql" \
    --cpus "$TRAIN_CPUS" --memory "$TRAIN_MEMORY" \
    -e SPRING_APPLICATION_JSON="$APP_JSON" \
    -e SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/external_mock?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&sslMode=DISABLED' \
    -e SPRING_DATASOURCE_USERNAME=nova -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    "$@"
}

# ── 3) 학습 ──────────────────────────────────────────────────────
# 이미지의 ENTRYPOINT 대신 java 를 직접 부른다 — 학습 옵션(-XX:AOTCacheOutput)을 붙여야 해서다.
train=$PROJECT-train
app_container "$train" -d --entrypoint java "$BASE" \
  -XX:AOTCacheOutput=/tmp/app.aot "$AOT_MODULES" -jar /app/app.jar > /dev/null

# 운영의 컨테이너 헬스 체크와 같은 것을 묻는다 — 관리 포트의 readiness (MockDefaults). 이미지에 curl 이 없어 bash 로 직접 묻는다
probe="exec 3<>/dev/tcp/127.0.0.1/$MANAGEMENT_PORT && printf 'GET /actuator/health/readiness HTTP/1.0\r\n\r\n' >&3 && read -r _ code _ <&3 && [ \"\${code%\$'\r'}\" = 200 ]"
up=false
for _ in $(seq 90); do
  [ "$(docker inspect --format '{{.State.Running}}' "$train")" = true ] || fail_with_logs "Mock 이 뜨다 멈췄다" "$train"
  if docker exec "$train" bash -c "$probe" 2> /dev/null; then up=true; break; fi
  sleep 2
done
[ "$up" = true ] || fail_with_logs "Mock 이 3분 안에 응답하지 않았다" "$train"

# 대표 요청. 작업자 4개가 등록 → 재생 → 조회 → 취소 → 취소 뒤 재등록을 돌고, 설정 변경 · 주입 실패 · 초기화를 한 번씩 지난다.
# 응답 코드가 기대와 다르면 실패한다. 지연은 5ms 로 낮춘다 — 캐시에 담기는 것은 지난 경로이지 기다린 시간이 아니다.
log "요청 학습(작업자 4 × ${ROUNDS}회)"
docker run --rm -i --label "$PROJECT" --network "container:$mysql" -e ROUNDS="$ROUNDS" -e PORT="$PORT" \
  --entrypoint sh "$CURL_IMAGE" -s <<'WORKLOAD' || fail_with_logs "요청 학습 실패" "$train"
set -eu
base="http://127.0.0.1:$PORT/external"
json='Content-Type: application/json'
# expect <기대 코드 패턴> <응답을 둘 파일> curl 인자…
expect() {
  want=$1 out=$2
  shift 2
  got=$(curl -s -o "$out" -w '%{http_code}' "$@")
  case "$got" in
    $want) ;;
    *) echo "기대 $want, 받음 $got: $*" >&2; cat "$out" >&2; exit 1 ;;
  esac
}
body() {
  printf '{"ourReservationId":"%s","customerRef":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d","itemCode":"0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e","optionCode":"%s","qty":1,"scope":"preorder"}' "$1" "$2"
}
register() { expect "$1" "$2" -X POST "$base/reservations" -H "$json" -H "Idempotency-Key: $3" -d "$(body "$3" "$4")"; }

expect 200 /tmp/c "$base/config"
expect 200 /tmp/c -X PUT "$base/config" -H "$json" -d '{"registerLatencyMs":5,"failureRate":0.0,"failureMode":"HTTP_5XX"}'

worker() {
  out=/tmp/w$1
  i=0
  while [ "$i" -lt "$ROUNDS" ]; do
    key=$(cat /proc/sys/kernel/random/uuid)
    register 201 "$out" "$key" SM-G999-256-BLK
    number=$(sed -n 's/.*"externalNumber":"\([^"]*\)".*/\1/p' "$out")
    [ -n "$number" ] || { echo "등록 응답에 externalNumber 가 없다" >&2; cat "$out" >&2; exit 1; }
    register '20?' "$out" "$key" SM-G999-256-BLK          # 같은 키 · 같은 내용 — 재생
    register 422 "$out" "$key" SM-G999-512-WHT            # 같은 키 · 다른 내용
    expect 200 "$out" "$base/reservations/by-key/$key"
    expect 200 "$out" "$base/reservations/$number"
    if [ $((i % 3)) -eq 0 ]; then
      expect 200 "$out" -X POST "$base/cancellations" -H "$json" -d "{\"externalKey\":\"$key\",\"reservationNo\":\"$number\",\"reason\":\"USER_CANCEL\"}"
      register 409 "$out" "$key" SM-G999-256-BLK          # 취소 뒤 재등록
    fi
    if [ $((i % 10)) -eq 0 ]; then
      expect 200 "$out" "$base/reservations?size=100"
      register 400 "$out" "$key" ''                       # 형식 오류
    fi
    i=$((i + 1))
  done
}
pids=''
for w in 1 2 3 4; do
  worker "$w" &
  pids="$pids $!"
done
for pid in $pids; do wait "$pid"; done

# 주입 실패. 저장하지 않으므로 실패율을 내리면 같은 키가 곧바로 성공한다
expect 200 /tmp/c -X PUT "$base/config" -H "$json" -d '{"registerLatencyMs":5,"failureRate":1.0,"failureMode":"MIXED"}'
key=$(cat /proc/sys/kernel/random/uuid)
i=0
while [ "$i" -lt 20 ]; do
  register '5??' /tmp/c "$key" SM-G999-256-BLK
  i=$((i + 1))
done
expect 200 /tmp/c -X PUT "$base/config" -H "$json" -d '{"registerLatencyMs":5,"failureRate":0.0,"failureMode":"HTTP_5XX"}'
register 201 /tmp/c "$key" SM-G999-256-BLK
expect 200 /tmp/c -X POST "$base/reset" -H "$json" -d '{"confirm":"RESET"}'
WORKLOAD

# 정상 종료(SIGTERM)에서 캐시가 쓰인다. 종료 처리 · 캐시 조립에 시간이 걸려 넉넉히 기다린다.
# 캐시가 없으면 어떻게 끝났는지(종료 코드 · OOM)를 함께 남긴다 — 137 은 캐시를 다 쓰기 전에 강제 종료된 것이다
docker stop -t 180 "$train" > /dev/null
mkdir -p "$ROOT/build/aot"
if ! docker cp "$train:/tmp/app.aot" "$ROOT/build/aot/app.aot" > /dev/null 2>&1; then
  state=$(docker inspect -f 'exit {{.State.ExitCode}}, OOMKilled={{.State.OOMKilled}}' "$train" 2>&1 || true)
  fail_with_logs "AOT 캐시가 만들어지지 않았다($state)" "$train"
fi
docker rm "$train" > /dev/null

# ── 4) 최종 이미지 · 5) 검증 ─────────────────────────────────────
build -f "$ROOT/docker/Dockerfile.aot" --build-arg TRAINED_IMAGE="$BASE" -t "$FINAL_IMAGE" "$ROOT"

# 이미지를 그 ENTRYPOINT 그대로 띄워 빈 생성 완료(onRefresh)까지 걸린 벽시계 시간(컨테이너 시작 포함 — 두 이미지에 같은 몫).
# 옵션은 JDK_JAVA_OPTIONS 로 더한다(java 실행기가 명령줄 앞에 붙인다). 최종 이미지에 AOTMode=on 을 주면 캐시를 받지 못할 때
# 여기서 실패한다. 나머지 인자는 docker run 에 넘긴다(환경 변수).
#   startup_seconds <이미지> [더할 JVM 옵션] [docker run 인자…]
startup_seconds() {
  local image=$1 extra=${2:-} start
  shift 2 2> /dev/null || shift $#
  start=$(now)
  app_container "$PROJECT-measure" --rm -e JDK_JAVA_OPTIONS="$extra -Dspring.context.exit=onRefresh" "$@" "$image" \
    > "$WORK/measure.log" 2>&1 || { log "측정 실행 실패($image $extra)"; tail -n 60 "$WORK/measure.log" >&2; return 1; }
  awk -v s="$start" -v e="$(now)" 'BEGIN { printf "%.1f", e - s }'
}

# 운영처럼 에이전트를 켠다. 내보내기는 끈다 — 받을 곳이 없고, 기동에서 볼 것은 캐시 수락이다
AGENT_ENV=(-e OTEL_SERVICE_NAME=aot-training -e OTEL_TRACES_EXPORTER=none -e OTEL_METRICS_EXPORTER=none -e OTEL_LOGS_EXPORTER=none)

# 기준은 기반 이미지 그대로다(JDK 기본 CDS 는 켜진 채). AOTMode=off 는 기본 CDS 까지 꺼 효과를 부풀린다.
with=$(startup_seconds "$FINAL_IMAGE" -XX:AOTMode=on)
with_agent=$(startup_seconds "$FINAL_IMAGE" "-XX:AOTMode=on -javaagent:/otel/javaagent.jar" "${AGENT_ENV[@]}")
without=$(startup_seconds "$BASE")
aot=$(wc -c < "$ROOT/build/aot/app.aot" | tr -d ' ')
{
  echo "## AOT 캐시"
  echo "측정 자원 cpu $TRAIN_CPUS / 메모리 $TRAIN_MEMORY · 기동은 빈 생성 완료(onRefresh)까지"
  echo
  echo "| 기동(캐시 없음) | 기동(캐시) | 단축 | 기동(캐시 + 에이전트) | app.aot |"
  echo "|---|---|---|---|---|"
  awk -v a="$without" -v b="$with" -v c="$with_agent" -v size="$aot" \
    'BEGIN { printf "| %ss | %ss | %.0f%% | %ss | %.0f MB |\n", a, b, (1 - b / a) * 100, c, size / 1048576 }'
} >> "$SUMMARY"
log "검증 통과 — ${without}s → ${with}s(에이전트 ${with_agent}s) · $FINAL_IMAGE"
