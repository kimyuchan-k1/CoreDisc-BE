package com.coredisc.application.event;

public class BlockedEvent extends RelationshipEvent {

    private BlockedEvent(Long blockerId, Long blockedId) {
        super(blockerId, blockedId);
    }

    public static BlockedEvent of(Long blockerId, Long blockedId) {
        return new BlockedEvent(blockerId, blockedId);
    }
}
