package com.footballbot.scheduler;

import com.footballbot.repository.PublishedNewsRepository;
import com.footballbot.service.AiProcessingQueueService;
import com.footballbot.service.AiProcessorService;
import com.footballbot.service.AiRankingService;
import com.footballbot.service.BatchFilterService;
import com.footballbot.service.DeduplicationService;
import com.footballbot.service.HealthMonitorService;
import com.footballbot.service.LiveGoalService;
import com.footballbot.service.MatchCacheService;
import com.footballbot.service.MatchResultService;
import com.footballbot.service.MatchScheduleService;
import com.footballbot.service.NewsPublishQueueService;
import com.footballbot.service.PostMatchStatsService;
import com.footballbot.service.PreMatchAnalysisService;
import com.footballbot.service.RssParserService;
import com.footballbot.service.TelegramPublisherService;
import com.footballbot.service.PlayerRosterService;
import com.footballbot.service.StandingsImageService;
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
    private final AiProcessingQueueService aiProcessingQueueService;
    private final TelegramPublisherService telegramPublisherService;
    private final PublishedNewsRepository publishedNewsRepository;
    private final BatchFilterService batchFilterService;
    private final DeduplicationService deduplicationService;
    private final AiRankingService aiRankingService;
    private final NewsPublishQueueService newsPublishQueueService;
    private final MatchCacheService matchCacheService;
    private final MatchScheduleService matchScheduleService;
    private final PreMatchAnalysisService preMatchAnalysisService;
    private final WeeklyRoundupService weeklyRoundupService;
    private final StandingsImageService standingsImageService;
    private final MatchResultService matchResultService;
    private final PostMatchStatsService postMatchStatsService;
    private final HealthMonitorService healthMonitorService;
    private final LiveGoalService liveGoalService;
    private final PlayerRosterService playerRosterService;
    private final ScorerUtil scorerUtil;

    @Value("${scheduler.max.posts.per.run}")
    private int maxPostsPerRun;

    @Value("${news.min.score}")
    private int minScore;

    private boolean isSilenceHours() {
        int hour = LocalDateTime.now(ZoneId.of("Europe/Moscow")).getHour();
        return hour >= 1 && hour < 8;
    }

    // JOB 1 — News collecting every 10 minutes
    @Scheduled(fixedDelayString = "PT10M", initialDelay = 5000)
    public void runNewsJob() {
        if (isSilenceHours()) {
            log.info("Silence hours (01:00-08:00 MSK) — skipping news job");
            return;
        }
        log.info("Starting news job...");

        // Step 1: fetch all news
        List<NewsItem> allNews = new ArrayList<>(rssParserService.fetchAllNews());
        log.info("Total fetched: {}", allNews.size());
        allNews.forEach(item -> log.info("  [score={}] {}", scorerUtil.score(item), item.getTitleEn()));

        // Step 1.5: if last post was more than 1 hour ago, skip news older than 1 hour
        var lastPost = publishedNewsRepository.findTopByOrderByPostedAtDesc();
        var oneHourAgo = LocalDateTime.now(ZoneId.of("Europe/Moscow")).minusHours(1);
        boolean downtime = lastPost.isEmpty() || lastPost.get().getPostedAt().isBefore(oneHourAgo);
        if (downtime) {
            int before = allNews.size();
            allNews = allNews.stream()
                    .filter(item -> item.getPublishedAt() != null && item.getPublishedAt().isAfter(oneHourAgo))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            log.info("Downtime detected — skipped {} stale items (older than 1h)", before - allNews.size());
        }

        // Step 2: keyword pre-filter (fast, no AI) — blocks obvious non-EPL content
        var relevant = allNews.stream()
                .filter(RelevanceFilterUtil::isRelevant)
                .toList();
        log.info("After keyword filter: {}/{}", relevant.size(), allNews.size());

        // Step 2b: L1 score filter — blocks low-quality articles before hitting Groq
        var scored = relevant.stream()
                .filter(item -> scorerUtil.score(item) >= minScore)
                .toList();
        log.info("After score filter (>= {}): {}/{}", minScore, scored.size(), relevant.size());

        // Step 2c: filter already published
        var notPublished = scored.stream()
                .filter(item -> !publishedNewsRepository.existsById(item.getId()))
                .toList();

        // Step 3: local deduplication — proper noun matching, no AI needed
        var deduped = deduplicationService.filterDuplicates(notPublished);
        log.info("After local dedup: {}/{}", deduped.size(), notPublished.size());

        // Step 4: AI batch filter — relevance + semantic dedup via Groq
        var candidates = new ArrayList<>(batchFilterService.filterAndDeduplicate(deduped));

        log.info("After all filters: {} items remaining from {} fetched", candidates.size(), allNews.size());

        // Step 6: sort by L1 score, cap per run, and add to AI processing queue
        // AI processing happens in a separate thread respecting Groq rate limits
        candidates.sort((a, b) -> Integer.compare(b.getImportanceScore(), a.getImportanceScore()));
        var toEnqueue = candidates.stream().limit(maxPostsPerRun).toList();
        if (candidates.size() > maxPostsPerRun) {
            log.info("Capped candidates from {} to {} (maxPostsPerRun={})", candidates.size(), toEnqueue.size(), maxPostsPerRun);
        }
        toEnqueue.forEach(aiProcessingQueueService::enqueue);
        log.info("Added {} candidates to AI queue (AI queue size: {})", toEnqueue.size(), aiProcessingQueueService.queueSize());
    }

    // JOB 2 — Daily match cache refresh at 08:50 MSK (before schedule post)
    @Scheduled(cron = "0 50 8 * * *", zone = "Europe/Moscow")
    public void runDailyCacheRefresh() {
        log.info("Refreshing daily match cache...");
        matchCacheService.refreshTodayMatches();
    }

    // JOB 2b — Daily player roster refresh at 03:00 MSK
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Moscow")
    public void runRosterRefresh() {
        log.info("Refreshing player rosters...");
        playerRosterService.refreshRosters();
        scorerUtil.refreshCache();
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
        if (matchCacheService.isSilentHours()) return;

        var matches = matchCacheService.getTodayMatches();
        var now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));

        // Step 1: pre-match analysis + refresh status for all active-window matches
        for (var match : matches) {
            if (match.getKickoff() == null || match.getMatchId() == null) continue;
            long minutesUntil = java.time.temporal.ChronoUnit.MINUTES.between(now, match.getKickoff());

            // Pre-match analysis: 45-55 minutes before kickoff (lineups published ~60 min before)
            if (minutesUntil >= 45 && minutesUntil <= 55) {
                preMatchAnalysisService.generateAndPost(match);
            }

            // Refresh status for matches in active window (30 min before to 150 min after kickoff)
            // This updates IN_PLAY/PAUSED/FINISHED in cache so live goals and results can be detected
            if (minutesUntil >= -150 && minutesUntil <= 30) {
                matchCacheService.refreshMatchStatus(match.getMatchId());
            }
        }

        // Step 2: re-read updated cache and process finished matches
        for (var match : matchCacheService.getTodayMatches()) {
            if ("FINISHED".equals(match.getStatus())) {
                matchResultService.generateAndPost(match);
            }
        }
    }

    // JOB 5a — Weekly standings image every Monday 10:00 MSK
    @Scheduled(cron = "0 0 10 * * MON", zone = "Europe/Moscow")
    public void runWeeklyStandingsJob() {
        log.info("Starting weekly standings image job...");
        standingsImageService.postStandings();
    }

    // JOB 5b — Weekly scorers/assists image every Monday 10:10 MSK
    @Scheduled(cron = "0 10 10 * * MON", zone = "Europe/Moscow")
    public void runWeeklyScorersJob() {
        log.info("Starting weekly scorers image job...");
        standingsImageService.postScorers();
    }

    // JOB 6 — Live goals queue flush (every 5 minutes during active hours)
    @Scheduled(fixedDelay = 300000)
    public void runLiveGoalsJob() {
        if (matchCacheService.isSilentHours()) return;
        liveGoalService.checkAndFlushGoals();
    }

    // JOB 7 — Hourly health check
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    public void runHealthCheck() {
        healthMonitorService.sendHourlyReport();
    }
}
