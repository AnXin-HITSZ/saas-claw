# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG SERVICE_NAME
WORKDIR /workspace
COPY pom.xml .
COPY ${SERVICE_NAME}/pom.xml ${SERVICE_NAME}/
RUN mvn -q dependency:go-offline -pl ${SERVICE_NAME}
COPY . .
RUN mvn -q -DskipTests package -pl ${SERVICE_NAME}

# Runtime stage
FROM eclipse-temurin:17-jre
ARG SERVICE_NAME
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/${SERVICE_NAME}/target/${SERVICE_NAME}-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
