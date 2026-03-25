package com.coredisc.domain.notification;

public interface NotificationRepository {
    Notification save(Notification notification);

    void deleteAllBySenderIdAndReceiverId(Long senderId, Long receiverId);
}
