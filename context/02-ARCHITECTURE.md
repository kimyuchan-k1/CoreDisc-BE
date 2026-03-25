# 프로젝트 아키텍처

## 레이어드 아키텍처

CoreDisc-BE는 **Clean Architecture** 기반의 레이어드 아키텍처를 따릅니다.

```
┌─────────────────────────────────────────────┐
│              Presentation Layer              │
│         (Controller + DTO + Docs)            │
├─────────────────────────────────────────────┤
│              Application Layer               │
│       (Service + Schedule + Batch)           │
├─────────────────────────────────────────────┤
│               Domain Layer                   │
│      (Entity + Repository Interface)         │
├─────────────────────────────────────────────┤
│            Infrastructure Layer              │
│   (Repository Impl + AWS + External API)     │
├─────────────────────────────────────────────┤
│               Common Layer                   │
│  (Exception + Converter + Util + ApiPayload) │
├─────────────────────────────────────────────┤
│            Security & Config                 │
│      (JWT + OAuth2 + Spring Config)          │
└─────────────────────────────────────────────┘
```

## CQRS 패턴

서비스 계층에서 **Command-Query 분리** 패턴을 적용합니다:

- `*CommandService` : 생성/수정/삭제 (쓰기 작업)
- `*QueryService` : 조회 (읽기 작업)

## 디렉터리 구조

```
src/main/java/com/coredisc/
├── presentation/               # API 계층
│   ├── controller/             # REST 컨트롤러 (18개)
│   ├── controllerdocs/         # Swagger 인터페이스 문서
│   └── dto/                    # 요청/응답 DTO (도메인별)
│
├── application/                # 비즈니스 로직 계층
│   ├── service/                # 서비스 (20+ 도메인)
│   │   ├── auth/               # 인증 서비스
│   │   ├── member/             # 회원 관리
│   │   ├── post/               # 게시글 작성/조회
│   │   ├── comment/            # 댓글/대댓글
│   │   ├── notification/       # 알림 시스템
│   │   ├── follow/             # 팔로우/언팔로우
│   │   ├── like/               # 좋아요
│   │   ├── question/           # 질문 관리
│   │   ├── category/           # 카테고리
│   │   ├── disc/               # 월별 디스크
│   │   ├── calendar/           # 캘린더
│   │   ├── reportStat/         # 통계/리포트
│   │   ├── searchHistory/      # 검색 이력
│   │   ├── block/              # 사용자 차단
│   │   ├── device/             # 디바이스 토큰
│   │   ├── fcm/                # FCM 푸시
│   │   ├── terms/              # 약관
│   │   └── notificationReminderSetting/
│   └── schedule/               # 배치/스케줄링
│       ├── BatchScheduler
│       ├── NotificationScheduler
│       └── NotificationReminderScheduler
│
├── domain/                     # 도메인 모델 계층
│   ├── member/                 # 회원 엔티티/레포지토리
│   ├── post/                   # 게시글, 답변, 이미지, 좋아요
│   ├── comment/                # 계층형 댓글
│   ├── notification/           # 알림
│   ├── officialQuestion/       # 공식 질문
│   ├── personalQuestion/       # 개인 질문
│   ├── todayQuestion/          # 오늘의 질문
│   ├── category/               # 카테고리
│   ├── follow/                 # 팔로우 관계
│   ├── block/                  # 차단 관계
│   ├── device/                 # FCM 디바이스
│   ├── disc/                   # 월별 디스크
│   ├── profileImg/             # 프로필 이미지
│   ├── searchHistory/          # 검색 이력
│   ├── terms/                  # 약관
│   ├── reportStats/            # 통계
│   ├── mapping/                # 매핑 엔티티 (N:M)
│   └── common/                 # BaseEntity + Enums
│
├── infrastructure/             # 인프라 계층
│   ├── repository/             # JPA 레포지토리 구현체
│   │   └── (각 도메인별 + QueryDSL)
│   └── aws/s3/                 # S3 이미지 업로드
│
├── common/                     # 공통 모듈
│   ├── apiPayload/             # ApiResponse 표준 응답
│   ├── exception/              # 도메인별 예외 핸들러
│   ├── converter/              # Entity <-> DTO 변환
│   ├── util/                   # 유틸리티 클래스
│   └── properties/             # 설정 프로퍼티
│
├── security/                   # 보안 계층
│   ├── auth/                   # PrincipalDetailsService
│   └── jwt/                    # JWT 발급/검증/필터
│
└── config/                     # 설정 클래스
    ├── SecurityConfig
    ├── RedisConfig
    ├── S3Config
    ├── FirebaseConfig
    ├── MailConfig
    ├── QuerydslConfig
    ├── SwaggerConfig
    ├── AsyncConfig
    └── WebConfig
```

## 주요 설계 결정

1. **CQRS 패턴**: 읽기/쓰기 서비스 분리로 복잡도 관리
2. **커서 기반 페이지네이션**: 피드, 댓글 등에 적용 (오프셋 방식 대신)
3. **계층형 댓글**: depth 필드로 댓글/대댓글 구분 (최대 2depth)
4. **소프트 삭제**: 댓글은 isDeleted 플래그로 논리적 삭제
5. **비동기 처리**: @Async로 알림 전송 등 비동기 실행
6. **스케줄링**: 일간/월간 배치 작업으로 통계 생성, 디스크 생성
