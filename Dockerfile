FROM eclipse-temurin:21-jdk-alpine AS build

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

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S baton && adduser -S -G baton baton

WORKDIR /app
COPY --from=build --chown=baton:baton /workspace/baton.jar ./baton.jar

USER baton
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/baton.jar"]
