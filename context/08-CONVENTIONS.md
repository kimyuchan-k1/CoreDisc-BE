# 코딩 컨벤션 & 패턴

## 네이밍 규칙

### 서비스 클래스
- CQRS 패턴: `{Domain}CommandService` / `{Domain}QueryService`
- 인터페이스 + 구현체: `{Domain}CommandService` (interface) + `{Domain}CommandServiceImpl` (class)

### 컨트롤러
- `{Domain}Controller` - REST 컨트롤러
- `{Domain}ControllerDocs` - Swagger 문서 인터페이스

### DTO
- 요청: `{Domain}{Action}Request`
- 응답: `{Domain}{Action}Response`
- 패키지: `presentation/dto/{domain}/`

### 엔티티
- 패키지: `domain/{domain}/`
- 클래스명: 도메인 명사 (Member, Post, Comment ...)

### 레포지토리
- JPA: `{Domain}Repository` (interface)
- QueryDSL: `{Domain}RepositoryCustom` + `{Domain}RepositoryCustomImpl`

## API 응답 패턴

### 표준 응답 래퍼
```java
public class ApiResponse<T> {
    private boolean isSuccess;
    private String code;
    private String message;
    private T result;
}
```

### 성공/실패 코드
- 성공: `SuccessStatus` enum
- 실패: 도메인별 `ErrorStatus` enum

## 예외 처리 패턴

### 도메인별 예외 핸들러
- `common/exception/handler/` 아래 도메인별 예외 클래스
- GlobalExceptionHandler에서 통합 처리
- 커스텀 에러 코드와 메시지 반환

## 페이지네이션 패턴

### 커서 기반 (Cursor-based)
- 무한 스크롤 지원
- `cursor` (마지막 ID) + `size` (페이지 크기)
- 응답에 `hasNext` 포함

## 엔티티 변환 패턴

### Converter 클래스
- `common/converter/` 아래 도메인별 Converter
- Entity -> DTO, DTO -> Entity 변환 담당
- 정적 메서드 사용

## 감사(Auditing) 패턴

### BaseEntity
- `@MappedSuperclass`
- `createdAt`, `updatedAt` 자동 관리
- `@EnableJpaAuditing` 활성화

## QueryDSL 사용 패턴

### 복잡한 조회 쿼리
- `{Domain}RepositoryCustom` 인터페이스 정의
- `{Domain}RepositoryCustomImpl`에서 QueryDSL로 구현
- 동적 조건, 정렬, 페이지네이션 처리

## 이미지 처리 패턴

### S3 업로드 흐름
1. MultipartFile 수신
2. EXIF orientation 추출
3. 원본 S3 업로드
4. 썸네일 생성 (리사이즈) 후 S3 업로드
5. URL 반환

## Git 커밋 메시지 컨벤션

```
Feature: 기능 추가 설명
Refactor: 리팩토링 설명
Fix: 버그 수정 설명
```

한글 커밋 메시지 사용
