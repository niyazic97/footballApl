package com.footballbot.service;

import com.footballbot.repository.PublishedNewsRepository;
import com.footballbot.util.ScorerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Slf4j
public class BotCommandService {

    private final NewsPublishQueueService newsPublishQueueService;
    private final HealthMonitorService healthMonitorService;
    private final PublishedNewsRepository publishedNewsRepository;
    private final PlayerRosterService playerRosterService;
    private final ScorerUtil scorerUtil;
    private final StandingsImageService standingsImageService;

    @Value("${admin.telegram.user.id:}")
    private String adminUserId;

    public BotCommandService(
            @Lazy NewsPublishQueueService newsPublishQueueService,
            HealthMonitorService healthMonitorService,
            PublishedNewsRepository publishedNewsRepository,
            PlayerRosterService playerRosterService,
            ScorerUtil scorerUtil,
            StandingsImageService standingsImageService) {
        this.newsPublishQueueService = newsPublishQueueService;
        this.healthMonitorService = healthMonitorService;
        this.publishedNewsRepository = publishedNewsRepository;
        this.playerRosterService = playerRosterService;
        this.scorerUtil = scorerUtil;
        this.standingsImageService = standingsImageService;
    }

    public boolean isAdmin(String userId) {
        return adminUserId != null && adminUserId.equals(userId);
    }

    public String handleCommand(String command) {
        return switch (command) {
            case "/status" -> buildStatusMessage();
            case "/pause" -> {
                newsPublishQueueService.pause();
                yield "⏸ Публикация приостановлена";
            }
            case "/resume" -> {
                newsPublishQueueService.resume();
                yield "▶️ Публикация возобновлена";
            }
            case "/queue" -> buildQueueMessage();
            case "/refreshrosters" -> {
                playerRosterService.refreshRosters();
                scorerUtil.refreshCache();
                yield "✅ Ростеры обновлены: " + scorerUtil.getCacheSize() + " игроков загружено";
            }
            case "/standings" -> {
                new Thread(() -> {
                    standingsImageService.postStandings();
                    standingsImageService.postScorers();
                }, "manual-standings").start();
                yield "📊 Публикую таблицу и бомбардиров...";
            }
            case "/logs" -> buildLogsMessage();
            default -> "❓ Неизвестная команда. Доступные: /status, /pause, /resume, /queue, /refreshrosters, /standings, /logs";
        };
    }

    private String buildStatusMessage() {
        var now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));
        long uptimeMinutes = ChronoUnit.MINUTES.between(healthMonitorService.getBotStartTime(), now);
        long uptimeHours = uptimeMinutes / 60;
        long mins = uptimeMinutes % 60;

        var todayStart = now.toLocalDate().atStartOfDay();
        int todayPosts = publishedNewsRepository.findByPostedAtAfter(todayStart).size();

        var lastPosted = publishedNewsRepository.findByPostedAtAfter(now.minusHours(24))
                .stream()
                .map(p -> p.getPostedAt())
                .max(LocalDateTime::compareTo)
                .orElse(null);

        String lastPostStr = lastPosted != null
                ? ChronoUnit.MINUTES.between(lastPosted, now) + " мин назад"
                : "нет данных";

        String pausedStr = newsPublishQueueService.isPaused() ? " (⏸ на паузе)" : "";

        return String.format(
                "🤖 Бот работает%s\n" +
                "⏱ Uptime: %dч %dм\n" +
                "📰 Опубликовано сегодня: %d\n" +
                "📋 В очереди: %d\n" +
                "🕐 Последняя публикация: %s",
                pausedStr, uptimeHours, mins, todayPosts,
                newsPublishQueueService.queueSize(), lastPostStr
        );
    }

    private String buildQueueMessage() {
        int size = newsPublishQueueService.queueSize();
        if (size == 0) return "📋 Очередь пуста";
        return "📋 В очереди: " + size + " новост" + (size == 1 ? "ь" : size < 5 ? "и" : "ей");
    }

    private String buildLogsMessage() {
        try {
            var logFile = Path.of("bot.log");
            if (!Files.exists(logFile)) return "📄 Файл логов не найден";

            List<String> lines = Files.readAllLines(logFile);
            int total = lines.size();
            // Take last 5 lines
            var tail = lines.subList(Math.max(0, total - 5), total);
            String content = String.join("\n", tail)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            // Telegram message limit ~4096 chars; reserve space for header and <pre> tags
            if (content.length() > 3800) {
                content = "..." + content.substring(content.length() - 3800);
            }
            return "📄 Последние логи (" + total + " строк):\n<pre>" + content + "</pre>";
        } catch (IOException e) {
            return "❌ Не удалось прочитать логи: " + e.getMessage();
        }
    }
}
