package com.footballbot.scheduler;

import com.footballbot.repository.PublishedNewsRepository;
import com.footballbot.service.AiProcessorService;
import com.footballbot.service.AiRankingService;
import com.footballbot.service.DeduplicationService;
import com.footballbot.service.HealthMonitorService;
import com.footballbot.service.LiveGoalService;
import com.footballbot.service.MatchCacheService;
import com.footballbot.service.MatchResultService;
import com.footballbot.service.MatchScheduleService;
import com.footballbot.service.NewsPublishQueueService;
import com.footballbot.service.PreMatchAnalysisService;
import com.footballbot.service.RssParserService;
import com.footballbot.service.TelegramPublisherService;
import com.footballbot.service.WeeklyRoundupService;
import com.footballbot.util.RelevanceFilterUtil;
import com.footballbot.util.ScorerUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.footballbot.model.NewsItem;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class NewsScheduler {

    private final RssParserService rssParserService;
    private final AiProcessorService aiProcessorService;
    private final TelegramPublisherService telegramPublisherService;
    private final PublishedNewsRepository publishedNewsRepository;
    private final DeduplicationService deduplicationService;
    private final AiRankingService aiRankingService;
    private final NewsPublishQueueService newsPublishQueueService;
    private final MatchCacheService matchCacheService;
    private final MatchScheduleService matchScheduleService;
    private final PreMatchAnalysisService preMatchAnalysisService;
    private final WeeklyRoundupService weeklyRoundupService;
    private final MatchResultService matchResultService;
    private final HealthMonitorService healthMonitorService;
    private final LiveGoalService liveGoalService;

    @Value("${scheduler.max.posts.per.run}")
    private int maxPostsPerRun;

    @Value("${news.min.score}")
    private int minScore;

    // JOB 1 — News publishing every 5 minutes
    @Scheduled(fixedDelayString = "PT5M", initialDelay = 5000)
    public void runNewsJob() {
        log.info("Starting news job...");

        // Step 1: fetch all news
        List<NewsItem> allNews = new ArrayList<>(rssParserService.fetchAllNews());
        log.info("Total fetched: {}", allNews.size());
        allNews.forEach(item -> log.info("  [score={}] {}", ScorerUtil.score(item), item.getTitleEn()));

        // Step 1.5: if bot was down for 8+ hours, skip news older than 1 hour
        boolean longDowntime = !publishedNewsRepository.existsByPostedAtAfter(
                LocalDateTime.now(ZoneId.of("Europe/Moscow")).minusHours(8));
        if (longDowntime) {
            var cutoff = LocalDateTime.now(ZoneId.of("Europe/Moscow")).minusHours(1);
            int before = allNews.size();
            allNews = allNews.stream()
                    .filter(item -> item.getPublishedAt() != null && item.getPublishedAt().isAfter(cutoff))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            log.info("Long downtime detected — skipped {} stale items (older than 1h)", before - allNews.size());
        }

        // Step 2: filter already published
        var notPublished = allNews.stream()
                .filter(item -> !publishedNewsRepository.existsById(item.getId()))
                .toList();

        // Step 3: relevance filter (block non-EPL/UCL content)
        var relevant = notPublished.stream()
                .filter(item -> {
                    boolean ok = RelevanceFilterUtil.isRelevant(item);
                    return ok;
                })
                .toList();

        // Step 4: deduplication
        var deduplicated = deduplicationService.filterDuplicates(relevant);

        // Step 5: set importanceScore and filter by minScore
        deduplicated.forEach(item -> item.setImportanceScore(ScorerUtil.score(item)));
        var candidates = deduplicated.stream()
                .filter(item -> {
                    int s = item.getImportanceScore();
                    if (s < minScore) log.info("Filtered out (score={} < {}): {}", s, minScore, item.getTitleEn());
                    return s >= minScore;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        log.info("After all filters: {} items remaining from {} fetched", candidates.size(), allNews.size());

        // Step 6: AI processing + final score
        candidates.forEach(item -> {
            aiProcessorService.process(item);
            item.setFinalScore(aiRankingService.getFinalScore(item));
        });

        // Filter out items where AI processing failed (no Russian title)
        candidates.removeIf(item -> {
            if (item.getTitleRu() == null || item.getTitleRu().isBlank()) {
                log.warn("Skipping '{}' — AI processing failed, no Russian translation", item.getTitleEn());
                return true;
            }
            return false;
        });

        // Step 7: sort by finalScore DESC, take top maxPostsPerRun
        var top = candidates.stream()
                .sorted((a, b) -> Integer.compare(b.getFinalScore(), a.getFinalScore()))
                .limit(maxPostsPerRun)
                .toList();

        // Step 8: enqueue for publishing (actual publish happens in NewsPublishQueueService)
        top.forEach(newsPublishQueueService::enqueue);
        log.info("Enqueued {} items for publishing (queue size: {})", top.size(), newsPublishQueueService.queueSize());
    }

    // JOB 2 — Daily match cache refresh at 08:50 MSK (before schedule post)
    @Scheduled(cron = "0 50 8 * * *", zone = "Europe/Moscow")
    public void runDailyCacheRefresh() {
        log.info("Refreshing daily match cache...");
        matchCacheService.refreshTodayMatches();
    }

    // JOB 3 — Daily match schedule post at 09:00 MSK
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Moscow")
    public void runScheduleJob() {
        log.info("Starting match schedule job...");
        matchScheduleService.postSchedule();
    }

    // JOB 4 — Pre-match analysis + results monitor every 5 minutes
    @Scheduled(fixedDelay = 300000)
    public void runMatchMonitorJob() {
        if (!matchCacheService.isActiveHours()) return;

        var matches = matchCacheService.getTodayMatches();
        var now = LocalDateTime.now();

        for (var match : matches) {
            if (match.getKickoff() != null) {
                long minutesUntil = java.time.temporal.ChronoUnit.MINUTES.between(now, match.getKickoff());

                // Pre-match: 55-65 minutes before kickoff
                if (minutesUntil >= 55 && minutesUntil <= 65) {
                    matchCacheService.refreshMatchStatus(match.getMatchId());
                    preMatchAnalysisService.generateAndPost(match);
                }
            }

            // Results: check finished matches
            if ("FINISHED".equals(match.getStatus())) {
                matchCacheService.refreshMatchStatus(match.getMatchId());
                matchResultService.generateAndPost(match);
            }
        }
    }

    // JOB 5 — Weekly roundup every Monday 10:00 MSK
    @Scheduled(cron = "0 0 10 * * MON", zone = "Europe/Moscow")
    public void runWeeklyRoundupJob() {
        log.info("Starting weekly roundup job...");
        weeklyRoundupService.postRoundup();
    }

    // JOB 6 — Live goals queue flush (every 5 minutes during active hours)
    @Scheduled(fixedDelay = 300000)
    public void runLiveGoalsJob() {
        if (!matchCacheService.isActiveHours()) return;
        liveGoalService.checkAndFlushGoals();
    }

    // JOB 7 — Hourly health check
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    public void runHealthCheck() {
        healthMonitorService.sendHourlyReport();
    }
}
