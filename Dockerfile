# pglsync（Python 版）镜像
#   构建: docker build -t pglsync:1.0.0 .
#   ARM:  docker buildx build --platform linux/arm64 -t pglsync:1.0.0 --load .
#
# 无法访问 Docker Hub / PyPI 时，可通过构建参数替换为内网仓库或国内镜像：
#   docker build -t pglsync:1.0.0 \
#     --build-arg BASE_IMAGE=harbor.example.com/library/python:3.11-slim \
#     --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple .
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}

ARG PIP_INDEX_URL=
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 依赖单独一层，代码变更时不必重新安装
COPY requirements.txt .
RUN if [ -n "$PIP_INDEX_URL" ]; then \
        pip install --no-cache-dir -i "$PIP_INDEX_URL" -r requirements.txt; \
    else \
        pip install --no-cache-dir -r requirements.txt; \
    fi

COPY pglsync ./pglsync
COPY tools ./tools
COPY tests ./tests

# 构建时跑一遍单元测试（不需要数据库），并创建非 root 运行用户
RUN python -m unittest discover -s tests -q \
    && useradd --system --uid 10001 --no-create-home pglsync \
    && mkdir -p /app/config /app/logs \
    && chown pglsync /app/logs

USER pglsync

# 配置文件挂载到 /app/config/config.json；命令作为参数传入（默认 run）
ENTRYPOINT ["python", "-m", "pglsync", "-c", "/app/config/config.json"]
CMD ["run"]
