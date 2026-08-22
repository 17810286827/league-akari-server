FROM eclipse-temurin:21-jre-alpine

# 工作目录
WORKDIR /app

# 安装 wget 用于容器健康检查
RUN apk add --no-cache wget

# 复制构建产物（Maven 打包生成）
COPY target/league-akari-server-0.1.0.jar app.jar

# 环境变量（推荐通过 docker-compose 注入，运行时由外部 .env 提供）
ENV SPRING_PROFILES_ACTIVE=prod
ENV DB_USERNAME=${DB_USERNAME}
ENV DB_PASSWORD=${DB_PASSWORD}
ENV RIOT_API_KEY=${RIOT_API_KEY}
ENV AI_API_KEY=${AI_API_KEY}

# 容器内运行时 JVM 参数，可被 ENTRYPOINT 的 ${JAVA_OPTS} 展开
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication -Duser.timezone=Asia/Shanghai"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]