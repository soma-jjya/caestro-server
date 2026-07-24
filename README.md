# 📸 Caestro (카에스트로) Server

> **이제, 한 번에 인생샷.**  
> 찍히는 사람이 카메라를 조종하는 듀얼 디바이스 촬영 앱 "Caestro"의 백엔드 및 시그널링 서버 저장소입니다.

## 📖 프로젝트 소개
Caestro는 두 대의 스마트폰을 실시간으로 연결해, 사진에 찍히는 사람이 직접 카메라 시점을 공유받고 조종하는 새로운 형태의 협업 촬영 서비스입니다.
이 저장소는 Caestro 서비스의 **비즈니스 로직 처리, 사용자 인증(OAuth2), 그리고 WebRTC 통신을 위한 시그널링(Signaling) 역할**을 담당하는 Spring Boot 기반의 서버 코드를 포함하고 있습니다.

## ✨ 주요 기능
- **소셜 로그인 및 인증**: 카카오(Kakao), 구글(Google) OAuth2 로그인 및 JWT 기반 토큰(Access/Refresh) 인증 처리
- **사용자 관리**: 프로필 조회 및 세션(로그아웃 등) 관리 (Redis 활용)
- **시그널링 서버 (개발 예정)**: 두 기기 간의 P2P(WebRTC) 연결을 위한 세션 생성 및 Offer/Answer, ICE Candidate 교환
- **사진 및 메타데이터 관리 (개발 예정)**: 촬영된 듀얼샷 이미지의 클라우드 보관 및 DB 관리

## 🛠 기술 스택
- **Language**: Java 21
- **Framework**: Spring Boot
- **Database**: MySQL, Redis (Spring Data Redis)
- **Security**: Spring Security, JWT
- **API Docs**: Springdoc OpenAPI (Swagger UI)
- **Build Tool**: Gradle

## 🚀 로컬 실행 방법

### 1. 환경 변수 설정
로컬에서 실행하기 위해서는 보안상 GitHub에서 제외된 `application-dev.yml` 파일을 `src/main/resources/` 경로에 직접 생성해야 합니다.

<br>**J-J-YA 노션의 환경변수파일에서 application-dev.yml 확인**




### 2. 빌드 및 실행
```bash
# 도커 실행
docker compose up -d

# 권한 부여 (필요 시)
chmod +x gradlew

# 서버 실행
./gradlew bootRun
```

## 📚 API 문서 (Swagger)
서버가 실행된 후, 웹 브라우저에서 아래 주소로 접속하여 API 명세를 확인하고 테스트할 수 있습니다.
- **Swagger UI**: `http://localhost:8080/swagger-ui/index.html`

---
*본 프로젝트는 2026년도 AI·SW마에스트로 제17기 프로젝트의 일환으로 진행됩니다.*
