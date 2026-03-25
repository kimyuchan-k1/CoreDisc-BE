package com.coredisc.domain.reportStats;

import com.coredisc.domain.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "daily_answer_hour_stat",
        indexes = @Index(name = "idx_member_date_hour", columnList = "memberId, answerDate, hourOfDay"),
        uniqueConstraints = @UniqueConstraint(name = "uq_member_date_hour", columnNames = {"member_id", "answer_date", "hour_of_day"}))

public class DailyAnswerHourStat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private LocalDate answerDate;

    @Column(nullable = false)
    @Min(0) @Max(23)
    private int hourOfDay;

    @Column(nullable = false)
    @Min(0)
    private int answerCount;
}