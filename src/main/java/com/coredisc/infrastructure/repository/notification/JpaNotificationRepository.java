package com.coredisc.infrastructure.repository.notification;

import com.coredisc.domain.notification.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaNotificationRepository extends JpaRepository<Notification, Long> {

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.sender.id = :senderId AND n.receiver.id = :receiverId")
    void deleteAllBySenderIdAndReceiverId(@Param("senderId") Long senderId, @Param("receiverId") Long receiverId);
}
