package com.coredisc.application.event;

public class UnfollowedEvent extends RelationshipEvent {

    private UnfollowedEvent(Long unfollowerId, Long unfollowedId) {
        super(unfollowerId, unfollowedId);
    }

    public static UnfollowedEvent of(Long unfollowerId, Long unfollowedId) {
        return new UnfollowedEvent(unfollowerId, unfollowedId);
    }
}
