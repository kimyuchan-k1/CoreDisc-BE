package com.coredisc.application.event;

import com.coredisc.domain.common.enums.NotificationType;
import lombok.Getter;

@Getter
public class NotificationEvent {

    private final NotificationType type;
    private final Long senderId;
    private final Long receiverId;
    private final String senderNickname;
    private final String content;
    private final Long targetId;

    private NotificationEvent(NotificationType type, Long senderId, Long receiverId,
                              String senderNickname, String content, Long targetId) {
        this.type = type;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.senderNickname = senderNickname;
        this.content = content;
        this.targetId = targetId;
    }

    public static NotificationEvent like(Long senderId, Long receiverId, String senderNickname, Long postId) {
        return new NotificationEvent(
                NotificationType.LIKE, senderId, receiverId, senderNickname,
                senderNickname + "님이 게시글에 마음을 남겼어요.", postId);
    }

    public static NotificationEvent comment(Long senderId, Long receiverId, String senderNickname, Long postId) {
        return new NotificationEvent(
                NotificationType.COMMENT, senderId, receiverId, senderNickname,
                senderNickname + "님이 게시글에 댓글을 남겼어요.", postId);
    }

    public static NotificationEvent reply(Long senderId, Long receiverId, String senderNickname, Long postId) {
        return new NotificationEvent(
                NotificationType.COMMENT_REPLY, senderId, receiverId, senderNickname,
                senderNickname + "님이 댓글에 답글을 남겼어요.", postId);
    }
}
