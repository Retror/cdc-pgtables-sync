# pglsync（Java 版）镜像：多阶段构建，运行阶段只有 JRE 8
#   构建: docker build -t pglsync-java:1.0.0 .
#
# 无法访问 Docker Hub / Maven 中央仓库时：
#   docker build -t pglsync-java:1.0.0 \
#     --build-arg BUILD_IMAGE=<内网仓库>/maven:3.9-eclipse-temurin-8 \
#     --build-arg RUNTIME_IMAGE=<内网仓库>/eclipse-temurin:8-jre \
#     --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public .
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-8
ARG RUNTIME_IMAGE=eclipse-temurin:8-jre

FROM ${BUILD_IMAGE} AS build
ARG MAVEN_MIRROR_URL=
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
        mkdir -p /root/.m2 && printf '%s\n' \
        '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf>' \
        "<url>${MAVEN_MIRROR_URL}</url></mirror></mirrors></settings>" > /root/.m2/settings.xml; \
    fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM ${RUNTIME_IMAGE}
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /src/target/pglsync.jar /app/pglsync.jar
RUN useradd --system --uid 10001 --no-create-home pglsync \
    && mkdir -p /app/config /app/logs \
    && chown pglsync /app/logs
USER pglsync

# 外部配置挂载到 /app/config/application.yml（覆盖 jar 内置默认值）；命令作为参数传入（默认 run）
ENTRYPOINT ["java", "-Xms256m", "-Xmx1g", "-jar", "/app/pglsync.jar", \
            "--spring.config.additional-location=optional:file:/app/config/application.yml", \
            "--logging.file.name=/app/logs/pglsync.log"]
CMD ["run"]
