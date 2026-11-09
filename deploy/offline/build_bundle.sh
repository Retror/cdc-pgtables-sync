#!/usr/bin/env bash
# =============================================================================
# 在“有外网”的机器上运行：制作 pglsync（Python 版）离线安装包
#
# 用法:
#   deploy/offline/build_bundle.sh <目标机器的 Python 3 版本> [平台]
#
# 示例:
#   deploy/offline/build_bundle.sh 3.6                          # CentOS 7 自带的 python3（3.6）
#   deploy/offline/build_bundle.sh 3.11                         # 独立版 Python 3.11
#   deploy/offline/build_bundle.sh 3.6 manylinux2014_aarch64    # ARM 服务器（鲲鹏/飞腾）
#
# 产物: dist/pglsync-offline-py<版本>-<平台>.tar.gz
# 制作机器只需要能联网、装有 pip（任意操作系统均可，pip 会按 --platform 下载目标平台的包）。
# =============================================================================
set -euo pipefail

PYVER="${1:?请指定目标机器的 Python 3 版本，例如 3.6 / 3.8 / 3.11}"
PLATFORM="${2:-manylinux2014_x86_64}"
PIP="${PIP:-pip3}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIST="$ROOT/dist"
OUT="$DIST/pglsync-offline"
NAME="pglsync-offline-py${PYVER}-${PLATFORM}.tar.gz"

echo ">>> 目标: Python ${PYVER} / ${PLATFORM}"
rm -rf "$OUT"
mkdir -p "$OUT/wheels"

# 1. 下载与目标机器匹配的 psycopg2-binary 预编译包（内置 libpq，目标机器无需安装 PostgreSQL 客户端）
"$PIP" download "psycopg2-binary>=2.8.6,<2.10" \
    --only-binary=:all: \
    --platform "$PLATFORM" \
    --python-version "$PYVER" \
    --implementation cp \
    --dest "$OUT/wheels"

# 2. 复制程序文件
cp -r "$ROOT/pglsync" "$ROOT/tools" "$ROOT/tests" "$OUT/"
cp "$ROOT/config.example.json" "$ROOT/requirements.txt" "$OUT/"
[ -f "$ROOT/config.test.json" ] && cp "$ROOT/config.test.json" "$OUT/"
mkdir -p "$OUT/deploy" "$OUT/docs"
cp "$ROOT/deploy/pglsync.service" "$OUT/deploy/"
cp "$ROOT"/docs/*.md "$OUT/docs/"
cp "$ROOT/deploy/offline/install.sh" "$OUT/"
chmod +x "$OUT/install.sh"
find "$OUT" -name '__pycache__' -type d -prune -exec rm -rf {} +

# 3. 打包
tar -C "$DIST" -czf "$DIST/$NAME" pglsync-offline
echo
echo ">>> 已生成: $DIST/$NAME"
ls -l "$OUT/wheels"
echo
echo "把该文件拷贝到目标机器后执行:"
echo "  tar xzf $NAME && cd pglsync-offline && sudo PYTHON=python3 ./install.sh /opt/pglsync"
