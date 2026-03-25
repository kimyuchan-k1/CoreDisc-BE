package com.coredisc.application.event;

import lombok.Getter;

@Getter
public abstract class RelationshipEvent {

    private final Long actorId;
    private final Long targetId;

    protected RelationshipEvent(Long actorId, Long targetId) {
        this.actorId = actorId;
        this.targetId = targetId;
    }
}
