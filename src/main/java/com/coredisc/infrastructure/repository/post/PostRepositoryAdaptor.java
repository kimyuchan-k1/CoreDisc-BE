package com.coredisc.infrastructure.repository.post;


import com.coredisc.domain.common.enums.FeedType;
import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.common.enums.PublicityType;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.infrastructure.repository.post.queryDsl.QueryPostRepository;
import com.coredisc.presentation.dto.calendar.CalendarPostDTO;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
@RequiredArgsConstructor
public class PostRepositoryAdaptor implements PostRepository {

    private final JpaPostRepository jpaPostRepository;
    private final QueryPostRepository queryPostRepository;

    @Override
    public Post save(Post post) {
        return jpaPostRepository.save(post);
    }

    @Override
    public Optional<Post> findById(Long id) {
        return jpaPostRepository.findById(id);
    }

    @Override
    public void delete(Post post) {
        jpaPostRepository.delete(post);
    }

    @Override
    public void deleteById(Long id) {

    }

    @Override
    public long countByMemberAndStatus(Member member, PostStatus status) {
        return jpaPostRepository.countByMemberAndStatus(member, PostStatus.PUBLISHED);
    }

    @Override
    public long countByMemberAndStatusAndPublicityIn(Member member, PostStatus status, List<PublicityType> publicityTypes) {
        return jpaPostRepository.countByMemberAndStatusAndPublicityIn(member, status, publicityTypes);
    }

    @Override
    public List<Post> findMyPostsWithAnswers(Member member, Long cursorId, Pageable pageable) {
        return queryPostRepository.findMyPostsWithAnswers(member, cursorId, pageable);
    }

    @Override
    public List<Post> findUserPostsWithAnswers(Member member, boolean isCircle, Long cursorId, Pageable pageable) {
        return queryPostRepository.findUserPostsWithAnswers(member, isCircle, cursorId, pageable);
    }

    @Override
    public boolean existsByMemberAndIdLessThan(Member member, Long id, Set<PublicityType> allowTypes) {
        return queryPostRepository.existsByMemberAndIdLessThan(member, id, allowTypes);
    }

    @Override
    public List<Post> findTempPostByMemberAndDate(Member member, LocalDate today) {
        return queryPostRepository.findTempPostByMemberAndDate(member, today);
    }

    @Override
    public Post findPostDetail(Member member, Long postId) {
        return queryPostRepository.findPostDetail(member.getId(), postId);
    }

    @Override
    public List<PostAnswer> findTempPostWithAnswers(Long postId) {

        return queryPostRepository.findTempPostAnswerByPostId(postId);
    }

    @Override
    public List<PostResponseDTO.PostFeedResponseDTO.PostSummary> findPostFeed(Member member, FeedType feedType, Long lastPostId, Integer size, List<Long> followingIds, List<Long> circleIds) {
        return queryPostRepository.findPostFeed(member.getId(), feedType, lastPostId, size, followingIds, circleIds);
    }


    @Override
    public List<CalendarPostDTO> findPostInfoByMemberAndMonth(int year, int month, Member member) {
        return queryPostRepository.findPostInfoByMemberAndMonth(year, month, member);
    }

    @Override
    public boolean existsByMemberAndStatusAndCreatedAtBetween(Member member, PostStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return jpaPostRepository.existsByMemberAndStatusAndCreatedAtBetween(member,
                status,
                startOfDay,
                endOfDay);
    }

    @Override
    public List<Post> findAllByStatusAndCreatedAtBefore(PostStatus status, LocalDateTime createdAt) {
        return jpaPostRepository.findAllByStatusAndCreatedAtBefore(status, createdAt);
    }

    @Override
    public org.springframework.data.domain.Page<Post> findTempPostsPageable(PostStatus status, LocalDateTime createdAt, Pageable pageable) {
        return jpaPostRepository.findAllByStatusAndCreatedAtBefore(status, createdAt, pageable);
    }

    @Override
    public List<Long> findDistinctMemberIdsByStatusAndCreatedAtBetween(PostStatus postStatus, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return jpaPostRepository.findDistinctMemberIdsByStatusAndCreatedAtBetween(postStatus, startOfDay, endOfDay);
    }

    @Override
    public List<Post> findPostsByCreatedDate(LocalDate targetDate) {
        return queryPostRepository.findPostsByCreatedDate(targetDate);
    }

    @Override
    public List<Long> findDistinctMemberIdsByCreatedAtBetween(LocalDateTime start, LocalDateTime end) {
        return queryPostRepository.findDistinctMemberIdsByCreatedAtBetween(start, end);
    }

    @Override
    public List<Member> findMembersByPostCreatedAtBetween(LocalDateTime start, LocalDateTime end) {
        return queryPostRepository.findMembersByPostCreatedAtBetween(start, end);
    }

    @Override
    public List<Post> findAllByMemberAndCreatedAtBetweenOrderByCreatedAtAsc(Member member, LocalDateTime start, LocalDateTime end) {
        return jpaPostRepository.findAllByMemberAndCreatedAtBetweenOrderByCreatedAtAsc(member, start, end);
    }
}