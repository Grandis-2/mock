#!/usr/bin/env bash
# 마이그레이션의 파일 규칙을 보고, 빈 MySQL 에 적용해 본다.
# 이번 검사만의 MySQL 컨테이너를 띄우고 끝나면 지운다. 호스트 포트 · 볼륨을 쓰지 않고 다른 DB 에 접속하지 않는다.
#
# 사용: bash db/verify.sh
#   BASE=<커밋>    주면 그 커밋에 있던 마이그레이션이 그대로인지, 새 파일의 버전이 그보다 뒤인지도 본다 (CI 가 준다)
set -euo pipefail

db_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$db_dir/.." && pwd)"
mysql_image='mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242'
password=nova-ci-only

# ── 파일 규칙 ────────────────────────────────────────────────
# 이름은 V<yyyyMMddHHmm>__<설명>.sql 이다. 자릿수가 고정이라 이름순이 곧 적용 순서다.
shopt -s nullglob
files=("$db_dir"/migrations/*)
[ "${#files[@]}" -gt 0 ] || { echo "db/migrations 가 비었다" >&2; exit 1; }
for f in "${files[@]}"; do
  [[ "$(basename "$f")" =~ ^V[0-9]{12}__[a-z0-9_]+\.sql$ ]] \
    || { echo "이름 규칙(V<yyyyMMddHHmm>__<소문자_설명>.sql)에 맞지 않는다: $(basename "$f")" >&2; exit 1; }
done
dup=$(printf '%s\n' "${files[@]##*/}" | cut -c1-13 | sort | uniq -d)
[ -z "$dup" ] || { echo "버전이 겹친다: $dup" >&2; exit 1; }

# 합쳐진 마이그레이션은 고치지 않는다 — 이미 적용한 DB 와 체크섬이 어긋나 다음 배포가 멈춘다. 새 파일을 더한다.
if [ -n "${BASE:-}" ]; then
  changed=$(git -C "$repo_dir" diff --name-status --no-renames "$BASE" HEAD -- db/migrations)
  touched=$(awk '$1 != "A" {print $2}' <<< "$changed")
  [ -z "$touched" ] || { echo "기준($BASE)에 있던 마이그레이션을 고치거나 지웠다: $touched" >&2; exit 1; }
  newest_base=$(git -C "$repo_dir" ls-tree -r --name-only "$BASE" -- db/migrations | sort | tail -1)
  while read -r f; do
    [ -z "$f" ] || [ -z "$newest_base" ] || [[ "$(basename "$f")" > "$(basename "$newest_base")" ]] \
      || { echo "새 마이그레이션 $(basename "$f") 의 버전이 기준의 최신($(basename "$newest_base"))보다 앞이다. 시각을 다시 잡는다" >&2; exit 1; }
  done < <(awk '$1 == "A" {print $2}' <<< "$changed")
fi

# ── 빈 DB 에 적용 ────────────────────────────────────────────
image="nova-mock-migration:verify-$$"
docker build --quiet --platform linux/amd64 -t "$image" "$db_dir" > /dev/null
mysql=$(docker run -d --rm \
  -e MYSQL_ROOT_PASSWORD="$password" -e MYSQL_DATABASE=external_mock \
  -e MYSQL_USER=nova_migrator -e MYSQL_PASSWORD="$password" \
  "$mysql_image" --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci)
cleanup() {
  docker rm -f "$mysql" > /dev/null 2>&1 || true
  docker rmi -f "$image" > /dev/null 2>&1 || true
}
trap cleanup EXIT

root() { docker exec -i -e MYSQL_PWD="$password" "$mysql" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names "$@"; }
for _ in $(seq 90); do
  if docker exec -e MYSQL_PWD="$password" "$mysql" mysql --protocol=TCP -h127.0.0.1 -unova_migrator external_mock -e 'SELECT 1' > /dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[ "${ready:-}" = true ] || { docker logs "$mysql" >&2; exit 1; }

flyway() {
  docker run --rm --platform linux/amd64 --network "container:$mysql" \
    -e FLYWAY_URL='jdbc:mysql://127.0.0.1:3306/external_mock?allowPublicKeyRetrieval=true&sslMode=DISABLED' \
    -e FLYWAY_USER=nova_migrator -e FLYWAY_PASSWORD="$password" "$image" "$@"
}
history() { root -e 'SELECT installed_rank, version, checksum, success FROM external_mock.flyway_schema_history ORDER BY installed_rank'; }

flyway            # 운영과 같은 명령(migrate info)
flyway validate
before=$(history)
flyway            # 다시 돌려도 적용할 것이 없어야 한다
[ "$before" = "$(history)" ] || { echo "다시 돌리자 이력이 바뀌었다" >&2; exit 1; }
# shop 은 be 가 관리한다. 여기서 건드리면 안 된다
[ "$(root -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'shop'")" = 0 ] \
  || { echo "마이그레이션이 shop 을 만들었다" >&2; exit 1; }

echo '빈 DB 적용 · validate · 재실행 이력 불변 · shop 미생성 확인'
