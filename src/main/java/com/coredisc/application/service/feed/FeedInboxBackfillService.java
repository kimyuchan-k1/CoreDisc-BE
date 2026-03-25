package com.coredisc.application.service.feed;

import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.post.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Follow/Circle 변경 시 피드 인박스 backfill.
 *
 * - Follow: 상대의 최근 OFFICIAL 게시글을 내 ALL 인박스에 추가
 * - Circle 추가: 상대의 최근 CIRCLE+OFFICIAL 게시글을 내 CORE 인박스에 추가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedInboxBackfillService {

    private final FeedInboxService feedInboxService;
    private final PostRepository postRepository;

    private static final int BACKFILL_LIMIT = 50;

    /**
     * Follow 시 backfill: 상대의 최근 OFFICIAL 게시글 → 내 ALL 인박스
     */
    public void backfillOnFollow(Long followerId, Long followingId) {
        try {
            List<Long> recentPostIds = postRepository.findRecentPostIdsByMemberId(
                    followingId, List.of(PublicityType.OFFICIAL), BACKFILL_LIMIT);

            if (!recentPostIds.isEmpty()) {
                feedInboxService.addPostsToInbox(followerId, "ALL", recentPostIds);
                log.info("[BACKFILL] Follow backfill 완료: followerId={}, followingId={}, posts={}",
                        followerId, followingId, recentPostIds.size());
            }
        } catch (Exception e) {
            log.warn("[BACKFILL] Follow backfill 실패: followerId={}, followingId={}, error={}",
                    followerId, followingId, e.getMessage());
        }
    }

    /**
     * Circle 추가 시 backfill: 상대의 최근 CIRCLE+OFFICIAL 게시글 → 내 CORE 인박스에 추가,
     * CIRCLE 게시글은 ALL 인박스에도 추가
     */
    public void backfillOnCircleAdd(Long followerId, Long followingId) {
        try {
            // CORE 인박스: OFFICIAL + CIRCLE 게시글 추가
            List<Long> recentCorePostIds = postRepository.findRecentPostIdsByMemberId(
                    followingId, List.of(PublicityType.OFFICIAL, PublicityType.CIRCLE), BACKFILL_LIMIT);

            if (!recentCorePostIds.isEmpty()) {
                feedInboxService.addPostsToInbox(followerId, "CORE", recentCorePostIds);
            }

            // ALL 인박스: CIRCLE 게시글만 추가 (OFFICIAL은 이미 follow 시 추가됨)
            List<Long> recentCirclePostIds = postRepository.findRecentPostIdsByMemberId(
                    followingId, List.of(PublicityType.CIRCLE), BACKFILL_LIMIT);

            if (!recentCirclePostIds.isEmpty()) {
                feedInboxService.addPostsToInbox(followerId, "ALL", recentCirclePostIds);
            }

            log.info("[BACKFILL] Circle 추가 backfill 완료: followerId={}, followingId={}, core={}, circle={}",
                    followerId, followingId, recentCorePostIds.size(), recentCirclePostIds.size());
        } catch (Exception e) {
            log.warn("[BACKFILL] Circle 추가 backfill 실패: followerId={}, followingId={}, error={}",
                    followerId, followingId, e.getMessage());
        }
    }

    /**
     * Circle 해제 시: 상대의 CIRCLE 게시글을 내 CORE 인박스에서 제거 + ALL 인박스에서 제거
     */
    public void cleanupOnCircleRemove(Long followerId, Long followingId) {
        try {
            List<Long> circlePostIds = postRepository.findRecentPostIdsByMemberId(
                    followingId, List.of(PublicityType.CIRCLE), BACKFILL_LIMIT);

            if (!circlePostIds.isEmpty()) {
                feedInboxService.removePostsFromInbox(followerId, "CORE", circlePostIds);
                feedInboxService.removePostsFromInbox(followerId, "ALL", circlePostIds);
            }

            // CORE 인박스에서 해당 작성자의 OFFICIAL 게시글도 제거
            List<Long> officialPostIds = postRepository.findRecentPostIdsByMemberId(
                    followingId, List.of(PublicityType.OFFICIAL), BACKFILL_LIMIT);

            if (!officialPostIds.isEmpty()) {
                feedInboxService.removePostsFromInbox(followerId, "CORE", officialPostIds);
            }

            log.info("[BACKFILL] Circle 해제 정리 완료: followerId={}, followingId={}",
                    followerId, followingId);
        } catch (Exception e) {
            log.warn("[BACKFILL] Circle 해제 정리 실패: followerId={}, followingId={}, error={}",
                    followerId, followingId, e.getMessage());
        }
    }

    /**
     * Unfollow 시: 상대의 게시글을 내 ALL + CORE 인박스에서 제거
     */
    public void cleanupOnUnfollow(Long unfollowerId, Long unfollowedId) {
        try {
            List<Long> postIds = postRepository.findRecentPostIdsByMemberId(
                    unfollowedId, null, BACKFILL_LIMIT);

            if (!postIds.isEmpty()) {
                feedInboxService.removePostsFromInbox(unfollowerId, "ALL", postIds);
                feedInboxService.removePostsFromInbox(unfollowerId, "CORE", postIds);
            }

            log.info("[BACKFILL] Unfollow 정리 완료: unfollowerId={}, unfollowedId={}, posts={}",
                    unfollowerId, unfollowedId, postIds.size());
        } catch (Exception e) {
            log.warn("[BACKFILL] Unfollow 정리 실패: unfollowerId={}, unfollowedId={}, error={}",
                    unfollowerId, unfollowedId, e.getMessage());
        }
    }

    /**
     * Block 시: 양방향 인박스 정리
     */
    public void cleanupOnBlock(Long blockerId, Long blockedId) {
        try {
            // 상대 글을 내 피드에서 제거
            List<Long> blockedPostIds = postRepository.findRecentPostIdsByMemberId(
                    blockedId, null, BACKFILL_LIMIT);
            if (!blockedPostIds.isEmpty()) {
                feedInboxService.removePostsFromInbox(blockerId, "ALL", blockedPostIds);
                feedInboxService.removePostsFromInbox(blockerId, "CORE", blockedPostIds);
            }

            // 내 글을 상대 피드에서 제거
            List<Long> blockerPostIds = postRepository.findRecentPostIdsByMemberId(
                    blockerId, null, BACKFILL_LIMIT);
            if (!blockerPostIds.isEmpty()) {
                feedInboxService.removePostsFromInbox(blockedId, "ALL", blockerPostIds);
                feedInboxService.removePostsFromInbox(blockedId, "CORE", blockerPostIds);
            }

            log.info("[BACKFILL] Block 양방향 정리 완료: blockerId={}, blockedId={}",
                    blockerId, blockedId);
        } catch (Exception e) {
            log.warn("[BACKFILL] Block 정리 실패: blockerId={}, blockedId={}, error={}",
                    blockerId, blockedId, e.getMessage());
        }
    }
}
