package com.coredisc.application.event;

import com.coredisc.domain.post.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostCountEventListener {

    private final PostRepository postRepository;

    @Async("countExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePostCountEvent(PostCountEvent event) {
        try {
            switch (event.getCountType()) {
                case LIKE_INCREMENT -> postRepository.incrementLikeCount(event.getPostId());
                case LIKE_DECREMENT -> postRepository.decrementLikeCount(event.getPostId());
                case COMMENT_INCREMENT -> postRepository.incrementCommentCount(event.getPostId());
                case COMMENT_DECREMENT -> postRepository.decrementCommentCount(event.getPostId());
            }
        } catch (Exception e) {
            log.warn("Post count update failed for postId={}, type={}: {}",
                    event.getPostId(), event.getCountType(), e.getMessage());
        }
    }
}
