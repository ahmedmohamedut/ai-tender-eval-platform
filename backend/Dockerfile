# Build context is the repository root (the build needs api/openapi.yaml).
#   docker build -f backend/Dockerfile .
FROM gradle:9.9.2-jdk25 AS build
WORKDIR /src
COPY api/openapi.yaml api/openapi.yaml
COPY backend/ backend/
WORKDIR /src/backend
RUN gradle --no-daemon --console=plain bootJar -x test \
 && cp build/libs/*.jar /tmp/app.jar

FROM eclipse-temurin:25-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin app
USER 10001
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
