# ===== 1단계: 빌드 =====
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# (1) 빌드 설정·래퍼 먼저 복사 → "의존성" 캐시 레이어 (거의 안 변함)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# (2) 소스는 나중에 복사 → 코드만 바뀌면 위 의존성 캐시 재사용
COPY src src
RUN ./gradlew clean bootJar --no-daemon

# ===== 2단계: 실행 =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]