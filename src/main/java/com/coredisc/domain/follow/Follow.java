package com.coredisc.domain.follow;

import com.coredisc.domain.common.BaseEntity;
import com.coredisc.domain.member.Member;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(indexes = {
    @Index(name = "idx_follow_follower_circle", columnList = "follower_id, is_circle")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Follow extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 팔로우 요청한 주체 멤버
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id", nullable = false)
    private Member follower;

    // 팔로우 대상 멤버
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "following_id", nullable = false)
    private Member following;

    @Column(name = "is_circle", nullable = false)
    @Builder.Default
    private boolean isCircle = false;

    public void updateCircle(boolean isCircle) {
        this.isCircle = isCircle;
    }
}