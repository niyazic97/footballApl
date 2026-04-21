package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.MatchDay;
import com.footballbot.model.PublishedResult;
import com.footballbot.repository.PublishedResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchResultService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;
    private final PublishedResultRepository publishedResultRepository;
    private final MatchScheduleService matchScheduleService;
    private final BetService betService;
    private final VkPublisherService vkPublisherService;

    @Value("${football.api.key:}")
    private String footballApiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String footballApiUrl;

    @SuppressWarnings("unchecked")
    public void generateAndPost(MatchDay match) {
        if (match.getMatchId() == null) return;
        String matchKey = String.valueOf(match.getMatchId());

        if (publishedResultRepository.existsById(matchKey)) return;

        try {
            var details = fetchMatchDetails(match.getMatchId());
            if (details == null) return;

            var score = (Map<String, Object>) details.get("score");
            var fullTime = (Map<String, Object>) score.get("fullTime");

            int homeScore = fullTime.get("home") instanceof Number n ? n.intValue() : 0;
            int awayScore = fullTime.get("away") instanceof Number n ? n.intValue() : 0;

            var goals = (List<Map<String, Object>>) details.getOrDefault("goals", List.of());

            betService.resolveBet(matchKey, homeScore, awayScore);

            var post = formatPost(match, homeScore, awayScore, goals, matchKey);

            telegramPublisherService.sendTextMessage(post);
            vkPublisherService.publishText(post);

            publishedResultRepository.save(PublishedResult.builder()
                    .matchId(matchKey)
                    .homeTeam(match.getHomeTeam())
                    .awayTeam(match.getAwayTeam())
                    .score(homeScore + ":" + awayScore)
                    .postedAt(LocalDateTime.now())
                    .build());

            log.info("Match result posted: {} {}:{} {}", match.getHomeTeam(), homeScore, awayScore, match.getAwayTeam());
        } catch (Exception e) {
            log.warn("Match result failed for {}: {}", match.getMatchId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchMatchDetails(Integer matchId) throws Exception {
        var request = new Request.Builder()
                .url(footballApiUrl + "/matches/" + matchId)
                .header("X-Auth-Token", footballApiKey)
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var body = response.body();
            var root = objectMapper.readValue(body != null ? body.string() : "{}", Map.class);
            return (Map<String, Object>) root.get("match");
        }
    }

    @SuppressWarnings("unchecked")
    private String formatPost(MatchDay match, int homeScore, int awayScore,
                               List<Map<String, Object>> goals, String matchKey) {
        String homeRu = matchScheduleService.translateTeam(match.getHomeTeam());
        String awayRu = matchScheduleService.translateTeam(match.getAwayTeam());

        var sb = new StringBuilder();
        sb.append("🏁 ФИНАЛЬНЫЙ СВИСТОК!\n\n");

        if (homeScore == awayScore) sb.append("🤝 Ничья!\n\n");
        else if (Math.abs(homeScore - awayScore) >= 3) sb.append("🔥 РАЗГРОМ!\n\n");

        sb.append("🏴󠁧󠁢󠁥󠁮󠁧󠁿 Премьер-лига\n\n");
        sb.append(homeRu).append(" ").append(homeScore).append(" : ").append(awayScore).append(" ").append(awayRu).append("\n\n");

        if (!goals.isEmpty()) {
            sb.append("⚽ Голы:\n");
            for (var g : goals) {
                var scorer = (Map<String, Object>) g.get("scorer");
                var team = (Map<String, Object>) g.get("team");
                int minute = g.get("minute") instanceof Number n ? n.intValue() : 0;
                String scorerName = scorer != null ? (String) scorer.get("name") : "Unknown";
                String teamName = team != null ? matchScheduleService.translateTeam((String) team.get("shortName")) : "";
                sb.append(scorerName).append(" ").append(minute).append("' (").append(teamName).append(")\n");
            }
        } else {
            sb.append("Голов не было\n");
        }

        // Bet result for this match
        betService.getBetRecord(matchKey).ifPresent(bet -> {
            if ("SKIP".equals(bet.getResult())) return;
            sb.append("\n");
            String icon = "WIN".equals(bet.getResult()) ? "✅" : "❌";
            sb.append(icon).append(" Наш прогноз: ").append(bet.getBet());
            if (bet.getConfidence() != null) sb.append(" (").append(bet.getConfidence()).append(")");
            sb.append(" — ").append("WIN".equals(bet.getResult()) ? "угадали!" : "не угадали");
        });

        // Overall stats
        String statsLine = betService.getStatsLine();
        if (!statsLine.isBlank()) {
            sb.append("\n\n━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📊 ").append(statsLine).append("\n");
            sb.append("ℹ️ Это статистика наших прогнозов, не рекомендация к ставкам");
        }

        return sb.toString();
    }
}
