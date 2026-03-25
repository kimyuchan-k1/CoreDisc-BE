package com.coredisc.domain.comment;

import com.coredisc.domain.Comment;
import com.coredisc.presentation.dto.cursor.CursorDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CommentRepository {

    Comment save(Comment comment);

    Optional<Comment> findById(Long id);

    void delete(Comment comment);

    //댓글 존재 여부 확인
    boolean existsById(Long commentId);

    // 부모 댓글만 조회
    CursorDTO<Comment> findParentCommentByCursor(Long postId, Long cursorId, Integer size, Long memberId);

    // 특정 부모 댓글의 대댓글 조회
    CursorDTO<Comment> findRepliesByParentId(Long parentId, Long cursorId, Integer size, Long memberId);

    // 부모 댓글 ID 목록으로 대댓글 수 일괄 조회
    Map<Long, Long> countRepliesByParentIds(List<Long> parentIds);

    List<Comment> findAllActiveByMemberIdAndPostMemberId(Long commenterId, Long postAuthorId);

    int softDeleteAllByMemberIdAndPostMemberId(Long commenterId, Long postAuthorId);
}
