package com.coredisc.domain.post;

import com.coredisc.domain.member.Member;


public interface PostLikeRepository{

    boolean existsByMemberAndPost(Member member, Post post);

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    PostLike createPostLike(PostLike postLike);

    void deleteByPostAndMember(Post post, Member member);
}
