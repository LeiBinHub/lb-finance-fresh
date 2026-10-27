# 默认真名镜像；国内拉不动时可：
#   docker compose build --build-arg JAVA_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre
ARG JAVA_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_IMAGE}
WORKDIR /app
ENV TZ=Asia/Shanghai
# 由本机 mvn package 产出，避免在容器里再拉 Maven 镜像
COPY target/finance-fresh.jar app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
