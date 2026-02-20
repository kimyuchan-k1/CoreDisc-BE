package com.coredisc.domain;

import com.coredisc.domain.common.BaseEntity;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = {
    @Index(name = "idx_comment_post_depth_id", columnList = "post_id, depth, id"),
    @Index(name = "idx_comment_parent_id", columnList = "parent_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Comment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;

    // 0 : 댓글 ,1: 대댓글
    @Column(nullable = false)
    @Builder.Default
    private Integer depth =0;

    // 연관관계 매핑
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Comment> replies = new ArrayList<>();


    // 댓글사용자 본인 확인 용
    public boolean isOwner(Long memberId) {
        return this.member.getId().equals(memberId);
    }

    public void addReply(Comment reply) {
        replies.add(reply);
        reply.parent = this;
        reply.depth = this.depth + 1;
    }

    public boolean hasChild() {
        return !this.replies.isEmpty();
    }


    // 답글 수 조회
    public Integer getReplyCount() {
        return this.replies.size();

    }

    public String toTimeStamp() {

        Period period = Period.between(this.createdAt.toLocalDate(), LocalDateTime.now().toLocalDate());
        long years = period.getYears();
        long months = period.getMonths();
        long days = period.getDays();

        Duration duration = Duration.between(this.createdAt, LocalDateTime.now());
        long hours = duration.toHours();
        long minutes = duration.toMinutes();
        long seconds = duration.getSeconds();

        if (years > 0) {
            return years + "년 전";
        } else if (months > 0) {
            return months + "개월 전";
        } else if (days > 0) {
            return days + "일 전";
        } else if (hours > 0) {
            return hours + "시간 전";
        } else if (minutes > 0) {
            return minutes + "분 전";
        } else {
            return "방금 전";
        }
    }

}

