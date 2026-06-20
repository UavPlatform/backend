# ============================================================
# Stage 1: Build
# ============================================================
FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 代理（本地构建传入，CI 不传）
ARG PROXY=""

# ① 复制源码并直接编译打包
COPY pom.xml ./
COPY src/ src/
RUN set -eux; \
    if [ -n "$PROXY" ]; then \
      export http_proxy="$PROXY" https_proxy="$PROXY"; \
      proxy_addr="${PROXY#*://}"; \
      proxy_host="${proxy_addr%%:*}"; \
      proxy_port="${proxy_addr#*:}"; \
      proxy_port="${proxy_port%%/*}"; \
      mkdir -p /root/.m2; \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' \
        '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' \
        '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
        '  <proxies>' \
        '    <proxy><id>build-http</id><active>true</active><protocol>http</protocol><host>'"$proxy_host"'</host><port>'"$proxy_port"'</port></proxy>' \
        '    <proxy><id>build-https</id><active>true</active><protocol>https</protocol><host>'"$proxy_host"'</host><port>'"$proxy_port"'</port></proxy>' \
        '  </proxies>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi; \
    mvn -B -ntp -DskipTests clean package \
    && find target -maxdepth 1 -type f -name "*.jar" ! -name "original-*.jar" \
       -exec cp {} target/app.jar \;

# ② 提取 Spring Boot 分层
RUN java -Djarmode=layertools -jar target/app.jar extract --destination target/extracted

# ③ jlink 生成精简 JRE
RUN jlink \
         --module-path /opt/java/openjdk/jmods \
         --add-modules java.base,java.compiler,java.desktop,java.logging,\
java.management,java.naming,java.rmi,java.sql,java.transaction.xa,\
java.xml,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.unsupported \
         --output /jre \
         --no-header-files \
         --no-man-pages \
         --strip-debug \
         --compress=zip-9

# ============================================================
# Stage 2: Runtime（~40-60MB）
# ============================================================
FROM alpine:3.21

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring \
    && mkdir -p /app/logs && chown spring:spring /app/logs

COPY --from=builder /jre /app/jre

COPY --from=builder /build/target/extracted/dependencies/        ./
COPY --from=builder /build/target/extracted/spring-boot-loader/   ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/           ./

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS=""

EXPOSE 8081

USER spring

ENTRYPOINT ["/app/jre/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]
