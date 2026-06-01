FROM eclipse-temurin:8-jre-alpine

WORKDIR /app

# 非 root 用户运行（安全）
RUN addgroup -S scgroup && adduser -S scuser -G scgroup

# 从本地构建产物复制 JAR（需先执行: mvn clean package -DskipTests）
COPY target/SupplyChain-1.0.0.jar app.jar

# 日志目录
RUN mkdir -p /app/logs && chown -R scuser:scgroup /app

USER scuser

EXPOSE 8091

# JVM 参数（Java 8 容器内自动感知内存有限，手动指定）
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
