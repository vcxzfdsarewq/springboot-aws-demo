# syntax=docker/dockerfile:1

# ============================================================
# Stage 1: build (Maven + JDK 21)
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 依存だけ先に解決してレイヤキャッシュを効かせる
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# ソースをコピーしてビルド (テストはCIで実行するためイメージビルドでは skip)
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# ============================================================
# Stage 2: runtime (JRE only, non-root)
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# ヘルスチェック用に wget(busybox) は alpine に同梱。非rootユーザーを作成
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R app:app /app
USER app

ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
