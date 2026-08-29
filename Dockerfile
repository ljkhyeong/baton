FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY domain/build.gradle ./domain/build.gradle
COPY application/build.gradle ./application/build.gradle
COPY adapter-in-web/build.gradle ./adapter-in-web/build.gradle
COPY adapter-out-persistence/build.gradle ./adapter-out-persistence/build.gradle
COPY adapter-out-external/build.gradle ./adapter-out-external/build.gradle
COPY bootstrap/build.gradle ./bootstrap/build.gradle

COPY domain/src/main ./domain/src/main
COPY application/src/main ./application/src/main
COPY adapter-in-web/src/main ./adapter-in-web/src/main
COPY adapter-out-persistence/src/main ./adapter-out-persistence/src/main
COPY adapter-out-external/src/main ./adapter-out-external/src/main
COPY bootstrap/src/main ./bootstrap/src/main

RUN ./gradlew --no-daemon :bootstrap:bootJar \
    && find bootstrap/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' \
        -exec cp '{}' /workspace/baton.jar \; \
    && test -s /workspace/baton.jar

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699

RUN addgroup -S -g 10001 baton \
    && adduser -S -D -H -u 10001 -G baton baton \
    && install -d -o baton -g baton -m 0500 /run/baton-config /run/baton-keys

WORKDIR /app
COPY --from=build --chown=baton:baton /workspace/baton.jar ./baton.jar

USER baton
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/baton.jar"]
