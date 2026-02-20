package com.coredisc.infrastructure.repository.question;

import com.coredisc.domain.personalQuestion.PersonalQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.member.Member;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JpaTodayQuestionRepository extends JpaRepository<TodayQuestion,Long> {

    List<TodayQuestion> findByMember(Member member);

    Optional<TodayQuestion> findFirstByMemberAndQuestionOrderAndSelectedDateBetween(Member member, Integer questionOrder, LocalDate startDate, LocalDate endDate);

    Optional<TodayQuestion> findFirstByMemberAndQuestionOrderAndSelectedDate(Member member, Integer questionOrder, LocalDate selectedDate);

    boolean existsByPersonalQuestion(PersonalQuestion personalQuestion) ;

    List<TodayQuestion> findAllByQuestionOrderAndSelectedDate(int questionOrder, LocalDate targetDate);

    @EntityGraph(attributePaths = {"officialQuestion", "personalQuestion"})
    List<TodayQuestion> findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(List<Long> memberIds, List<Integer> questionOrders, LocalDate startDate, LocalDate endDate);
}
