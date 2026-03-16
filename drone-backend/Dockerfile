FROM maven:3.9.11-eclipse-temurin-17-alpine AS builder

WORKDIR /build

COPY pom.xml ./

RUN mvn -q -DskipTests dependency:go-offline

COPY src/ src/

RUN mvn -q -DskipTests clean package \
    && find target -maxdepth 1 -type f -name "*.jar" ! -name "original-*.jar" -exec cp {} target/app.jar \;

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=builder /build/target/app.jar app.jar

RUN mkdir -p /app/logs && chown -R spring:spring /app

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS=""

EXPOSE 8081

USER spring

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
