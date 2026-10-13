# Build multi-stage único para os dois serviços: SERVICE=launch-service | consolidation-service
FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY services/launch-service/pom.xml services/launch-service/pom.xml
COPY services/consolidation-service/pom.xml services/consolidation-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl services/${SERVICE} -am dependency:go-offline
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl services/${SERVICE} -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG SERVICE
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/services/${SERVICE}/target/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
