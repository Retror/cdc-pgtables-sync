#!/usr/bin/env bash
# =============================================================================
# 在“有外网、有 Docker”的机器上运行：构建镜像并导出为离线包
#
# 用法:
#   deploy/docker/build_and_save.sh [python|java] [平台，默认与本机相同]
#
# 示例:
#   deploy/docker/build_and_save.sh python                  # Python 版，本机架构
#   deploy/docker/build_and_save.sh python linux/arm64      # Python 版，ARM 服务器（跨架构）
#   deploy/docker/build_and_save.sh java                    # Java 版
#
# 无法访问 Docker Hub / PyPI / Maven 中央仓库时，用环境变量替换为内网仓库或国内镜像：
#   BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim \
#   PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
#   deploy/docker/build_and_save.sh python
#
#   BUILD_IMAGE=<maven:3.9-eclipse-temurin-8 的镜像地址> RUNTIME_IMAGE=<eclipse-temurin:8-jre 的镜像地址> \
#   MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public \
#   deploy/docker/build_and_save.sh java
#
# 产物: dist/docker/<镜像名>-<版本>-<架构>.tar.gz  以及部署所需的 compose / 配置样例
# =============================================================================
set -euo pipefail

FLAVOR="${1:-python}"
NATIVE="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
PLATFORM="${2:-$NATIVE}"
VERSION="1.0.0"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/dist/docker"
ARCH="${PLATFORM#linux/}"

BUILD_ARGS=()
add_arg() {   # 仅在环境变量非空时传入 --build-arg
    local name="$1"
    if [ -n "${!name:-}" ]; then
        BUILD_ARGS+=(--build-arg "$name=${!name}")
    fi
}

case "$FLAVOR" in
    python)
        IMAGE="pglsync:$VERSION"
        CONTEXT="$ROOT"
        BASES=("${BASE_IMAGE:-python:3.11-slim}")
        add_arg BASE_IMAGE
        add_arg PIP_INDEX_URL
        ;;
    java)
        IMAGE="pglsync-java:$VERSION"
        CONTEXT="$ROOT/pglsync-java"
        BASES=("${BUILD_IMAGE:-maven:3.9-eclipse-temurin-8}" "${RUNTIME_IMAGE:-eclipse-temurin:8-jre}")
        add_arg BUILD_IMAGE
        add_arg RUNTIME_IMAGE
        add_arg MAVEN_MIRROR_URL
        ;;
    *)
        echo "用法: $0 [python|java] [linux/amd64|linux/arm64]" >&2
        exit 1
        ;;
esac

# 1. 预先拉取基础镜像：失败时给出明确提示（最常见的是无法访问 Docker Hub）
for base in "${BASES[@]}"; do
    echo ">>> 拉取基础镜像 $base ($PLATFORM)"
    if ! docker pull --platform "$PLATFORM" "$base"; then
        cat >&2 <<EOF

错误: 无法拉取基础镜像 $base
  常见原因与处理（详见 docs/DOCKER_DEPLOY.md 第 3.3 节）：
  1. 本机无法访问 Docker Hub：在 /etc/docker/daemon.json 配置 registry-mirrors 后重启 docker，
     或用 BASE_IMAGE / BUILD_IMAGE / RUNTIME_IMAGE 指定内网仓库（Harbor）或国内镜像站地址；
  2. 镜像站没有该架构（$PLATFORM）的镜像：换一个镜像站，或在目标架构的机器上构建。
EOF
        exit 1
    fi
done

# 2. 构建：与本机架构相同时用普通 docker build（直接使用刚拉取到本地的基础镜像，
#    也会使用 daemon.json 中的镜像加速配置）；跨架构时才用 buildx
mkdir -p "$OUT"
echo ">>> 构建 $IMAGE ($PLATFORM，本机 $NATIVE)"
if [ "$PLATFORM" = "$NATIVE" ]; then
    docker build ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"} -t "$IMAGE" "$CONTEXT"
else
    docker buildx build --platform "$PLATFORM" ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"} -t "$IMAGE" --load "$CONTEXT"
fi

# 3. 导出
FILE="$OUT/${IMAGE/:/-}-$ARCH.tar.gz"
echo ">>> 导出 $FILE"
docker save "$IMAGE" | gzip > "$FILE"

# 4. 一并带上部署文件
if [ "$FLAVOR" = "python" ]; then
    cp "$ROOT/deploy/docker/docker-compose.yml" "$ROOT/deploy/docker/pglsync.env.example" "$OUT/"
    cp "$ROOT/config.example.json" "$OUT/"
    [ -f "$ROOT/config.test.json" ] && cp "$ROOT/config.test.json" "$OUT/"
else
    cp "$ROOT/pglsync-java/docker-compose.yml" "$OUT/docker-compose-java.yml"
    cp "$ROOT/pglsync-java/src/main/resources/application.yml" "$OUT/application.yml"
    cp "$ROOT/deploy/docker/pglsync.env.example" "$OUT/"
fi

ls -lh "$OUT"
cat <<EOF

把 dist/docker/ 整个目录拷贝到目标机器，然后：
  docker load -i $(basename "$FILE")
  详见 docs/DOCKER_DEPLOY.md
EOF
