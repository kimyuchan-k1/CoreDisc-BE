package com.coredisc.infrastructure.repository.follow.queryDSL;

import com.coredisc.domain.follow.Follow;
import com.coredisc.domain.follow.QFollow;
import com.coredisc.domain.member.Member;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class QueryFollowRepositoryImpl implements QueryFollowRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Follow> findCircleFollowers(Member member, Long cursorId, Pageable pageable) {
        QFollow follow = QFollow.follow;

        return queryFactory
                .selectFrom(follow)
                .where(
                        follow.following.eq(member),
                        follow.isCircle.isTrue(),
                        cursorId != null ? follow.id.lt(cursorId) : null
                )
                .orderBy(follow.id.desc())
                .limit(pageable.getPageSize() + 1)
                .fetch();
    }

    @Override
    public List<Follow> findFollowers(Member member, Long cursorId, Pageable pageable) {
        QFollow follow = QFollow.follow;

        return queryFactory
                .selectFrom(follow)
                .where(
                        follow.following.eq(member),
                        cursorId != null ? follow.id.lt(cursorId) : null
                )
                .orderBy(follow.id.desc())
                .limit(pageable.getPageSize() + 1)
                .fetch();
    }

    @Override
    public List<Follow> findFollowings(Member member, Long cursorId, Pageable pageable) {
        QFollow follow = QFollow.follow;

        return queryFactory
                .selectFrom(follow)
                .where(
                        follow.follower.eq(member),
                        cursorId != null ? follow.id.lt(cursorId) : null
                )
                .orderBy(follow.id.desc())
                .limit(pageable.getPageSize() + 1)
                .fetch();
    }

    @Override
    public int countCircleFollowers(Member member) {
        QFollow follow = QFollow.follow;

        Long count = queryFactory
                .select(follow.count())
                .from(follow)
                .where(
                        follow.following.eq(member),
                        follow.isCircle.isTrue()
                )
                .fetchOne();

        return count != null ? count.intValue() : 0;
    }

    @Override
    public int countFollowers(Member member) {
        QFollow follow = QFollow.follow;

        Long count = queryFactory
                .select(follow.count())
                .from(follow)
                .where(follow.following.eq(member))
                .fetchOne();

        return count != null ? count.intValue() : 0;
    }

    @Override
    public int countFollowings(Member member) {
        QFollow follow = QFollow.follow;

        Long count = queryFactory
                .select(follow.count())
                .from(follow)
                .where(follow.follower.eq(member))
                .fetchOne();

        return count != null ? count.intValue() : 0;
    }

    @Override
    public List<Long> findFollowingIds(Long memberId) {
        QFollow follow = QFollow.follow;
        return queryFactory
                .select(follow.following.id)
                .from(follow)
                .where(follow.follower.id.eq(memberId))
                .fetch();
    }

    @Override
    public List<Long> findCircleFollowingIds(Long memberId) {
        QFollow follow = QFollow.follow;
        return queryFactory
                .select(follow.following.id)
                .from(follow)
                .where(follow.follower.id.eq(memberId)
                        .and(follow.isCircle.eq(true)))
                .fetch();
    }
}
