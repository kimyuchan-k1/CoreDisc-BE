package com.coredisc.application.service.post;


import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.PostConverter;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.common.enums.AnswerType;
import com.coredisc.domain.common.enums.PostStatus;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.*;
import com.coredisc.domain.postAnswer.PostAnswerRepository;
import com.coredisc.domain.postAnswerImage.PostAnswerImageRepository;
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
import com.coredisc.infrastructure.aws.s3.ImageStorageService;
import com.coredisc.infrastructure.aws.s3.ImageUploadResult;
import com.coredisc.presentation.dto.post.PostRequestDTO;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import com.coredisc.application.event.PostDeletedEvent;
import com.coredisc.application.event.PostPublishedEvent;
import com.coredisc.application.service.feed.FeedCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Slf4j
public class PostCommandServiceImpl implements PostCommandService {
    private final PostRepository postRepository;
    private final PostAnswerRepository postAnswerRepository;
    private final PostAnswerImageRepository postAnswerImageRepository;
    private final TodayQuestionRepository todayQuestionRepository;
    private final ImageStorageService amazonS3Manager;
    private final FeedCacheService feedCacheService;
    private final ApplicationEventPublisher eventPublisher;

    //  빈 게시글 생성
    @Override
    @Transactional
    public PostResponseDTO.CreatePostResultDto createEmptyPost(Member member, PostRequestDTO.CreatePostDto req) {

        LocalDate selectedDate = req.getSelectedDate();

        log.info("빈 게시글 생성 시작 - 회원ID: {}, 날짜: {}", member.getId(), selectedDate);

        // 중복 검증 - 오늘 날짜에 이미 등록된 게시글이 있다면? 예외를 던진다.
        validatePostNotExists(member);

        List<TodayQuestion> todayQuestions = validateTodayQuestions(member);

        Post emptyPost = Post.builder()
                .member(member)
                .status(PostStatus.TEMP)
                .likeCount(0)
                .commentCount(0)
                .viewCount(0)
                .build();

        Post savedPost = postRepository.save(emptyPost);

        return PostConverter.toCreatePostResponse(savedPost, todayQuestions, selectedDate);

    }


    @Override
    public PostResponseDTO.AnswerResultDto updateTextAnswer(Member member,Long postId, Integer questionOrder, PostRequestDTO.TextAnswerDto request) {

        //게시글 및 권한 확인
        Post post = validatePostOwnership(member,postId);

        log.info("게시판 가져오기 게시글ID: {}",post.getId() );

        //  질문 데이터 가져오기
        TodayQuestion todayQuestion = validateTodayQuestion(member, questionOrder);

        log.info("질문ID: {}",todayQuestion.getId());

        // 답변 데이터 가져오기
        Optional<PostAnswer> existingAnswer = postAnswerRepository
                .findByPostAndQuestionOrder(post,questionOrder);

        PostAnswer answer;

        if(existingAnswer.isPresent()) {

            // 이미 존재하는 답변이 있는 경우
            answer = existingAnswer.get();

            // 기존 답변이 이미지 일 겨우 삭제 처리
            deleteExistingImageIfPresent(answer);

            answer.updateTextAnswer(request.getContent());

        } else {
            // 질문 저장 - > 만약 질문이 발행 질문이라면? 발행 질문 content 를 가져온다.
            String questionContent = todayQuestion.getQuestionContent();
            log.info("questionContent:{}",questionContent);

            answer = PostAnswer.builder()
                    .post(post)
                    .answerOrder(questionOrder)
                    .type(AnswerType.TEXT)
                    .textContent(request.getContent())
                    .build();

        }

        PostAnswer savedAnswer = postAnswerRepository.save(answer);

        return PostConverter.toAnswerResultDto(savedAnswer);
    }


    /**
     * 이미지 작성 및 수정 로직 구현
     */
    @Override
    @Transactional
    public PostResponseDTO.AnswerResultDto updateImageAnswer(Member member, Long postId, Integer questionOrder, MultipartFile image) {

        log.info("이미지 답변 수정 시작 - 회원ID: {}, 게시글ID: {}, 질문타입: {}, 파일: {}",
                member.getId(), postId, questionOrder, image.getOriginalFilename());

        // 1. 게시글 및 권한 확인
        Post post = validatePostOwnership(member, postId);

        // 2. 질문 조회 및 검증
        TodayQuestion todayQuestion = validateTodayQuestion(member, questionOrder);

        // 3. 이미지 파일 저장
        ImageUploadResult s3Result = amazonS3Manager.uploadFile(image, member.getId());

        // 4. 기존 답변 조회
        Optional<PostAnswer> existingAnswer = postAnswerRepository
                .findByPostAndQuestionOrder(post,questionOrder);

        PostAnswer answer;

        if (existingAnswer.isPresent()) {
            // 기존 답변 수정
            answer = existingAnswer.get();

            // 기존 이미지 파일 삭제
            deleteExistingImageIfPresent(answer);

            // 새 이미지로 교체
            PostAnswerImage newImage = createPostAnswerImage(answer, s3Result);
            answer.updateToImageAnswer(newImage);

            log.info("기존 이미지 답변 수정 완료 - 답변ID: {}", answer.getId());

        } else {

            answer = PostAnswer.builder()
                    .post(post)
                    .answerOrder(questionOrder)
                    .type(AnswerType.IMAGE)
                    .build();

            // 이미지 엔티티 생성 및 연결
            PostAnswerImage answerImage = createPostAnswerImage(answer, s3Result);
            answer.updateToImageAnswer(answerImage);

            log.info("새 이미지 답변 생성 완료");
        }

        PostAnswer savedAnswer = postAnswerRepository.save(answer);

        return PostConverter.toAnswerResultDto(savedAnswer);
    }

    // 게시글 발행하기 - 실제 발행
    @Override
    @Transactional
    public Post publishPost(Member member, Long postId, PostRequestDTO.PublishPostDto request) {
        Post post = validatePostOwnership(member, postId);
        PostRequestDTO.SelectiveDiaryDto selectiveDiaryDto = request.getSelectiveDiary();

        // 예외처리 추가 -> 답변이 하나라도 작성 안된 경우
        if(post.getAnswers().size() != 4) {
            throw new PostHandler(ErrorStatus.POST_NOT_READY_TO_PUBLISH);
        }

        //발행으로 변경
        post.publish();
        // 선택형 일기 업데이트
        post.updateSelectiveDiary(
                selectiveDiaryDto.getWho(),
                selectiveDiaryDto.getWhere(),
                selectiveDiaryDto.getWhat(),
                selectiveDiaryDto.getDetail());

        post.updatePublicity(request.getPublicity());


        Post savedPost = postRepository.save(post);

        // 본인 피드 캐시 즉시 무효화 (팔로워 캐시는 TTL 30초 후 자동 만료)
        feedCacheService.evict(member.getId());

        // Fan-out: 팔로워 인박스에 push (트랜잭션 커밋 후 비동기 실행)
        eventPublisher.publishEvent(
                PostPublishedEvent.of(savedPost.getId(), member.getId(), request.getPublicity()));

        log.info("게시글 발행 완료 - 게시글 ID: {}, 회원 ID: {}",postId, member.getId());
        return savedPost;

    }

    /**
     * 게시글 삭제
     */
    @Transactional
    @Override
    public void deletePost(Member member, Long postId) {

        // 1.게시글 조회 및 검증
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

        post.validateOwnership(member);

        // imageUrl 불러오기
        List<String> imageUrls = extractImageUrls(post);
        List<String> thumbnailUrls = extractThumbnailUrls(post);

        // S3 이미지 파일들 삭제 (트랜잭션 외부에서 처리)
        deleteS3Images(imageUrls);
        deleteS3Images(thumbnailUrls);

        //TODO : 삭제 전 정리 작업 -> 통계 업데이트, 로그 기록,,..

        // 삭제 전 publicity 저장 (이벤트 발행에 필요)
        var publicity = post.getPublicity();

        // DB에서 게시글 삭제 (Cascade로 연관 엔티티들 자동 삭제)
        postRepository.delete(post);

        // Fan-out 인박스 정리 (트랜잭션 커밋 후 비동기 실행)
        if (post.isPublished()) {
            eventPublisher.publishEvent(PostDeletedEvent.of(postId, member.getId(), publicity));
        }

        log.info("게시글 완전 삭제 완료 - 게시글ID: {}, 회원ID: {}, 삭제된 이미지 수: {}",
                postId, member.getId(), imageUrls.size());
    }


    private PostAnswerImage createPostAnswerImage(PostAnswer answer, ImageUploadResult s3Result) {
        return PostAnswerImage.builder()
                .postAnswer(answer)
                .imgUrl(s3Result.getOriginalUrl())
                .thumbnailUrl(s3Result.getThumbnailUrl())
                .originalFileName(s3Result.getOriginalFileName())
                .build();
    }

    /**
     * 기존 이미지 파일 삭제
     */
    private void deleteExistingImageIfPresent(PostAnswer answer) {
        // 만약 이미지 가 있다면?
        if (answer.getType() == AnswerType.IMAGE && answer.getPostAnswerImage() != null) {
            PostAnswerImage existingImage = answer.getPostAnswerImage();

            // 실제 파일 삭제 -> image, thumbnail url 둘 다 삭제
            if (existingImage.getImgUrl() != null) {
                amazonS3Manager.deleteImage(existingImage.getImgUrl());
            }
            if(existingImage.getThumbnailUrl() != null) {
                amazonS3Manager.deleteImageByUrl(existingImage.getThumbnailUrl());
            }

            // DB에서 이미지 엔티티 삭제
            postAnswerImageRepository.delete(existingImage);

        }

    }


    /**
     * 오늘 발행한 게시글이 있는지 검증
     */

    private void validatePostNotExists(Member member) {
        LocalDate today = LocalDate.now();
        boolean exists = postRepository.existsByMemberAndStatusAndCreatedAtBetween(
                member,
                PostStatus.PUBLISHED,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );

        if(exists)
        {
            throw new PostHandler(ErrorStatus.POST_ALREADY_PUBLISHED);
        }}

    /**
     * 오늘의 질문 리스트 가져오기
     */
    private List<TodayQuestion> validateTodayQuestions(Member member) {

        LocalDate today = LocalDate.now();
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);

        List<TodayQuestion> todayQuestions = new ArrayList<>();

        // 고정질문과 랜덤질문 가져오기

        for (int order = 1; order <= 4; order++) {
            Optional<TodayQuestion> todayQuestion;

            if (order == 4) {
                todayQuestion = todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDate(member, order, today);
            } else {
                todayQuestion = todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween(member, order, startOfMonth, endOfMonth);
            }

            // 오늘의 질문이 없는 경우
            if(todayQuestion.isEmpty()) {
                throw new PostHandler(ErrorStatus.INCOMPLETE_TODAY_QUESTIONS);
            }

            todayQuestions.add(todayQuestion.get());

        }

        // 오늘의 질문이 하나라도 없는 경우 - 예외처리
        for (TodayQuestion todayQuestion : todayQuestions) {
            if(todayQuestion.getQuestionContent().isEmpty())
            {
                throw new PostHandler(ErrorStatus.INCOMPLETE_TODAY_QUESTIONS);
            }
        }
        return todayQuestions;
    }

    /**
     * questionOrder 에 해당하는 질문 검증 및 조회
     */

    private TodayQuestion validateTodayQuestion(Member member, Integer questionOrder) {
        LocalDate today = LocalDate.now();
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);


        Optional<TodayQuestion> todayQuestion = questionOrder == 4 ?
                todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDate(member, questionOrder,today)
                : todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween(member, questionOrder,startOfMonth,endOfMonth);

        if(todayQuestion.isEmpty()) throw new PostHandler(ErrorStatus.QUESTION_TYPE_NOT_FOUND);

        return todayQuestion.get();
    }


    private Post validatePostOwnership(Member member, Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow( () -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

        // 실제 포스트 작성자인지
        if(!post.getMember().getId().equals(member.getId())) {
            throw new PostHandler(ErrorStatus.NOT_POST_OWNER);
        }

        if(post.isPublished()) {
            throw new PostHandler(ErrorStatus.POST_ALREADY_PUBLISHED);
        }

        return post;

    }

    /**
     * 게시글에서 모든 이미지 URL 추출
     */
    private List<String> extractImageUrls(Post post) {
        return post.getAnswers().stream()
                .filter(answer -> answer.getType() == AnswerType.IMAGE)
                .filter(answer -> answer.getPostAnswerImage() != null)
                .map(answer -> answer.getPostAnswerImage().getImgUrl())
                .filter(url -> url != null && !url.isEmpty())
                .toList();
    }

    /**
     * 게시글에서 모든 썸네일 URL 추출
     */

    private List<String> extractThumbnailUrls(Post post) {
        return post.getAnswers().stream()
                .filter(answer -> answer.getType() == AnswerType.IMAGE)
                .filter(answer -> answer.getPostAnswerImage() != null)
                .map(answer -> answer.getPostAnswerImage().getThumbnailUrl())
                .filter(url -> url != null && !url.isEmpty())
                .toList();
    }


    /**
     * S3에서 이미지 파일들 삭제 (실패해도 전체 프로세스 중단하지 않음)
     */
    private void deleteS3Images(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            try {
                amazonS3Manager.deleteImageByUrl(imageUrl);
                log.info("S3 이미지 파일 삭제 성공: {}", imageUrl);
            } catch (Exception e) {
                log.error("S3 이미지 파일 삭제 실패: {}, 에러: {}", imageUrl, e.getMessage());
                // S3 삭제 실패는 전체 삭제를 중단시키지 않음
                // 별도의 배치 작업으로 정리하거나 모니터링 필요
            }
        }
    }

    /**
     * 임시저장 게시글 정리 (배치 작업용) — 청크 기반 페이지네이션
     */
    @Transactional
    public void cleanupOldTempPosts(LocalDate cutoffDate) {
        log.info("[배치] {}일 이전 임시저장 게시글 정리 작업 시작", cutoffDate);

        final int CHUNK_SIZE = 100;
        int deletedCount = 0;
        int failedCount = 0;
        int page = 0;

        org.springframework.data.domain.Page<Post> postPage;
        do {
            // 항상 page=0으로 조회 (삭제하면 다음 페이지 데이터가 당겨오므로)
            postPage = postRepository.findTempPostsPageable(
                    PostStatus.TEMP,
                    cutoffDate.plusDays(1).atStartOfDay(),
                    org.springframework.data.domain.PageRequest.of(0, CHUNK_SIZE)
            );

            for (Post post : postPage.getContent()) {
                try {
                    List<String> imageUrls = extractImageUrls(post);
                    List<String> thumbnailUrls = extractThumbnailUrls(post);

                    amazonS3Manager.deleteImagesByUrls(imageUrls);
                    amazonS3Manager.deleteImagesByUrls(thumbnailUrls);

                    postRepository.delete(post);
                    deletedCount++;
                } catch (Exception e) {
                    failedCount++;
                    log.error("TEMP 게시글 삭제 실패 - ID: {}, 에러: {}", post.getId(), e.getMessage());
                }
            }

            page++;
            log.info("[배치] 청크 {} 처리 완료 - 이번 청크 {}건", page, postPage.getNumberOfElements());
        } while (postPage.hasNext());

        log.info("[배치] 임시저장 게시글 삭제 완료 - 성공: {}건, 실패: {}건", deletedCount, failedCount);
    }

}
