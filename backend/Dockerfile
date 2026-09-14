# MediCore backend review image (plan Task 6). Multi-stage: dependencies and
# compilation happen in a pinned Java 21 + Maven build stage; the runtime
# stage is a pinned-major JRE running as a non-root user. No secret, runtime
# env file, or credential-shaped file is copied into either stage; all
# runtime configuration arrives exclusively through environment variables.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin medicore
WORKDIR /app
COPY --from=build --chown=medicore:medicore /src/target/medicore-hospital-*.jar /app/app.jar
USER medicore
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
