package com.coredisc.application.service.feed;

import com.coredisc.application.service.follow.FollowQueryService;
import com.coredisc.common.converter.PostConverter;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.presentation.dto.post.PostRequestDTO;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 피드 읽기 오케스트레이션 서비스.
 *
 * 1. DTO 캐시 히트 → path=cache
 * 2. Redis 인박스 히트 → path=inbox
 * 3. Pull fallback → path=pull
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedReadService {

    private final FeedInboxService feedInboxService;
    private final FeedCacheService feedCacheService;
    private final PostRepository postRepository;
    private final FollowQueryService followQueryService;
    private final MeterRegistry meterRegistry;

    private Counter cacheCounter;
    private Counter inboxCounter;
    private Counter pullCounter;
    private Timer cacheTimer;
    private Timer inboxTimer;
    private Timer pullTimer;

    @PostConstruct
    void initMetrics() {
        cacheCounter = Counter.builder("feed.request").tag("path", "cache").register(meterRegistry);
        inboxCounter = Counter.builder("feed.request").tag("path", "inbox").register(meterRegistry);
        pullCounter = Counter.builder("feed.request").tag("path", "pull").register(meterRegistry);
        cacheTimer = Timer.builder("feed.duration").tag("path", "cache").register(meterRegistry);
        inboxTimer = Timer.builder("feed.duration").tag("path", "inbox").register(meterRegistry);
        pullTimer = Timer.builder("feed.duration").tag("path", "pull").register(meterRegistry);
    }

    public PostResponseDTO.PostFeedResponseDTO findPostFeed(Member member, PostRequestDTO.PostFeedRequestDto request) {
        Long memberId = member.getId();
        String feedType = request.getFeedType().name();
        Long cursor = request.getLastPostId();
        int size = request.getSize();
        boolean isFirstPage = cursor == null;

        // 1. 첫 페이지: DTO 캐시 먼저 체크 (30초 TTL)
        if (isFirstPage) {
            Timer.Sample cacheSample = Timer.start(meterRegistry);
            PostResponseDTO.PostFeedResponseDTO cached = feedCacheService.get(memberId, feedType);
            if (cached != null) {
                cacheSample.stop(cacheTimer);
                cacheCounter.increment();
                return cached;
            }
        }

        // 2. 인박스에서 postId 조회
        Timer.Sample inboxSample = Timer.start(meterRegistry);
        List<Long> postIds;
        if (isFirstPage) {
            postIds = feedInboxService.getInbox(memberId, feedType, size + 1);
        } else {
            postIds = feedInboxService.getInboxByCursor(memberId, feedType, cursor, size + 1);
        }

        // 3. 인박스에 데이터 있으면 → DB에서 엔티티 조회 후 DTO 변환 + 캐시 저장
        if (!postIds.isEmpty()) {
            PostResponseDTO.PostFeedResponseDTO result = buildFeedFromInbox(postIds, size);
            if (isFirstPage) {
                feedCacheService.put(memberId, feedType, result);
            }
            inboxSample.stop(inboxTimer);
            inboxCounter.increment();
            return result;
        }

        // 4. 인박스 비어있으면 → Pull fallback
        Timer.Sample pullSample = Timer.start(meterRegistry);
        PostResponseDTO.PostFeedResponseDTO result = pullFallback(member, request);
        pullSample.stop(pullTimer);
        pullCounter.increment();
        return result;
    }

    private PostResponseDTO.PostFeedResponseDTO buildFeedFromInbox(List<Long> postIds, int size) {
        boolean hasNext = postIds.size() > size;
        List<Long> pageIds = hasNext ? postIds.subList(0, size) : postIds;

        List<PostResponseDTO.PostFeedResponseDTO.PostSummary> posts =
                postRepository.findPostSummariesByIds(pageIds);

        if (posts.size() < pageIds.size()) {
            log.debug("[FEED] Stale postIds detected: requested={}, found={}", pageIds.size(), posts.size());
        }

        Long nextCursor = null;
        if (hasNext && !posts.isEmpty()) {
            nextCursor = posts.get(posts.size() - 1).getPostId();
        }

        return PostConverter.toPostFeedResponseDto(posts, nextCursor, hasNext);
    }

    /**
     * Pull fallback: 기존 Cache Aside + Thundering Herd 방어 경로
     */
    private PostResponseDTO.PostFeedResponseDTO pullFallback(Member member, PostRequestDTO.PostFeedRequestDto request) {
        boolean isFirstPage = request.getLastPostId() == null;
        if (isFirstPage) {
            String feedType = request.getFeedType().name();
            Long memberId = member.getId();

            PostResponseDTO.PostFeedResponseDTO cached = feedCacheService.get(memberId, feedType);
            if (cached != null) return cached;

            if (feedCacheService.tryLock(memberId, feedType)) {
                try {
                    PostResponseDTO.PostFeedResponseDTO result = queryPostFeed(member, request);
                    feedCacheService.put(memberId, feedType, result);
                    return result;
                } finally {
                    feedCacheService.unlock(memberId, feedType);
                }
            } else {
                PostResponseDTO.PostFeedResponseDTO waitResult = feedCacheService.waitAndGet(memberId, feedType);
                if (waitResult != null) return waitResult;
                return queryPostFeed(member, request);
            }
        }

        return queryPostFeed(member, request);
    }

    private PostResponseDTO.PostFeedResponseDTO queryPostFeed(Member member, PostRequestDTO.PostFeedRequestDto request) {
        List<Long> followingIds = followQueryService.getFollowingIds(member.getId());
        List<Long> circleIds = followQueryService.getCircleFollowingIds(member.getId());

        List<PostResponseDTO.PostFeedResponseDTO.PostSummary> posts = postRepository.findPostFeed(
                member,
                request.getFeedType(),
                request.getLastPostId(),
                request.getSize(),
                followingIds,
                circleIds
        );

        boolean hasNext = posts.size() > request.getSize();
        if (hasNext) {
            posts = posts.subList(0, request.getSize());
        }

        Long nextCursor = null;
        if (hasNext && !posts.isEmpty()) {
            nextCursor = posts.get(posts.size() - 1).getPostId();
        }

        return PostConverter.toPostFeedResponseDto(posts, nextCursor, hasNext);
    }
}
