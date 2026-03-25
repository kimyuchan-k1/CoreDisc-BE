package com.coredisc.application.event;

import com.coredisc.domain.Comment;
import com.coredisc.domain.comment.CommentRepository;
import com.coredisc.domain.post.PostLike;
import com.coredisc.domain.post.PostLikeRepository;
import com.coredisc.domain.post.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionCleanupListener {

    private final PostLikeRepository postLikeRepository;
    private final CommentRepository commentRepository;
    private final PostRepository postRepository;

    @Async("countExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleBlockedEvent(BlockedEvent event) {
        Long blockerId = event.getActorId();
        Long blockedId = event.getTargetId();

        try {
            // 양방향 좋아요 정리
            cleanupLikes(blockedId, blockerId);
            cleanupLikes(blockerId, blockedId);

            // 양방향 댓글 정리
            cleanupComments(blockedId, blockerId);
            cleanupComments(blockerId, blockedId);

            log.info("[ASYNC] Block 상호작용 정리 완료: blockerId={}, blockedId={}", blockerId, blockedId);
        } catch (Exception e) {
            log.error("[ASYNC] Block 상호작용 정리 실패: blockerId={}, blockedId={}, error={}",
                    blockerId, blockedId, e.getMessage(), e);
        }
    }

    private void cleanupLikes(Long likerId, Long postAuthorId) {
        // 먼저 조회하여 postId별 count 집계
        List<PostLike> likes = postLikeRepository.findAllByMemberIdAndPostMemberId(likerId, postAuthorId);
        if (likes.isEmpty()) return;

        Map<Long, Long> countByPostId = likes.stream()
                .collect(Collectors.groupingBy(pl -> pl.getPost().getId(), Collectors.counting()));

        // 벌크 삭제
        postLikeRepository.deleteAllByMemberIdAndPostMemberId(likerId, postAuthorId);

        // postId별 likeCount 보정
        countByPostId.forEach((postId, count) ->
                postRepository.decrementLikeCountByAmount(postId, count.intValue()));

        log.info("[ASYNC] 좋아요 정리: likerId={}, authorId={}, 삭제 수={}", likerId, postAuthorId, likes.size());
    }

    private void cleanupComments(Long commenterId, Long postAuthorId) {
        // 먼저 조회하여 postId별 count 집계
        List<Comment> comments = commentRepository.findAllActiveByMemberIdAndPostMemberId(commenterId, postAuthorId);
        if (comments.isEmpty()) return;

        Map<Long, Long> countByPostId = comments.stream()
                .collect(Collectors.groupingBy(c -> c.getPost().getId(), Collectors.counting()));

        // 벌크 soft delete
        int deleted = commentRepository.softDeleteAllByMemberIdAndPostMemberId(commenterId, postAuthorId);

        // postId별 commentCount 보정
        countByPostId.forEach((postId, count) ->
                postRepository.decrementCommentCountByAmount(postId, count.intValue()));

        log.info("[ASYNC] 댓글 정리: commenterId={}, authorId={}, soft delete 수={}", commenterId, postAuthorId, deleted);
    }
}
