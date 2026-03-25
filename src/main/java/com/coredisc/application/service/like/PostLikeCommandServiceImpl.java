package com.coredisc.application.service.like;

import com.coredisc.application.event.NotificationEvent;
import com.coredisc.application.event.PostCountEvent;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.PostConverter;
import com.coredisc.common.exception.handler.LikeHandler;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostLike;
import com.coredisc.domain.post.PostLikeRepository;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.application.service.post.PostVisibilityChecker;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostLikeCommandServiceImpl implements PostLikeCommandService{

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PostVisibilityChecker postVisibilityChecker;

    @Transactional
    public PostResponseDTO.PostLikeDto createLike(Long postId, Member member) {
        Post post = findAndValidatePost(postId);
        postVisibilityChecker.validateAccess(post, member);

        if(postLikeRepository.existsByMemberAndPost(member,post)) {
            throw new LikeHandler(ErrorStatus.POST_LIKE_DUPLICATED);
        }

        PostLike postLike = PostLike.create(post,member);

        PostLike savedPostlike;
        try {
            savedPostlike = postLikeRepository.createPostLike(postLike);
        } catch (DataIntegrityViolationException e) {
            throw new LikeHandler(ErrorStatus.POST_LIKE_DUPLICATED);
        }

        // 좋아요 카운트 원자적 증가 (트랜잭션 커밋 후 별도 실행 — FK Deadlock 방지)
        eventPublisher.publishEvent(PostCountEvent.likeIncrement(postId));

        // 자신의 게시글에 좋아요를 누를 시에는 알림이 생성되지 않도록
        if (!post.getMember().getId().equals(member.getId())) {
            eventPublisher.publishEvent(NotificationEvent.like(
                    member.getId(), post.getMember().getId(),
                    member.getNickname(), post.getId()));
        }

        return PostConverter.toPostLikeDto(savedPostlike.getPost().getId(),true);
    }

    @Transactional
    public PostResponseDTO.PostLikeDto deleteLike(Long postId, Member member) {
        Post post = findAndValidatePost(postId);
        postVisibilityChecker.validateAccess(post, member);

        if (!postLikeRepository.existsByMemberAndPost(member, post)) {
            throw new LikeHandler(ErrorStatus.POST_LIKE_NOT_FOUND);
        }

        postLikeRepository.deleteByPostAndMember(post, member);
        // 좋아요 카운트 원자적 감소 (트랜잭션 커밋 후 별도 실행)
        eventPublisher.publishEvent(PostCountEvent.likeDecrement(postId));
        return PostConverter.toPostLikeDto(postId, false);
    }

    private Post findAndValidatePost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() ->new PostHandler(ErrorStatus.POST_NOT_FOUND) );
    }
}
