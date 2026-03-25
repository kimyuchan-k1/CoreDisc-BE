# CoreDisc-BE 프로젝트 개요

## 서비스 소개

**CoreDisc**는 매일 4개의 질문에 답변하며 자신의 "Core"를 발견하는 자기발견 저널링 SNS 서비스입니다.
간단한 기록과 행동 분석을 결합하여 더 깊은 자기 인식을 제공합니다.

## 기술 스택

| 구분 | 기술 |
|------|------|
| **언어** | Java 17 |
| **프레임워크** | Spring Boot 3.5.3 |
| **빌드 도구** | Gradle |
| **ORM** | Spring Data JPA + QueryDSL 5.0.0 |
| **데이터베이스** | MySQL (AWS RDS) |
| **캐싱** | Redis (Lettuce) |
| **클라우드** | AWS (S3, EC2, RDS) |
| **인증** | JWT (jjwt 0.12.3) + OAuth2 (Kakao, Naver) |
| **푸시 알림** | Firebase Cloud Messaging (FCM) |
| **이메일** | Spring Mail (SMTP) |
| **API 문서** | SpringDoc OpenAPI 2.7.0 (Swagger) |
| **모니터링** | Spring Actuator + Micrometer Prometheus |
| **이미지 처리** | metadata-extractor 2.18.0 (EXIF 처리) |

## 팀 구성

- 김유찬, 강도경, 박세은, 백지은, 황민지 (백엔드 5명)

## 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 프로덕션 배포 |
| `develop` | 개발/스테이징 |
| `feature/#이슈번호` | 기능 개발 |
| `refactor/#이슈번호` | 리팩토링 |

## 버전

- v0.0.1-SNAPSHOT (활발한 개발 중)
