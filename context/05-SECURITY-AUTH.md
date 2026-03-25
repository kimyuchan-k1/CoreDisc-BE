# 보안 & 인증/인가

## JWT 기반 인증 흐름

```
[클라이언트] ──> [JwtFilter] ──> [SecurityContext] ──> [Controller]
                    │
                    ├── Authorization 헤더에서 Bearer 토큰 추출
                    ├── JwtProvider로 토큰 검증
                    ├── PrincipalDetailsService로 사용자 로드
                    └── SecurityContext에 인증 정보 설정
```

## 토큰 관리

### Access Token
- **수명**: 설정 파일에서 관리 (짧은 수명)
- **저장**: 클라이언트 측
- **용도**: API 요청 인증

### Refresh Token
- **수명**: 설정 파일에서 관리 (긴 수명)
- **저장**: Redis
- **용도**: Access Token 재발급

### 토큰 구조
- **알고리즘**: HS256
- **Payload**: username, memberId, tokenType

## @CurrentMember 어노테이션

컨트롤러에서 현재 인증된 사용자를 주입받는 커스텀 어노테이션:

```java
@GetMapping("/my-home")
public ApiResponse<?> getMyHome(@CurrentMember Member member) {
    // member는 JWT에서 추출된 현재 로그인 사용자
}
```

## OAuth2 소셜 로그인

### 지원 플랫폼
- **카카오** (Kakao)
- **네이버** (Naver)

### 소셜 로그인 흐름
1. 클라이언트에서 소셜 플랫폼 인증 후 토큰 획득
2. 서버로 소셜 토큰 전송
3. 서버에서 소셜 API로 사용자 정보 조회
4. 기존 회원이면 로그인 / 신규 회원이면 자동 가입
5. JWT 토큰 발급

## 보안 설정 (SecurityConfig)

### 허용 URL (인증 불필요)
- Swagger UI 관련 경로
- `/api/auth/**` (인증 관련 전체)
- `/actuator/**` (모니터링)

### 보안 설정
- CSRF: 비활성화 (REST API)
- Form Login: 비활성화
- Session: STATELESS (JWT 사용)
- CORS: 설정됨

### 필터 체인
```
요청 ──> JwtFilter ──> UsernamePasswordAuthenticationFilter ──> Controller
```

## 비밀번호 관리

- **암호화**: BCryptPasswordEncoder
- **비밀번호 재설정**: 이메일 인증 코드 방식
- **이메일 인증 코드**: Redis에 임시 저장

## 역할 (Role)

| Role | 설명 |
|------|------|
| USER | 일반 사용자 |
| ADMIN | 관리자 |
