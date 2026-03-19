package com.coredisc.application.service.block;

import com.coredisc.application.event.BlockedEvent;
import com.coredisc.application.service.feed.FeedCacheService;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.BlockConverter;
import com.coredisc.common.exception.handler.BlockHandler;
import com.coredisc.common.exception.handler.MemberHandler;
import com.coredisc.domain.block.Block;
import com.coredisc.domain.block.BlockRepository;
import com.coredisc.domain.follow.Follow;
import com.coredisc.domain.follow.FollowRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class BlockCommandServiceImpl implements BlockCommandService {

    private final MemberRepository memberRepository;
    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FeedCacheService feedCacheService;

    @Override
    @Caching(evict = {
            @CacheEvict(value = "followingIds", key = "#member.id"),
            @CacheEvict(value = "circleIds", key = "#member.id"),
            @CacheEvict(value = "followingIds", key = "#targetId"),
            @CacheEvict(value = "circleIds", key = "#targetId")
    })
    public Block block(Member member, Long targetId) {

        if (member.getId().equals(targetId)) {
            throw new BlockHandler(ErrorStatus.SELF_BLOCK_NOT_ALLOWED);
        }

        Member target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        // 이미 차단한 이력이 있을 경우
        if (blockRepository.existsByBlockerAndBlocked(member, target)) {
            throw new BlockHandler(ErrorStatus.ALREADY_BLOCKING);
        }

        // 팔로우 취소 : 내가 상대방을 팔로우 하고 있는 걸 끊기
        Follow followToTarget = followRepository.findByFollowerAndFollowing(member, target);
        if (followToTarget != null) {
            followRepository.delete(followToTarget);
        }

        // 팔로우 취소 : 상대방이 나를 팔로우 하고 있는 걸 끊기
        Follow followFromTarget = followRepository.findByFollowerAndFollowing(target, member);
        if (followFromTarget != null) {
            followRepository.delete(followFromTarget);
        }

        Block block = BlockConverter.toBlock(member, target);
        Block savedBlock = blockRepository.save(block);

        // 동기적 피드 DTO 캐시 무효화 (Block 후 다음 피드 요청에서 stale 피드 방지)
        feedCacheService.evict(member.getId());
        feedCacheService.evict(targetId);

        // 비동기 이벤트: 좋아요/댓글/알림/인박스 정리 (트랜잭션 커밋 후 실행)
        eventPublisher.publishEvent(BlockedEvent.of(member.getId(), targetId));

        return savedBlock;
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "followingIds", key = "#member.id"),
            @CacheEvict(value = "circleIds", key = "#member.id"),
            @CacheEvict(value = "followingIds", key = "#targetId"),
            @CacheEvict(value = "circleIds", key = "#targetId")
    })
    public void unblock(Member member, Long targetId) {
        if (member.getId().equals(targetId)) {
            throw new BlockHandler(ErrorStatus.SELF_UNBLOCK_NOT_ALLOWED);
        }

        Member target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        // 차단한 이력이 없을 경우
        if (!blockRepository.existsByBlockerAndBlocked(member, target)) {
            throw new BlockHandler(ErrorStatus.BLOCK_NOT_FOUND);
        }

        Block block = blockRepository.findByBlockerAndBlocked(member, target);

        blockRepository.delete(block);
    }
}
