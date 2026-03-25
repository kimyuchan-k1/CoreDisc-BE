package com.coredisc.infrastructure.repository.follow.queryDSL;

import com.coredisc.domain.follow.Follow;
import com.coredisc.domain.member.Member;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface QueryFollowRepository {

    List<Follow> findCircleFollowers(Member member, Long cursorId, Pageable pageable);
    List<Follow> findFollowers(Member member, Long cursorId, Pageable pageable);
    List<Follow> findFollowings(Member member, Long cursorId, Pageable pageable);

    int countCircleFollowers(Member member);
    int countFollowers(Member member);
    int countFollowings(Member member);

    List<Long> findFollowingIds(Long memberId);
    List<Long> findCircleFollowingIds(Long memberId);

    // Fan-out용: 나를 팔로우하는 사람들의 ID (역방향)
    List<Long> findFollowerIds(Long memberId);
    // Fan-out용: 나를 서클로 지정한 팔로워들의 ID (역방향)
    List<Long> findCircleFollowerIds(Long memberId);
}
