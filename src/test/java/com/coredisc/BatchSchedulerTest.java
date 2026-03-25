package com.coredisc;

import com.coredisc.application.schedule.BatchScheduler;
import com.coredisc.application.schedule.NotificationReminderScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

@SpringBootTest
@ActiveProfiles("local")
public class BatchSchedulerTest {

    private final BatchScheduler batchScheduler;
    private final NotificationReminderScheduler notificationReminderScheduler;

    @Autowired
    public BatchSchedulerTest(BatchScheduler batchScheduler,
                              NotificationReminderScheduler notificationReminderScheduler) {
        this.batchScheduler = batchScheduler;
        this.notificationReminderScheduler = notificationReminderScheduler;
    }

    @Test
    public void testRunBatchForMonth() {
        LocalDate targetMonth = LocalDate.of(2025, 8, 1);
        batchScheduler.runBatchForMonth(targetMonth);
    }

    @Test
    public void testRunBatchForDay() {
        LocalDate targetDate = LocalDate.of(2025, 7, 26);
        batchScheduler.runBatchForDay(targetDate);
    }

    @Test
    public void testBatchSequential() {
        LocalDate targetDate = LocalDate.of(2025, 5, 15);
        batchScheduler.runBatchForDaySequential(targetDate);
    }

    @Test
    public void testBatchParallel() {
        LocalDate targetDate = LocalDate.of(2025, 6, 15);
        batchScheduler.runBatchForDay(targetDate);
    }

    @Test
    public void testReminderScheduler() {
        // 21:00 — 시드 데이터의 리마인더 설정 시간
        notificationReminderScheduler.executeReminder(21, 0);
    }
}
