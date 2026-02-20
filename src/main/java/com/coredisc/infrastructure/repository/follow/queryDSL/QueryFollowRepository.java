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
}
