package com.coredisc.infrastructure.repository.answer;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;
import com.coredisc.domain.postAnswer.PostAnswerRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class PostAnswerRepositoryAdaptor implements PostAnswerRepository {

    private final JpaPostAnswerRepository jpaPostAnswerRepository;

    @Override
    public PostAnswer save(PostAnswer postAnswer) {
        return jpaPostAnswerRepository.save(postAnswer);
    }

    @Override
    public Optional<PostAnswer> findById(Long id) {
        return jpaPostAnswerRepository.findById(id);
    }

    @Override
    public void delete(PostAnswer postAnswer) {
        jpaPostAnswerRepository.delete(postAnswer);

    }

    /**
     * questionOrder 에 해당하는 답변 찾기 - 오늘
     */
    @Override
    public Optional<PostAnswer> findByPostAndQuestionOrder(Post post, Integer questionOrder) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();


        return jpaPostAnswerRepository.findPostAnswerByPostAndAnswerOrderAndCreatedAtBetween(
                post,
                questionOrder,
                startOfDay,
                endOfDay
        );
    }

    @Override
    public List<PostAnswer> findByPostOrderByQuestionOrder(Post post) {
        return List.of();
    }

    @Override
    public boolean existsByPostMemberAndAnswerOrderAndPostCreatedAtBetween(Member member, int order, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return jpaPostAnswerRepository.existsByPostMemberAndAnswerOrderAndPostCreatedAtBetween(member, order, startOfDay, endOfDay);
    }

    @Override
    public List<Object[]> findAnswerOrdersByMemberIdsAndCreatedAtBetween(List<Long> memberIds, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return jpaPostAnswerRepository.findAnswerOrdersByMemberIdsAndCreatedAtBetween(memberIds, startOfDay, endOfDay);
    }

}
