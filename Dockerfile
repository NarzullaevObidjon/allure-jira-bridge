# Base images are pinned by digest (multi-arch index); bump tag and digest together.
# Works with the legacy builder too (no BuildKit-only syntax).

# Build
FROM maven:3.9.16-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS build
WORKDIR /app
COPY pom.xml .
# Own layer, rebuilt only when pom.xml changes; the few plugins it misses are fetched by package.
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests && mv target/allure-jira-bridge-*.jar app.jar

# Run
FROM eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8fda3408cfe792db2b666c0e3db8ea4ae263ab4d0c3805933e5355f45
WORKDIR /app
RUN useradd --system --uid 10001 bridge
COPY --from=build /app/app.jar app.jar
USER bridge
EXPOSE 8080
# No curl in the JRE image: ask /actuator/health over bash's /dev/tcp.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
