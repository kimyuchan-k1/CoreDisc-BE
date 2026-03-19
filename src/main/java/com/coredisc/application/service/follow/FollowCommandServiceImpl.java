package com.coredisc.application.service.follow;

import com.coredisc.application.event.CircleChangedEvent;
import com.coredisc.application.event.FollowedEvent;
import com.coredisc.application.event.UnfollowedEvent;
import com.coredisc.application.service.feed.FeedCacheService;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.FollowConverter;
import com.coredisc.common.exception.handler.CircleHandler;
import com.coredisc.common.exception.handler.FollowHandler;
import com.coredisc.common.exception.handler.MemberHandler;
import com.coredisc.domain.block.BlockRepository;
import com.coredisc.domain.follow.Follow;
import com.coredisc.domain.follow.FollowRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FollowCommandServiceImpl implements FollowCommandService {

    private final MemberRepository memberRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FeedCacheService feedCacheService;

    @Override
    @CacheEvict(value = "followingIds", key = "#member.id")
    public Follow follow(Member member, Long targetId) {

        if (member.getId().equals(targetId)) {
            throw new FollowHandler(ErrorStatus.SELF_FOLLOW_NOT_ALLOWED);
        }

        Member target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        // 이미 팔로우한 이력이 있을 경우
        if (followRepository.existsByFollowerAndFollowing(member, target)){
            throw new FollowHandler(ErrorStatus.ALREADY_FOLLOWING);
        }

        // 차단 관계인지 확인
        if (blockRepository.existsByBlockerAndBlocked(member, target) || blockRepository.existsByBlockerAndBlocked(target, member)) {
            throw new FollowHandler(ErrorStatus.BLOCK_RELATIONSHIP_EXISTS);
        }

        Follow follow = followRepository.save(FollowConverter.toFollow(member, target));

        // 비동기 이벤트로 알림 + FCM 전송 (트랜잭션 커밋 후 실행)
        eventPublisher.publishEvent(FollowedEvent.of(member.getId(), targetId, member.getNickname()));

        return follow;
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "followingIds", key = "#member.id"),
            @CacheEvict(value = "circleIds", key = "#member.id"),
            @CacheEvict(value = "circleIds", key = "#targetId")
    })
    public void unfollow(Member member, Long targetId) {

        if (member.getId().equals(targetId)) {
            throw new FollowHandler(ErrorStatus.SELF_UNFOLLOW_NOT_ALLOWED);
        }

        Member target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        // 팔로우한 이력이 없을 경우
        Follow followToTarget = followRepository.findByFollowerAndFollowing(member, target);
        if (followToTarget == null){
            throw new FollowHandler(ErrorStatus.FOLLOW_NOT_FOUND);
        }

        // 언팔로우 시, 친한친구 상태 해제
        if (followToTarget.isCircle()){
            followToTarget.updateCircle(false);
        }

        // 상대방이 나를 친한친구로 설정해둔 거 해제
        Follow followFromTarget = followRepository.findByFollowerAndFollowing(target, member);
        if (followFromTarget != null && followFromTarget.isCircle()) {
            followFromTarget.updateCircle(false);
        }

        followRepository.delete(followToTarget);

        // 동기적 피드 DTO 캐시 무효화 (Unfollow 후 상대 글 즉시 미노출)
        feedCacheService.evict(member.getId());

        eventPublisher.publishEvent(UnfollowedEvent.of(member.getId(), targetId));
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "circleIds", key = "#member.id"),
            @CacheEvict(value = "circleIds", key = "#targetId")
    })
    // 차단 시, Follow 관계가 삭제되기에 친친 설정 로직에서 차단 여부는 체크하지 않음
    public void updateCircleStatus(Member member, Long targetId, boolean isCircle) {

        // 자기 자신을 친한친구로 설정하려는 경우
        if (member.getId().equals(targetId)) {
            throw new CircleHandler(ErrorStatus.SELF_CIRCLE_NOT_ALLOWED);
        }

        Member target = memberRepository.findById(targetId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        // 상대방이 나를 팔로우 하고 있는지 확인
        Follow followFromTarget = followRepository.findByFollowerAndFollowing(target, member);
        if (followFromTarget == null) {
            throw new FollowHandler(ErrorStatus.MUST_BE_MUTUAL_FOLLOW_TO_BE_CIRCLE);
        }

        // 나도 상대방을 팔로우 하고 있는지 확인 (맞팔인 경우에만 친친 가능)
        Follow followToTarget = followRepository.findByFollowerAndFollowing(member, target);
        if (followToTarget == null) {
            throw new FollowHandler(ErrorStatus.MUST_BE_MUTUAL_FOLLOW_TO_BE_CIRCLE);
        }

        // 나를 팔로우 하고 있는 팔로워 중에서 친한친구 설정 가능
        followFromTarget.updateCircle(isCircle);

        eventPublisher.publishEvent(CircleChangedEvent.of(member.getId(), targetId, isCircle));
    }
}
