package com.coredisc.infrastructure.repository.answer;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JpaPostAnswerRepository extends JpaRepository<PostAnswer,Long> {

    Optional<PostAnswer> findPostAnswerByPostAndAnswerOrderAndCreatedAtBetween(Post post, Integer answerOrder, LocalDateTime createdAtAfter, LocalDateTime createdAtBefore);

    boolean existsByPostMemberAndAnswerOrderAndPostCreatedAtBetween(
            Member member,
            int answerOrder,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT pa.post.member.id, pa.answerOrder
        FROM PostAnswer pa
        WHERE pa.post.member.id IN :memberIds
          AND pa.post.createdAt >= :start
          AND pa.post.createdAt < :end
    """)
    List<Object[]> findAnswerOrdersByMemberIdsAndCreatedAtBetween(
            @org.springframework.data.repository.query.Param("memberIds") List<Long> memberIds,
            @org.springframework.data.repository.query.Param("start") LocalDateTime start,
            @org.springframework.data.repository.query.Param("end") LocalDateTime end
    );
}