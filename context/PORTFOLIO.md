# CoreDisc - 백엔드 포트폴리오

> 매일 4개의 질문에 답변하며 자신의 "Core"를 발견하는 자기발견 저널링 SNS 서비스

---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **프로젝트명** | CoreDisc (코어디스크) |
| **서비스 소개** | 매일 4개의 질문에 답변하며 자기발견을 하는 저널링 SNS |
| **개발 기간** | 2025.07 ~ 현재 |
| **팀 구성** | 백엔드 5명 |
| **역할** | **백엔드 리더** — 아키텍처 설계, 인프라 구축, 핵심 도메인 개발 |
| **기술 스택** | Java 17, Spring Boot 3.5.3, Spring Data JPA, QueryDSL 5.0, MySQL, Redis, AWS (EC2, RDS, S3), Docker, GitHub Actions, Firebase FCM, Prometheus, Grafana |

---

## 담당 역할 및 기여 요약

백엔드 팀 리더로서 프로젝트의 **기술적 기반 전체**를 설계하고 구축했습니다.

| 담당 영역 | 상세 |
|-----------|------|
| **아키텍처 설계** | Clean Architecture + CQRS 패턴 기반 레이어 구조 설계 |
| **인프라 구축** | AWS 클라우드 환경 구성 (EC2, RDS, S3) + Docker 컨테이너화 |
| **CI/CD 파이프라인** | GitHub Actions 기반 자동 빌드/배포 파이프라인 구축 |
| **모니터링 시스템** | Spring Actuator + Prometheus + Grafana 모니터링 스택 구성 |
| **게시글 CRUD** | 저널 작성/조회/수정/삭제 전체 비즈니스 로직 |
| **임시 저장 게시글** | 2단계 게시글 라이프사이클 (임시저장 → 발행) 설계/구현 |
| **이미지 처리 파이프라인** | S3 연동, EXIF 보정, 자동 썸네일 생성 시스템 |
| **피드 시스템** | 커서 기반 무한스크롤 피드 + 공개범위/팔로우 기반 가시성 필터링 |
| **댓글 시스템** | 계층형 댓글/대댓글 구조 + 실시간 FCM 알림 연동 |
| **좋아요 시스템** | 동시성 안전한 좋아요/취소 로직 |
| **보안 설계** | JWT 인증 체계 + Redis 기반 토큰 관리 설계 |

---

---

# 1. 아키텍처 설계 — Clean Architecture + CQRS

## 1.1 레이어드 아키텍처 설계

프로젝트 초기에 팀의 코드 일관성과 확장성을 위해 **Clean Architecture 기반 레이어드 아키텍처**를 설계하고 팀 전체에 적용했습니다.

```
┌─────────────────────────────────────────────┐
│          Presentation Layer                 │
│   Controller + ControllerDocs + DTO         │
│   (HTTP 요청/응답 처리, Swagger 문서화)       │
├─────────────────────────────────────────────┤
│          Application Layer                  │
│   CommandService + QueryService + Schedule  │
│   (비즈니스 로직, 트랜잭션 경계, 배치 작업)     │
├─────────────────────────────────────────────┤
│            Domain Layer                     │
│   Entity + Repository Interface             │
│   (순수 도메인 모델, 비즈니스 규칙)             │
├─────────────────────────────────────────────┤
│         Infrastructure Layer                │
│   RepositoryImpl + Adaptor + AWS S3         │
│   (기술 구현 세부사항, 외부 시스템 연동)         │
├─────────────────────────────────────────────┤
│            Common Layer                     │
│   ApiResponse + Exception + Converter       │
│   (공통 유틸리티, 표준 응답, 예외 처리)          │
└─────────────────────────────────────────────┘
```

### 설계 의도

- **관심사의 분리**: 각 레이어가 단 하나의 책임만 갖도록 분리하여, 한 레이어의 변경이 다른 레이어에 영향을 최소화
- **의존성 방향 통일**: 상위 레이어 → 하위 레이어로만 의존, Domain Layer는 어떤 프레임워크(Spring, JPA)에도 의존하지 않음
- **테스트 용이성**: 각 레이어를 독립적으로 단위 테스트 가능한 구조

### 팀 리더로서의 역할

- 프로젝트 초기에 패키지 구조와 레이어 규칙을 문서화하고 팀원들에게 가이드
- 코드 리뷰를 통해 레이어 간 의존성 규칙 준수 여부를 지속적으로 검토
- 새 도메인 추가 시 일관된 패키지 구조를 유지하도록 템플릿 제공

## 1.2 CQRS 패턴 적용

모든 서비스 계층에 **Command-Query Responsibility Segregation** 패턴을 적용했습니다.

```
PostCommandService (Interface)          PostQueryService (Interface)
        │                                       │
PostCommandServiceImpl                  PostQueryServiceImpl
  ├── createEmptyPost()                   ├── findPostFeed()
  ├── updateTextAnswer()                  ├── findPostDetail()
  ├── updateImageAnswer()                 ├── getTempPosts()
  ├── publishPost()                       └── getTempPost()
  └── deletePost()
```

### 적용 이유

- **읽기/쓰기 최적화 분리**: 읽기 작업은 `@Transactional(readOnly = true)`로 JPA 더티 체킹 비용 제거, 쓰기 작업은 트랜잭션 안전성 보장
- **코드 가독성 향상**: 하나의 서비스 클래스가 수십 개의 메서드로 비대해지는 것을 방지
- **팀 협업 효율화**: 5명의 개발자가 동시에 작업할 때 충돌을 최소화 (읽기/쓰기 파일이 분리되므로 Git 충돌 감소)

## 1.3 Repository 3계층 추상화 패턴

도메인 계층의 순수성을 유지하면서도 복잡한 쿼리를 지원하기 위해 **3계층 Repository 패턴**을 설계했습니다.

```
Domain Layer:     PostRepository (Interface - 순수 Java)
                       │
Infrastructure:   PostRepositoryAdaptor (구현체)
                    ├── JpaPostRepository (Spring Data JPA)
                    └── QueryPostRepository (QueryDSL)
                            └── QueryPostRepositoryImpl (구현체)
```

### 설계 장점

- **도메인 순수성**: `PostRepository`는 Spring/JPA를 전혀 모르는 순수 Java 인터페이스
- **유연한 구현 교체**: JPA → MyBatis 등 기술 변경 시 Adaptor만 수정
- **QueryDSL 분리**: 단순 CRUD는 JPA, 복잡한 동적 쿼리는 QueryDSL로 분리하여 각각의 강점을 활용
- **테스트 용이성**: Mock 객체로 쉽게 대체 가능

---

---

# 2. 인프라 구축 및 DevOps

## 2.1 AWS 클라우드 아키텍처

서비스의 전체 클라우드 인프라를 설계하고 구축했습니다.

```
┌─────────────────────────────────────────────────────┐
│                   Client (Mobile App)               │
└───────────────────────┬─────────────────────────────┘
                        │ HTTPS
                        ▼
┌─────────────────────────────────────────────────────┐
│                  Nginx (Reverse Proxy)              │
│             SSL 종료 + 로드 밸런싱                     │
└───────────────────────┬─────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│              AWS EC2 Instance                       │
│  ┌───────────────────────────────────────────┐     │
│  │  Docker Container (Spring Boot App)       │     │
│  │  Port: 8080                               │     │
│  └───────────────────────────────────────────┘     │
│  ┌───────────────────────────────────────────┐     │
│  │  Docker Container (Redis)                 │     │
│  │  Port: 6379                               │     │
│  └───────────────────────────────────────────┘     │
└───────────────────────┬─────────────────────────────┘
                        │
            ┌───────────┼───────────┐
            ▼           ▼           ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │ AWS RDS  │ │ AWS S3   │ │ Firebase │
     │ (MySQL)  │ │ (Images) │ │  (FCM)   │
     └──────────┘ └──────────┘ └──────────┘
```

### 인프라 구성 상세

| 서비스 | 용도 | 선택 이유 |
|--------|------|-----------|
| **EC2** | 애플리케이션 서버 | 비용 효율적이며 Docker 환경 지원 |
| **RDS (MySQL)** | 메인 데이터베이스 | 관계형 데이터 모델에 적합, 관리형 서비스로 운영 부담 감소 |
| **S3** | 이미지 저장소 | 무제한 확장성, CDN 연동 가능, 비용 효율적 |
| **Redis** | 토큰/캐시 저장소 | 인메모리 기반 초고속 읽기/쓰기, TTL 지원 |
| **Nginx** | 리버스 프록시 | SSL 종료, 정적 파일 서빙, 로드 밸런싱 |

## 2.2 Docker 컨테이너화

애플리케이션의 환경 일관성과 배포 편의성을 위해 Docker 컨테이너화를 구현했습니다.

### Dockerfile 설계

```dockerfile
FROM openjdk:17-jdk
WORKDIR /app
ARG JAR_FILE=./build/libs/coredisc-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT [ "java", "-jar", "app.jar" ]
```

### Docker Compose 구성

```yaml
version: '3.8'
services:
  redis:
    image: redis:latest
    container_name: redis
    ports:
      - "6379:6379"
```

- 개발/운영 환경 간 동일한 Redis 인스턴스 구성 보장
- 컨테이너 기반 배포로 서버 환경 의존성 제거

## 2.3 CI/CD 파이프라인 구축

GitHub Actions를 활용한 **완전 자동화된 빌드/배포 파이프라인**을 구축했습니다.

```
[develop 브랜치 Push]
        │
        ▼
┌──────────────────────────┐
│  1. 소스 코드 체크아웃      │
│  2. JDK 17 환경 설정       │
│  3. Gradle 캐시 활용       │
└────────────┬─────────────┘
             ▼
┌──────────────────────────┐
│  4. 설정 파일 생성         │
│  - application.yml       │
│  - Firebase 키 파일       │
│  (GitHub Secrets 활용)    │
└────────────┬─────────────┘
             ▼
┌──────────────────────────┐
│  5. Gradle Build         │
│  ./gradlew build -x test │
└────────────┬─────────────┘
             ▼
┌──────────────────────────┐
│  6. Docker 이미지 빌드     │
│  7. Docker Hub Push      │
└────────────┬─────────────┘
             ▼
┌──────────────────────────┐
│  8. EC2 SSH 접속          │
│  9. 기존 컨테이너 정리     │
│  10. 새 이미지 Pull       │
│  11. docker-compose up   │
│  12. 불필요 이미지 정리    │
└──────────────────────────┘
```

### 파이프라인 설계 포인트

- **Gradle 캐시**: `~/.gradle` 디렉터리를 캐싱하여 반복 빌드 시간 대폭 단축
- **Secrets 관리**: `application.yml`, Firebase 서비스 계정 키 등 민감 정보를 GitHub Secrets로 관리하여 코드 저장소에 노출 방지
- **무중단 배포 흐름**: 기존 컨테이너 중지 → 새 이미지 Pull → 재시작으로 다운타임 최소화
- **이미지 정리**: `docker image prune -f`로 불필요한 dangling 이미지 자동 정리

---

---

# 3. 모니터링 시스템

## 3.1 Prometheus + Grafana 모니터링 스택

서비스의 실시간 상태 파악과 장애 대응을 위해 모니터링 시스템을 구축했습니다.

```
Spring Boot App                 Prometheus              Grafana
┌──────────────┐    scrape    ┌──────────────┐    query  ┌──────────────┐
│ /actuator    │ ◄──────────  │  Time-Series │ ────────► │  Dashboard   │
│ /prometheus  │              │   Database   │           │  시각화 패널   │
│              │              └──────────────┘           └──────────────┘
│ Micrometer   │
│  메트릭 수집  │
└──────────────┘
```

### 수집 메트릭

| 카테고리 | 수집 항목 |
|----------|----------|
| **JVM** | 힙 메모리 사용량, GC 횟수/시간, 스레드 수, 클래스 로딩 수 |
| **HTTP** | 요청/응답 시간, 상태코드별 카운트, 엔드포인트별 처리 시간 |
| **DB** | 커넥션 풀 사용량, 활성/유휴 커넥션 수, 쿼리 실행 시간 |
| **시스템** | CPU 사용률, 디스크 I/O, 네트워크 트래픽 |
| **비즈니스** | 커스텀 메트릭 (게시글 생성 수, 이미지 업로드 수 등) |

### 기술적 구현

```gradle
// build.gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

- **Spring Actuator**: 헬스 체크, 메트릭 노출 엔드포인트 제공
- **Micrometer**: 벤더 중립적 메트릭 수집 추상화 (Prometheus, Datadog 등 교체 가능)
- **Prometheus**: 주기적 스크래핑으로 시계열 데이터 저장
- **Grafana**: 대시보드 기반 시각화 및 알림 설정

### 모니터링 엔드포인트 보안

```java
// SecurityConfig에서 Actuator 엔드포인트 인증 없이 허용
String[] allowUrl = { ..., "/actuator/**", ... };
```

운영 환경에서는 네트워크 레벨(Security Group)에서 Prometheus 서버만 접근 가능하도록 제한.

---

---

# 4. 게시글 CRUD — 핵심 도메인 설계 및 구현

## 4.1 게시글 도메인 모델 설계

CoreDisc의 핵심 기능인 "매일 4개의 질문에 답변하는 저널"을 위한 도메인 모델을 설계했습니다.

```
Post (게시글)
├── PostAnswer #1 (질문1 답변) ─── PostAnswerImage (선택적 이미지)
├── PostAnswer #2 (질문2 답변) ─── PostAnswerImage
├── PostAnswer #3 (질문3 답변) ─── PostAnswerImage
├── PostAnswer #4 (질문4 답변) ─── PostAnswerImage
├── Comment[] (댓글 목록)
└── PostLike[] (좋아요 목록)
```

### Post 엔티티 — 2단계 라이프사이클

```java
@Entity
public class Post extends BaseEntity {
    @ManyToOne(fetch = LAZY)
    private Member member;              // 작성자

    @Enumerated(EnumType.STRING)
    private PostStatus status;          // TEMP → PUBLISHED

    @Enumerated(EnumType.STRING)
    private PublicityType publicity;     // OFFICIAL / CIRCLE / PERSONAL

    @Enumerated(EnumType.STRING)
    private DiaryWho diaryWho;          // 선택 일기: 누구와
    @Enumerated(EnumType.STRING)
    private DiaryWhere diaryWhere;      // 선택 일기: 어디서
    @Enumerated(EnumType.STRING)
    private DiaryWhat diaryWhat;        // 선택 일기: 무엇을
    private String dailyDetail;         // 오늘의 기분 상세

    private int likeCount;              // 비정규화 카운트
    private int commentCount;
    private int viewCount;

    @OneToMany(cascade = ALL, orphanRemoval = true)
    private List<PostAnswer> postAnswers;

    @OneToMany(cascade = ALL, orphanRemoval = true)
    private List<Comment> comments;

    @OneToMany(cascade = ALL, orphanRemoval = true)
    private List<PostLike> postLikes;
}
```

### 설계 결정 사항

**1. 통계 비정규화 (likeCount, commentCount, viewCount)**

피드 조회 시 매번 `COUNT` 집계 쿼리를 실행하는 것은 성능 저하를 유발합니다. 따라서 Post 엔티티에 카운트 필드를 직접 저장하여 피드 조회 시 단일 쿼리로 모든 통계를 가져올 수 있도록 했습니다.

- **장점**: 피드 조회 시 추가 쿼리 불필요, 대량 데이터에서도 일정한 성능
- **트레이드오프**: 좋아요/댓글 생성/삭제 시 카운트 동기화 필요

**2. Cascade ALL + OrphanRemoval**

게시글 삭제 시 관련 답변, 이미지, 댓글, 좋아요가 모두 자동으로 함께 삭제됩니다. 이는 데이터 정합성을 보장하면서도 삭제 로직을 단순화합니다.

**3. 3단계 공개범위 (PublicityType)**

| 공개범위 | 가시성 |
|----------|--------|
| `OFFICIAL` | 전체 공개 — 모든 사용자 |
| `CIRCLE` | 서클 공개 — 서로 서클로 지정한 친한 친구만 |
| `PERSONAL` | 비공개 — 작성자 본인만 |

이 공개범위는 피드 조회 쿼리에서 복잡한 가시성 필터링 로직으로 반영됩니다.

### PostAnswer 엔티티 — 다형적 답변 구조

```java
@Entity
public class PostAnswer extends BaseEntity {
    @ManyToOne(fetch = LAZY)
    private Post post;

    private int answerOrder;            // 1, 2, 3, 4

    @Enumerated(EnumType.STRING)
    private AnswerType type;            // IMAGE 또는 TEXT

    private String textContent;         // TEXT 답변 내용

    @OneToOne(cascade = ALL, orphanRemoval = true)
    private PostAnswerImage postAnswerImage;  // IMAGE 답변
}
```

**핵심 설계 — 답변 타입 다형성**:

하나의 답변이 텍스트 또는 이미지 중 하나의 타입을 가지며, 런타임에 자유롭게 전환할 수 있습니다.

```java
// 텍스트 답변으로 변경
public void updateTextAnswer(String content) {
    this.type = AnswerType.TEXT;
    this.textContent = content;
    this.postAnswerImage = null;     // 기존 이미지 제거 (orphanRemoval)
}

// 이미지 답변으로 변경
public void updateToImageAnswer(PostAnswerImage image) {
    this.type = AnswerType.IMAGE;
    this.textContent = null;         // 기존 텍스트 제거
    this.postAnswerImage = image;
}
```

이 설계로 사용자가 텍스트로 답변 후 이미지로 변경하거나, 그 반대의 경우에도 자연스럽게 처리됩니다. 별도의 `ImageAnswer`, `TextAnswer` 서브클래스를 만들지 않아 엔티티 구조를 단순하게 유지합니다.

## 4.2 게시글 생성 — 빈 게시글 + 점진적 작성

### 설계 철학: "빈 게시글 먼저, 답변은 점진적으로"

사용자가 저널을 작성할 때 4개의 답변을 한 번에 보내는 것이 아니라, **빈 게시글을 먼저 생성**하고 각 답변을 **개별적으로 저장**하는 점진적 작성 방식을 채택했습니다.

```
[빈 게시글 생성] → [답변1 저장] → [답변2 저장] → ... → [발행]
    TEMP              TEMP          TEMP              PUBLISHED
```

### 구현 상세 — createEmptyPost

```java
@Transactional
public CreatePostResultDto createEmptyPost(Member member, CreatePostDto request) {
    // 1. 하루 1개 발행 게시글 제한 검증
    validatePostNotExists(member, request.selectedDate());

    // 2. 오늘의 질문 4개 로드 (월간 3개 + 일간 1개)
    List<TodayQuestion> todayQuestions = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
        TodayQuestion question = findTodayQuestion(member, i, request.selectedDate());
        todayQuestions.add(question);
    }

    // 3. TEMP 상태의 빈 게시글 생성
    Post post = PostConverter.toPost(member, request.selectedDate());
    post = postRepository.save(post);

    return PostConverter.toCreatePostResultDto(post, todayQuestions);
}
```

### 질문 로딩 전략 — 월간 vs 일간

```
질문 1~3번: 월간 질문 (한 달 동안 같은 질문 유지)
  → findByMemberAndQuestionOrderAndSelectedDateBetween(member, order, startOfMonth, endOfMonth)

질문 4번: 일간 질문 (매일 다른 랜덤 질문)
  → findByMemberAndQuestionOrderAndSelectedDate(member, 4, exactDate)
```

이 구분은 서비스의 핵심 기획입니다. 질문 1~3번은 한 달간 일관된 주제로 자기 탐색을 하고, 4번째 질문만 매일 새롭게 변경되어 신선함을 유지합니다.

## 4.3 답변 업데이트 — 텍스트와 이미지

### 텍스트 답변 저장

```java
@Transactional
public AnswerResultDto updateTextAnswer(Member member, Long postId,
                                         int questionOrder, TextAnswerDto request) {
    // 1. 소유권 + 임시저장 상태 검증
    Post post = validatePostOwnership(member, postId);

    // 2. 질문 메타데이터 로드
    TodayQuestion question = findTodayQuestion(member, questionOrder, ...);

    // 3. 기존 답변 존재 여부에 따라 분기
    Optional<PostAnswer> existingAnswer = findExistingAnswer(post, questionOrder);

    if (existingAnswer.isPresent()) {
        // 기존 답변이 이미지였다면 → S3 파일 삭제 후 텍스트로 전환
        PostAnswer answer = existingAnswer.get();
        if (answer.isImageAnswer()) {
            deleteS3Image(answer.getPostAnswerImage());
        }
        answer.updateTextAnswer(request.content());
    } else {
        // 새 답변 생성
        PostAnswer newAnswer = PostAnswer.createTextAnswer(post, questionOrder, request.content(), question);
        postAnswerRepository.save(newAnswer);
    }
}
```

### 이미지 답변 저장

```java
@Transactional
public AnswerResultDto updateImageAnswer(Member member, Long postId,
                                          int questionOrder, MultipartFile file) {
    // 1. 소유권 + 임시저장 상태 검증
    Post post = validatePostOwnership(member, postId);

    // 2. S3 업로드 (원본 + 썸네일 + EXIF 보정)
    ImageUploadResult s3Result = amazonS3Manager.uploadImage(file, member.getId());

    // 3. 기존 답변이 있으면 기존 S3 파일 삭제
    Optional<PostAnswer> existingAnswer = findExistingAnswer(post, questionOrder);
    if (existingAnswer.isPresent()) {
        deleteExistingS3Files(existingAnswer.get());
    }

    // 4. PostAnswerImage 엔티티 생성 (원본URL, 썸네일URL, S3키)
    PostAnswerImage image = PostAnswerImage.create(
        s3Result.originalUrl(),
        s3Result.thumbnailUrl(),
        s3Result.originalKey()
    );

    // 5. 답변 저장 또는 업데이트
    saveOrUpdateImageAnswer(post, questionOrder, image, question);
}
```

### S3 삭제 복원력 패턴

이미지 삭제 시 S3 장애가 DB 작업을 차단하지 않도록 **복원력 있는 삭제 패턴**을 적용했습니다.

```java
private void deleteS3Images(List<String> imageUrls) {
    for (String imageUrl : imageUrls) {
        try {
            amazonS3Manager.deleteImageByUrl(imageUrl);
            log.info("S3 이미지 삭제 성공: {}", imageUrl);
        } catch (Exception e) {
            log.error("S3 이미지 삭제 실패: {}, error: {}", imageUrl, e.getMessage());
            // 예외를 던지지 않고 다음 이미지 삭제 계속 진행
        }
    }
}
```

**설계 의도**: S3의 일시적 장애로 인해 DB의 게시글 삭제가 실패하는 것을 방지합니다. 미삭제된 S3 파일은 로그를 통해 추적하고 별도 배치로 정리할 수 있습니다.

## 4.4 게시글 발행 — 임시저장 → 발행 전환

```java
@Transactional
public PublishResultDto publishPost(Member member, Long postId, PublishPostDto request) {
    // 1. 소유권 + 임시저장 상태 검증
    Post post = validatePostOwnership(member, postId);

    // 2. 4개 답변 모두 작성되었는지 검증
    if (post.getPostAnswers().size() != 4) {
        throw new PostHandler(ErrorStatus.POST_NOT_READY_TO_PUBLISH);
    }

    // 3. 상태 전환: TEMP → PUBLISHED
    post.publish();

    // 4. 선택 일기 정보 업데이트
    post.updateSelectiveDiary(
        request.diaryWho(),
        request.diaryWhere(),
        request.diaryWhat(),
        request.dailyDetail()
    );

    // 5. 공개범위 설정
    post.updatePublicity(request.publicity());

    return PostConverter.toPublishResultDto(post);
}
```

### 발행 검증 규칙

| 규칙 | 예외 |
|------|------|
| 게시글 소유자만 발행 가능 | `NOT_POST_OWNER` |
| TEMP 상태에서만 발행 가능 | `POST_ALREADY_PUBLISHED` |
| 4개 답변 모두 존재해야 발행 가능 | `POST_NOT_READY_TO_PUBLISH` |
| 하루 1개만 발행 가능 | `POST_ALREADY_EXISTS` |

## 4.5 게시글 삭제 — S3 연쇄 삭제

```java
@Transactional
public void deletePost(Member member, Long postId) {
    // 1. 소유권 검증
    Post post = validatePostOwner(member, postId);

    // 2. 모든 답변에서 이미지 URL 추출
    List<String> imageUrls = extractAllImageUrls(post);
    List<String> thumbnailUrls = extractAllThumbnailUrls(post);

    // 3. S3 파일 삭제 (복원력 패턴 적용)
    deleteS3Images(imageUrls);
    deleteS3Images(thumbnailUrls);

    // 4. DB 삭제 (Cascade로 답변/이미지/댓글/좋아요 연쇄 삭제)
    postRepository.delete(post);
}
```

### 삭제 순서의 중요성

S3 파일을 **먼저** 삭제한 후 DB 레코드를 삭제합니다. 이 순서를 지킨 이유:
- DB 먼저 삭제하면 S3 URL 정보가 사라져 파일을 찾을 수 없음
- S3 삭제 실패 시 DB 레코드가 남아있어 재시도 가능
- S3 삭제는 복원력 패턴으로 실패를 허용하므로 DB 삭제가 차단되지 않음

---

---

# 5. 임시 저장 게시글 시스템

## 5.1 2단계 라이프사이클 설계

CoreDisc의 저널은 일반 게시글과 달리 **임시 저장 → 발행**의 2단계 라이프사이클을 가집니다.

```
┌──────────────────┐         ┌──────────────────┐
│   TEMP (임시저장)  │ ──────► │ PUBLISHED (발행)  │
│                  │ publish │                  │
│ - 답변 수정 가능   │         │ - 수정 불가        │
│ - 피드에 노출 안됨  │         │ - 피드에 노출      │
│ - 배치 정리 대상   │         │ - 영구 보관        │
└──────────────────┘         └──────────────────┘
```

### 설계 의도

- **작성 중 이탈 보호**: 사용자가 4개 답변 작성 중 앱을 종료해도 데이터 유실 없음
- **점진적 작성 UX**: 각 답변을 실시간으로 저장하여 "저장" 버튼을 누를 필요 없음
- **발행 품질 보장**: 4개 답변이 모두 완성되어야만 발행 가능
- **하루 1개 제한**: 발행된 게시글은 하루 1개만 허용, 임시 저장은 여러 개 가능

## 5.2 임시 저장 조회

```java
@Transactional(readOnly = true)
public TempPostDetailDto getTempPost(Member member, Long postId) {
    // 1. 게시글 조회
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

    // 2. 임시 저장 상태 검증
    if (post.isPublished()) {
        throw new PostHandler(ErrorStatus.POST_ALREADY_PUBLISHED);
    }

    // 3. 소유권 검증
    if (!post.getMember().getId().equals(member.getId())) {
        throw new PostHandler(ErrorStatus.NOT_POST_OWNER);
    }

    // 4. 답변 목록 조회 (순서대로)
    List<PostAnswer> answers = postAnswerRepository
        .findByPostIdOrderByAnswerOrder(postId);

    // 5. 4개 슬롯에 대해 답변 유무를 포함하여 DTO 변환
    return PostConverter.toTempPostDetailDto(post, answers);
}
```

### 임시 저장 응답 구조

```json
{
  "postId": 123,
  "selectedDate": "2025-08-15",
  "answers": [
    {
      "answerId": 1,
      "questionOrder": 1,
      "answerType": "TEXT",
      "textContent": "오늘은...",
      "isAnswered": true,
      "updatedAt": "2025-08-15 14:30"
    },
    {
      "answerId": null,
      "questionOrder": 2,
      "answerType": null,
      "isAnswered": false
    },
    {
      "answerId": 2,
      "questionOrder": 3,
      "answerType": "IMAGE",
      "imageUrl": "https://...",
      "thumbnailUrl": "https://...",
      "isAnswered": true,
      "updatedAt": "2025-08-15 14:35"
    },
    {
      "answerId": null,
      "questionOrder": 4,
      "answerType": null,
      "isAnswered": false
    }
  ]
}
```

`isAnswered` 플래그로 어떤 질문에 답변했고 어떤 질문이 미답변인지 클라이언트가 쉽게 판단할 수 있습니다.

## 5.3 오래된 임시 저장 자동 정리 — 배치 스케줄러

```java
@Scheduled(cron = "...")
@Transactional
public void cleanupOldTempPosts() {
    LocalDateTime cutoffDate = LocalDateTime.now().minusDays(RETENTION_DAYS);

    List<Post> oldTempPosts = postRepository
        .findAllByStatusAndCreatedAtBefore(PostStatus.TEMP, cutoffDate);

    for (Post post : oldTempPosts) {
        // S3 이미지 삭제
        List<String> imageUrls = extractAllImageUrls(post);
        deleteS3Images(imageUrls);

        // DB 삭제
        postRepository.delete(post);
    }
}
```

오래된 임시 저장 게시글이 무한히 쌓이는 것을 방지하기 위해 배치 스케줄러로 자동 정리합니다. S3 파일까지 함께 삭제하여 스토리지 비용을 절약합니다.

---

---

# 6. 이미지 처리 파이프라인 & S3 연동

## 6.1 이미지 업로드 전체 흐름

모바일 앱에서 촬영한 사진을 최적의 상태로 저장하기 위한 **완전한 이미지 처리 파이프라인**을 구축했습니다.

```
[모바일 사진 촬영]
        │
        ▼
┌──────────────────┐
│  1. 파일 검증     │  10MB 제한, 이미지 타입 확인
└────────┬─────────┘
         ▼
┌──────────────────┐
│  2. EXIF 메타데이터│  회전 정보 (orientation) 추출
│     추출          │
└────────┬─────────┘
         ▼
┌──────────────────┐
│  3. 이미지 회전    │  EXIF 방향에 따라 실제 회전 적용
│     보정          │
└────────┬─────────┘
         ▼
┌────────┴─────────────────────┐
│                               │
▼                               ▼
┌──────────────┐    ┌──────────────────┐
│  원본 이미지   │    │   썸네일 생성      │
│  S3 업로드    │    │   (800×800 이내)  │
│  original/   │    │   고품질 리사이즈    │
└──────────────┘    │   S3 업로드        │
                    │   thumbnail/      │
                    └──────────────────┘
         │                    │
         ▼                    ▼
┌──────────────────────────────┐
│  ImageUploadResult 반환       │
│  - originalUrl               │
│  - thumbnailUrl              │
│  - s3Key                     │
└──────────────────────────────┘
```

## 6.2 파일 검증

```java
public void validateFile(MultipartFile file) {
    if (file.isEmpty()) {
        throw new PostHandler(ErrorStatus.POST_IMAGE_EMPTY);
    }
    if (file.getSize() > MAX_FILE_SIZE) {       // 10MB
        throw new PostHandler(ErrorStatus.POST_IMAGE_SIZE_EXCEEDED);
    }
    if (!file.getContentType().startsWith("image/")) {
        throw new PostHandler(ErrorStatus.POST_IMAGE_INVALID_TYPE);
    }
}
```

## 6.3 EXIF 방향 보정 — 모바일 사진 회전 문제 해결

### 문제 상황

스마트폰으로 사진을 세로로 찍으면 실제 이미지 데이터는 가로로 저장되고, **EXIF 메타데이터의 orientation 태그**에 회전 정보가 기록됩니다. 대부분의 앱은 EXIF를 읽어서 보정하지만, 웹 브라우저나 일부 환경에서는 EXIF를 무시하여 **사진이 90도 회전**되어 보이는 문제가 발생합니다.

### 해결 방법

이미지 업로드 시점에 EXIF orientation을 읽어서 **실제 픽셀 데이터를 회전**시킨 후 저장합니다.

```java
private int getExifOrientation(MultipartFile file) {
    try {
        Metadata metadata = ImageMetadataReader.readMetadata(file.getInputStream());
        ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

        if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        }
    } catch (Exception e) {
        log.warn("EXIF 메타데이터 읽기 실패, 기본 방향(1) 사용");
    }
    return 1;  // 기본값: 회전 불필요
}
```

### EXIF Orientation 매핑

| Orientation 값 | 의미 | 적용할 회전 |
|----------------|------|------------|
| 1 | 정상 | 회전 없음 |
| 3 | 180도 회전 | 180도 회전 |
| 6 | 시계방향 90도 | CW 90도 회전 |
| 8 | 반시계방향 90도 | CCW 90도 회전 |

### 회전 적용 + 썸네일 생성

```java
private BufferedImage rotateAndResize(BufferedImage original, int orientation) {
    // 1. EXIF orientation에 따른 AffineTransform 생성
    AffineTransform transform = getTransformForOrientation(original, orientation);

    // 2. 회전 적용
    BufferedImage rotated = applyTransform(original, transform);

    // 3. 썸네일 리사이즈 (800×800 이내, 비율 유지)
    int targetWidth, targetHeight;
    if (rotated.getWidth() > 800 || rotated.getHeight() > 800) {
        double ratio = Math.min(800.0 / rotated.getWidth(),
                                800.0 / rotated.getHeight());
        targetWidth = (int)(rotated.getWidth() * ratio);
        targetHeight = (int)(rotated.getHeight() * ratio);
    } else {
        targetWidth = rotated.getWidth();
        targetHeight = rotated.getHeight();
    }

    // 4. 고품질 렌더링
    BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, TYPE_INT_RGB);
    Graphics2D g2d = thumbnail.createGraphics();
    g2d.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
    g2d.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
    g2d.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    g2d.drawImage(rotated, 0, 0, targetWidth, targetHeight, null);
    g2d.dispose();

    return thumbnail;
}
```

### 고품질 렌더링 힌트 선택 이유

| 렌더링 힌트 | 선택 | 이유 |
|-------------|------|------|
| INTERPOLATION_BILINEAR | O | 속도와 품질의 균형 (BICUBIC보다 빠르면서 충분한 품질) |
| RENDER_QUALITY | O | 최종 결과물의 품질 우선 |
| ANTIALIAS_ON | O | 리사이즈 시 계단 현상 방지 |

## 6.4 S3 업로드 구현

### 파일 키 생성 전략

```java
String fileKey = String.format("user_%d_%s", memberId, generateUUID12());
// 예: user_123_abc123def456
```

- `user_` 접두사로 사용자 소유 이미지 식별
- `memberId`로 사용자별 추적 가능
- 12자리 UUID로 충돌 방지

### S3 저장 구조

```
S3 Bucket/
├── original/
│   ├── user_123_abc123def456.jpg
│   ├── user_123_xyz789ghi012.jpg
│   └── ...
├── thumbnail/
│   ├── user_123_abc123def456.jpg
│   ├── user_123_xyz789ghi012.jpg
│   └── ...
└── profiles/
    └── user_123_pro456abc789.jpg
```

### 원본 이미지 업로드

```java
public String uploadOriginalToS3(MultipartFile file, String s3Key) {
    String fullKey = "original/" + s3Key + ".jpg";

    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(file.getSize());
    metadata.setContentType(file.getContentType());

    amazonS3.putObject(new PutObjectRequest(bucket, fullKey, file.getInputStream(), metadata));

    return amazonS3.getUrl(bucket, fullKey).toString();
}
```

### 썸네일 업로드 (BufferedImage → S3)

```java
public String uploadThumbnailToS3(BufferedImage thumbnail, String s3Key) {
    String fullKey = "thumbnail/" + s3Key + ".jpg";

    // BufferedImage → byte[] 변환
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(thumbnail, "jpg", baos);
    byte[] bytes = baos.toByteArray();

    // byte[] → InputStream 변환하여 S3 업로드
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(bytes.length);
    metadata.setContentType("image/jpeg");

    amazonS3.putObject(new PutObjectRequest(
        bucket, fullKey, new ByteArrayInputStream(bytes), metadata));

    return amazonS3.getUrl(bucket, fullKey).toString();
}
```

## 6.5 S3 이미지 삭제

### URL → S3 Key 파싱

S3 URL에서 파일 키를 추출하여 삭제합니다.

```java
private String extractKeyFromUrl(String imageUrl) {
    // Regex: original/ 또는 thumbnail/ 뒤의 파일키 추출
    Pattern pattern = Pattern.compile(
        "(?:original|thumbnail|profiles)/(user_\\d+_[a-zA-Z0-9]+)\\.jpg"
    );
    Matcher matcher = pattern.matcher(imageUrl);
    if (matcher.find()) {
        return matcher.group(1);
    }
    // Fallback: URL 파싱
    return manualUrlParsing(imageUrl);
}
```

### 원본 + 썸네일 동시 삭제

```java
public void deleteImage(String s3Key) {
    amazonS3.deleteObject(bucket, "original/" + s3Key + ".jpg");
    amazonS3.deleteObject(bucket, "thumbnail/" + s3Key + ".jpg");
}
```

하나의 키로 원본과 썸네일을 모두 삭제하여 고아 파일 발생을 방지합니다.

---

---

# 7. 피드 시스템 — 커서 기반 무한 스크롤

## 7.1 피드 조회 아키텍처

CoreDisc의 피드는 단순 시간순 정렬이 아닌, **공개범위 + 팔로우 관계 + 서클 관계**를 복합적으로 고려하는 복잡한 가시성 필터링을 수행합니다.

### 피드 유형

| FeedType | 표시 대상 |
|----------|----------|
| `ALL` | 내 게시글 + 팔로잉한 사람들의 게시글 |
| `CORE` | 서클(친한 친구)로 지정한 사람들의 게시글만 |

### 가시성 필터링 규칙

```
게시글 공개범위 × 열람자 관계 → 가시성 결정

OFFICIAL (전체 공개)  → 모든 피드에서 표시
CIRCLE   (서클 공개)  → 서로 서클인 경우에만 표시
PERSONAL (비공개)     → 작성자 본인의 ALL 피드에서만 표시
```

## 7.2 QueryDSL 기반 피드 쿼리 구현

피드 조회는 이 프로젝트에서 **가장 복잡한 쿼리**입니다. QueryDSL을 활용하여 타입 안전하게 동적 조건을 조합합니다.

```java
public List<Post> findPostFeed(Member member, FeedType feedType,
                                Long cursor, int size) {
    BooleanBuilder condition = new BooleanBuilder();

    // 기본 조건: PUBLISHED 상태만
    condition.and(post.status.eq(PostStatus.PUBLISHED));

    // 피드 타입별 회원 필터링
    if (feedType == FeedType.ALL) {
        // 내 게시글 + 팔로잉의 게시글
        List<Long> followingIds = getFollowingIds(member);
        followingIds.add(member.getId());  // 내 게시글 포함
        condition.and(post.member.id.in(followingIds));
    } else if (feedType == FeedType.CORE) {
        // 서클 멤버의 게시글만
        List<Long> circleIds = getCircleIds(member);
        condition.and(post.member.id.in(circleIds));
    }

    // 공개범위 필터링
    BooleanBuilder visibilityCondition = new BooleanBuilder();
    visibilityCondition.or(post.publicity.eq(PublicityType.OFFICIAL));  // 전체 공개
    visibilityCondition.or(                                             // 서클 공개
        post.publicity.eq(PublicityType.CIRCLE)
            .and(isMutualCircle(member, post.member))
    );
    if (feedType == FeedType.ALL) {
        visibilityCondition.or(                                         // 비공개 (본인만)
            post.publicity.eq(PublicityType.PERSONAL)
                .and(post.member.id.eq(member.getId()))
        );
    }
    condition.and(visibilityCondition);

    // 커서 페이지네이션
    if (cursor != null) {
        condition.and(post.id.lt(cursor));
    }

    // 실행: size+1개 가져와서 hasNext 판단
    return jpaQueryFactory
        .selectFrom(post)
        .leftJoin(post.member, member).fetchJoin()
        .leftJoin(member.profileImg, profileImg).fetchJoin()
        .where(condition)
        .orderBy(post.id.desc())
        .limit(size + 1)
        .fetch();
}
```

## 7.3 커서 기반 페이지네이션 구현

기존의 오프셋 기반 페이지네이션(`LIMIT 10 OFFSET 100`) 대신 **커서 기반 페이지네이션**을 채택했습니다.

### 오프셋 vs 커서 비교

| 항목 | 오프셋 방식 | 커서 방식 (채택) |
|------|-----------|---------------|
| 쿼리 성능 | O(N) — offset이 커질수록 느림 | O(1) — 항상 일정한 성능 |
| 데이터 일관성 | 새 데이터 추가 시 중복/누락 | 항상 정확한 순서 보장 |
| 무한 스크롤 | 페이지 번호 관리 필요 | 마지막 ID만 전달하면 됨 |
| 구현 복잡도 | 단순 | 약간 복잡 |

### 구현 패턴: Fetch N+1

```java
// 서비스 레이어
public PostFeedResponseDTO findPostFeed(Member member, FeedType type,
                                         Long cursor, int size) {
    // 1. size+1개 조회 → hasNext 판단
    List<Post> posts = queryPostRepository.findPostFeed(member, type, cursor, size);

    boolean hasNext = posts.size() > size;

    // 2. 실제 응답은 size개만
    if (hasNext) {
        posts = posts.subList(0, size);
    }

    // 3. 다음 커서 = 마지막 항목의 ID
    Long nextCursor = hasNext ? posts.get(posts.size() - 1).getId() : null;

    return new PostFeedResponseDTO(convertToSummaries(posts), nextCursor, hasNext);
}
```

### 응답 구조

```json
{
  "posts": [
    {
      "postId": 150,
      "member": { "username": "kim", "profileImg": "..." },
      "selectedDate": "2025-08-15",
      "publicity": "OFFICIAL",
      "answer": {
        "questionContent": "오늘 가장 기억에 남는 순간은?",
        "answerType": "TEXT",
        "textContent": "아침에 본 일출이..."
      }
    },
    ...
  ],
  "nextCursor": 141,
  "hasNext": true
}
```

## 7.4 N+1 문제 방지 — 배치 쿼리 전략

피드에서 N개의 게시글을 가져올 때, 각 게시글의 답변을 개별 조회하면 N+1 문제가 발생합니다. 이를 **배치 쿼리**로 해결했습니다.

```java
// 쿼리 1: 게시글 + 작성자 (FetchJoin)
List<Post> posts = jpaQueryFactory
    .selectFrom(post)
    .leftJoin(post.member).fetchJoin()
    .leftJoin(post.member.profileImg).fetchJoin()
    .where(condition)
    .fetch();

// 쿼리 2: 모든 게시글의 답변을 한 번에 조회 (IN 절)
List<Long> postIds = posts.stream().map(Post::getId).toList();
List<PostAnswer> allAnswers = jpaQueryFactory
    .selectFrom(postAnswer)
    .leftJoin(postAnswer.postAnswerImage).fetchJoin()
    .where(postAnswer.post.id.in(postIds))
    .orderBy(postAnswer.post.id.asc(), postAnswer.answerOrder.asc())
    .fetch();

// 메모리에서 게시글별로 그룹핑
Map<Long, List<PostAnswer>> answersByPostId = allAnswers.stream()
    .collect(Collectors.groupingBy(a -> a.getPost().getId()));
```

### 쿼리 실행 횟수

| 방식 | 쿼리 수 | 설명 |
|------|---------|------|
| 개별 조회 (N+1) | 1 + N | 게시글 1번 + 답변 N번 |
| **배치 조회 (적용)** | **2~3** | 게시글 1번 + 답변 1번 (+ 질문 배치) |

피드 크기와 관계없이 **고정된 쿼리 수**로 조회하여 성능을 보장합니다.

---

---

# 8. 댓글 시스템 — 계층형 댓글/대댓글

## 8.1 계층형 댓글 도메인 설계

댓글은 **자기 참조(self-referencing)** 관계로 계층 구조를 구현했습니다.

```
Comment (depth=0, parent=null)     ← 부모 댓글
├── Reply (depth=1, parent=Comment)  ← 대댓글
├── Reply (depth=1, parent=Comment)
└── Reply (depth=1, parent=Comment)
```

### Comment 엔티티

```java
@Entity
public class Comment extends BaseEntity {
    @ManyToOne(fetch = LAZY)
    private Post post;              // 소속 게시글

    @ManyToOne(fetch = LAZY)
    private Member member;          // 작성자

    private String content;         // 댓글 내용

    private int depth;              // 0: 댓글, 1: 대댓글

    @ManyToOne(fetch = LAZY)
    private Comment parent;         // 부모 댓글 (self-referencing)

    @OneToMany(mappedBy = "parent", cascade = ALL)
    private List<Comment> replies;  // 자식 댓글들

    private boolean isDeleted;      // 소프트 삭제 플래그
}
```

### 깊이 제한 — 최대 1depth

무한 대댓글을 허용하면 UI/UX가 복잡해지고 모바일 환경에서 보기 어려워집니다. 따라서 **최대 1depth (댓글-대댓글)**로 제한했습니다.

```java
public void createReply(Long parentId, Member member, String content) {
    Comment parent = commentRepository.findById(parentId)
        .orElseThrow(() -> new CommentHandler(ErrorStatus.COMMENT_NOT_FOUND));

    // 깊이 검증: 대댓글의 대댓글은 불가
    if (parent.getDepth() >= 1) {
        throw new CommentHandler(ErrorStatus.COMMENT_DEPTH_EXCEEDED);
    }

    Comment reply = Comment.createReply(parent, member, content);
    parent.addReply(reply);  // 양방향 관계 설정
    commentRepository.save(reply);
}
```

## 8.2 상대 시간 표시 — "방금 전", "3시간 전"

댓글에 절대 시간 대신 상대 시간을 표시하여 사용자 경험을 향상시켰습니다.

```java
public String toTimeStamp() {
    LocalDateTime now = LocalDateTime.now();
    long years = ChronoUnit.YEARS.between(this.createdAt, now);
    long months = ChronoUnit.MONTHS.between(this.createdAt, now);
    long days = ChronoUnit.DAYS.between(this.createdAt, now);
    long hours = ChronoUnit.HOURS.between(this.createdAt, now);
    long minutes = ChronoUnit.MINUTES.between(this.createdAt, now);

    if (years > 0) return years + "년 전";
    if (months > 0) return months + "개월 전";
    if (days > 0) return days + "일 전";
    if (hours > 0) return hours + "시간 전";
    if (minutes > 0) return minutes + "분 전";
    return "방금 전";
}
```

## 8.3 댓글 작성 + 실시간 알림

댓글 작성 시 **게시글 작성자에게 FCM 푸시 알림**을 발송합니다.

```java
@Transactional
public CommentCreateResponse createComment(Long postId, Member member, String content) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

    Comment comment = CommentConverter.toComment(post, member, content);
    commentRepository.save(comment);

    // 자기 자신의 게시글에 댓글 달면 알림 보내지 않음
    if (!post.getMember().getId().equals(member.getId())) {
        // 알림 생성 (DB 저장)
        notificationCommandService.createNotification(
            post.getMember(),       // 수신자: 게시글 작성자
            member,                 // 발신자: 댓글 작성자
            NotificationType.COMMENT,
            post.getId()            // 이동 대상: 게시글
        );

        // FCM 푸시 알림 발송
        fcmService.sendNotification(
            post.getMember().getDeviceTokens(),
            member.getNickname() + "님이 댓글을 남겼습니다",
            content
        );
    }

    return CommentConverter.toCreateResponse(comment);
}
```

### 대댓글 알림 로직

대댓글의 경우 **부모 댓글 작성자에게** 알림을 발송합니다.

```java
@Transactional
public CommentCreateResponse createReply(Long parentId, Member member, String content) {
    Comment parent = commentRepository.findById(parentId)
        .orElseThrow(() -> new CommentHandler(ErrorStatus.COMMENT_NOT_FOUND));

    // 깊이 검증
    if (parent.getDepth() >= 1) {
        throw new CommentHandler(ErrorStatus.COMMENT_DEPTH_EXCEEDED);
    }

    Comment reply = CommentConverter.toReply(parent, member, content);
    parent.addReply(reply);
    commentRepository.save(reply);

    // 부모 댓글 작성자 ≠ 대댓글 작성자일 때만 알림
    if (!parent.getMember().getId().equals(member.getId())) {
        notificationCommandService.createNotification(
            parent.getMember(),         // 수신자: 부모 댓글 작성자
            member,                     // 발신자: 대댓글 작성자
            NotificationType.COMMENT_REPLY,
            parent.getPost().getId()    // 이동 대상: 게시글
        );

        fcmService.sendNotification(
            parent.getMember().getDeviceTokens(),
            member.getNickname() + "님이 답글을 남겼습니다",
            content
        );
    }

    return CommentConverter.toReplyCreateResponse(reply, member);
}
```

### 알림 정책

| 상황 | 알림 수신자 | 알림 유형 |
|------|-----------|----------|
| 댓글 작성 | 게시글 작성자 | COMMENT |
| 대댓글 작성 | 부모 댓글 작성자 | COMMENT_REPLY |
| 본인 게시글에 본인 댓글 | 알림 없음 | - |
| 본인 댓글에 본인 대댓글 | 알림 없음 | - |

## 8.4 댓글 목록 조회 — 커서 기반 페이지네이션

부모 댓글과 대댓글 각각에 독립적인 커서 기반 페이지네이션을 적용했습니다.

### 부모 댓글 조회

```java
public CursorDTO<CommentCreateResponse> getParentComments(
        Long postId, Long cursorId, int size, Long memberId) {

    List<Comment> comments = commentRepository
        .findParentCommentByCursor(postId, cursorId, size, memberId);

    List<CommentCreateResponse> responses = comments.stream()
        .map(comment -> CommentConverter.toCreateResponseWithChildExists(
            comment,
            comment.hasChild(),     // 대댓글 존재 여부
            comment.getReplyCount(), // 대댓글 수
            comment.isOwner(memberId) // 본인 댓글 여부
        ))
        .toList();

    return new CursorDTO<>(responses, hasNext, nextCursor);
}
```

### 대댓글 조회

```java
public CursorDTO<CommentCreateResponse> getChildComments(
        Long parentId, Long cursorId, int size, Long memberId) {

    List<Comment> replies = commentRepository
        .findRepliesByParentId(parentId, cursorId, size, memberId);

    // ... 커서 기반 페이지네이션 동일 패턴
}
```

### 응답에 포함되는 메타 정보

```json
{
  "commentId": 456,
  "content": "멋진 답변이네요!",
  "member": {
    "memberId": 789,
    "username": "user1",
    "profileImg": "https://..."
  },
  "depth": 0,
  "hasReplies": true,
  "replyCount": 3,
  "timeStamp": "2시간 전",
  "isOwner": false
}
```

`hasReplies`와 `replyCount`로 클라이언트가 "답글 3개 보기" 같은 UI를 별도 API 호출 없이 렌더링할 수 있습니다.

---

---

# 9. 좋아요 시스템 — 동시성 안전 설계

## 9.1 동시성 문제와 해결

여러 사용자가 동시에 같은 게시글에 좋아요를 누르면 **중복 좋아요**가 발생할 수 있습니다. 이를 **2중 방어 전략**으로 해결했습니다.

```
┌──────────────────────────────────────────────────┐
│               Layer 1: 애플리케이션 레벨 검증        │
│   existsByMemberAndPost() → 빠른 실패 (Fast Fail)  │
└──────────────────────┬───────────────────────────┘
                       │ 통과
                       ▼
┌──────────────────────────────────────────────────┐
│               Layer 2: DB 유니크 제약조건            │
│   UNIQUE(post_id, member_id) → 최종 안전장치        │
│   DataIntegrityViolationException 캐치             │
└──────────────────────────────────────────────────┘
```

### 구현 코드

```java
@Transactional
public PostLikeDto createLike(Member member, Long postId) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

    // Layer 1: 애플리케이션 레벨 중복 검사 (빠른 실패)
    if (postLikeRepository.existsByMemberAndPost(member, post)) {
        throw new LikeHandler(ErrorStatus.POST_LIKE_DUPLICATED);
    }

    // 좋아요 생성
    PostLike postLike = PostLike.create(post, member);

    try {
        // Layer 2: DB 저장 (유니크 제약조건이 최종 방어)
        postLikeRepository.save(postLike);
    } catch (DataIntegrityViolationException e) {
        // 동시성 경합 상황: Layer 1 통과 후 다른 스레드가 먼저 INSERT
        throw new LikeHandler(ErrorStatus.POST_LIKE_DUPLICATED);
    }

    // 좋아요 알림 (자기 게시글 제외)
    if (!post.getMember().equals(member)) {
        notificationCommandService.createNotification(
            post.getMember(), member, NotificationType.LIKE, post.getId()
        );
        fcmService.sendNotification(
            post.getMember().getDeviceTokens(),
            member.getNickname() + "님이 좋아요를 눌렀습니다",
            ""
        );
    }

    return new PostLikeDto(postId, true);
}
```

### 왜 2중 방어인가?

| 시나리오 | Layer 1만 | Layer 2만 | 2중 방어 (채택) |
|----------|----------|----------|---------------|
| 일반 중복 | 빠른 응답 | DB 쿼리 후 예외 | **빠른 응답** |
| 동시 요청 | **중복 발생** | 하나만 성공 | **하나만 성공** |
| 사용자 경험 | 빠름 | 느림 | **빠름** |
| 데이터 무결성 | 보장 안됨 | **보장** | **보장** |

- Layer 1: 대부분의 중복 요청을 빠르게 거부하여 불필요한 DB 작업 방지
- Layer 2: 동시성 경합(Race Condition)에서 데이터 무결성 보장

## 9.2 좋아요 취소

```java
@Transactional
public PostLikeDto deleteLike(Member member, Long postId) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

    postLikeRepository.deleteByPostAndMember(post, member);

    return new PostLikeDto(postId, false);
}
```

좋아요 취소 시에는 별도의 알림을 발송하지 않습니다. 불필요한 알림으로 사용자를 방해하지 않기 위한 의도적인 설계입니다.

---

---

# 10. 보안 설계 — JWT 인증 체계

## 10.1 JWT 인증 아키텍처

팀 리더로서 프로젝트 전체의 인증/인가 체계를 설계했습니다.

```
┌─────────┐    Authorization: Bearer {token}    ┌──────────┐
│ Client  │ ──────────────────────────────────► │ JwtFilter │
└─────────┘                                     └─────┬────┘
                                                      │
                                               ┌──────▼──────┐
                                               │ JwtProvider  │
                                               │ 토큰 검증     │
                                               └──────┬──────┘
                                                      │
                                           ┌──────────▼──────────┐
                                           │ PrincipalDetails     │
                                           │ Service              │
                                           │ DB에서 사용자 로드      │
                                           └──────────┬──────────┘
                                                      │
                                           ┌──────────▼──────────┐
                                           │ SecurityContext      │
                                           │ 인증 정보 저장         │
                                           └──────────┬──────────┘
                                                      │
                                              ┌───────▼───────┐
                                              │  Controller   │
                                              │ @CurrentMember│
                                              └───────────────┘
```

## 10.2 토큰 구조

```
Access Token:
{
  "sub": "username",
  "id": 123,
  "tokenType": "access",
  "iat": 1692000000,
  "exp": 1692003600
}

Refresh Token:
{
  "sub": "username",
  "id": 123,
  "tokenType": "refresh",
  "iat": 1692000000,
  "exp": 1694592000
}
```

- **Access Token**: 짧은 수명, 매 요청에 사용
- **Refresh Token**: 긴 수명, Redis에 저장하여 서버 측 폐기 가능

## 10.3 Redis 기반 토큰 관리

```
로그인 시:
  Redis SET username → refreshToken (TTL: refreshExpiration)

토큰 재발급 시:
  Redis GET username → 저장된 refreshToken과 비교
  새 accessToken + refreshToken 발급
  Redis SET username → newRefreshToken

로그아웃 시:
  Redis SET token → "logout" (TTL: accessToken 남은 수명)
  Redis DELETE username (refreshToken 삭제)
```

### 블랙리스트 기반 로그아웃

JWT는 stateless이므로 토큰 자체를 무효화할 수 없습니다. 따라서 Redis에 **로그아웃된 토큰을 블랙리스트**로 저장하여, JwtFilter에서 블랙리스트 확인 후 요청을 거부합니다.

## 10.4 @CurrentMember 커스텀 어노테이션

매 요청에서 인증된 사용자를 편리하게 주입받기 위한 커스텀 어노테이션을 설계했습니다.

```java
// 어노테이션 정의
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMember {}

// 리졸버 구현
public class CurrentMemberResolver implements HandlerMethodArgumentResolver {
    @Override
    public Object resolveArgument(...) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        PrincipalDetails details = (PrincipalDetails) auth.getPrincipal();
        return details.getMember();
    }
}

// 컨트롤러에서 사용
@GetMapping("/my-home")
public ApiResponse<?> getMyHome(@CurrentMember Member member) {
    return ApiResponse.onSuccess(memberQueryService.getMyHome(member));
}
```

이 패턴으로 모든 컨트롤러에서 **한 줄의 코드로** 현재 인증된 사용자를 가져올 수 있습니다.

---

---

# 11. 공통 모듈 설계

## 11.1 표준 API 응답 형식

모든 API가 일관된 응답 형식을 사용하도록 `ApiResponse` 래퍼를 설계했습니다.

```java
@JsonPropertyOrder({"isSuccess", "code", "message", "result"})
public class ApiResponse<T> {
    private final boolean isSuccess;
    private final String code;
    private final String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private T result;

    // 성공 응답
    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, "2000", "OK", result);
    }

    // 실패 응답
    public static <T> ApiResponse<T> onFailure(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}
```

### 성공 응답 예시

```json
{
  "isSuccess": true,
  "code": "2000",
  "message": "OK",
  "result": {
    "postId": 123,
    "status": "PUBLISHED"
  }
}
```

### 실패 응답 예시

```json
{
  "isSuccess": false,
  "code": "4041",
  "message": "해당 게시글을 찾을 수 없습니다.",
  "result": null
}
```

## 11.2 글로벌 예외 처리

```java
@RestControllerAdvice
public class ExceptionAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ApiResponse<?>> handleGeneralException(GeneralException e) {
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(ApiResponse.onFailure(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        // DTO 유효성 검증 실패 처리
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleAll(Exception e) {
        // 예상치 못한 예외 처리 (500 Internal Server Error)
    }
}
```

모든 예외가 일관된 `ApiResponse` 형식으로 클라이언트에 전달되어, **프론트엔드 개발자가 예측 가능한 에러 처리**를 할 수 있습니다.

## 11.3 도메인별 예외 핸들러

```
GeneralException (추상 부모)
├── PostHandler       → POST_NOT_FOUND, NOT_POST_OWNER, POST_ALREADY_PUBLISHED, ...
├── CommentHandler    → COMMENT_NOT_FOUND, COMMENT_DEPTH_EXCEEDED, ...
├── LikeHandler       → POST_LIKE_DUPLICATED
├── AuthHandler       → LOGIN_FAILED, TOKEN_EXPIRED, ...
├── MemberHandler     → MEMBER_NOT_FOUND, DUPLICATE_USERNAME, ...
└── ...
```

각 도메인별 예외 클래스를 분리하여 **에러 추적과 디버깅을 용이**하게 했습니다.

---

---

# 12. 기술적 성과 및 문제 해결

## 12.1 해결한 기술적 도전

### 1. 모바일 사진 회전 문제
- **문제**: 스마트폰으로 촬영한 세로 사진이 가로로 보이는 현상
- **원인**: EXIF orientation 메타데이터가 처리되지 않아 발생
- **해결**: metadata-extractor 라이브러리로 EXIF 방향 추출 후 업로드 시점에 픽셀 데이터를 실제 회전하여 저장
- **결과**: 모든 플랫폼에서 일관된 이미지 방향 보장

### 2. 복잡한 피드 가시성 필터링
- **문제**: 공개범위(전체/서클/비공개) × 팔로우 관계 × 서클 관계의 복합 조건
- **해결**: QueryDSL의 BooleanBuilder로 동적 조건을 타입 안전하게 조합
- **결과**: 단일 쿼리로 모든 가시성 규칙을 처리

### 3. N+1 쿼리 문제
- **문제**: 피드 조회 시 게시글마다 답변/이미지를 개별 조회하면 쿼리 폭증
- **해결**: 게시글 조회 → 답변 배치 조회 (IN 절) → 메모리 그룹핑 패턴 적용
- **결과**: 피드 크기와 무관하게 2~3개의 쿼리로 고정

### 4. 좋아요 동시성 문제
- **문제**: 여러 사용자가 동시에 좋아요 시 중복 데이터 발생 가능
- **해결**: 애플리케이션 레벨 검증 + DB 유니크 제약조건의 2중 방어
- **결과**: Race Condition에서도 데이터 무결성 보장

### 5. 임시 저장 데이터 관리
- **문제**: 작성 중 이탈한 임시 저장 데이터가 무한히 쌓이는 문제
- **해결**: 배치 스케줄러로 오래된 임시 저장 게시글을 S3 파일과 함께 자동 정리
- **결과**: 스토리지 비용 절감, 데이터 위생 유지

## 12.2 팀 리더로서의 기여

### 아키텍처 가이드
- Clean Architecture + CQRS 패턴을 설계하고 팀 전체에 적용
- 패키지 구조, 네이밍 컨벤션, 코딩 규칙을 문서화하여 팀원들에게 가이드
- 코드 리뷰를 통해 아키텍처 일관성 유지

### 인프라 원스톱 구축
- AWS 환경 (EC2, RDS, S3) 설정부터 Docker 컨테이너화, CI/CD 파이프라인까지 인프라 전체를 단독 구축
- 팀원들이 코드 작성에만 집중할 수 있는 환경 조성

### 기술적 의사결정 리드
- 커서 기반 페이지네이션 도입 결정
- CQRS 패턴 적용 범위 결정
- 이미지 처리 파이프라인 아키텍처 설계
- 알림 시스템 설계 (FCM + DB + 비동기)
- 보안 아키텍처 설계 (JWT + Redis + OAuth2)

---

---

# 기술 스택 상세

| 분류 | 기술 | 버전 | 용도 |
|------|------|------|------|
| Language | Java | 17 | 메인 언어 |
| Framework | Spring Boot | 3.5.3 | 애플리케이션 프레임워크 |
| ORM | Spring Data JPA | - | 데이터 접근 추상화 |
| Query | QueryDSL | 5.0.0 | 타입 안전 동적 쿼리 |
| Database | MySQL | - | 메인 데이터베이스 (AWS RDS) |
| Cache | Redis | Lettuce | 토큰 저장, 캐싱 |
| Security | Spring Security | - | 인증/인가 프레임워크 |
| JWT | jjwt | 0.12.3 | 토큰 발급/검증 |
| Cloud | AWS S3 | - | 이미지 저장소 |
| Cloud | AWS EC2 | - | 애플리케이션 서버 |
| Cloud | AWS RDS | - | 관리형 데이터베이스 |
| Push | Firebase FCM | 9.5.0 | 모바일 푸시 알림 |
| Email | Spring Mail | - | 이메일 인증 |
| Image | metadata-extractor | 2.18.0 | EXIF 메타데이터 처리 |
| Docs | SpringDoc OpenAPI | 2.7.0 | API 문서 자동화 (Swagger) |
| Monitor | Spring Actuator | - | 상태 모니터링 |
| Monitor | Micrometer Prometheus | - | 메트릭 수집 |
| Container | Docker | - | 애플리케이션 컨테이너화 |
| CI/CD | GitHub Actions | - | 자동 빌드/배포 |
| Build | Gradle | - | 빌드 도구 |
