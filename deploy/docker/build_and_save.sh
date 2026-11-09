#!/usr/bin/env bash
# =============================================================================
# 在“有外网、有 Docker”的机器上运行：构建镜像并导出为离线包
#
# 用法:
#   deploy/docker/build_and_save.sh [python|java] [平台，默认 linux/amd64]
#
# 示例:
#   deploy/docker/build_and_save.sh python                  # Python 版，x86_64
#   deploy/docker/build_and_save.sh python linux/arm64      # Python 版，ARM 服务器
#   deploy/docker/build_and_save.sh java                    # Java 版
#
# 产物: dist/docker/pglsync-<版本>-<平台>.tar.gz  以及部署所需的 compose / 配置样例
# =============================================================================
set -euo pipefail

FLAVOR="${1:-python}"
PLATFORM="${2:-linux/amd64}"
VERSION="1.0.0"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/dist/docker"
ARCH="${PLATFORM#linux/}"

case "$FLAVOR" in
    python)
        IMAGE="pglsync:$VERSION"
        CONTEXT="$ROOT"
        ;;
    java)
        IMAGE="pglsync-java:$VERSION"
        CONTEXT="$ROOT/pglsync-java"
        ;;
    *)
        echo "用法: $0 [python|java] [linux/amd64|linux/arm64]" >&2
        exit 1
        ;;
esac

mkdir -p "$OUT"
echo ">>> 构建 $IMAGE ($PLATFORM)"
docker buildx build --platform "$PLATFORM" -t "$IMAGE" --load "$CONTEXT"

FILE="$OUT/${IMAGE/:/-}-$ARCH.tar.gz"
echo ">>> 导出 $FILE"
docker save "$IMAGE" | gzip > "$FILE"

# 一并带上部署文件
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
