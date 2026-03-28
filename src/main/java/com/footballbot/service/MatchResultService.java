package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.MatchDay;
import com.footballbot.model.PublishedResult;
import com.footballbot.repository.PublishedResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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

    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.api.url}")
    private String groqApiUrl;

    @Value("${groq.model}")
    private String groqModel;

    @Value("${football.api.key:}")
    private String footballApiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String footballApiUrl;

    public void generateAndPost(MatchDay match) {
        if (match.getMatchId() == null) return;
        String matchKey = String.valueOf(match.getMatchId());

        if (publishedResultRepository.existsById(matchKey)) return;

        try {
            var details = fetchMatchDetails(match.getMatchId());
            if (details == null) return;

            var score = (Map<String, Object>) details.get("score");
            var fullTime = (Map<String, Object>) score.get("fullTime");
            var halfTime = (Map<String, Object>) score.get("halfTime");

            int homeScore = fullTime.get("home") instanceof Number n ? n.intValue() : 0;
            int awayScore = fullTime.get("away") instanceof Number n ? n.intValue() : 0;
            int htHome = halfTime.get("home") instanceof Number n ? n.intValue() : 0;
            int htAway = halfTime.get("away") instanceof Number n ? n.intValue() : 0;

            var goals = (List<Map<String, Object>>) details.getOrDefault("goals", List.of());
            String scorersList = goals.stream()
                    .map(g -> {
                        var scorer = (Map<String, Object>) g.get("scorer");
                        var team = (Map<String, Object>) g.get("team");
                        int minute = g.get("minute") instanceof Number n ? n.intValue() : 0;
                        return (scorer != null ? scorer.get("name") : "Unknown") + " " + minute + "'";
                    })
                    .collect(Collectors.joining(", "));

            var aiReaction = getAiReaction(match, homeScore, awayScore, htHome, htAway, scorersList);
            var post = formatPost(match, homeScore, awayScore, htHome, htAway, goals, aiReaction);

            telegramPublisherService.sendTextMessage(post);

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
            var root = objectMapper.readValue(response.body().string(), Map.class);
            return (Map<String, Object>) root.get("match");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getAiReaction(MatchDay match, int homeScore, int awayScore,
                                               int htHome, int htAway, String scorersList) {
        try {
            String prompt = String.format("""
                    You are an emotional football commentator for a Russian Telegram channel.
                    A match just finished. Respond ONLY with valid JSON:
                    {
                      "reaction": "2-3 emotional sentences reacting to the result in Russian",
                      "match_summary": "1-2 sentences describing how the match went in Russian",
                      "star_of_match": "name of best player and why in Russian"
                    }
                    Match: %s %d:%d %s
                    Half-time: %d:%d
                    Scorers: %s""",
                    match.getHomeTeam(), homeScore, awayScore, match.getAwayTeam(),
                    htHome, htAway, scorersList.isBlank() ? "no goals" : scorersList);

            var body = objectMapper.writeValueAsString(Map.of(
                    "model", groqModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            ));

            var request = new Request.Builder()
                    .url(groqApiUrl)
                    .header("Authorization", "Bearer " + groqApiKey)
                    .post(RequestBody.create(body, MediaType.get("application/json")))
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var choices = (List<Map<String, Object>>) root.get("choices");
                if (choices == null || choices.isEmpty()) return Map.of();
                var message = (Map<String, Object>) choices.get(0).get("message");
                var text = (String) message.get("content");
                text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                return objectMapper.readValue(text, Map.class);
            }
        } catch (Exception e) {
            log.warn("AI reaction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private String formatPost(MatchDay match, int homeScore, int awayScore,
                               int htHome, int htAway,
                               List<Map<String, Object>> goals,
                               Map<String, String> aiReaction) {
        String homeRu = matchScheduleService.translateTeam(match.getHomeTeam());
        String awayRu = matchScheduleService.translateTeam(match.getAwayTeam());
        String leagueEmoji = "UCL".equals(match.getLeague()) ? "🏆" : "🏴󠁧󠁢󠁥󠁮󠁧󠁿";
        String leagueName = "UCL".equals(match.getLeague()) ? "Лига Чемпионов" : "Премьер-лига";
        String leagueTag = "UCL".equals(match.getLeague()) ? "#лигачемпионов" : "#апл";

        var sb = new StringBuilder();
        sb.append("🏁 ФИНАЛЬНЫЙ СВИСТОК!\n\n");

        if (homeScore == awayScore) sb.append("🤝 Ничья!\n\n");
        else if (Math.abs(homeScore - awayScore) >= 3) sb.append("🔥 РАЗГРОМ!\n\n");

        sb.append(leagueEmoji).append(" ").append(leagueName).append("\n\n");
        sb.append(homeRu).append(" ").append(homeScore).append(" : ").append(awayScore).append(" ").append(awayRu).append("\n\n");

        if (!goals.isEmpty()) {
            sb.append("⚽ Голы:\n");
            for (var g : goals) {
                var scorer = (Map<String, Object>) g.get("scorer");
                var team = (Map<String, Object>) g.get("team");
                int minute = g.get("minute") instanceof Number n ? n.intValue() : 0;
                String scorerName = scorer != null ? (String) scorer.get("name") : "Unknown";
                String teamName = team != null ? matchScheduleService.translateTeam((String) team.get("shortName")) : "";
                boolean isLate = minute >= 85;
                sb.append("• ").append(scorerName).append(" ").append(minute).append("' (").append(teamName).append(")");
                if (isLate) sb.append(" 😱");
                sb.append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("Голов не было\n\n");
        }

        sb.append("⏱ Счёт в перерыве: ").append(htHome).append(":").append(htAway).append("\n\n");

        String reaction = aiReaction.getOrDefault("reaction", "");
        String summary = aiReaction.getOrDefault("match_summary", "");
        String star = aiReaction.getOrDefault("star_of_match", "");

        if (!reaction.isBlank()) sb.append("💬 ").append(reaction).append("\n\n");
        if (!summary.isBlank()) sb.append("📝 ").append(summary).append("\n\n");
        if (!star.isBlank()) sb.append("⭐ Игрок матча: ").append(star).append("\n\n");

        sb.append("#Результат ").append(leagueTag);
        return sb.toString();
    }
}
