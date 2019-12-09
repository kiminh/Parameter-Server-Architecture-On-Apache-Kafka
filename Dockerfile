FROM gradle:6.0.1-jdk11 AS build

WORKDIR /app

# Restore maven dependencies in a separate build step
COPY build.gradle .

COPY src src
RUN gradle build


FROM openjdk:11-jre-slim

WORKDIR /app

COPY --from=build /app/build build

CMD [ "java", "-jar", "build/libs/kafka-ps.jar" ]