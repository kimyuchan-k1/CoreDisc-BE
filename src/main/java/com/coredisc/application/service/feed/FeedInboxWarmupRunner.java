package com.coredisc.application.service.feed;

import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.infrastructure.repository.follow.queryDSL.QueryFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 앱 시작 시 기존 게시글을 팔로워 인박스에 backfill.
 *
 * 전략: 작성자(author) 기준으로 순회
 * - 각 author의 최근 게시글 조회 (1 DB query)
 * - 각 author의 팔로워 목록 조회 (1 DB query)
 * - 팔로워 인박스에 Pipeline ZADD (1 Redis round-trip)
 * → O(authors) DB queries, not O(authors × followers)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedInboxWarmupRunner {

    private final FeedInboxService feedInboxService;
    private final PostRepository postRepository;
    private final QueryFollowRepository queryFollowRepository;

    private static final int RECENT_POSTS_LIMIT = 50;

    @EventListener(ApplicationReadyEvent.class)
    @Async("fanoutExecutor")
    public void warmupInboxes() {
        log.info("[WARMUP] 피드 인박스 워밍업 시작...");
        long start = System.currentTimeMillis();

        int authorCount = 0;
        int inboxUpdates = 0;

        try {
            // 이미 인박스가 채워져 있으면 skip
            if (feedInboxService.hasInbox(1L, "ALL")) {
                log.info("[WARMUP] 인박스 이미 존재. 워밍업 건너뜀.");
                return;
            }

            for (long authorId = 1; authorId <= 10000; authorId++) {
                // 1. author의 최근 게시글 조회 (publicity별)
                List<Long> officialPostIds = postRepository.findRecentPostIdsByMemberId(
                        authorId, List.of(PublicityType.OFFICIAL), RECENT_POSTS_LIMIT);
                List<Long> circlePostIds = postRepository.findRecentPostIdsByMemberId(
                        authorId, List.of(PublicityType.CIRCLE), RECENT_POSTS_LIMIT);
                List<Long> allPostIds = postRepository.findRecentPostIdsByMemberId(
                        authorId, null, RECENT_POSTS_LIMIT);

                if (allPostIds.isEmpty()) continue;
                authorCount++;

                // 2. 본인 인박스에 자기 게시글 추가
                feedInboxService.addPostsToInbox(authorId, "ALL", allPostIds);
                inboxUpdates++;

                // 3. 팔로워 인박스에 OFFICIAL 게시글 push
                if (!officialPostIds.isEmpty()) {
                    List<Long> followerIds = queryFollowRepository.findFollowerIds(authorId);
                    for (Long followerId : followerIds) {
                        feedInboxService.addPostsToInbox(followerId, "ALL", officialPostIds);
                        inboxUpdates++;
                    }

                    // circle 팔로워 → CORE 인박스에도 OFFICIAL 게시글 추가
                    List<Long> circleFollowerIds = queryFollowRepository.findCircleFollowerIds(authorId);
                    for (Long circleFollowerId : circleFollowerIds) {
                        feedInboxService.addPostsToInbox(circleFollowerId, "CORE", officialPostIds);
                        inboxUpdates++;
                    }
                }

                // 4. circle 팔로워 인박스에 CIRCLE 게시글 push
                if (!circlePostIds.isEmpty()) {
                    List<Long> circleFollowerIds = queryFollowRepository.findCircleFollowerIds(authorId);
                    for (Long circleFollowerId : circleFollowerIds) {
                        feedInboxService.addPostsToInbox(circleFollowerId, "ALL", circlePostIds);
                        feedInboxService.addPostsToInbox(circleFollowerId, "CORE", circlePostIds);
                        inboxUpdates += 2;
                    }
                }

                if (authorId % 1000 == 0) {
                    log.info("[WARMUP] 진행: {}/10000 authors, 처리 {}명, inbox updates={}",
                            authorId, authorCount, inboxUpdates);
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[WARMUP] 피드 인박스 워밍업 완료: authors={}, inbox updates={}, {}초 소요",
                    authorCount, inboxUpdates, elapsed / 1000);

        } catch (Exception e) {
            log.error("[WARMUP] 피드 인박스 워밍업 실패: {}", e.getMessage(), e);
        }
    }
}
