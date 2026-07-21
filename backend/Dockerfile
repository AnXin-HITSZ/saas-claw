# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG SERVICE_NAME
WORKDIR /workspace

# Copy all POMs first so Maven can resolve the full multi-module reactor
COPY pom.xml .
COPY gateway/pom.xml gateway/
COPY backend-for-frontend/pom.xml backend-for-frontend/
COPY claw-service/pom.xml claw-service/
COPY runtime-service/pom.xml runtime-service/
COPY agent-marketplace-service/pom.xml agent-marketplace-service/
COPY billing-service/pom.xml billing-service/
COPY skill-marketplace-service/pom.xml skill-marketplace-service/

RUN mvn -q dependency:go-offline -pl ${SERVICE_NAME}

# Copy sources and build the target module
COPY . .
RUN mvn -q -DskipTests package spring-boot:repackage -pl ${SERVICE_NAME}

# Runtime stage
FROM eclipse-temurin:17-jre
ARG SERVICE_NAME
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/${SERVICE_NAME}/target/${SERVICE_NAME}-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
