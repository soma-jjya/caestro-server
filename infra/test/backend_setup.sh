#!/bin/bash
set -euxo pipefail

# Docker + compose 설치
curl -fsSL https://get.docker.com | sh
usermod -aG docker ubuntu

# 2GB(t4g.small) 메모리 여유용 스왑 (JVM+MySQL+Redis OOM 방지)
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /opt/app

# 앱 이미지 빌드용 Dockerfile (업로드한 app.jar 사용)
cat > /opt/app/Dockerfile <<'EOF'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar app.jar
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
EOF

# app + mysql + redis 한 박스에서 기동 (DB/Redis 포트는 외부 미개방)
cat > /opt/app/docker-compose.yml <<'EOF'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: caestro1234
      MYSQL_DATABASE: caestro
    volumes:
      - mysqldata:/var/lib/mysql
    restart: unless-stopped
  redis:
    image: redis:7.0
    restart: unless-stopped
  app:
    build: .
    env_file: .env
    ports:
      - "8080:8080"
    depends_on:
      - mysql
      - redis
    restart: unless-stopped
volumes:
  mysqldata:
EOF

# 환경변수 템플릿 (배포 시 .env 로 복사 후 OAuth/JWT/EIP 채우기)
cat > /opt/app/.env.example <<'EOF'
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:mysql://mysql:3306/caestro?serverTimezone=UTC&characterEncoding=UTF-8
DB_USERNAME=root
DB_PASSWORD=caestro1234
REDIS_URL=redis://redis:6379
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
KAKAO_REDIRECT_URI=http://<BACKEND_EIP>:8080/auth/kakao/callback
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URI=http://<BACKEND_EIP>:8080/auth/google/callback
JWT_ACCESS_SECRET=change_me_access_secret_min_32_chars
JWT_REFRESH_SECRET=change_me_refresh_secret_min_32_chars
JWT_ACCESS_EXPIRATION=3600
JWT_REFRESH_EXPIRATION=2592000
EOF

chown -R ubuntu:ubuntu /opt/app
