# 외부 의존성 처리 전략

> 부하 테스트와 로컬 개발을 위해 외부 서비스(FCM, S3, Mail)를
> Mock 없이 깔끔하게 대체하는 전략입니다.

---

## 방법 비교

| 방법 | 장점 | 단점 | 추천도 |
|------|------|------|:---:|
| **A. Profile + Stub 구현체** ✅ | 코드 변경 최소, 깔끔, 포트폴리오 가치 | 인터페이스 추출 필요 | ★★★★★ |
| B. @MockBean (테스트용) | 테스트에서만 사용 가능 | 부하 테스트(k6)에서 못 씀 | ★★★☆☆ |
| C. 주석 처리 | 가장 빠름 | 더럽고, 실수로 커밋 위험 | ★☆☆☆☆ |
| D. LocalStack + Embedded Redis | 가장 완벽한 환경 | 설정 복잡, 무거움 | ★★★☆☆ |
| E. Testcontainers | 통합 테스트에 적합 | 부하 테스트에는 과함 | ★★☆☆☆ |

**결론: 방법 A (Profile + Stub)를 메인으로 사용하고, Redis만 Docker로 실행**

---

## 서비스별 전략

```
FCM   → Profile Stub (로그만 출력, 실제 전송 안 함)
S3    → Profile Stub (로컬 파일 저장 또는 가짜 URL 반환)
Mail  → Profile Stub (로그만 출력)
Redis → Docker 실행 (docker-compose up redis)
MySQL → Docker 또는 로컬 MySQL
```

**Redis/MySQL을 Stub으로 안 하는 이유:**
- Redis는 캐싱/토큰 관리의 핵심 → 실제 동작을 테스트해야 의미 있음
- MySQL은 쿼리 최적화 대상 → 실제 DB로 테스트해야 함
- 둘 다 Docker로 간단히 실행 가능

---

---

## 구현 방법: Step by Step

### Step 1: 인터페이스 추출

현재 `FcmService`, `AmazonS3Manager`, `MailService`가 구체 클래스로 직접 주입됩니다.
**인터페이스를 추출**하여 실제/Stub 구현체를 교체 가능하게 합니다.

#### FcmService 인터페이스 추출

```java
// 1. 인터페이스 생성
public interface FcmService {
    void sendNotificationToToken(String token, String title, String body, Map<String, String> data);
    boolean isTokenValid(String token);
}

// 2. 기존 클래스 → 인터페이스 구현체로 변경 (이름 변경)
@Service
@Profile("!local")  // local 프로필이 아닐 때만 활성화
public class FcmServiceImpl implements FcmService {
    // 기존 코드 그대로 유지
    @Override
    public void sendNotificationToToken(String token, String title, String body, Map<String, String> data) {
        // 실제 Firebase 전송 로직 (기존 코드)
    }

    @Override
    public boolean isTokenValid(String token) {
        // 실제 토큰 검증 로직 (기존 코드)
    }
}

// 3. Stub 구현체 생성
@Service
@Profile("local")  // local 프로필에서만 활성화
public class FcmServiceStub implements FcmService {
    private static final Logger log = LoggerFactory.getLogger(FcmServiceStub.class);

    @Override
    public void sendNotificationToToken(String token, String title, String body, Map<String, String> data) {
        log.info("[STUB] FCM 전송 생략 - title: {}, body: {}", title, body);
        // 실제 전송 안 함 — 로그만 출력
    }

    @Override
    public boolean isTokenValid(String token) {
        return true;  // 항상 유효하다고 반환
    }
}
```

**기존 비즈니스 코드 변경 없음!** FcmService 인터페이스를 주입받으므로 자동으로 교체됩니다.

#### AmazonS3Manager 인터페이스 추출

```java
// 1. 인터페이스 생성
public interface ImageStorageService {
    ImageUploadResult uploadFile(MultipartFile file, Long memberId);
    void deleteImage(String s3Key);
    void deleteImageByUrl(String imageUrl);
    void deleteImagesByUrls(List<String> imageUrls);
}

// 2. 기존 클래스 → 구현체
@Component
@Profile("!local")
public class AmazonS3Manager implements ImageStorageService {
    // 기존 코드 그대로
}

// 3. Stub 구현체
@Component
@Profile("local")
public class LocalImageStorageStub implements ImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalImageStorageStub.class);
    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public ImageUploadResult uploadFile(MultipartFile file, Long memberId) {
        String fakeKey = "user_" + memberId + "_" + counter.incrementAndGet();
        log.info("[STUB] 이미지 업로드 생략 - key: {}, size: {}KB",
                 fakeKey, file.getSize() / 1024);

        return ImageUploadResult.builder()
            .originalUrl("https://stub-s3.local/original/" + fakeKey + ".jpg")
            .thumbnailUrl("https://stub-s3.local/thumbnail/" + fakeKey + ".jpg")
            .originalKey(fakeKey)
            .originalFileName(file.getOriginalFilename())
            .build();
    }

    @Override
    public void deleteImage(String s3Key) {
        log.info("[STUB] 이미지 삭제 생략 - key: {}", s3Key);
    }

    @Override
    public void deleteImageByUrl(String imageUrl) {
        log.info("[STUB] 이미지 URL 삭제 생략 - url: {}", imageUrl);
    }

    @Override
    public void deleteImagesByUrls(List<String> imageUrls) {
        log.info("[STUB] 이미지 {} 건 삭제 생략", imageUrls.size());
    }
}
```

#### MailService 인터페이스 추출

```java
// 1. 인터페이스 생성
public interface MailService {
    void sendEmail(String email, EmailRequestType type);
}

// 2. 기존 클래스 → MailServiceImpl
@Service
@Profile("!local")
public class MailServiceImpl implements MailService { ... }

// 3. Stub
@Service
@Profile("local")
public class MailServiceStub implements MailService {
    private static final Logger log = LoggerFactory.getLogger(MailServiceStub.class);

    @Override
    public void sendEmail(String email, EmailRequestType type) {
        log.info("[STUB] 메일 전송 생략 - to: {}, type: {}", email, type);
    }
}
```

### Step 2: FirebaseConfig 프로필 분리

Firebase는 앱 시작 시 초기화되므로, **local 프로필에서는 아예 로드하지 않습니다.**

```java
@Configuration
@Profile("!local")  // local이 아닐 때만 Firebase 초기화
public class FirebaseConfig {
    @PostConstruct
    public void init() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            // 기존 초기화 코드
        }
    }
}
```

S3Config도 동일하게 처리:

```java
@Configuration
@Profile("!local")
public class S3Config {
    // 기존 S3 클라이언트 빈 설정
}

// MailConfig도 동일
@Configuration
@Profile("!local")
public class MailConfig {
    // 기존 SMTP 설정
}
```

### Step 3: 로컬용 application-local.yml 생성

```yaml
# src/main/resources/application-local.yml
spring:
  # MySQL (로컬 Docker 또는 원격)
  datasource:
    url: jdbc:mysql://localhost:3306/coredisc
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

  # Redis (docker-compose)
  data:
    redis:
      host: localhost
      port: 6379
      password:

  # JPA
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  # JWT (로컬 테스트용)
  jwt:
    secret: local-test-secret-key-that-is-long-enough-for-hs256
    token:
      access-expiration-time: 3600000    # 1시간
      refresh-expiration-time: 604800000  # 7일

# Actuator (모니터링)
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus

# 로깅
logging:
  level:
    com.coredisc: DEBUG
    org.hibernate.SQL: DEBUG
```

### Step 4: 실행 방법

```bash
# 1. Redis 실행
docker-compose up -d redis

# 2. 로컬 MySQL 실행 (Docker로)
docker run -d --name coredisc-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=coredisc \
  -p 3306:3306 \
  mysql:8.0

# 3. local 프로필로 앱 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 또는 IntelliJ에서:
# Run Configuration → VM Options: -Dspring.profiles.active=local
```

---

---

## 각 상황별 추천 조합

### 상황 1: 부하 테스트 (k6)

```
목적: 순수 서버 성능 측정 (외부 I/O 배제)

프로필: local
  FCM  → Stub (로그만)         ← 외부 I/O 제거
  S3   → Stub (가짜 URL 반환)  ← 외부 I/O 제거
  Mail → Stub (로그만)         ← 외부 I/O 제거
  Redis → 실제 Docker          ← 캐싱 성능 측정 필요
  MySQL → 실제 Docker          ← 쿼리 성능 측정 필요

이점:
  - FCM/S3 I/O가 빠져서 순수 비즈니스 로직 + DB + Redis 성능만 측정
  - 나중에 비동기 FCM/S3 적용 후 prod 프로필로 재측정하면 Before/After 비교 가능
```

### 상황 2: 쿼리 최적화 테스트

```
목적: N+1 해결, 인덱스 효과 측정

프로필: local + p6spy
  위와 동일 + SQL 로깅 활성화

추가 설정 (application-local.yml):
  decorator:
    datasource:
      p6spy:
        enable-logging: true
```

### 상황 3: 비동기 전환 후 통합 테스트

```
목적: S3 병렬 업로드, FCM 이벤트 기반 전환 검증

프로필: local
  FCM → Stub에 Thread.sleep(500) 추가하여 실제 지연 시뮬레이션
  S3  → Stub에 Thread.sleep(800) 추가하여 실제 지연 시뮬레이션

이점:
  - 실제 외부 서비스 없이도 비동기 효과를 측정 가능
  - sleep을 넣은 Stub → 비동기 전환 전후 응답 시간 차이 확인
```

```java
// 지연 시뮬레이션이 가능한 Stub 예시
@Component
@Profile("local")
public class LocalImageStorageStub implements ImageStorageService {

    @Value("${stub.s3.delay-ms:0}")  // 기본 0ms, 필요시 설정으로 변경
    private long delayMs;

    @Override
    public ImageUploadResult uploadFile(MultipartFile file, Long memberId) {
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
        }
        // 가짜 결과 반환
    }
}
```

```yaml
# 비동기 테스트 시 지연 시뮬레이션 활성화
stub:
  s3:
    delay-ms: 800   # S3 업로드 지연 시뮬레이션
  fcm:
    delay-ms: 500   # FCM 전송 지연 시뮬레이션
```

---

---

## 작업 체크리스트

### 인터페이스 추출 + Stub 생성

- [ ] **FcmService** 인터페이스 추출
  - [ ] `FcmService` 인터페이스 생성
  - [ ] 기존 `FcmService` → `FcmServiceImpl`로 리네임, `@Profile("!local")` 추가
  - [ ] `FcmServiceStub` 생성, `@Profile("local")` 추가
  - [ ] 기존 주입부(`PostLikeCommandServiceImpl`, `CommentCommandServiceImpl` 등) 확인 — 타입이 인터페이스로 바뀌므로 자동 호환

- [ ] **ImageStorageService** 인터페이스 추출
  - [ ] `ImageStorageService` 인터페이스 생성
  - [ ] `AmazonS3Manager`에 `implements ImageStorageService`, `@Profile("!local")` 추가
  - [ ] `LocalImageStorageStub` 생성, `@Profile("local")` 추가
  - [ ] 기존 주입부 확인 — 타입을 `ImageStorageService`로 변경

- [ ] **MailService** 인터페이스 추출
  - [ ] `MailService` 인터페이스 생성 (이미 인터페이스면 확인)
  - [ ] 기존 구현체에 `@Profile("!local")` 추가
  - [ ] `MailServiceStub` 생성, `@Profile("local")` 추가

### Config 프로필 분리

- [ ] `FirebaseConfig`에 `@Profile("!local")` 추가
- [ ] `S3Config`에 `@Profile("!local")` 추가
- [ ] `MailConfig`에 `@Profile("!local")` 추가

### 로컬 환경 설정

- [ ] `application-local.yml` 생성
- [ ] `docker-compose.yml`에 MySQL 서비스 추가 (선택)
- [ ] `.gitignore`에 `application-local.yml` 추가 (비밀번호 노출 방지)

### 실행 확인

- [ ] `--spring.profiles.active=local`로 앱 시작 성공 확인
- [ ] 로그에 `[STUB]` 메시지 출력 확인
- [ ] 피드 조회 API 정상 동작 확인
- [ ] 이미지 업로드 API 정상 동작 확인 (가짜 URL 반환)

---

## 이 작업의 포트폴리오 가치

이 리팩토링 자체가 포트폴리오에 쓸 수 있는 좋은 소재입니다:

```
✅ "외부 서비스(FCM, S3, Mail)에 대한 인터페이스 추상화를 도입하여
   테스트 가능한 아키텍처로 개선했습니다."

✅ "Spring Profile 기반 환경 분리로 로컬/테스트/운영 환경에서
   외부 의존성을 자유롭게 교체할 수 있는 구조를 설계했습니다."

✅ "Stub 구현체에 지연 시뮬레이션을 넣어 실제 외부 서비스 없이도
   비동기 최적화 효과를 정량적으로 측정할 수 있었습니다."
```

이는 **Dependency Inversion Principle (DIP)** 과 **Strategy Pattern** 의 실전 적용 사례이며,
면접에서 "테스트 가능한 설계"에 대한 질문에 구체적으로 답변할 수 있는 소재가 됩니다.
