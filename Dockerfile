# syntax=docker/dockerfile:1

# ---- build: compile, run tests, collect runtime jars ----
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

# ---- runtime ----
FROM eclipse-temurin:21.0.8_9-jre
# Non-root; uid 1000 is the image's existing "ubuntu" user.
RUN mkdir -p /data && chown ubuntu:ubuntu /data
COPY --from=build /src/target/access.jar /app/access.jar
COPY --from=build /src/target/lib /app/lib
USER ubuntu
WORKDIR /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/access.jar"]
CMD ["--help"]
