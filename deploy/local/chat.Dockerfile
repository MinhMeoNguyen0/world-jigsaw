FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/services/chat
COPY services/chat ./
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/services/chat/build/install/world-jigsaw-chat /opt/chat
ENTRYPOINT ["/opt/chat/bin/world-jigsaw-chat"]
