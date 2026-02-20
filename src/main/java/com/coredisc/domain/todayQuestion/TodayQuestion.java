package com.coredisc.domain.todayQuestion;

import com.coredisc.domain.common.BaseEntity;
import com.coredisc.domain.common.enums.QuestionType;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.PostAnswer;
import com.coredisc.domain.officialQuestion.OfficialQuestion;
import com.coredisc.domain.personalQuestion.PersonalQuestion;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = {
    @Index(name = "idx_tq_member_order_date", columnList = "member_id, question_order, selected_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class TodayQuestion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate selectedDate;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(10)")
    private QuestionType questionType;

    @Column(nullable = false)
    private Integer questionOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "official_question_id")
    private OfficialQuestion officialQuestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "personal_question_id")
    private PersonalQuestion personalQuestion;


    public String getQuestionContent() {
        if (this.officialQuestion != null) {
            return this.officialQuestion.getContents();
        } else if (this.personalQuestion != null) {
            return this.personalQuestion.getContent();
        } else {
            return null;
        }
    }
}

