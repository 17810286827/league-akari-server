FROM openjdk:21-jdk-slim

# 工作目录
WORKDIR /app

# 安装系统依赖（MySQL Connector-J 需要 libmysqlclient-dev / libmariadb-dev）
RUN apt-get update && apt-get install -y \
    libmariadb-java-dev \
    wget \
    && rm -rf /var/lib/apt/lists/*

# 复制 Maven 仓库（若有缓存）
COPY --from=maven:3.9.6-eclipse-temurin-21 /usr/share/maven /usr/share/maven
COPY --from=maven:3.9.6-eclipse-temurin-21 /usr/share/maven /usr/local/maven

# 复制构建产物（本地 Maven 构建后）
COPY target/league-akari-server-0.1.0.jar app.jar

# 环境变量（推荐通过 docker-compose 注入）
ENV SPRING_PROFILES_ACTIVE=prod
ENV DB_USERNAME=${DB_USERNAME}
ENV DB_PASSWORD=${DB_PASSWORD}
ENV RIOT_API_KEY=${RIOT_API_KEY}
ENV AI_API_KEY=${AI_API_KEY}

# 容器内运行时设置 JVM 参数（生产建议：-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms512m -Xmx2g）
# 基础配置（可通过 -e 或 docker run -e 覆盖）
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication -Duser.timezone=Asia/Shanghai"

EXPOSE 8081

# 启动命令
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]