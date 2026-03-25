# 외부 서비스 연동

## AWS 서비스

### S3 (이미지 저장소)

**용도**: 게시글 답변 이미지, 프로필 이미지 업로드

**파일 구조**:
```
S3 Bucket/
├── original/          # 원본 이미지
│   └── user_{memberId}_{uuid}.{ext}
└── thumbnail/         # 썸네일 (최대 800x800)
    └── user_{memberId}_{uuid}.{ext}
```

**이미지 처리 흐름**:
1. 클라이언트에서 이미지 업로드
2. EXIF 메타데이터에서 회전 정보 추출
3. 원본 이미지 S3 업로드
4. 썸네일 생성 (800x800 이내) 후 S3 업로드
5. 원본/썸네일 URL 반환

### RDS (MySQL)
- 메인 데이터베이스
- Spring Data JPA로 접근

### EC2
- 애플리케이션 서버 배포

---

## Firebase Cloud Messaging (FCM)

**용도**: 모바일 푸시 알림

**알림 유형**:
| 유형 | 설명 |
|------|------|
| DAILY_REMINDER | 매일 저널 작성 리마인더 |
| COMMENT | 댓글 알림 |
| COMMENT_REPLY | 대댓글 알림 |
| LIKE | 좋아요 알림 |
| FOLLOW | 팔로우 알림 |
| MONTHLY_REPORT | 월간 리포트 알림 |
| TEMP_POSTS | 임시 저장 게시글 알림 |

**디바이스 토큰 관리**:
- 로그인 시 디바이스 토큰 등록
- 로그아웃 시 디바이스 토큰 삭제
- 사용자당 여러 디바이스 지원

---

## Redis

**용도**:
1. **Refresh Token 저장**: JWT 리프레시 토큰 관리
2. **이메일 인증 코드 캐싱**: 임시 인증 코드 저장 (TTL 적용)
3. **임시 데이터 캐싱**: 세션성 데이터

**설정**: Lettuce 클라이언트, StringRedisSerializer

---

## 이메일 서비스 (SMTP)

**용도**:
- 회원가입 시 이메일 인증 코드 발송
- 비밀번호 재설정 인증 코드 발송

**구현**: Spring Mail (JavaMailSender)

---

## OAuth2 소셜 로그인

### 카카오 (Kakao)
- 카카오 계정으로 간편 로그인
- 카카오 API로 사용자 프로필 조회

### 네이버 (Naver)
- 네이버 계정으로 간편 로그인
- 네이버 API로 사용자 프로필 조회

---

## 모니터링

### Spring Actuator
- 헬스 체크 엔드포인트
- 메트릭스 노출

### Micrometer Prometheus
- Prometheus 메트릭 수집
- 모니터링 대시보드 연동 가능

---

## Docker

### docker-compose.yml
- Redis 컨테이너 실행

### Dockerfile
- Java 애플리케이션 컨테이너화
