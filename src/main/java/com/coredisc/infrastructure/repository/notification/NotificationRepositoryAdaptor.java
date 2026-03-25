package com.coredisc.infrastructure.repository.notification;

import com.coredisc.domain.notification.Notification;
import com.coredisc.domain.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryAdaptor implements NotificationRepository {

    private final JpaNotificationRepository jpaNotificationRepository;

    @Override
    public Notification save(Notification notification) {
        return jpaNotificationRepository.save(notification);
    }

    @Override
    public void deleteAllBySenderIdAndReceiverId(Long senderId, Long receiverId) {
        jpaNotificationRepository.deleteAllBySenderIdAndReceiverId(senderId, receiverId);
    }
}
