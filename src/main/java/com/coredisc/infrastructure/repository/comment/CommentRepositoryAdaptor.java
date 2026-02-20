package com.coredisc.infrastructure.repository.comment;

import com.coredisc.domain.Comment;
import com.coredisc.domain.comment.CommentRepository;
import com.coredisc.infrastructure.repository.comment.queryDsl.CommentQueryRepository;
import com.coredisc.presentation.dto.cursor.CursorDTO;
import com.querydsl.core.QueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CommentRepositoryAdaptor implements CommentRepository {

    private final JpaCommentRepository jpaCommentRepository;
    private final CommentQueryRepository commentQueryRepository;

    @Override
    public Comment save(Comment comment) {
        return jpaCommentRepository.save(comment);
    }

    @Override
    public Optional<Comment> findById(Long id) {
        return jpaCommentRepository.findById(id);
    }

    @Override
    public void delete(Comment comment) {
        jpaCommentRepository.delete(comment);
    }

    @Override
    public boolean existsById(Long commentId) {
        return false;
    }

    @Override
    public CursorDTO<Comment> findParentCommentByCursor(Long postId, Long cursorId, Integer size, Long memberId) {

        return commentQueryRepository.findParentCommentsByCursor(postId,cursorId,size,memberId);
    }

    @Override
    public CursorDTO<Comment> findRepliesByParentId(Long parentId, Long cursorId, Integer size, Long memberId) {
        return commentQueryRepository.findRepliesByParentIds(parentId,cursorId,size,memberId);
    }

    @Override
    public Map<Long, Long> countRepliesByParentIds(List<Long> parentIds) {
        return commentQueryRepository.countRepliesByParentIds(parentIds);
    }

}
