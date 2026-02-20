package com.coredisc.application.service.comment;

import com.coredisc.common.converter.CommentConverter;
import com.coredisc.domain.Comment;
import com.coredisc.domain.comment.CommentRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.presentation.dto.comment.CommentResponseDTO;
import com.coredisc.presentation.dto.cursor.CursorDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentQueryServiceImpl implements CommentQueryService{

    private final CommentRepository commentRepository;


    @Override
    public CursorDTO<CommentResponseDTO.CommentCreateResponse> getParentComments(Long postId, Long cursorId, Integer size, Member member){

        CursorDTO<Comment> page = commentRepository.findParentCommentByCursor(postId, cursorId, size, member.getId());

        // 대댓글 수를 배치 쿼리로 한 번에 조회 (N+1 방지)
        List<Long> parentIds = page.getValues().stream()
                .map(Comment::getId)
                .collect(Collectors.toList());
        Map<Long, Long> replyCountMap = commentRepository.countRepliesByParentIds(parentIds);

        return new CursorDTO<>(page.getValues().stream()
                .map(comment -> {
                    long replyCount = replyCountMap.getOrDefault(comment.getId(), 0L);
                    boolean hasChild = replyCount > 0;
                    return CommentConverter.toCreateResponseWithChildExists(
                            comment, hasChild, (int) replyCount, comment.isOwner(member.getId()));
                })
                .toList(),page.getHasNext());
    }

    @Override
    public CursorDTO<CommentResponseDTO.CommentCreateResponse> getChildComments(Long parentId, Long cursorId, Integer size, Member member){

        CursorDTO<Comment> page = commentRepository.findRepliesByParentId(parentId, cursorId, size,member.getId());

        return new CursorDTO<>(page.getValues().stream()
                .map(comment -> CommentConverter.toReplyCreateResponse(comment,comment.isOwner(member.getId())))
                .toList(),page.getHasNext());
    }

}
