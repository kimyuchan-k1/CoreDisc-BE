package com.coredisc.application.service.comment;

import com.coredisc.application.event.NotificationEvent;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.CommentConverter;
import com.coredisc.common.exception.handler.CommentHandler;
import com.coredisc.common.exception.handler.MemberHandler;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.Comment;
import com.coredisc.domain.comment.CommentRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import com.coredisc.domain.post.Post;
import com.coredisc.domain.post.PostRepository;
import com.coredisc.presentation.dto.comment.CommentRequestDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CommentCommandServiceImpl implements CommentCommandService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Comment createComment(Long postId, CommentRequestDTO request, Long memberId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostHandler(ErrorStatus.POST_NOT_FOUND));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        Comment comment = CommentConverter.toComment(request.getContent(), post, member);
        Comment savedComment = commentRepository.save(comment);

        // 댓글 작성자와 게시글 작성자가 다른 경우에만 비동기 알림 발행
        if (!post.getMember().getId().equals(member.getId())) {
            eventPublisher.publishEvent(NotificationEvent.comment(
                    member.getId(), post.getMember().getId(),
                    member.getNickname(), post.getId()));
        }

        return savedComment;
    }

    @Override
    public Comment createReply(Long commentId, CommentRequestDTO request, Long memberId) {
        Comment parentComment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentHandler(ErrorStatus.COMMENT_NOT_FOUND));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberHandler(ErrorStatus.MEMBER_NOT_FOUND));

        if (parentComment.getDepth() >= 1) {
            throw new CommentHandler(ErrorStatus.COMMENT_DEPTH_EXCEEDED);
        }

        Comment reply = CommentConverter.toComment(request.getContent(), parentComment.getPost(), member);
        parentComment.addReply(reply);
        Comment savedReply = commentRepository.save(reply);

        // 부모 댓글 작성자와 대댓글 작성자가 다를 때만 비동기 알림 발행
        if (!parentComment.getMember().getId().equals(member.getId())) {
            eventPublisher.publishEvent(NotificationEvent.reply(
                    member.getId(), parentComment.getMember().getId(),
                    member.getNickname(), parentComment.getPost().getId()));
        }

        return savedReply;
    }

    @Override
    public void deleteComment(Long commentId, Long memberId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentHandler(ErrorStatus.COMMENT_NOT_FOUND));

        if (!comment.isOwner(memberId)) {
            throw new CommentHandler(ErrorStatus.COMMENT_ACCESS_DENIED);
        }

        commentRepository.delete(comment);
    }

}
