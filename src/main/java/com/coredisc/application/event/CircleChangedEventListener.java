package com.coredisc.application.event;

import com.coredisc.domain.follow.Follow;
import com.coredisc.domain.follow.FollowRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircleChangedEventListener {

    private final FollowRepository followRepository;
    private final MemberRepository memberRepository;
    private final CacheManager cacheManager;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleCircleChanged(CircleChangedEvent event) {
        // Circle 추가는 양방향 불필요
        if (event.isCircle()) return;

        try {
            Member actor = memberRepository.findById(event.getActorId()).orElse(null);
            Member target = memberRepository.findById(event.getTargetId()).orElse(null);

            if (actor == null || target == null) {
                log.warn("Circle 양방향 해제 실패: actor={} target={} 존재하지 않음",
                        event.getActorId(), event.getTargetId());
                return;
            }

            // 반대 방향 Follow 조회
            Follow reverseFollow = followRepository.findByFollowerAndFollowing(target, actor);
            if (reverseFollow != null && reverseFollow.isCircle()) {
                reverseFollow.updateCircle(false);
                followRepository.save(reverseFollow);

                // async 컨텍스트이므로 @CacheEvict 불가 — 수동 무효화
                evictCache("circleIds", event.getTargetId());
                evictCache("circleIds", event.getActorId());

                log.info("[ASYNC] Circle 양방향 해제 완료: actorId={}, targetId={}",
                        event.getActorId(), event.getTargetId());
            }
        } catch (Exception e) {
            log.error("[ASYNC] Circle 양방향 해제 실패: actorId={}, targetId={}, error={}",
                    event.getActorId(), event.getTargetId(), e.getMessage(), e);
        }
    }

    private void evictCache(String cacheName, Long key) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }
}
