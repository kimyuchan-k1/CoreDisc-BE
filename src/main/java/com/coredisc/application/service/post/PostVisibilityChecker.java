package com.coredisc.application.service.post;

import com.coredisc.application.service.follow.FollowQueryService;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.PostHandler;
import com.coredisc.domain.block.BlockRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.post.Post;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PostVisibilityChecker {

    private final FollowQueryService followQueryService;
    private final BlockRepository blockRepository;

    public void validateAccess(Post post, Member requester) {
        Long requesterId = requester.getId();
        Long authorId = post.getMember().getId();

        // 본인 글 → 항상 접근 가능
        if (requesterId.equals(authorId)) return;

        // Block 양방향 체크
        if (isBlocked(requester, post.getMember())) {
            throw new PostHandler(ErrorStatus.POST_NOT_FOUND);
        }

        // Publicity별 접근 판단
        switch (post.getPublicity()) {
            case OFFICIAL -> { /* 비차단 유저는 모두 접근 가능 */ }
            case CIRCLE -> {
                List<Long> circleIds = followQueryService.getCircleFollowingIds(requesterId);
                if (!circleIds.contains(authorId)) {
                    throw new PostHandler(ErrorStatus.POST_NOT_FOUND);
                }
            }
            case PERSONAL -> throw new PostHandler(ErrorStatus.POST_NOT_FOUND);
        }
    }

    private boolean isBlocked(Member a, Member b) {
        return blockRepository.existsByBlockerAndBlocked(a, b)
                || blockRepository.existsByBlockerAndBlocked(b, a);
    }
}
