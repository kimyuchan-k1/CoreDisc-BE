package com.coredisc.application.event;

import com.coredisc.application.service.feed.FeedCacheService;
import com.coredisc.application.service.feed.FeedInboxService;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.infrastructure.repository.follow.queryDSL.QueryFollowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Fan-out on Write: 게시글 발행 시 팔로워 인박스에 Push.
 *
 * - OFFICIAL: 전체 팔로워 ALL + circle 팔로워 CORE
 * - CIRCLE: circle 팔로워만 ALL + CORE
 * - PERSONAL: 작성자 본인 ALL 인박스에만 push
 * - 팔로워 5000명 초과 시 skip (Pull path가 처리)
 * - 500명 단위 chunk + 최대 2회 retry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutEventListener {

    private final FeedInboxService feedInboxService;
    private final FeedCacheService feedCacheService;
    private final QueryFollowRepository queryFollowRepository;
    private final MeterRegistry meterRegistry;

    private static final int CELEBRITY_THRESHOLD = 5000;
    private static final int CHUNK_SIZE = 500;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_INTERVAL_MS = 100;

    private Timer fanoutDuration;
    private Counter retryCounter;
    private Counter failureCounter;

    @PostConstruct
    void initMetrics() {
        fanoutDuration = Timer.builder("feed.fanout.duration")
                .description("Fan-out 전체 소요 시간")
                .register(meterRegistry);
        retryCounter = Counter.builder("feed.fanout.retry")
                .description("Fan-out retry 발생 횟수")
                .register(meterRegistry);
        failureCounter = Counter.builder("feed.fanout.failure")
                .description("Fan-out 영구 실패 횟수")
                .register(meterRegistry);
    }

    @Async("fanoutExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostPublished(PostPublishedEvent event) {
        Long postId = event.getPostId();
        Long authorId = event.getAuthorId();
        PublicityType publicity = event.getPublicity();

        fanoutDuration.record(() -> {
            try {
                // 작성자 본인 인박스에 항상 추가 (ALL만)
                feedInboxService.addToInbox(authorId, "ALL", postId);

                if (publicity == PublicityType.PERSONAL) {
                    log.debug("[FANOUT] PERSONAL 게시글, 작성자 인박스에만 추가: postId={}, authorId={}", postId, authorId);
                    return;
                }

                List<Long> followerIds = queryFollowRepository.findFollowerIds(authorId);

                if (followerIds.size() > CELEBRITY_THRESHOLD) {
                    log.info("[FANOUT] Celebrity skip: authorId={}, followers={}", authorId, followerIds.size());
                    return;
                }

                List<Long> circleFollowerIds = queryFollowRepository.findCircleFollowerIds(authorId);

                if (publicity == PublicityType.OFFICIAL) {
                    fanoutWithRetry(followerIds, "ALL", postId);
                    if (!circleFollowerIds.isEmpty()) {
                        fanoutWithRetry(circleFollowerIds, "CORE", postId);
                    }
                    evictFollowerCaches(followerIds);
                    log.info("[FANOUT] OFFICIAL fan-out 완료: postId={}, followers={}, circle={}",
                            postId, followerIds.size(), circleFollowerIds.size());

                } else if (publicity == PublicityType.CIRCLE) {
                    if (!circleFollowerIds.isEmpty()) {
                        fanoutWithRetry(circleFollowerIds, "ALL", postId);
                        fanoutWithRetry(circleFollowerIds, "CORE", postId);
                        evictFollowerCaches(circleFollowerIds);
                    }
                    log.info("[FANOUT] CIRCLE fan-out 완료: postId={}, circle={}",
                            postId, circleFollowerIds.size());
                }

            } catch (Exception e) {
                log.error("[FANOUT] Fan-out 실패: postId={}, authorId={}, error={}",
                        postId, authorId, e.getMessage(), e);
            }
        });
    }

    private void fanoutWithRetry(List<Long> followerIds, String feedType, Long postId) {
        List<List<Long>> chunks = partition(followerIds, CHUNK_SIZE);
        for (List<Long> chunk : chunks) {
            boolean success = false;
            for (int attempt = 0; attempt <= MAX_RETRIES && !success; attempt++) {
                try {
                    if (attempt > 0) {
                        retryCounter.increment();
                        Thread.sleep(RETRY_INTERVAL_MS);
                    }
                    feedInboxService.addToInboxes(chunk, feedType, postId);
                    success = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES) {
                        failureCounter.increment();
                        log.error("[FANOUT] 청크 영구 실패: feedType={}, postId={}, chunkSize={}, error={}",
                                feedType, postId, chunk.size(), e.getMessage());
                    }
                }
            }
        }
    }

    private void evictFollowerCaches(List<Long> followerIds) {
        for (Long followerId : followerIds) {
            try {
                feedCacheService.evict(followerId);
            } catch (Exception e) {
                log.warn("[FANOUT] 캐시 evict 실패: followerId={}, error={}", followerId, e.getMessage());
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
