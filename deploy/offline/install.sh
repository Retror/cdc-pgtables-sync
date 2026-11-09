#!/usr/bin/env bash
# =============================================================================
# 在“无外网”的目标机器上运行：安装 pglsync（Python 版）离线包
#
# 用法:
#   PYTHON=<python3 解释器> ./install.sh [安装目录，默认 /opt/pglsync]
#
# 示例:
#   sudo PYTHON=python3 ./install.sh                         # 系统 python3（CentOS 7 为 3.6）
#   PYTHON=/opt/python3/bin/python3 ./install.sh ~/pglsync   # 独立版 Python，无需 root
#
# 说明:
#   * 不会修改系统自带的 python / python2.7；
#   * 依赖安装到 <安装目录>/lib，不写入系统 site-packages；
#   * 目标机器没有 pip 也能安装（直接解压 wheel）。
# =============================================================================
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="${1:-/opt/pglsync}"
PYTHON="${PYTHON:-python3}"

# ---------------------------------------------------------------- 检查 Python
if ! PYTHON_ABS="$(command -v "$PYTHON")"; then
    echo "错误: 找不到 Python 解释器 '$PYTHON'。请先安装 Python 3.6+，或用 PYTHON=/path/to/python3 指定" >&2
    exit 1
fi
if ! "$PYTHON_ABS" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 6) else 1)'; then
    echo "错误: pglsync 需要 Python 3.6 及以上，当前 $PYTHON_ABS 为: $("$PYTHON_ABS" -V 2>&1)" >&2
    echo "      请不要使用 Python 2.7，见 docs/OFFLINE_DEPLOY.md" >&2
    exit 1
fi
echo ">>> 使用解释器: $PYTHON_ABS ($("$PYTHON_ABS" -V 2>&1))"

# ---------------------------------------------------------------- 复制程序
mkdir -p "$DEST/lib" "$DEST/bin" "$DEST/logs"
for item in pglsync tools tests deploy docs config.example.json requirements.txt; do
    rm -rf "${DEST:?}/$item"
    cp -r "$SRC/$item" "$DEST/"
done
# 已有的测试配置不覆盖（可能已按实际环境修改过）
if [ -f "$SRC/config.test.json" ] && [ ! -f "$DEST/config.test.json" ]; then
    cp "$SRC/config.test.json" "$DEST/"
fi

# ---------------------------------------------------------------- 安装依赖
WHEEL="$(ls "$SRC"/wheels/psycopg2_binary-*.whl 2>/dev/null | head -n 1 || true)"
if [ -z "$WHEEL" ]; then
    echo "错误: 离线包中没有 psycopg2_binary 的 wheel 文件（wheels/ 目录为空）" >&2
    exit 1
fi
rm -rf "$DEST"/lib/psycopg2* "$DEST"/lib/psycopg2_binary*
if "$PYTHON_ABS" -m pip --version >/dev/null 2>&1; then
    echo ">>> 使用 pip 安装 $(basename "$WHEEL")"
    "$PYTHON_ABS" -m pip install --no-index --find-links "$SRC/wheels" --target "$DEST/lib" psycopg2-binary
else
    echo ">>> 目标机器没有 pip，直接解压 $(basename "$WHEEL")"
    "$PYTHON_ABS" -m zipfile -e "$WHEEL" "$DEST/lib"
fi

# ---------------------------------------------------------------- 生成启动脚本
cat > "$DEST/bin/pglsync" <<EOF
#!/bin/sh
# pglsync 启动脚本（由 install.sh 生成）
export PYTHONPATH="$DEST/lib:$DEST\${PYTHONPATH:+:\$PYTHONPATH}"
exec "$PYTHON_ABS" -m pglsync "\$@"
EOF
cat > "$DEST/bin/load_writer" <<EOF
#!/bin/sh
# 测试数据写入脚本（由 install.sh 生成）
export PYTHONPATH="$DEST/lib:$DEST\${PYTHONPATH:+:\$PYTHONPATH}"
exec "$PYTHON_ABS" "$DEST/tools/load_writer.py" "\$@"
EOF
chmod +x "$DEST/bin/pglsync" "$DEST/bin/load_writer"

# ---------------------------------------------------------------- 自检
echo ">>> 自检"
if ! PYTHONPATH="$DEST/lib" "$PYTHON_ABS" -c 'import psycopg2; print("psycopg2", psycopg2.__version__)'; then
    echo "错误: psycopg2 无法导入。常见原因：wheel 与目标机器的 Python 版本 / CPU 架构 / glibc 版本不匹配，" >&2
    echo "      请按 docs/OFFLINE_DEPLOY.md 第 8 节排查（ldd --version / uname -m / python3 -V）" >&2
    exit 1
fi
"$DEST/bin/pglsync" --version
(cd "$DEST" && PYTHONPATH="$DEST/lib:$DEST" "$PYTHON_ABS" -m unittest discover -s tests 2>&1 | tail -n 3)

cat <<EOF

安装完成: $DEST
  同步程序:   $DEST/bin/pglsync -c <配置文件> <命令>
  测试写入:   $DEST/bin/load_writer -c <配置文件> [--once --rows 100]
  配置样例:   $DEST/config.example.json
EOF
