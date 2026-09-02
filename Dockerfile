FROM eclipse-temurin:21-jre-alpine

# 工作目录
WORKDIR /app

# 安装 wget（健康检查）与 freetype（Java2D 战报图渲染需要 native 光栅化；字体随 jar 内置思源黑体）
RUN apk add --no-cache wget freetype

# 复制构建产物（Maven 打包生成）
COPY target/league-akari-server-0.1.0.jar app.jar

# 环境变量（推荐通过 docker-compose 注入，运行时由外部 .env 提供）
ENV SPRING_PROFILES_ACTIVE=prod
ENV DB_USERNAME=${DB_USERNAME}
ENV DB_PASSWORD=${DB_PASSWORD}
ENV RIOT_API_KEY=${RIOT_API_KEY}
ENV AI_API_KEY=${AI_API_KEY}
ENV PUSH_ENABLED=${PUSH_ENABLED}
ENV PUSH_GROUP_OPEN_ID=${PUSH_GROUP_OPEN_ID}
ENV QQ_BOT_APP_ID=${QQ_BOT_APP_ID}
ENV QQ_BOT_CLIENT_SECRET=${QQ_BOT_CLIENT_SECRET}
ENV PUSH_WS_ENABLED=${PUSH_WS_ENABLED}

# 容器内运行时 JVM 参数，可被 ENTRYPOINT 的 ${JAVA_OPTS} 展开
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication -Duser.timezone=Asia/Shanghai"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]