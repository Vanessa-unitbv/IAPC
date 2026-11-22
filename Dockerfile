# syntax=docker/dockerfile:1
# One Dockerfile for every service. docker-compose passes the module folder name as MODULE.
#   docker build --build-arg MODULE=ingest-static -t rag/ingest-static .

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /build
COPY . .
# -am also builds the modules it depends on (rag-common). The cache mount keeps ~/.m2 between builds.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$MODULE" \
 && mvn -B -q -pl "$MODULE" -am -DskipTests package \
 && cp "$MODULE"/target/"$MODULE"-*.jar /build/app.jar

# ---------- run ----------
FROM eclipse-temurin:21-jre
# wget is used by the docker-compose healthchecks.
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
