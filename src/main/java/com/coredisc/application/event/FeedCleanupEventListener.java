package com.coredisc.application.event;

import com.coredisc.application.service.feed.FeedInboxBackfillService;
import com.coredisc.application.service.feed.FeedInboxService;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.infrastructure.repository.follow.queryDSL.QueryFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 피드 인박스 정리/backfill 이벤트 리스너.
 *
 * - PostDeletedEvent: 팔로워 인박스에서 삭제된 게시글 제거 (청크 retry)
 * - BlockedEvent: 양방향 인박스 정리
 * - UnfollowedEvent: 상대 게시글을 내 인박스에서 제거
 * - FollowedEvent: 상대의 최근 게시글을 내 인박스에 backfill
 * - CircleChangedEvent: circle 추가/해제 시 인박스 조정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCleanupEventListener {

    private final FeedInboxService feedInboxService;
    private final FeedInboxBackfillService feedInboxBackfillService;
    private final QueryFollowRepository queryFollowRepository;

    private static final int CHUNK_SIZE = 500;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_INTERVAL_MS = 100;

    /**
     * 게시글 삭제 시 → 팔로워 인박스에서 제거 (청크 retry)
     */
    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostDeleted(PostDeletedEvent event) {
        Long postId = event.getPostId();
        Long authorId = event.getAuthorId();
        PublicityType publicity = event.getPublicity();

        try {
            feedInboxService.removeFromInbox(authorId, "ALL", postId);

            List<Long> followerIds = queryFollowRepository.findFollowerIds(authorId);
            List<Long> circleFollowerIds = queryFollowRepository.findCircleFollowerIds(authorId);

            if (publicity == PublicityType.OFFICIAL) {
                removeWithRetry(followerIds, "ALL", postId);
                if (!circleFollowerIds.isEmpty()) {
                    removeWithRetry(circleFollowerIds, "CORE", postId);
                }
            } else if (publicity == PublicityType.CIRCLE) {
                if (!circleFollowerIds.isEmpty()) {
                    removeWithRetry(circleFollowerIds, "ALL", postId);
                    removeWithRetry(circleFollowerIds, "CORE", postId);
                }
            }

            log.info("[FEED_CLEANUP] 게시글 삭제 인박스 정리 완료: postId={}", postId);
        } catch (Exception e) {
            log.error("[FEED_CLEANUP] 게시글 삭제 인박스 정리 실패: postId={}, error={}", postId, e.getMessage(), e);
        }
    }

    /**
     * Block 시 → 양방향 인박스 정리
     */
    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBlocked(BlockedEvent event) {
        try {
            feedInboxBackfillService.cleanupOnBlock(event.getActorId(), event.getTargetId());
        } catch (Exception e) {
            log.error("[FEED_CLEANUP] Block 인박스 정리 실패: blockerId={}, blockedId={}, error={}",
                    event.getActorId(), event.getTargetId(), e.getMessage(), e);
        }
    }

    /**
     * Unfollow 시 → 상대 게시글을 내 인박스에서 제거
     */
    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUnfollowed(UnfollowedEvent event) {
        try {
            feedInboxBackfillService.cleanupOnUnfollow(event.getActorId(), event.getTargetId());
        } catch (Exception e) {
            log.error("[FEED_CLEANUP] Unfollow 인박스 정리 실패: unfollowerId={}, unfollowedId={}, error={}",
                    event.getActorId(), event.getTargetId(), e.getMessage(), e);
        }
    }

    /**
     * Follow 시 → 상대의 최근 OFFICIAL 게시글 backfill
     */
    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFollowed(FollowedEvent event) {
        try {
            feedInboxBackfillService.backfillOnFollow(event.getActorId(), event.getTargetId());
        } catch (Exception e) {
            log.error("[FEED_CLEANUP] Follow backfill 실패: followerId={}, followingId={}, error={}",
                    event.getActorId(), event.getTargetId(), e.getMessage(), e);
        }
    }

    /**
     * Circle 변경 시 → 추가면 backfill, 해제면 정리
     */
    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCircleChanged(CircleChangedEvent event) {
        try {
            if (event.isCircle()) {
                feedInboxBackfillService.backfillOnCircleAdd(event.getActorId(), event.getTargetId());
            } else {
                feedInboxBackfillService.cleanupOnCircleRemove(event.getActorId(), event.getTargetId());
            }
        } catch (Exception e) {
            log.error("[FEED_CLEANUP] Circle 변경 인박스 처리 실패: setterId={}, targetId={}, isCircle={}, error={}",
                    event.getActorId(), event.getTargetId(), event.isCircle(), e.getMessage(), e);
        }
    }

    private void removeWithRetry(List<Long> memberIds, String feedType, Long postId) {
        List<List<Long>> chunks = partition(memberIds, CHUNK_SIZE);
        for (List<Long> chunk : chunks) {
            boolean success = false;
            for (int attempt = 0; attempt <= MAX_RETRIES && !success; attempt++) {
                try {
                    if (attempt > 0) {
                        Thread.sleep(RETRY_INTERVAL_MS);
                    }
                    feedInboxService.removeFromInboxes(chunk, feedType, postId);
                    success = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES) {
                        log.error("[FEED_CLEANUP] 청크 제거 영구 실패: feedType={}, postId={}, chunkSize={}, error={}",
                                feedType, postId, chunk.size(), e.getMessage());
                    }
                }
            }
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
