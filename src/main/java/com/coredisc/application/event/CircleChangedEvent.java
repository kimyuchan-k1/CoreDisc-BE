package com.coredisc.application.event;

import lombok.Getter;

@Getter
public class CircleChangedEvent extends RelationshipEvent {

    private final boolean isCircle;

    private CircleChangedEvent(Long setterId, Long targetId, boolean isCircle) {
        super(setterId, targetId);
        this.isCircle = isCircle;
    }

    public static CircleChangedEvent of(Long setterId, Long targetId, boolean isCircle) {
        return new CircleChangedEvent(setterId, targetId, isCircle);
    }
}
