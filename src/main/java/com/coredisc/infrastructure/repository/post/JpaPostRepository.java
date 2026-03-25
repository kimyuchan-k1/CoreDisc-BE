package com.coredisc.infrastructure.repository.post;


import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.post.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import java.time.LocalDateTime;

public interface JpaPostRepository extends JpaRepository<Post, Long> {

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
    void incrementLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :postId")
    void incrementCommentCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = CASE WHEN p.commentCount > 0 THEN p.commentCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementCommentCount(@Param("postId") Long postId);

    boolean existsByMemberAndStatusAndCreatedAtBetween(Member member, PostStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay);

    long countByMemberAndStatus(Member member, PostStatus status);
    long countByMemberAndStatusAndPublicityIn(Member member, PostStatus status, List<PublicityType> publicityTypes);

    List<Post> findAllByMemberAndCreatedAtBetweenOrderByCreatedAtAsc(Member member, LocalDateTime start, LocalDateTime end);

    List<Post> findAllByStatusAndCreatedAtBefore(PostStatus status, LocalDateTime startOfDay);

    Page<Post> findAllByStatusAndCreatedAtBefore(PostStatus status, LocalDateTime startOfDay, Pageable pageable);

    @Query("""
        select distinct p.member.id
        from Post p
        where p.status = :status
          and p.createdAt >= :start
          and p.createdAt < :end
    """)
    List<Long> findDistinctMemberIdsByStatusAndCreatedAtBetween(
            @Param("status") PostStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount >= :amount THEN p.likeCount - :amount ELSE 0 END WHERE p.id = :postId")
    void decrementLikeCountByAmount(@Param("postId") Long postId, @Param("amount") int amount);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = CASE WHEN p.commentCount >= :amount THEN p.commentCount - :amount ELSE 0 END WHERE p.id = :postId")
    void decrementCommentCountByAmount(@Param("postId") Long postId, @Param("amount") int amount);
}




