package com.coredisc.domain.post;


import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.Comment;
import com.coredisc.domain.common.BaseEntity;
import com.coredisc.domain.common.enums.*;
import com.coredisc.domain.member.Member;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = {
    @Index(name = "idx_post_member_status", columnList = "member_id, status"),
    @Index(name = "idx_post_status_created", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Post extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PublicityType publicity;  // PUBLIC, PRIVATE, CIRCLE

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PostStatus status = PostStatus.TEMP;  // TEMP, PUBLISHED

    // 선택형 일기 -> enum 타입으로 관리
    @Column(name = "daily_who", length = 50)
    @Enumerated(value = EnumType.STRING)
    private DiaryWho dailyWho;

    @Column(name = "daily_where", length = 50)
    @Enumerated(value = EnumType.STRING)
    private DiaryWhere dailyWhere;

    @Column(name = "daily_what", length = 50)
    @Enumerated(value = EnumType.STRING)
    private DiaryWhat dailyWhat;

    @Column(name = "daily_detail", length = 200)
    private String dailyDetail;  // mood로 변경 고려

    // 통계
    @Column(name = "like_count")
    @Builder.Default
    private Integer likeCount = 0;

    @Column(name = "comment_count")
    @Builder.Default
    private Integer commentCount = 0;

    @Column(name = "view_count")
    @Builder.Default
    private Integer viewCount = 0;

    // 연관관계
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostAnswer> answers = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL , orphanRemoval = true)
    private List<PostLike> postLikes = new ArrayList<>();

    // 비지니스 로직

    // 발행
    public void publish()
    {
        this.status = PostStatus.PUBLISHED;
    }

    // 임시 저장 여부확인
    public boolean isTemp() {
        return this.status == PostStatus.TEMP;
    }

    // 발행 여부 확인
    public boolean isPublished() {
        return this.status == PostStatus.PUBLISHED;
    }

    // 선택형 일기 저장
    public void updateSelectiveDiary(DiaryWho who, DiaryWhere where, DiaryWhat what, String detail) {
        this.dailyWho = who;
        this.dailyWhere = where;
        this.dailyWhat  = what;
        this.dailyDetail = detail;
    }

    // 공개범위 설정
    public void updatePublicity(PublicityType publicityType) {
        this.publicity = publicityType;
    }

    /**
     * 멤버 소유권 확인
     */
    public void validateOwnership(Member member) {
        if (!this.member.getId().equals(member.getId())) {
            throw new PostHandler(ErrorStatus.NOT_POST_OWNER);
        }
    }

}
