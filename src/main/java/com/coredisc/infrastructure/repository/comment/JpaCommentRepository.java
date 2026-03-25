package com.coredisc.infrastructure.repository.comment;

import com.coredisc.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JpaCommentRepository extends JpaRepository<Comment,Long> {

    // 게시글의 전체 댓글 수
    Long countByPostId(Long postId);

    // 특정 댓글의 대댓글 수
    Long countByParentId(Long parentId);

    @Query("SELECT c FROM Comment c WHERE c.member.id = :memberId AND c.post.member.id = :authorId AND c.isDeleted = false")
    List<Comment> findAllActiveByMemberIdAndPostMemberId(@Param("memberId") Long memberId, @Param("authorId") Long authorId);

    @Modifying
    @Query("UPDATE Comment c SET c.isDeleted = true WHERE c.member.id = :memberId AND c.post.member.id = :authorId AND c.isDeleted = false")
    int softDeleteAllByMemberIdAndPostMemberId(@Param("memberId") Long memberId, @Param("authorId") Long authorId);
}
