package com.coredisc.infrastructure.repository.comment.queryDsl;

import com.coredisc.domain.Comment;
import com.coredisc.domain.QComment;
import com.coredisc.presentation.dto.cursor.CursorDTO;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.coredisc.domain.QComment.*;
import static com.coredisc.domain.member.QMember.*;
import static com.coredisc.domain.profileImg.QProfileImg.*;

@Repository
@RequiredArgsConstructor
public class commentQueryRepositoryImpl implements CommentQueryRepository{

    private final JPAQueryFactory queryFactory;


    @Override
    public CursorDTO<Comment> findParentCommentsByCursor(Long postId, Long cursorId, Integer size, Long memberId) {

        List<Comment> results = queryFactory
                .selectFrom(comment)
                .leftJoin(comment.member, member).fetchJoin()
                .leftJoin(member.profileImg, profileImg).fetchJoin()
                .where(
                        comment.post.id.eq(postId),
                        comment.depth.eq(0),
                        cursorId != null ? comment.id.lt(cursorId) : null
                )
                .orderBy(comment.id.desc())
                .limit(size+1)
                .fetch();
        return buildCursorPage(results,size);
    }

    @Override
    public CursorDTO<Comment> findRepliesByParentIds(Long parentId, Long cursorId, Integer size, Long memberId) {
        List<Comment> results = queryFactory
                .selectFrom(comment)
                .leftJoin(comment.member, member).fetchJoin()
                .leftJoin(member.profileImg, profileImg).fetchJoin()
                .where(
                        comment.parent.id.eq(parentId),
                        cursorId != null ? comment.id.lt(cursorId) : null
                )
                .orderBy(comment.id.desc())
                .limit(size+1)
                .fetch();

        return buildCursorPage(results,size);
    }

    @Override
    public Map<Long, Long> countRepliesByParentIds(List<Long> parentIds) {
        QComment reply = new QComment("reply");

        List<Tuple> results = queryFactory
                .select(reply.parent.id, reply.count())
                .from(reply)
                .where(reply.parent.id.in(parentIds))
                .groupBy(reply.parent.id)
                .fetch();

        return results.stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(reply.parent.id),
                        tuple -> tuple.get(reply.count())
                ));
    }

    private CursorDTO<Comment> buildCursorPage(List<Comment> results, int size){
        boolean hasNext = results.size() > size;
        if(hasNext){
            results = results.subList(0, size);
        }
        return new CursorDTO<>(results, hasNext);
    }
}