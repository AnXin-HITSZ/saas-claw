# saas-claw gateway（Spring Cloud Gateway, reactive）生产镜像
# 多阶段：Maven 构建 fat jar -> temurin 21-jre 运行；端口 8888。
# 默认 profile（application.yaml），全部配置由 K8s env 注入；application-local.yml 不打进镜像。
# 必须运行在 K3s 集群内：/v1 路由解析 claw-{id}.claw-{id}.svc.cluster.local。

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests \
    && cp target/gateway-0.0.1-SNAPSHOT.jar /build/app.jar

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --create-home --uid 10001 appuser
USER appuser

COPY --from=build /build/app.jar ./app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

EXPOSE 8888

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
