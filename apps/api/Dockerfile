# Build context: repository root (migrations live in db/migrations).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY apps/api/pom.xml apps/api/pom.xml
COPY db db
RUN mvn -f apps/api/pom.xml -q -B dependency:go-offline
COPY apps/api/src apps/api/src
RUN mvn -f apps/api/pom.xml -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home glassbox     && mkdir -p /data/files && chown 10001 /data/files
COPY --from=build /src/apps/api/target/glassbox-api-*.jar /app/app.jar
USER 10001
EXPOSE 8080 8081
# SPRING_PROFILES_ACTIVE=api or worker selects the run mode.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
