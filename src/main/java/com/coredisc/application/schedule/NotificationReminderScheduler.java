package com.coredisc.application.schedule;

import com.coredisc.application.service.fcm.FcmService;
import com.coredisc.domain.common.enums.NotificationType;
import com.coredisc.domain.device.Device;
import com.coredisc.domain.device.DeviceRepository;
import com.coredisc.domain.mapping.notificationReminderSetting.NotificationReminderSetting;
import com.coredisc.domain.mapping.notificationReminderSetting.NotificationReminderSettingRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.postAnswer.PostAnswerRepository;
import com.coredisc.domain.todayQuestion.TodayQuestion;
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
public class NotificationReminderScheduler {

    private final NotificationReminderSettingRepository notificationReminderSettingRepository;
    private final TodayQuestionRepository todayQuestionRepository;
    private final PostAnswerRepository postAnswerRepository;
    private final DeviceRepository deviceRepository;
    private final FcmService fcmService;

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void reminderNotification() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        int hh = LocalTime.now(ZoneId.of("Asia/Seoul")).getHour();
        int mm = LocalTime.now(ZoneId.of("Asia/Seoul")).getMinute();

        /*
        데일리 리마인더 알림 생성 (사용자가 설정한 시간에 생성되도록 시간, 분 매칭)
        - 고정질문 < 4일 때 => 질문 생성해보세요 알림
        - 답변 작성 X => 답변 작성해보세요 알림
        - 오늘 답변 전부 마무리한 상태 => 알림 발송 X
        */
        List<NotificationReminderSetting> dailyTargets =
                notificationReminderSettingRepository
                        .findAllByDailyReminderEnabledTrueAndDailyReminderTime(hh, mm);

        /*
        미응답 리마인더 알림 생성 (사용자가 설정한 시간에 생성되도록 시간, 분 매칭)
        */
        List<NotificationReminderSetting> unansweredTargets =
                notificationReminderSettingRepository.findAllByUnansweredReminderEnabledTrueAndDailyReminderTime(hh, mm);

        // 모든 대상 멤버 ID 수집
        Set<Long> allMemberIds = new HashSet<>();
        dailyTargets.forEach(s -> allMemberIds.add(s.getMember().getId()));
        unansweredTargets.forEach(s -> allMemberIds.add(s.getMember().getId()));

        if (allMemberIds.isEmpty()) return;

        // 배치 쿼리: 모든 대상 멤버의 TodayQuestion 한번에 조회
        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);

        List<TodayQuestion> allQuestions = todayQuestionRepository
                .findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
                        new ArrayList<>(allMemberIds),
                        List.of(1, 2, 3, 4),
                        startOfMonth,
                        endOfMonth
                );

        // 멤버별 질문 순서 맵 구성 (questionOrder 4는 오늘 날짜만 필터)
        Map<Long, Set<Integer>> memberQuestionOrders = new HashMap<>();
        for (TodayQuestion q : allQuestions) {
            if (q.getQuestionOrder() == 4 && !q.getSelectedDate().equals(today)) continue;
            if (q.getQuestionContent() == null || q.getQuestionContent().isBlank()) continue;

            memberQuestionOrders
                    .computeIfAbsent(q.getMember().getId(), k -> new HashSet<>())
                    .add(q.getQuestionOrder());
        }

        // 배치 쿼리: 모든 대상 멤버의 오늘 답변 순서 한번에 조회
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        List<Object[]> answerData = postAnswerRepository
                .findAnswerOrdersByMemberIdsAndCreatedAtBetween(
                        new ArrayList<>(allMemberIds), startOfDay, endOfDay
                );

        // 멤버별 답변 순서 맵 구성
        Map<Long, Set<Integer>> memberAnswerOrders = new HashMap<>();
        for (Object[] row : answerData) {
            Long memberId = (Long) row[0];
            Integer answerOrder = (Integer) row[1];
            memberAnswerOrders
                    .computeIfAbsent(memberId, k -> new HashSet<>())
                    .add(answerOrder);
        }

        // 데일리 리마인더 처리
        dailyTargets.forEach(setting -> processDailyReminder(
                setting.getMember(), memberQuestionOrders, memberAnswerOrders));

        // 미응답 리마인더 처리
        unansweredTargets.forEach(setting -> processUnansweredReminder(
                setting.getMember(), memberQuestionOrders, memberAnswerOrders));
    }

    private void processDailyReminder(Member member,
                                      Map<Long, Set<Integer>> questionMap,
                                      Map<Long, Set<Integer>> answerMap) {
        List<Device> devices = deviceRepository.findByMemberAndIsActiveTrue(member);
        if (devices.isEmpty()) return;

        boolean hasAllQuestions = hasAllFourQuestions(member.getId(), questionMap);

        if (!hasAllQuestions) {
            sendFcmToDevices(devices, member, "질문 선택 필요",
                    "오늘의 질문이 완성되지 않았어요.\n먼저 하나 골라볼까요?",
                    NotificationType.DAILY_REMINDER);
            log.info("DAILY_REMINDER - memberId={} : 오늘의 질문을 생성해 주세요.", member.getId());
        } else if (!hasAllFourAnswers(member.getId(), answerMap)) {
            sendFcmToDevices(devices, member, "Disc 준비 완료",
                    "오늘 Disc가 준비됐어요.\nCore에 한 조각 더해볼까요?",
                    NotificationType.DAILY_REMINDER_ANSWER);
            log.info("DAILY_REMINDER - memberId={} : 오늘의 질문 답변을 작성해 주세요.", member.getId());
        } else {
            log.info("DAILY_REMINDER - memberId={} : 모든 질문 답변 완료했으니까 알림 미발송", member.getId());
        }
    }

    private void processUnansweredReminder(Member member,
                                           Map<Long, Set<Integer>> questionMap,
                                           Map<Long, Set<Integer>> answerMap) {
        List<Device> devices = deviceRepository.findByMemberAndIsActiveTrue(member);
        if (devices.isEmpty()) return;

        boolean hasAllQuestions = hasAllFourQuestions(member.getId(), questionMap);

        if (!hasAllQuestions) {
            sendFcmToDevices(devices, member, "마지막 질문 기회",
                    "Core를 완성하려면 지금이에요.\n오늘 질문이 비어있어요.",
                    NotificationType.UNANSWERED_QUESTION);
            log.info("UNANSWERED_QUESTION - memberId={} : 오늘의 질문을 생성해 주세요.", member.getId());
        } else if (!hasAllFourAnswers(member.getId(), answerMap)) {
            sendFcmToDevices(devices, member, "CoreDisc 미완성",
                    "오늘의 CoreDisc가 아직 없어요.\n놓치면 내일로 넘어가요.",
                    NotificationType.UNANSWERED_QUESTION_ANSWER);
            log.info("UNANSWERED_QUESTION - memberId={} : 오늘의 질문 답변을 마저 작성해 주세요.", member.getId());
        } else {
            log.info("UNANSWERED_QUESTION - memberId={} : 모든 질문 답변 완료했으니까 알림 미발송", member.getId());
        }
    }

    // 배치 조회 결과로 4개 질문이 모두 있는지 확인 (DB 쿼리 없음)
    private boolean hasAllFourQuestions(Long memberId, Map<Long, Set<Integer>> questionMap) {
        Set<Integer> orders = questionMap.getOrDefault(memberId, Set.of());
        return orders.contains(1) && orders.contains(2) && orders.contains(3) && orders.contains(4);
    }

    // 배치 조회 결과로 4개 답변이 모두 있는지 확인 (DB 쿼리 없음)
    private boolean hasAllFourAnswers(Long memberId, Map<Long, Set<Integer>> answerMap) {
        Set<Integer> orders = answerMap.getOrDefault(memberId, Set.of());
        return orders.contains(1) && orders.contains(2) && orders.contains(3) && orders.contains(4);
    }

    // FCM 전송 공통 메서드
    private void sendFcmToDevices(List<Device> devices, Member member,
                                  String title, String body, NotificationType type) {
        Map<String, String> data = new HashMap<>();
        data.put("notificationType", type.name());

        for (Device device : devices) {
            String token = device.getToken();
            if (fcmService.isTokenValid(token)) {
                fcmService.sendNotificationToToken(token, title, body, data);
            } else {
                log.warn("유효하지 않은 토큰 발견: memberId={}, token={}", member.getId(), token);
            }
        }
    }
}
