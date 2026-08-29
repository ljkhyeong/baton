FROM eclipse-temurin:25-jdk-alpine@sha256:09349d79941fd53bb3d487b393ca118d8853c08c09193f416fe6a8718df9e732 AS build

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

COPY domain/src ./domain/src
COPY application/src ./application/src
COPY adapter-in-web/src ./adapter-in-web/src
COPY adapter-out-persistence/src ./adapter-out-persistence/src
COPY adapter-out-external/src ./adapter-out-external/src
COPY bootstrap/src ./bootstrap/src

RUN ./gradlew --no-daemon :bootstrap:bootJar \
    && find bootstrap/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' \
        -exec cp '{}' /workspace/baton.jar \; \
    && test -s /workspace/baton.jar

FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

RUN addgroup -S -g 10001 baton \
    && adduser -S -D -H -u 10001 -G baton baton \
    && install -d -o baton -g baton -m 0500 /run/baton-config /run/baton-keys

WORKDIR /app
COPY --from=build --chown=baton:baton /workspace/baton.jar ./baton.jar

USER baton
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/baton.jar"]
