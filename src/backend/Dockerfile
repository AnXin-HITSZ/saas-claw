# saas-claw backend（Spring Boot）生产镜像
# 多阶段：Maven 构建 fat jar -> temurin 17-jre 运行；端口 8080，context-path /api。
# 生产必须以非 local profile 启动（SPRING_PROFILES_ACTIVE=prod），避免加载 application-local.*。

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 pom 预热依赖缓存（离线依赖下载）
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

# 再拷源码构建
COPY src ./src
RUN mvn -q -B clean package -DskipTests \
    && cp target/backend-0.0.1-SNAPSHOT.jar /build/app.jar

# ---- runtime stage ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 非 root
RUN useradd --create-home --uid 10001 appuser
USER appuser

COPY --from=build /build/app.jar ./app.jar

# 生产默认走 prod profile；可被 K8s env 覆盖
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
