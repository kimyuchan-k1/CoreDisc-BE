package com.coredisc.application.event;

import com.coredisc.domain.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupListener {

    private final NotificationRepository notificationRepository;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleBlockedEvent(BlockedEvent event) {
        Long blockerId = event.getActorId();
        Long blockedId = event.getTargetId();

        try {
            // 양방향 알림 삭제
            notificationRepository.deleteAllBySenderIdAndReceiverId(blockedId, blockerId);
            notificationRepository.deleteAllBySenderIdAndReceiverId(blockerId, blockedId);

            log.info("[ASYNC] Block 알림 정리 완료: blockerId={}, blockedId={}", blockerId, blockedId);
        } catch (Exception e) {
            log.error("[ASYNC] Block 알림 정리 실패: blockerId={}, blockedId={}, error={}",
                    blockerId, blockedId, e.getMessage(), e);
        }
    }
}
