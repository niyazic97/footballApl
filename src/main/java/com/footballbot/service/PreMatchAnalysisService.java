package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.config.GroqProperties;
import com.footballbot.model.MatchDay;
import com.footballbot.model.PublishedAnalysis;
import com.footballbot.repository.PublishedAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PreMatchAnalysisService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;
    private final PublishedAnalysisRepository publishedAnalysisRepository;
    private final MatchScheduleService matchScheduleService;
    private final GroqRateLimiter groqRateLimiter;
    private final GroqProperties groqProperties;

    public void generateAndPost(MatchDay match) {
        if (match.getMatchId() == null) {
            log.warn("Match has no ID, skipping pre-match analysis");
            return;
        }
        String matchKey = String.valueOf(match.getMatchId());

        if (publishedAnalysisRepository.existsById(matchKey)) {
            log.info("Pre-match analysis already posted for match {}", matchKey);
            return;
        }

        try {
            var post = buildAnalysisPost(match);
            telegramPublisherService.sendTextMessage(post);
            publishedAnalysisRepository.save(PublishedAnalysis.builder()
                    .matchId(matchKey)
                    .homeTeam(match.getHomeTeam())
                    .awayTeam(match.getAwayTeam())
                    .postedAt(LocalDateTime.now())
                    .build());
            log.info("Pre-match analysis posted for {} vs {}", match.getHomeTeam(), match.getAwayTeam());
        } catch (Exception e) {
            log.warn("Pre-match analysis failed for {} vs {}: {}", match.getHomeTeam(), match.getAwayTeam(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String buildAnalysisPost(MatchDay match) throws Exception {
        String homeRu = matchScheduleService.translateTeam(match.getHomeTeam());
        String awayRu = matchScheduleService.translateTeam(match.getAwayTeam());
        String leagueName = "UCL".equals(match.getLeague()) ? "Лига Чемпионов" : "Премьер-лига";
        String kickoffStr = match.getKickoff().format(DateTimeFormatter.ofPattern("HH:mm"));

        String prompt = String.format("""
                You are a football analyst for a Russian Telegram channel.
                Write a pre-match analysis in Russian. Respond ONLY with valid JSON:
                {
                  "headline": "catchy headline in Russian, max 10 words",
                  "home_form": "2-3 sentences about home team current form in Russian",
                  "away_form": "2-3 sentences about away team current form in Russian",
                  "key_players": "2-3 key players to watch with brief description in Russian",
                  "prediction": "short emotional prediction in Russian, like a football fan",
                  "tension_level": <integer 1-10>
                }
                Match: %s vs %s
                Competition: %s
                Kickoff: %s MSK""", match.getHomeTeam(), match.getAwayTeam(), leagueName, kickoffStr);

        var body = objectMapper.writeValueAsString(Map.of(
                "model", groqProperties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        var httpRequest = new Request.Builder()
                .url(groqProperties.getApi().getUrl())
                .header("Authorization", "Bearer " + groqProperties.getApi().getKey())
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

        String responseJson = groqRateLimiter.submit(
                "pre-match:" + match.getHomeTeam() + "vs" + match.getAwayTeam(),
                () -> {
                    try (var response = httpClient.newCall(httpRequest).execute()) {
                        var rb = response.body();
                        return rb != null ? rb.string() : "";
                    }
                }
        ).get();

        {
            var root = objectMapper.readValue(responseJson, Map.class);
            var choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) throw new RuntimeException("No choices in response");

            var message = (Map<String, Object>) choices.get(0).get("message");
            var text = (String) message.get("content");
            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            var parsed = objectMapper.readValue(text, Map.class);
            String headline = (String) parsed.getOrDefault("headline", homeRu + " vs " + awayRu);
            String homeForm = (String) parsed.getOrDefault("home_form", "");
            String awayForm = (String) parsed.getOrDefault("away_form", "");
            String keyPlayers = (String) parsed.getOrDefault("key_players", "");
            String prediction = (String) parsed.getOrDefault("prediction", "");
            int tension = parsed.get("tension_level") instanceof Number n ? n.intValue() : 5;

            String leagueEmoji = "UCL".equals(match.getLeague()) ? "🏆" : "🏴󠁧󠁢󠁥󠁮󠁧󠁿";
            String leagueTag = "UCL".equals(match.getLeague()) ? "#лигачемпионов" : "#апл";
            String tensionLabel = tension >= 8 ? "🔥 ДЕРБИ\n\n" : "";

            return tensionLabel +
                    "🔥 " + headline + "\n\n" +
                    "🏟 " + homeRu + " — " + awayRu + "\n" +
                    "🕐 Начало в " + kickoffStr + " МСК | " + leagueEmoji + " " + leagueName + "\n\n" +
                    "📊 Форма команд:\n" +
                    "🏠 " + homeRu + ": " + homeForm + "\n" +
                    "✈️ " + awayRu + ": " + awayForm + "\n\n" +
                    "⭐ На кого смотреть:\n" + keyPlayers + "\n\n" +
                    "🎯 Прогноз: " + prediction + "\n\n" +
                    "#Превью " + leagueTag;
        }
    }
}
