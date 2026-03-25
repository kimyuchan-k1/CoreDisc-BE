package com.coredisc.infrastructure.repository.postLike;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JpaPostLikeRepository extends JpaRepository<PostLike,Long> {

    boolean existsByMemberAndPost(Member member, Post post);

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    void deleteByPostAndMember(Post post, Member member);

    @Query("SELECT pl FROM PostLike pl WHERE pl.member.id = :memberId AND pl.post.member.id = :authorId")
    List<PostLike> findAllByMemberIdAndPostMemberId(@Param("memberId") Long memberId, @Param("authorId") Long authorId);

    @Modifying
    @Query("DELETE FROM PostLike pl WHERE pl.member.id = :memberId AND pl.post.member.id = :authorId")
    int deleteAllByMemberIdAndPostMemberId(@Param("memberId") Long memberId, @Param("authorId") Long authorId);
}
