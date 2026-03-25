package com.coredisc.infrastructure.repository.postLike;

import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostLike;
import com.coredisc.domain.post.PostLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class PostLikeRepositoryAdaptor implements PostLikeRepository {


    private final JpaPostLikeRepository jpaPostLikeRepository;

    @Override
    public boolean existsByMemberAndPost(Member member, Post post) {
        return jpaPostLikeRepository.existsByMemberAndPost(member,post);
    }

    @Override
    public boolean existsByMemberIdAndPostId(Long memberId, Long postId) {
        return jpaPostLikeRepository.existsByMemberIdAndPostId(memberId, postId);
    }

    @Override
    public PostLike createPostLike(PostLike postLike) {
        return jpaPostLikeRepository.save(postLike);
    }

    @Override
    public void deleteByPostAndMember(Post post , Member member) {
        jpaPostLikeRepository.deleteByPostAndMember(post,member);
    }

    @Override
    public List<PostLike> findAllByMemberIdAndPostMemberId(Long likerId, Long postAuthorId) {
        return jpaPostLikeRepository.findAllByMemberIdAndPostMemberId(likerId, postAuthorId);
    }

    @Override
    public void deleteAllByMemberIdAndPostMemberId(Long likerId, Long postAuthorId) {
        jpaPostLikeRepository.deleteAllByMemberIdAndPostMemberId(likerId, postAuthorId);
    }
}
