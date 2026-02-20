package com.coredisc.infrastructure.repository.post;


import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.post.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import java.time.LocalDateTime;

public interface JpaPostRepository extends JpaRepository<Post, Long> {

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
}




