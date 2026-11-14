FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY services/backend services/backend
COPY packages/contracts/v1/schema.json packages/contracts/v1/schema.json
WORKDIR /src/services/backend
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/services/backend/build/libs/*.jar /opt/game.jar
COPY packages/generator/fixtures/hoi-an-compact-60.json /opt/hoi-an-compact-60.json
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/game.jar"]
