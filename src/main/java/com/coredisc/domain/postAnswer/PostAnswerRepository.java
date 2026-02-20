package com.coredisc.domain.postAnswer;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PostAnswerRepository {

    PostAnswer save(PostAnswer postAnswer);
    Optional<PostAnswer> findById(Long id);
    void delete(PostAnswer postAnswer);

    // 특정 게시글의 특정 질문 순서 답변 조회
    Optional<PostAnswer> findByPostAndQuestionOrder(Post post, Integer questionOrder);

    // 특정 게시글의 모든 답변 조회 (questionOrder 순서대로)
    List<PostAnswer> findByPostOrderByQuestionOrder(Post post);

    boolean existsByPostMemberAndAnswerOrderAndPostCreatedAtBetween(
            Member member,
            int answerOrder,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay
    );

    List<Object[]> findAnswerOrdersByMemberIdsAndCreatedAtBetween(
            List<Long> memberIds,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay
    );
}
