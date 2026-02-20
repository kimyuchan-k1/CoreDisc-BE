package com.coredisc.infrastructure.repository.postLike;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPostLikeRepository extends JpaRepository<PostLike,Long> {

    boolean existsByMemberAndPost(Member member, Post post);

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    void deleteByPostAndMember(Post post, Member member);
}
