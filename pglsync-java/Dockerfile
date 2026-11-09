# pglsync（Java 版）镜像：多阶段构建，运行阶段只有 JRE 8
#   构建: docker build -t pglsync-java:1.0.0 .
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:8-jre
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
