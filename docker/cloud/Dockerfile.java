# 运行镜像只装 JRE，不含构建工具；四个服务共用同一镜像，由 APP_JAR 选择入口
FROM eclipse-temurin:8-jre

WORKDIR /app

COPY build/jars/ /app/

ENV JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8"

# 不做 curl 健康检查，容器内只保证进程存活与端口可达
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/$APP_JAR"]
