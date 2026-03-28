package com.footballbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class HealthMonitorService {

    private final TelegramPublisherService telegramPublisherService;

    @Value("${admin.telegram.user.id:}")
    private String adminUserId;

    @Value("${health.check.enabled:false}")
    private boolean enabled;

    private final AtomicInteger postsPublishedSinceLastCheck = new AtomicInteger(0);
    private final AtomicInteger errorsLastHour = new AtomicInteger(0);
    private final LocalDateTime botStartTime = LocalDateTime.now();

    public void incrementPostCount() {
        postsPublishedSinceLastCheck.incrementAndGet();
    }

    public void incrementErrorCount() {
        errorsLastHour.incrementAndGet();
    }

    public void sendHourlyReport() {
        if (!enabled || adminUserId == null || adminUserId.isBlank()) return;

        int posts = postsPublishedSinceLastCheck.getAndSet(0);
        int errors = errorsLastHour.getAndSet(0);
        var now = LocalDateTime.now();
        long uptimeHours = java.time.temporal.ChronoUnit.HOURS.between(botStartTime, now);

        String report = String.format(
                "🤖 Bot Status\n" +
                "🕐 %s\n" +
                "⏱ Uptime: %d ч\n" +
                "📬 Опубликовано за час: %d\n" +
                "❌ Ошибок за час: %d",
                now.format(DateTimeFormatter.ofPattern("HH:mm dd.MM")),
                uptimeHours,
                posts,
                errors
        );

        try {
            telegramPublisherService.sendTextMessageToUser(adminUserId, report);
        } catch (Exception e) {
            log.warn("Failed to send health report: {}", e.getMessage());
        }
    }
}
