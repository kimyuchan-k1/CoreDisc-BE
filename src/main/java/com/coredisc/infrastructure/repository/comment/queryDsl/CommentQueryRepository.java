package com.coredisc.infrastructure.repository.comment.queryDsl;


import com.coredisc.domain.Comment;
import com.coredisc.presentation.dto.cursor.CursorDTO;

import java.util.List;
import java.util.Map;

public interface CommentQueryRepository {

    CursorDTO<Comment> findParentCommentsByCursor(Long postId, Long cursorId, Integer size, Long memberId);

    CursorDTO<Comment> findRepliesByParentIds(Long parentId, Long cursorId, Integer size, Long memberId);

    Map<Long, Long> countRepliesByParentIds(List<Long> parentIds);

}
