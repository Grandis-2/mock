#!/usr/bin/env bash
# Mock 서비스를 ECS 에 다시 배포하고(force-new-deployment) 새 태스크가 자리 잡을 때까지 기다린다.
# 태스크 정의는 인프라가 관리한다(<저장소>:latest). 여기서는 latest 를 다시 받게만 한다.
# 인프라가 Mock 을 한 대(singleton)로 두면 옛 태스크가 내려간 뒤 새 태스크가 뜨므로 그사이 요청은 실패한다.
# 그래서 시작만 하고 끝내지 않는다. 새 태스크가 뜨지 못하면 Mock 이 내려간 채 초록이 되기 때문이다.
#
# 클러스터가 아직 없거나(ECS 스택 전) 서비스가 아직 없으면(인프라의 deploy: false) 알림을 남기고 건너뛴다 —
# 이미지는 이미 올라가 있어, 인프라가 서비스를 만들면 그 이미지로 뜬다.
#
# 사용: ECS_CLUSTER=nova-prod ECS_SERVICE=nova-prod-mock [EXPECTED_DIGEST=sha256:...] ecs-deploy.sh
#   EXPECTED_DIGEST 를 주면 자리 잡은 태스크가 그 이미지로 도는지도 본다
set -euo pipefail

cluster=${ECS_CLUSTER:?ECS_CLUSTER 가 없다}
service=${ECS_SERVICE:?ECS_SERVICE 가 없다}
summary=${GITHUB_STEP_SUMMARY:-/dev/null}

# 클러스터가 없으면 describe-services 가 빈 답이 아니라 ClusterNotFoundException 으로 실패한다. 먼저 본다
active=$(aws ecs describe-clusters --clusters "$cluster" \
  --query "clusters[?status=='ACTIVE'] | length(@)" --output text)
if [ "$active" != 1 ]; then
  echo "::notice::$cluster 클러스터가 없어 배포를 건너뜀"
  echo "- $cluster 클러스터가 없어 배포를 건너뜀" >> "$summary"
  exit 0
fi
status=$(aws ecs describe-services --cluster "$cluster" --services "$service" \
  --query 'services[0].status' --output text)
if [ "$status" != ACTIVE ]; then
  echo "::notice::$service 가 ECS 에 없어 배포를 건너뜀 — 인프라에서 서비스를 만들면 올려 둔 이미지로 뜬다"
  echo "- $service: ECS 에 없어 건너뜀" >> "$summary"
  exit 0
fi

aws ecs update-service --cluster "$cluster" --service "$service" \
  --force-new-deployment --query 'service.serviceName' --output text
echo "$service 배포 시작"

# 대기 한 번은 15초 간격 40번(10분)이다. 두 번까지 기다린다
stable=false
wait_err=""
for _ in 1 2; do
  if wait_err=$(aws ecs wait services-stable --cluster "$cluster" --services "$service" 2>&1); then
    stable=true
    break
  fi
done
if [ "$stable" != true ]; then
  aws ecs describe-services --cluster "$cluster" --services "$service" \
    --query 'services[0].events[:5].message' --output text | tr '\t' '\n' || true
  echo "::error::$service 가 자리 잡지 못했다 — 위 이벤트와 서비스 로그를 본다. 원인: ${wait_err//$'\n'/ }"
  echo "- $service: 배포가 자리 잡지 못함" >> "$summary"
  exit 1
fi

# 자리 잡은 태스크가 이 실행이 올린 이미지인지 본다. 다르면 다른 실행이 그사이 latest 를 옮긴 것이다
if [ -n "${EXPECTED_DIGEST:-}" ]; then
  tasks=$(aws ecs list-tasks --cluster "$cluster" --service-name "$service" --desired-status RUNNING \
    --query 'taskArns' --output json)
  [ "$(jq length <<< "$tasks")" -gt 0 ] || { echo "::error::$service 에 도는 태스크가 없다"; exit 1; }
  # shellcheck disable=SC2046  # 태스크 ARN 을 인자 여럿으로 넘긴다
  desc=$(aws ecs describe-tasks --cluster "$cluster" --tasks $(jq -r '.[]' <<< "$tasks") --output json)
  if ! jq -e --arg d "$EXPECTED_DIGEST" '[.tasks[] | any(.containers[]; .imageDigest == $d)] | all' <<< "$desc" > /dev/null; then
    echo "::error::$service 가 이 실행이 올린 이미지($EXPECTED_DIGEST)로 돌지 않는다: $(jq -c '[.tasks[].containers[] | {name, imageDigest}]' <<< "$desc")"
    exit 1
  fi
fi
echo "- $service 배포 완료${EXPECTED_DIGEST:+ (\`$EXPECTED_DIGEST\`)}" >> "$summary"
