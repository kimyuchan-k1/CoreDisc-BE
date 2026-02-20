package com.coredisc.application.service.post;

import com.coredisc.application.service.follow.FollowQueryService;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.PostConverter;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostAnswer;
import com.coredisc.domain.post.PostLikeRepository;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
import com.coredisc.presentation.dto.post.PostRequestDTO;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PostQueryServiceImpl implements PostQueryService {

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final TodayQuestionRepository todayQuestionRepository;
    private final FollowQueryService followQueryService;

    @Override
    public List<Post> getTempPosts(Member member) {

        LocalDate today = LocalDate.now();

        List<Post> posts = postRepository.findTempPostByMemberAndDate(member,today);

        if(posts.isEmpty()) throw new PostHandler(ErrorStatus.POST_NOT_FOUND);

        return posts;
    }


    @Override
    public PostResponseDTO.TempPostDetailDto getTempPost(Member member, Long postId) {


        // 게시글 단건 조회
        Post post = postRepository.findById(postId).orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

        // 임시저장된 게시글인지 확인
        if (post.getStatus() != PostStatus.TEMP) {
            throw new PostHandler(ErrorStatus.POST_ALREADY_PUBLISHED);
        }

        // 3. 작성자 본인인지 확인
        if (!post.getMember().getId().equals(member.getId())) {
            throw new PostHandler(ErrorStatus.NOT_POST_OWNER);
        }

        // 4. 해당 게시글의 답변들 조회 (1,2,3,4 순서로)
        List<PostAnswer> answers = postRepository.findTempPostWithAnswers(post.getId());


        return PostConverter.toTempPostDetailDto(post, answers);
    }

    @Override
    public PostResponseDTO.PostFeedResponseDTO findPostFeed(Member member, PostRequestDTO.PostFeedRequestDto request) {
        // 캐시된 팔로잉/서클 ID 목록 조회 (Caffeine 캐시 히트 시 DB 쿼리 없음)
        List<Long> followingIds = followQueryService.getFollowingIds(member.getId());
        List<Long> circleIds = followQueryService.getCircleFollowingIds(member.getId());

        List<PostResponseDTO.PostFeedResponseDTO.PostSummary> posts = postRepository.findPostFeed(
                member,
                request.getFeedType(),
                request.getLastPostId(),
                request.getSize(),
                followingIds,
                circleIds
        );

        // hasNext 체크
        boolean hasNext = posts.size() > request.getSize();
        if (hasNext) {
            posts = posts.subList(0, request.getSize());
        }

        // nextCursor 설정
        Long nextCursor = null;
        if (hasNext && !posts.isEmpty()) {
            nextCursor = posts.get(posts.size() - 1).getPostId();
        }

        return PostConverter.toPostFeedResponseDto(posts, nextCursor,hasNext);
    }

    @Override
    public PostResponseDTO.PostDetailDto findPostDetail(Member member, Long postId) {

        Post findPost = postRepository.findById(postId).orElseThrow(
                () -> new PostHandler(ErrorStatus.POST_NOT_FOUND)
        );

        Post post = postRepository.findPostDetail(findPost.getMember(),postId);

        List<PostAnswer> answers = postRepository.findTempPostWithAnswers(postId);

        // 게시글 존재 여부 예외처리
        if(post == null) throw new PostHandler(ErrorStatus.POST_NOT_FOUND);

        // 발행 여부 예외처리
        if(post.isTemp()) throw new PostHandler(ErrorStatus.POST_NOT_READY_TO_PUBLISH);

        // 좋아요 여부 체크
        boolean isLiked = checkIsLiked(member.getId(),postId);

        // 본인 여부 체크
        boolean isOwner = checkIsOwner(member.getId(), post.getMember().getId());


        LocalDate date = post.getCreatedAt().toLocalDate();

        List<TodayQuestion> questions = findQuestionContent(post.getMember(),date);

        List<String> questionContents = questions.stream()
                .map(
                        TodayQuestion::getQuestionContent
                ).toList();


        return PostConverter.toPostDetailResponse(post,answers, questionContents, isLiked, isOwner );
    }

    private List<TodayQuestion> findQuestionContent(Member member, LocalDate date) {

        // 4개 개별 쿼리 → 1개 배치 쿼리 (@EntityGraph로 officialQuestion/personalQuestion 즉시 로딩)
        LocalDate startOfMonth = date.withDayOfMonth(1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);

        List<TodayQuestion> allQuestions = todayQuestionRepository.findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
                List.of(member.getId()),
                List.of(1, 2, 3, 4),
                startOfMonth,
                endOfMonth
        );

        // questionOrder 4는 해당 날짜만 필터, 1~3은 월 범위 내 첫 번째만 사용
        return allQuestions.stream()
                .filter(q -> q.getQuestionOrder() != 4 || q.getSelectedDate().equals(date))
                .toList();
    }

    /**
     * 좋아요 여부 확인 — ID 기반 쿼리로 엔티티 로딩 없이 직접 체크 (3 쿼리 → 1 쿼리)
     */
    private boolean checkIsLiked(Long memberId, Long postId) {
        return postLikeRepository.existsByMemberIdAndPostId(memberId, postId);
    }

    private boolean checkIsOwner(Long currentMemberId, Long postOwnerId) {
        return currentMemberId.equals(postOwnerId);
    }


}
