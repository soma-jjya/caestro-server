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
# 디자이너 스튜디오 키(catalog.studio-key): 배포 워크플로가 GitHub Secret 에서 build-arg 로 넘긴다.
# Spring 이 환경변수 CATALOG_STUDIO_KEY 를 catalog.studio-key 로 읽는다(relaxed binding). 비면 스튜디오 쓰기 전체가 403.
# 운영 시크릿을 Secrets Manager 로 옮기면 이 두 줄과 워크플로의 build-args 를 지우면 된다.
ARG CATALOG_STUDIO_KEY=""
ENV CATALOG_STUDIO_KEY=${CATALOG_STUDIO_KEY}
# Google Play 구독 확인(contracts/subscription-google-play.md): 서비스 계정 키(base64)와 실시간 알림 URL 토큰.
# google.play.service-account-json / google.play.rtdn-token 으로 읽힌다. 비면 확인 503, 알림 403 (서버는 정상 기동)
ARG GOOGLE_PLAY_SERVICE_ACCOUNT_JSON=""
ENV GOOGLE_PLAY_SERVICE_ACCOUNT_JSON=${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON}
ARG GOOGLE_PLAY_RTDN_TOKEN=""
ENV GOOGLE_PLAY_RTDN_TOKEN=${GOOGLE_PLAY_RTDN_TOKEN}
# App Store 구독 확인(contracts/subscription-app-store.md): 인앱 결제 키(Issuer ID · Key ID · .p8 한 줄 base64)와 서버 알림 URL 토큰.
# apple.app-store.issuer-id / key-id / private-key / notification-token 으로 읽힌다. 비면 확인 503, 알림 403 (서버는 정상 기동)
ARG APPLE_APP_STORE_ISSUER_ID=""
ENV APPLE_APP_STORE_ISSUER_ID=${APPLE_APP_STORE_ISSUER_ID}
ARG APPLE_APP_STORE_KEY_ID=""
ENV APPLE_APP_STORE_KEY_ID=${APPLE_APP_STORE_KEY_ID}
ARG APPLE_APP_STORE_PRIVATE_KEY=""
ENV APPLE_APP_STORE_PRIVATE_KEY=${APPLE_APP_STORE_PRIVATE_KEY}
ARG APPLE_APP_STORE_NOTIFICATION_TOKEN=""
ENV APPLE_APP_STORE_NOTIFICATION_TOKEN=${APPLE_APP_STORE_NOTIFICATION_TOKEN}
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]