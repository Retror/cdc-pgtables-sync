# pglsync（Python 版）镜像
#   构建: docker build -t pglsync:1.0.0 .
#   ARM:  docker buildx build --platform linux/arm64 -t pglsync:1.0.0 --load .
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 依赖单独一层，代码变更时不必重新安装
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

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
