package com.coredisc.presentation.dto.post;

import com.coredisc.domain.common.enums.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class PostResponseDTO {

    @Getter
    @Builder
    public static class PostLikeDto {
       private Long postId;
       private boolean liked;

    }

    @Getter
    @Builder
    public static class CreatePostResultDto {
        private Long postId;
        private Long memberId;

        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate selectedDate;

        private PostStatus status; //TEMP
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
        private LocalDateTime createdAt;
    }

    /**
     * 임시 today_question_dto
     */
    @Getter
    @Builder
    public static class TodayQuestionDto {
        private Long questionId;
        private String publicity; // PERSONAL 또는 OFFICIAL
        private Boolean isAnswered; //답변 완료 여부
        private AnswerType answerType; // 답변 타입 (있는 경우)
    }

    @Getter
    @Builder
    public static class PublishResultDto {
        private Long postId;
        private PostStatus status;
        private LocalDateTime publishedAt;
    }


    @Getter
    @Builder
    public static class ImageAnswerDto {
        private String imageUrl;
        private String thumbnailUrl;
        private String originalFileName;
    }

    @Getter
    @Builder
    public static class TextAnswerDto {
        private String content;
    }

    @Getter
    @Builder
    public static class AnswerResultDto {
        private Long answerId;
        private Integer questionOrder;
        private AnswerType answerType;
        private ImageAnswerDto imageAnswer;
        private TextAnswerDto textAnswer;
    }

    /**
     * 임시저장 게시글 상세 응답 DTO (postId로 답변들 조회)
     */
    @Builder
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL) // null 값인 필드 제외
    public static class TempPostDetailDto {
        private Long postId;
        private PostStatus status;
        private List<TempAnswerDto> answers;
    }

    /**
     * 임시저장 답변 DTO (questionOrder 기반)
     */
    @Builder
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL) // null 값인 필드 제외
    public static class TempAnswerDto {
        private Long answerId;              // 답변 ID (없으면 null)
        private Integer questionOrder;      // 질문 순서 1,2,3,4
        private AnswerType answerType;      // 답변 타입 (TEXT, IMAGE)
        private String textContent;         // 텍스트 답변 내용
        private String imageUrl;           // 이미지 URL
        private Boolean isAnswered;        // 답변 완료 여부
        private LocalDateTime updatedAt;   // 마지막 수정 시간
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostFeedResponseDTO {
        private List<PostSummary> posts;
        private Long nextCursor;
        private Boolean hasNext;

        @Getter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class PostSummary {
            private Long postId;
            private MemberInfo member;
            private LocalDate selectedDate;
            private Answer answer;  // 답변 1개 조회
            private PublicityType publicity; // 공개 여부 -> 전채 공개, 친한 친구 공개 여부 -> OFFICIAL OR CIRCLE

            @Getter
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public static class MemberInfo {
                private Long memberId;
                private String username;
                private String profileImg;
            }

            @Getter
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public static class Answer {
                private Long answerId;
                private String questionContent;
                private AnswerType answerType;
                private ImageAnswer imageAnswer;  // IMAGE 타입일 때만
                private TextAnswer textAnswer;    // TEXT 타입일 때만

                @Getter
                @Builder
                @NoArgsConstructor
                @AllArgsConstructor
                public static class ImageAnswer {
                    private String thumbnailUrl;
                }

                @Getter
                @Builder
                @NoArgsConstructor
                @AllArgsConstructor
                public static class TextAnswer {
                    private String content;
                }
            }
        }
    }

    @Getter
    @Builder
    public static class PostDetailDto {
        private Long postId;
        private MemberInfo member;
        private LocalDate selectedDate;
        private PublicityType publicity;
        private List<PostFeedResponseDTO.PostSummary.Answer> answers;
        private SelectiveDiary selectiveDiary;
        private Boolean isLiked;
        private Boolean isOwner;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        @Getter
        @Builder
        public static class MemberInfo {
            private Long memberId;
            private String username;
            private String profileImg;
        }

        @Getter
        @Builder
        public static class Answer {
            private Long answerId;
            private String questionContent;
            private AnswerType answerType;
            private ImageAnswer imageAnswer;
            private TextAnswer textAnswer;

            @Getter
            @Builder
            public static class ImageAnswer {
                private String imageUrl;
                private String thumbnailUrl;
            }

            @Getter
            @Builder
            public static class TextAnswer {
                private String content;
            }
        }

        @Getter
        @Builder
        public static class SelectiveDiary {
            private DiaryWho who;
            private DiaryWhere where;
            private DiaryWhat what;
            private String mood;
        }

        @Getter
        @Builder
        public static class Statistics {
            private Integer likeCount;
            private Integer commentCount;
            private Integer viewCount;
        }
    }

    /**
     * 오늘 날짜 기준 임시저장된 게시글들만 조회 - 응답
     */
    @Builder
    @Getter
    public static class TempAnswerPostDto {
        private List<TempPostSummary> tempPosts;

        @Getter
        @Builder
        public static class TempPostSummary {
            private Long postId;
            private String lastModified;  // "2025-07-07 13:10" 형식으로 포맷된 날짜
        }
    }



}
