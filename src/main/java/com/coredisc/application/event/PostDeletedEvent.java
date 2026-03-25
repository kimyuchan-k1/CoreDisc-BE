package com.coredisc.application.event;

import com.coredisc.domain.common.enums.PublicityType;
import lombok.Getter;

@Getter
public class PostDeletedEvent {

    private final Long postId;
    private final Long authorId;
    private final PublicityType publicity;

    private PostDeletedEvent(Long postId, Long authorId, PublicityType publicity) {
        this.postId = postId;
        this.authorId = authorId;
        this.publicity = publicity;
    }

    public static PostDeletedEvent of(Long postId, Long authorId, PublicityType publicity) {
        return new PostDeletedEvent(postId, authorId, publicity);
    }
}
