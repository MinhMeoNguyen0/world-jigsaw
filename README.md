# World Jigsaw

## Backend local setup

The backend is a Java 21 Spring Boot service in `services/backend`, built with the Gradle wrapper. You need a JDK (any version Gradle can run on; the build downloads Java 21 through a toolchain if it is missing) and Docker.

1. Start PostgreSQL from the repository root:

   ```sh
   docker compose up -d postgres
   ```

2. Run the backend (it applies Flyway migrations on startup):

   ```sh
   cd services/backend
   ./gradlew bootRun
   ```

   Check it is up: `curl http://localhost:8080/actuator/health` reports `"status":"UP"`.

3. Run the build: formatting check and tests. The tests start their own PostgreSQL container with Testcontainers, so Docker must be running, but the Compose database is not needed:

   ```sh
   cd services/backend
   ./gradlew build
   ```

   Fix formatting with `./gradlew spotlessApply`.
