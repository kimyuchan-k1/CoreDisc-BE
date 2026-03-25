package com.coredisc.application.event;

import com.coredisc.domain.common.enums.PublicityType;
import lombok.Getter;

@Getter
public class PostPublishedEvent {

    private final Long postId;
    private final Long authorId;
    private final PublicityType publicity;

    private PostPublishedEvent(Long postId, Long authorId, PublicityType publicity) {
        this.postId = postId;
        this.authorId = authorId;
        this.publicity = publicity;
    }

    public static PostPublishedEvent of(Long postId, Long authorId, PublicityType publicity) {
        return new PostPublishedEvent(postId, authorId, publicity);
    }
}
