package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.GoalEvent;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class LiveGoalService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MatchCacheService matchCacheService;
    private final TelegramPublisherService telegramPublisherService;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    // matchId → Set of "scorerName:minute"
    private final Map<String, Set<String>> seenGoals = new HashMap<>();
    private ZonedDateTime lastGoalPostTime = ZonedDateTime.now(MOSCOW).minusHours(1);
    private final List<GoalEvent> goalQueue = new CopyOnWriteArrayList<>();
    private final AtomicInteger goalPostsThisHour = new AtomicInteger(0);
    private ZonedDateTime hourWindowStart = ZonedDateTime.now(MOSCOW);

    public void checkAndFlushGoals() {
        if (apiKey == null || apiKey.isBlank()) return;

        // Reset hourly counter
        var now = ZonedDateTime.now(MOSCOW);
        if (ChronoUnit.HOURS.between(hourWindowStart, now) >= 1) {
            goalPostsThisHour.set(0);
            hourWindowStart = now;
        }

        // STEP 1 — Check for new goals in all live matches
        var liveMatches = matchCacheService.getTodayMatches().stream()
                .filter(m -> "IN_PLAY".equals(m.getStatus()) || "PAUSED".equals(m.getStatus()))
                .toList();

        for (var match : liveMatches) {
            if (match.getMatchId() == null) continue;
            String matchId = String.valueOf(match.getMatchId());
            seenGoals.putIfAbsent(matchId, new HashSet<>());

            try {
                var url = apiUrl + "/matches/" + matchId;
                var request = new Request.Builder()
                        .url(url)
                        .header("X-Auth-Token", apiKey)
                        .build();

                try (var response = httpClient.newCall(request).execute()) {
                    var root = objectMapper.readValue(response.body().string(), Map.class);
                    parseGoals(root, matchId);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch live goals for match {}: {}", matchId, e.getMessage());
            }
        }

        // STEP 2 — Decide whether to publish
        if (goalQueue.isEmpty()) return;

        long minutesSinceLastPost = ChronoUnit.MINUTES.between(lastGoalPostTime, now);

        // Check for late goals — always publish immediately
        boolean hasLateGoal = goalQueue.stream().anyMatch(g -> g.getMinute() >= 85);
        boolean canPost = minutesSinceLastPost >= 5
                && (goalPostsThisHour.get() < 3 || hasLateGoal);

        if (!canPost) return;

        // STEP 3 — Build and publish aggregated post
        var goalsToPost = new ArrayList<>(goalQueue);
        goalsToPost.sort(Comparator.comparing(GoalEvent::getDetectedAt));

        String post = formatGoalPost(goalsToPost);
        telegramPublisherService.sendTextMessage(post);
        lastGoalPostTime = now;
        goalPostsThisHour.incrementAndGet();
        goalQueue.clear();
        log.info("Published goal post with {} goals", goalsToPost.size());
    }

    @SuppressWarnings("unchecked")
    private void parseGoals(Map<String, Object> root, String matchId) {
        var homeTeamMap = (Map<String, Object>) root.get("homeTeam");
        var awayTeamMap = (Map<String, Object>) root.get("awayTeam");
        var score = (Map<String, Object>) root.get("score");
        var goals = (List<Map<String, Object>>) root.get("goals");

        if (goals == null || homeTeamMap == null || awayTeamMap == null) return;

        String homeTeam = EntityDictionaryUtil.normalizeEntities(
                (String) homeTeamMap.getOrDefault("shortName", homeTeamMap.get("name")));
        String awayTeam = EntityDictionaryUtil.normalizeEntities(
                (String) awayTeamMap.getOrDefault("shortName", awayTeamMap.get("name")));

        int homeScore = 0, awayScore = 0;
        if (score != null) {
            var fullTime = (Map<String, Object>) score.get("fullTime");
            if (fullTime == null) fullTime = (Map<String, Object>) score.get("regularTime");
            if (fullTime != null) {
                homeScore = fullTime.get("home") instanceof Number n ? n.intValue() : 0;
                awayScore = fullTime.get("away") instanceof Number n ? n.intValue() : 0;
            }
        }

        Set<String> seen = seenGoals.get(matchId);

        for (var goal : goals) {
            var scorer = (Map<String, Object>) goal.get("scorer");
            if (scorer == null) continue;

            String scorerName = (String) scorer.get("name");
            if (scorerName == null) continue;

            int minute = goal.get("minute") instanceof Number n ? n.intValue() : 0;
            String goalKey = scorerName + ":" + minute;

            if (seen.contains(goalKey)) continue;
            seen.add(goalKey);

            String type = (String) goal.getOrDefault("type", "REGULAR");
            boolean isOwnGoal = "OWN_GOAL".equals(type);
            boolean isPenalty = "PENALTY".equals(type);

            String scorerRu = EntityDictionaryUtil.normalizeEntities(scorerName);

            var event = GoalEvent.builder()
                    .matchId(matchId)
                    .homeTeam(homeTeam)
                    .awayTeam(awayTeam)
                    .homeScore(homeScore)
                    .awayScore(awayScore)
                    .scorerName(scorerRu)
                    .minute(minute)
                    .isOwnGoal(isOwnGoal)
                    .isPenalty(isPenalty)
                    .detectedAt(ZonedDateTime.now(MOSCOW))
                    .build();

            goalQueue.add(event);
            log.info("New goal detected: {} {}' — {}:{}", scorerRu, minute, homeScore, awayScore);
        }
    }

    private String formatGoalPost(List<GoalEvent> goals) {
        var sb = new StringBuilder();

        // Late goal special label
        boolean hasLateGoal = goals.stream().anyMatch(g -> g.getMinute() >= 85);
        boolean isMatchWinner = goals.stream().anyMatch(g -> g.getMinute() >= 88);

        if (isMatchWinner) {
            sb.append("🔥 ГОЛ НА ПОСЛЕДНИХ МИНУТАХ!\n\n");
        } else if (hasLateGoal) {
            sb.append("😱 ПОЗДНИЙ ГОЛ!\n\n");
        }

        if (goals.size() == 1) {
            // Single goal format
            var g = goals.get(0);
            String emoji = g.isOwnGoal() ? "🤦" : g.isPenalty() ? "🎯" : "⚽";
            sb.append("⚽ ГОЛ! ").append(g.getHomeTeam()).append("\n\n");
            sb.append(scoreToEmoji(g.getHomeScore())).append(" — ").append(scoreToEmoji(g.getAwayScore())).append("\n");
            sb.append("🏴󠁧󠁢󠁥󠁮󠁧󠁿 ").append(g.getHomeTeam()).append(" ")
                    .append(g.getHomeScore()).append(":").append(g.getAwayScore())
                    .append(" ").append(g.getAwayTeam()).append("\n\n");
            sb.append(emoji).append(" ").append(g.getScorerName()).append(" — ").append(g.getMinute()).append("'");
            if (g.isPenalty()) sb.append(" (пен.)");
            if (g.isOwnGoal()) sb.append(" (автогол)");
            sb.append("\n\n");
        } else {
            // Aggregated format
            sb.append("⚽ Голы последних минут\n\n");
            for (var g : goals) {
                String emoji = g.isOwnGoal() ? "🤦" : g.isPenalty() ? "🎯" : "🏴󠁧󠁢󠁥󠁮󠁧󠁿";
                sb.append(scoreToEmoji(g.getHomeScore())).append(" — ").append(scoreToEmoji(g.getAwayScore())).append("\n");
                sb.append(emoji).append(" ")
                        .append(g.getHomeTeam()).append(" ")
                        .append(g.getHomeScore()).append(":").append(g.getAwayScore())
                        .append(" ").append(g.getAwayTeam())
                        .append(" — ").append(g.getScorerName())
                        .append(" ").append(g.getMinute()).append("'");
                if (g.isPenalty()) sb.append(" (пен.)");
                if (g.isOwnGoal()) sb.append(" (авт.)");
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Hat-trick check
        Map<String, Long> scorerCount = new HashMap<>();
        for (var g : goals) {
            scorerCount.merge(g.getScorerName(), 1L, Long::sum);
        }
        scorerCount.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .forEach(e -> sb.append("🎩 ХЕТ-ТРИК! ").append(e.getKey()).append("!\n"));

        sb.append("#апл");
        return sb.toString();
    }

    private String scoreToEmoji(int n) {
        return switch (n) {
            case 0 -> "0️⃣";
            case 1 -> "1️⃣";
            case 2 -> "2️⃣";
            case 3 -> "3️⃣";
            case 4 -> "4️⃣";
            case 5 -> "5️⃣";
            case 6 -> "6️⃣";
            case 7 -> "7️⃣";
            case 8 -> "8️⃣";
            case 9 -> "9️⃣";
            default -> String.valueOf(n);
        };
    }
}
