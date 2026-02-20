package com.coredisc.domain.device;

import com.coredisc.domain.common.BaseEntity;
import com.coredisc.domain.member.Member;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(indexes = {
    @Index(name = "idx_device_member_active", columnList = "member_id, is_active")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Device extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 255)
    private String token;

    @Column(name = "device_type", nullable = false, length = 20)
    @Builder.Default
    private String deviceType = "iOS";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    public void updateActive(boolean isActive) {
        this.isActive = isActive;
    }

}