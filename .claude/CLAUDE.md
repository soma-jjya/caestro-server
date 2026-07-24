# Caestro Server

## 프로젝트 개요
Caestro는 두 대의 스마트폰을 실시간으로 연결해 찍히는 사람이 카메라를 지휘하는
듀얼 디바이스 협업 촬영 앱의 백엔드 서버다.
찍히는 사람(디렉터)이 QR로 세션을 생성하고, 촬영자가 참여해 WebRTC P2P로 연결된다.

핵심 기능:
- 카카오/구글/애플 OAuth + JWT 인증
- WebRTC 시그널링 서버 (WebSocket)
- 세션 수립 및 기기 스펙 중계
- 촬영 결과물 클라우드 백업 (프리미엄)
- 푸시 알림 (FCM / APNs)

## 기술 스택
- Java 21 + Spring Boot
- Spring Security + JWT (jjwt)
- Spring Data JPA + MySQL
- Spring Data Redis
- Spring WebSocket (순수 WebSocket, STOMP 미사용)
- WebFlux (WebClient - 카카오/구글 API 호출)
- Springdoc OpenAPI (Swagger)
- Lombok
- Docker + GitHub Actions (CI/CD)
- AWS EC2 / S3 / ElastiCache

## 패키지 구조 규칙
- domain/ : 비즈니스 도메인별로 분리한다
    - 각 도메인은 controller / service / repository / dto 구조를 따른다
    - dto는 request / response 로 하위 분리한다
    - controller 하위에 api/ 패키지를 두고 Swagger 명세 인터페이스를 작성한다
- global/ : 도메인에 종속되지 않는 공통 설정, 예외 처리, 보안 로직
- 새 도메인 추가 시 domain/ 하위에 동일한 구조로 생성한다

## 코드 컨벤션

### DTO
- Request DTO : 이름은 {동사}{명사}Request.java
- Response DTO : 이름은 {명사}Response.java
- 모든 DTO는 record로 생성한다 (Lombok 클래스 대신 record 사용)
- 유효성 검증: @NotBlank, @NotNull 등 Bean Validation 어노테이션을 record 컴포넌트에 적용
- Response DTO는 엔티티 → DTO 변환용 정적 팩토리 메서드 from()을 제공한다

```java
// Request DTO
public record RefreshRequest(

    @NotBlank(message = "리프레시토큰은 필수입니다")
    String refreshToken
) {
}

// Response DTO - 정적 팩토리 메서드 from() 으로 엔티티를 변환한다
public record SessionResponse(
    Long id,
    String sessionCode,
    String status
) {
    public static SessionResponse from(Session session) {
        return new SessionResponse(session.getId(), session.getSessionCode(), session.getStatus());
    }
}
```

### Controller
- 모든 컨트롤러는 controller/api/ 하위의 {도메인}Api.java 인터페이스를 구현한다
- Swagger 명세는 Api 인터페이스에만 작성하고 Controller 구현체에는 작성하지 않는다
- @RequestMapping은 Controller 구현체에만 선언한다

```java
// api/AuthApi.java - Swagger 명세만 담당
@Tag(name = "Auth", description = "인증 API")
public interface AuthApi {

    @Operation(summary = "카카오 로그인 콜백",
               description = "카카오 SDK로 받은 인가코드로 JWT 토큰 발급")
    @ApiResponse(responseCode = "200", description = "토큰 발급 성공")
    @ApiResponse(responseCode = "400", description = "인가코드 없음")
    @ApiResponse(responseCode = "502", description = "외부 인증 서버 오류")
    ResponseEntity<TokenResponse> socialCallback(
        @Parameter(description = "소셜 로그인 provider")
        @PathVariable String provider,
        @Parameter(description = "인가코드", required = true)
        @RequestParam(required = false) String code
    );
}

// AuthController.java - 비즈니스 로직만 담당, Swagger 어노테이션 작성하지 않음
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {
    ...
}
```

### Service
- 메서드마다 /** */ JavaDoc 주석으로 역할, 파라미터, 반환값, 예외를 명시한다
- 비즈니스 로직의 각 단계를 인라인 주석으로 구분해 가독성을 확보한다

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 소셜 로그인 처리.
     * provider에 해당하는 OAuth 전략으로 사용자 정보를 조회해 JWT 토큰을 발급한다.
     *
     * @param providerName OAuth provider 이름 (kakao, google, apple)
     * @param code         소셜 플랫폼에서 받은 인가코드
     * @return accessToken + refreshToken 쌍
     * @throws AppException INVALID_OAUTH_PROVIDER - 지원하지 않는 provider
     * @throws AppException OAUTH_LOGIN_FAILED - 외부 API 호출 실패
     */
    public TokenResponse socialLogin(String providerName, String code) {
        // 1. provider 조회
        OAuthProvider provider = providerRegistry.getProvider(providerName);

        // 2. 소셜 프로필 조회
        OAuthProfile profile = provider.getProfile(code);

        // 3. 유저 조회 또는 생성 (최초 로그인 시 회원가입 처리)
        User user = findOrCreateUser(profile, providerName);

        // 4. JWT 발급
        return generateTokens(user.getId(), user.getRole());
    }
}
```

### Entity
- @Getter, @NoArgsConstructor(access = AccessLevel.PROTECTED), @Builder 기본 적용
- @Table(name = "테이블명") 명시
- @EntityListeners(AuditingEntityListener.class) + @CreatedDate 사용

### 예외 처리
- 비즈니스 예외는 CustomException(ErrorCode) 사용
- ErrorCode enum에 HTTP 상태코드와 메시지를 함께 정의
- 모든 예외는 GlobalExceptionHandler에서 중앙 처리하며 개별 컨트롤러에서 예외 응답을 직접 만들지 않는다
- 에러 응답 형식: { "message": "에러 메시지" }

```java
// 사용 예시
throw new CustomException(ErrorCode.USER_NOT_FOUND);
throw new CustomException(ErrorCode.INVALID_TOKEN);
```

### 네이밍
- 클래스: PascalCase
- 메서드/변수: camelCase
- 상수: UPPER_SNAKE_CASE
- 테이블/컬럼: snake_case
- API 경로: kebab-case, 복수형 명사 (/sessions, /shots)

## API 설계 원칙
- RESTful 설계. 모든 응답은 JSON
- 인증이 필요한 API는 Authorization: Bearer {accessToken} 헤더 필수
- Swagger UI: http://localhost:8080/api-docs

## 환경 설정

### 로컬 실행
```bash
# 1. MySQL + Redis 도커 실행
docker-compose up -d

# 2. application-dev.yml에 실제 키 값 입력 후 실행
./gradlew bootRun
```

### 프로파일
- dev: 로컬 개발 (application-dev.yml - 실제 값 직접 입력, Git 제외)
- prod: 운영 (application-prod.yml - 환경변수로 주입)

### Git 제외 파일
- application-dev.yml은 .gitignore에 반드시 포함한다

## Git 컨벤션

### 브랜치 전략
main              배포 브랜치
develop           개발 통합 브랜치
feature/{기능명}   기능 개발 (예: feature/signaling-server)
fix/{이슈명}       버그 수정

### 커밋 메시지
feat:     새로운 기능
fix:      버그 수정
refactor: 코드 리팩토링
docs:     문서 수정
chore:    빌드, 설정 변경

