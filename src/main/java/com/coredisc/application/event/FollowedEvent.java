package com.coredisc.application.event;

import lombok.Getter;

@Getter
public class FollowedEvent extends RelationshipEvent {

    private final String actorNickname;

    private FollowedEvent(Long followerId, Long followingId, String actorNickname) {
        super(followerId, followingId);
        this.actorNickname = actorNickname;
    }

    public static FollowedEvent of(Long followerId, Long followingId, String nickname) {
        return new FollowedEvent(followerId, followingId, nickname);
    }
}
