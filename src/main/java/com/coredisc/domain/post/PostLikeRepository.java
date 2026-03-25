package com.coredisc.domain.post;

import com.coredisc.domain.member.Member;

import java.util.List;

public interface PostLikeRepository{

    boolean existsByMemberAndPost(Member member, Post post);

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    PostLike createPostLike(PostLike postLike);

    void deleteByPostAndMember(Post post, Member member);

    List<PostLike> findAllByMemberIdAndPostMemberId(Long likerId, Long postAuthorId);

    void deleteAllByMemberIdAndPostMemberId(Long likerId, Long postAuthorId);
}
