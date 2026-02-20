package com.coredisc.infrastructure.repository.post.queryDsl;

import com.coredisc.domain.common.enums.FeedType;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import com.coredisc.presentation.dto.calendar.CalendarPostDTO;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface QueryPostRepository {

    List<Post> findMyPostsWithAnswers(Member member, Long cursorId, Pageable pageable);

    List<Post> findUserPostsWithAnswers(Member member, boolean isCircle, Long cursorId, Pageable pageable);

    boolean existsByMemberAndIdLessThan(Member member, Long id, Set<PublicityType> allowTypes);

    List<Post> findTempPostByMemberAndDate(Member member, LocalDate today);

    List<PostAnswer> findTempPostAnswerByPostId(Long postId);

    List<PostResponseDTO.PostFeedResponseDTO.PostSummary> findPostFeed(Long memberId, FeedType feedType, Long lastPostId, Integer size, List<Long> followingIds, List<Long> circleIds);

    Post findPostDetail(Long memberId, Long postId);

    List<CalendarPostDTO> findPostInfoByMemberAndMonth(int year, int month, Member member);
    List<Post> findPostsByCreatedDate(LocalDate targetDate);
    List<Long> findDistinctMemberIdsByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    List<Member> findMembersByPostCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
