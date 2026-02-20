package com.coredisc.infrastructure.repository.post.queryDsl;

import com.coredisc.common.converter.PostConverter;
import com.coredisc.domain.common.enums.FeedType;
import com.coredisc.common.util.DateUtil;
import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.*;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import com.querydsl.core.BooleanBuilder;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.QPost;
import com.coredisc.domain.post.QPostAnswer;
import com.coredisc.domain.post.QPostAnswerImage;
import com.coredisc.presentation.dto.calendar.CalendarPostDTO;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.coredisc.domain.follow.QFollow.follow;
import static com.coredisc.domain.member.QMember.*;
import static com.coredisc.domain.post.QPost.*;
import static com.coredisc.domain.post.QPostAnswer.*;
import static com.coredisc.domain.post.QPostAnswerImage.*;
import static com.coredisc.domain.profileImg.QProfileImg.*;
import static com.coredisc.domain.todayQuestion.QTodayQuestion.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class QueryPostRepositoryImpl implements QueryPostRepository {

    private final JPAQueryFactory jpaQueryFactory;
    private final TodayQuestionRepository todayQuestionRepository;

    @Override
    public List<Post> findMyPostsWithAnswers(Member member, Long cursorId, Pageable pageable) {

        QPost p = post;
        QPostAnswer pa = QPostAnswer.postAnswer;
        QPostAnswerImage pai = QPostAnswerImage.postAnswerImage;

        return jpaQueryFactory
                .selectFrom(p)
                .leftJoin(p.answers, pa).fetchJoin()
                .leftJoin(pa.postAnswerImage, pai).fetchJoin()
                .where(
                        p.member.eq(member),
                        p.status.ne(PostStatus.TEMP),
                        cursorId != null ? p.id.lt(cursorId) : null
                )
                .orderBy(p.id.desc())
                .limit(pageable.getPageSize())
                .fetch();
    }

    @Override
    public List<Post> findUserPostsWithAnswers(Member member, boolean isCircle, Long cursorId, Pageable pageable) {

        QPost p = post;
        QPostAnswer pa = QPostAnswer.postAnswer;
        QPostAnswerImage pai = QPostAnswerImage.postAnswerImage;

        return jpaQueryFactory
                .selectFrom(p)
                .leftJoin(p.answers, pa).fetchJoin()
                .leftJoin(pa.postAnswerImage, pai).fetchJoin()
                .where(
                        p.member.eq(member),
                        p.status.ne(PostStatus.TEMP),
                        (isCircle ?
                                (p.publicity.eq(PublicityType.CIRCLE).or(p.publicity.eq(PublicityType.OFFICIAL)))
                                : p.publicity.eq(PublicityType.OFFICIAL)
                        ),
                        cursorId != null ? p.id.lt(cursorId) : null
                )
                .orderBy(p.id.desc())
                .limit(pageable.getPageSize())
                .fetch();
    }

    @Override
    public boolean existsByMemberAndIdLessThan(Member member, Long id,
                                               Set<PublicityType> allowTypes) {
        QPost p = post;
        Integer fetchOne = jpaQueryFactory
                .selectOne()
                .from(p)
                .where(
                        p.member.eq(member),
                        p.status.ne(PostStatus.TEMP),
                        allowTypes != null && !allowTypes.isEmpty()
                                ? p.publicity.in(allowTypes) : null,
                        p.id.lt(id)
                )
                .fetchFirst();
        return fetchOne != null;
    }

    @Override
    public List<Post> findTempPostByMemberAndDate(Member member, LocalDate today) {
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        return jpaQueryFactory
                .selectFrom(post)
                .where(
                        post.member.eq(member),
                        post.status.eq(PostStatus.TEMP),
                        post.createdAt.goe(start),
                        post.createdAt.lt(end)
                ).orderBy(post.updatedAt.desc())
                .fetch();
    }

    @Override
    public List<PostAnswer> findTempPostAnswerByPostId(Long postId) {

        return jpaQueryFactory
                .selectFrom(postAnswer)
                .join(postAnswer.post, post).fetchJoin()
                .where(post.id.eq(postId))
                .orderBy(postAnswer.answerOrder.asc()) // 1,2,3,4
                .fetch();
    }

    @Override
    public List<PostResponseDTO.PostFeedResponseDTO.PostSummary> findPostFeed(Long memberId, FeedType feedType, Long lastPostId, Integer size, List<Long> followingIds, List<Long> circleIds) {

        BooleanBuilder condition = new BooleanBuilder();

        // 기본 조건: 발행된 게시글만
        condition.and(post.status.eq(PostStatus.PUBLISHED));

        // 피드 타입별 필터링 (캐시된 ID 리스트 사용 — 서브쿼리 제거)
        BooleanBuilder memberCondition = new BooleanBuilder();

        if (feedType == FeedType.ALL) {
            // 팔로우하는 모든 사용자의 게시글 + 내 게시글
            memberCondition.or(post.member.id.eq(memberId));
            if (!followingIds.isEmpty()) {
                memberCondition.or(post.member.id.in(followingIds));
            }
        } else if (feedType == FeedType.CORE) {
            // 친한친구로 설정한 사용자들의 게시글만
            if (!circleIds.isEmpty()) {
                memberCondition.and(post.member.id.in(circleIds));
            } else {
                // 서클 팔로잉이 없으면 결과 없음
                memberCondition.and(post.member.id.eq(-1L));
            }
        }

        condition.and(memberCondition);

        // 공개 범위 필터링 (캐시된 ID 리스트 사용 — 서브쿼리 제거)
        BooleanBuilder visibilityCondition = new BooleanBuilder();

        // PUBLIC 게시글은 누구나 볼 수 있음
        visibilityCondition.or(post.publicity.eq(PublicityType.OFFICIAL));

        // CIRCLE 게시글은 서로 친한친구인 경우만 보이도록
        if (!circleIds.isEmpty()) {
            visibilityCondition.or(
                    post.publicity.eq(PublicityType.CIRCLE)
                            .and(post.member.id.in(circleIds))
            );
        }

        // ALL 피드에서만 내 게시글의 모든 공개범위 허용
        if (feedType == FeedType.ALL) {
            visibilityCondition.or(
                    post.member.id.eq(memberId)
                            .and(post.publicity.in(PublicityType.OFFICIAL, PublicityType.CIRCLE, PublicityType.PERSONAL))
            );
        }

        condition.and(visibilityCondition);

        // 커서 페이지네이션 조건 추가
        if (lastPostId != null) {
            condition.and(post.id.lt(lastPostId));
        }

        // Pull 모델: 실시간으로 게시글 조회
        List<Post> posts = jpaQueryFactory
                .selectFrom(post)
                .leftJoin(post.member, member).fetchJoin()
                .leftJoin(member.profileImg, profileImg).fetchJoin()
                .where(condition)
                .orderBy(post.id.desc()) // 최신순 정렬
                .limit(size + 1) // hasNext 체크를 위해 +1
                .fetch();

        // 게시글 ID 리스트 추출
        List<Long> postIds = posts.stream()
                .map(Post::getId)
                .toList();

        // 각 게시글의 생성일자를 Map으로 저장 (질문 조회용)
        Map<Long, LocalDate> postDateMap = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p.getCreatedAt().toLocalDate()));

        //  모든 게시글의 답변을 한 번에 조회 (N+1 방지) - answerOrder로 정렬
        List<com.coredisc.domain.post.PostAnswer> allAnswers = jpaQueryFactory
                .selectFrom(postAnswer)
                .leftJoin(postAnswer.postAnswerImage, postAnswerImage).fetchJoin()
                .where(postAnswer.post.id.in(postIds))
                .orderBy(postAnswer.post.id.asc(), postAnswer.answerOrder.asc()) // answerOrder로 정렬 추가
                .fetch();

        // 게시글별로 답변 그룹핑 (answerOrder 순서대로)
        Map<Long, List<PostAnswer>> answersMap = allAnswers.stream()
                .collect(Collectors.groupingBy(
                        answer -> answer.getPost().getId(),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream()
                                        .sorted(Comparator.comparing(PostAnswer::getAnswerOrder))
                                        .collect(Collectors.toList())
                        )
                ));

        // TodayQuestion 배치 조회 (N+1 방지)
        // 모든 게시글의 멤버 ID와 날짜 범위를 수집하여 한 번에 조회
        Set<Long> memberIds = posts.stream()
                .limit(size)
                .map(p -> p.getMember().getId())
                .collect(Collectors.toSet());

        LocalDate minDate = postDateMap.values().stream().min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate maxDate = postDateMap.values().stream().max(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate startOfMinMonth = minDate.withDayOfMonth(1);
        LocalDate endOfMaxMonth = maxDate.withDayOfMonth(maxDate.lengthOfMonth());

        List<TodayQuestion> allQuestions = todayQuestionRepository.findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
                new ArrayList<>(memberIds), List.of(1, 2, 3, 4), startOfMinMonth, endOfMaxMonth);

        // (memberId, questionOrder, yearMonth) -> TodayQuestion 룩업 맵 구성
        Map<String, TodayQuestion> questionLookup = new HashMap<>();
        for (TodayQuestion tq : allQuestions) {
            Long mId = tq.getMember().getId();
            int order = tq.getQuestionOrder();
            LocalDate selDate = tq.getSelectedDate();

            if (order <= 3) {
                // 월별 질문: key = memberId:order:yearMonth
                String key = mId + ":" + order + ":" + selDate.getYear() + "-" + selDate.getMonthValue();
                questionLookup.putIfAbsent(key, tq);
            } else {
                // 일별 질문: key = memberId:order:date
                String key = mId + ":" + order + ":" + selDate;
                questionLookup.putIfAbsent(key, tq);
            }
        }

        // PostSummary DTO로 변환
        return posts.stream()
                .limit(size) // hasNext 체크 후 실제 반환할 크기로 제한
                .map(postEntity -> {
                    List<PostAnswer> postAnswers = answersMap.getOrDefault(postEntity.getId(), List.of());

                    // 첫 번째 답변 가져오기 (answerOrder가 가장 작은 값)
                    PostAnswer firstAnswer = postAnswers.isEmpty() ? null : postAnswers.get(0);

                    // 첫 번째 답변에 매칭되는 질문 찾기 (룩업 맵에서 조회)
                    String firstQuestion = null;
                    if (firstAnswer != null) {
                        LocalDate postDate = postDateMap.get(postEntity.getId());
                        int answerOrder = firstAnswer.getAnswerOrder();
                        Long mId = postEntity.getMember().getId();

                        String lookupKey;
                        if (answerOrder <= 3) {
                            lookupKey = mId + ":" + answerOrder + ":" + postDate.getYear() + "-" + postDate.getMonthValue();
                        } else {
                            lookupKey = mId + ":" + answerOrder + ":" + postDate;
                        }

                        TodayQuestion tq = questionLookup.get(lookupKey);
                        if (tq != null) {
                            firstQuestion = tq.getQuestionContent();
                        }
                    }

                    return PostConverter.toPostSummary(postEntity, firstAnswer, firstQuestion);
                })
                .collect(Collectors.toList());
    }

    @Override
    public Post findPostDetail(Long memberId, Long postId) {

        Post post = jpaQueryFactory
                .selectFrom(QPost.post)
                .leftJoin(QPost.post.member, member).fetchJoin()
                .leftJoin(member.profileImg, profileImg).fetchJoin()
                .where(QPost.post.id.eq(postId)
                        .and(QPost.post.status.eq(PostStatus.PUBLISHED)))
                .fetchOne();

        if (post == null) {
            return null;
        }


        return post;
    }






    // 캘린더 기능에 사용하기 위한 메소드 추가
    @Override
    public List<CalendarPostDTO> findPostInfoByMemberAndMonth(int year, int month, Member member) {
        QPost p = post;

        LocalDate start = DateUtil.getStartDate(year, month);
        LocalDate end = DateUtil.getEndDate(year, month);

        return jpaQueryFactory
                .select(Projections.constructor(
                        CalendarPostDTO.class,
                        p.id,
                        p.createdAt
                ))
                .from(p)
                .where(
                        p.member.eq(member),
                        p.status.ne(PostStatus.TEMP),
                        p.createdAt.between(start.atStartOfDay(), end.atTime(LocalTime.MAX))
                )
                .orderBy(p.createdAt.asc())
                .fetch();
    }

    @Override
    public List<Post> findPostsByCreatedDate(LocalDate targetDate) {
        QPost post = QPost.post;

        return jpaQueryFactory
                .selectFrom(post)
                .where(post.createdAt.between(
                        targetDate.atStartOfDay(),
                        targetDate.plusDays(1).atStartOfDay().minusNanos(1)),
                        post.status.ne(PostStatus.TEMP)
                )
                .fetch();
    }

    @Override
    public List<Long> findDistinctMemberIdsByCreatedAtBetween(LocalDateTime start, LocalDateTime end) {
        QPost post = QPost.post;

        return jpaQueryFactory
                .select(post.member.id)
                .distinct()
                .from(post)
                .where(post.createdAt.between(start, end))
                .fetch();
    }


    @Override
    public List<Member> findMembersByPostCreatedAtBetween(LocalDateTime start, LocalDateTime end) {
        QPost post = QPost.post;

        return jpaQueryFactory
                .select(post.member)
                .distinct()
                .from(post)
                .where(post.createdAt.between(start, end),
                        post.status.ne(PostStatus.TEMP))
                .fetch();
    }
}
