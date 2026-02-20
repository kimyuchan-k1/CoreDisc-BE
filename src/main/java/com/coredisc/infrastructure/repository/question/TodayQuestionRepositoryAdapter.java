package com.coredisc.infrastructure.repository.question;


import com.coredisc.domain.member.Member;
import com.coredisc.domain.personalQuestion.PersonalQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TodayQuestionRepositoryAdapter  implements TodayQuestionRepository {

    private final JpaTodayQuestionRepository jpaTodayQuestionRepository;

    @Override
    public TodayQuestion save(TodayQuestion todayQuestion) {
        return jpaTodayQuestionRepository.save(todayQuestion);
    }

    @Override
    public Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDateBetween(Member member, Integer questionOrder, LocalDate startDate, LocalDate endDate) {
        return jpaTodayQuestionRepository.findFirstByMemberAndQuestionOrderAndSelectedDateBetween(member, questionOrder, startDate, endDate);
    }

    @Override
    public Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDate(Member member, Integer questionOrder, LocalDate selectedDate) {
        return jpaTodayQuestionRepository.findFirstByMemberAndQuestionOrderAndSelectedDate(member, questionOrder, selectedDate);
    }
    @Override
    public boolean existsByPersonalQuestion(PersonalQuestion personalQuestion) {
        return jpaTodayQuestionRepository.existsByPersonalQuestion(personalQuestion);
    }

    @Override
    public List<TodayQuestion> findAllByQuestionOrderAndSelectedDate(int questionOrder, LocalDate targetDate) {
        return jpaTodayQuestionRepository.findAllByQuestionOrderAndSelectedDate(questionOrder, targetDate);
    }

    @Override
    public List<TodayQuestion> findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(List<Long> memberIds, List<Integer> questionOrders, LocalDate startDate, LocalDate endDate) {
        return jpaTodayQuestionRepository.findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(memberIds, questionOrders, startDate, endDate);
    }

}
