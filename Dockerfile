# Build
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 bridge
COPY --from=build /app/target/allure-jira-bridge-0.1.0.jar app.jar
USER bridge
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
