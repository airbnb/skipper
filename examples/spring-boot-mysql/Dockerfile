# Runtime image only. Build the jar on the host first, so it uses the same Gradle cache, toolchain and
# Skipper version override as everything else:
#
#   ./gradlew bootJar && docker compose up --build
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
