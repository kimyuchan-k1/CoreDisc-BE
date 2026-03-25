package com.coredisc.application.event;

import lombok.Getter;

@Getter
public class PostCountEvent {

    public enum CountType { LIKE_INCREMENT, LIKE_DECREMENT, COMMENT_INCREMENT, COMMENT_DECREMENT }

    private final Long postId;
    private final CountType countType;

    private PostCountEvent(Long postId, CountType countType) {
        this.postId = postId;
        this.countType = countType;
    }

    public static PostCountEvent likeIncrement(Long postId) {
        return new PostCountEvent(postId, CountType.LIKE_INCREMENT);
    }

    public static PostCountEvent likeDecrement(Long postId) {
        return new PostCountEvent(postId, CountType.LIKE_DECREMENT);
    }

    public static PostCountEvent commentIncrement(Long postId) {
        return new PostCountEvent(postId, CountType.COMMENT_INCREMENT);
    }

    public static PostCountEvent commentDecrement(Long postId) {
        return new PostCountEvent(postId, CountType.COMMENT_DECREMENT);
    }
}
