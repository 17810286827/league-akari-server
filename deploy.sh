#!/usr/bin/env bash
set -Eeuo pipefail

# 固定部署目录：仓库可被同步覆盖，生产配置与版本状态始终在目录外。
DEPLOY_ROOT="/opt/league-akari"
REPO_DIR="${DEPLOY_ROOT}/repo"
CONFIG_DIR="${DEPLOY_ROOT}/config"
ENV_FILE="${CONFIG_DIR}/.env"
COMPOSE_FILE="${REPO_DIR}/docker-compose.yml"
CURRENT_FILE="${CONFIG_DIR}/current-image"
PREVIOUS_FILE="${CONFIG_DIR}/previous-image"
FAILED_PREFIX="failed-$(date +%Y%m%d%H%M%S)-"
HEALTH_URL="http://127.0.0.1:8081/actuator/health"
HEALTH_TIMEOUT_SECONDS=120
HEALTH_INTERVAL_SECONDS=10

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  log "ERROR: $*"
  exit 1
}

[[ -f "${ENV_FILE}" ]] || fail "缺少生产配置：${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || fail "缺少 Compose 文件：${COMPOSE_FILE}"

# 当前镜像由 workflow 传入，避免部署脚本自行猜测版本。
: "${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"
export IMAGE_REPOSITORY IMAGE_TAG

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

previous_image=''
if [[ -s "${CURRENT_FILE}" ]]; then
  previous_image="$(tr -d '\r\n' < "${CURRENT_FILE}")"
fi

log "拉取镜像 ${IMAGE_REPOSITORY}:${IMAGE_TAG}"
compose pull league-akari-server

# 保存当前版本，首次部署没有回滚目标时保留空值。
if [[ -n "${previous_image}" ]]; then
  printf '%s\n' "${previous_image}" > "${PREVIOUS_FILE}"
  log "记录可回滚版本：${previous_image}"
else
  rm -f "${PREVIOUS_FILE}"
  log "首次部署，没有可回滚版本"
fi

printf '%s:%s\n' "${IMAGE_REPOSITORY}" "${IMAGE_TAG}" > "${CURRENT_FILE}.pending"
compose up -d --no-build --force-recreate league-akari-server

is_healthy() {
  curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null 2>&1
}

cleanup_images() {
  # 仅清理未被容器使用的旧 SHA 镜像，保留当前、上一个和最近三个失败标签。
  local current previous latest failed_image_count
  current="$(cat "${CURRENT_FILE}")"
  previous=''
  latest="${IMAGE_REPOSITORY}:latest"
  [[ -f "${PREVIOUS_FILE}" ]] && previous="$(cat "${PREVIOUS_FILE}")"
  failed_image_count=0
  while IFS= read -r image; do
    [[ "${image}" == "${current}" || "${image}" == "${previous}" || "${image}" == "${latest}" ]] && continue
    if [[ "${image}" == *":failed-"* ]]; then
      failed_image_count=$((failed_image_count + 1))
      if (( failed_image_count <= 3 )); then
        continue
      fi
    fi
    docker image rm "${image}" || true
  done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "${IMAGE_REPOSITORY}")
}

log "等待健康检查，最多 ${HEALTH_TIMEOUT_SECONDS} 秒"
for ((elapsed=0; elapsed<HEALTH_TIMEOUT_SECONDS; elapsed+=HEALTH_INTERVAL_SECONDS)); do
  if is_healthy; then
    mv "${CURRENT_FILE}.pending" "${CURRENT_FILE}"
    log "部署成功：${IMAGE_REPOSITORY}:${IMAGE_TAG}"
    cleanup_images
    exit 0
  fi
  sleep "${HEALTH_INTERVAL_SECONDS}"
done

log "健康检查失败，输出应用状态和日志"
compose ps
compose logs --tail=200 league-akari-server || true

failed_image="${IMAGE_REPOSITORY}:${FAILED_PREFIX}${IMAGE_TAG}"
docker image tag "${IMAGE_REPOSITORY}:${IMAGE_TAG}" "${failed_image}" || true
rm -f "${CURRENT_FILE}.pending"

if [[ -n "${previous_image}" ]]; then
  log "回滚到 ${previous_image}"
  export IMAGE_REPOSITORY="${previous_image%:*}"
  export IMAGE_TAG="${previous_image##*:}"
  compose up -d --no-build --force-recreate league-akari-server
  if is_healthy; then
    printf '%s\n' "${previous_image}" > "${CURRENT_FILE}"
    log "回滚成功，数据库迁移不会自动回滚"
  else
    log "ERROR: 回滚后健康检查仍失败，请人工处理"
  fi
else
  log "首次部署失败，不执行回滚；失败镜像已标记为 ${failed_image}"
fi

exit 1
