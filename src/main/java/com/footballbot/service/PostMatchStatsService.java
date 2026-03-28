package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.FixtureIdMapping;
import com.footballbot.model.MatchDay;
import com.footballbot.repository.FixtureIdMappingRepository;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PostMatchStatsService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;
    private final FixtureIdMappingRepository fixtureMappingRepository;
    private final MatchScheduleService matchScheduleService;

    @Value("${apifootball.api.key:}")
    private String apiKey;

    @Value("${apifootball.api.url:https://v3.football.api-sports.io}")
    private String apiUrl;

    @Value("${groq.api.key}")
    private String groqKey;

    @Value("${groq.api.url}")
    private String groqUrl;

    @Value("${groq.model}")
    private String groqModel;

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public void generateAndPost(MatchDay match) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("API-Football key not configured, skipping post-match stats");
            return;
        }

        // Wait 10 minutes for stats to be populated
        try {
            Thread.sleep(600_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            Integer fixtureId = resolveFixtureId(match);
            if (fixtureId == null) {
                log.warn("Could not find API-Football fixture ID for match {}", match.getMatchId());
                return;
            }

            var stats = fetchPlayerStats(fixtureId);
            if (stats == null || stats.isEmpty()) {
                log.warn("No player stats available for fixture {}", fixtureId);
                return;
            }

            String homeRu = matchScheduleService.translateTeam(match.getHomeTeam());
            String awayRu = matchScheduleService.translateTeam(match.getAwayTeam());

            var post = buildPost(match, homeRu, awayRu, stats);
            telegramPublisherService.sendTextMessage(post);
            log.info("Post-match stats published for {} vs {}", homeRu, awayRu);

        } catch (Exception e) {
            log.warn("Failed to post match stats: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Integer resolveFixtureId(MatchDay match) {
        String key = String.valueOf(match.getMatchId());
        var cached = fixtureMappingRepository.findById(key);
        if (cached.isPresent()) return cached.get().getApiFootballId();

        // Search by teams + date
        try {
            LocalDate matchDate = match.getKickoff() != null
                    ? match.getKickoff().atZone(MOSCOW).toLocalDate()
                    : LocalDate.now(MOSCOW);

            String url = apiUrl + "/fixtures?league=39&season=2025&date=" + matchDate
                    + "&team=" + resolveApiFootballTeamId(match.getHomeTeam());

            var request = new Request.Builder()
                    .url(url)
                    .header("x-apisports-key", apiKey)
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var fixtures = (List<Map<String, Object>>) root.get("response");
                if (fixtures == null || fixtures.isEmpty()) return null;

                var fixture = (Map<String, Object>) fixtures.get(0).get("fixture");
                if (fixture == null) return null;

                int fixtureId = fixture.get("id") instanceof Number n ? n.intValue() : 0;
                if (fixtureId == 0) return null;

                fixtureMappingRepository.save(FixtureIdMapping.builder()
                        .footballDataId(key)
                        .apiFootballId(fixtureId)
                        .homeTeam(match.getHomeTeam())
                        .matchDate(matchDate)
                        .build());

                return fixtureId;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve fixture ID: {}", e.getMessage());
            return null;
        }
    }

    // Simple team name → API-Football team ID mapping (EPL teams)
    private static final Map<String, Integer> TEAM_IDS = Map.ofEntries(
            Map.entry("Arsenal", 42), Map.entry("Chelsea", 49),
            Map.entry("Liverpool", 40), Map.entry("Man City", 50),
            Map.entry("Man United", 33), Map.entry("Tottenham", 47),
            Map.entry("Newcastle", 34), Map.entry("Aston Villa", 66),
            Map.entry("West Ham", 48), Map.entry("Brighton", 51),
            Map.entry("Everton", 45), Map.entry("Fulham", 36),
            Map.entry("Wolves", 39), Map.entry("Crystal Palace", 52),
            Map.entry("Nottm Forest", 65), Map.entry("Bournemouth", 35),
            Map.entry("Brentford", 55), Map.entry("Leicester", 46),
            Map.entry("Ipswich", 57), Map.entry("Southampton", 41)
    );

    private int resolveApiFootballTeamId(String shortName) {
        return TEAM_IDS.getOrDefault(shortName, 0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPlayerStats(int fixtureId) {
        try {
            var url = apiUrl + "/fixtures/players?fixture=" + fixtureId;
            var request = new Request.Builder()
                    .url(url)
                    .header("x-apisports-key", apiKey)
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                return (List<Map<String, Object>>) root.get("response");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch player stats: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildPost(MatchDay match, String homeRu, String awayRu,
                             List<Map<String, Object>> statsResponse) {
        record PlayerStat(String name, double rating, int goals, int assists,
                          int shots, double passAcc, int keyPasses, boolean isHome) {}

        var players = new ArrayList<PlayerStat>();

        for (var teamData : statsResponse) {
            var teamMap = (Map<String, Object>) teamData.get("team");
            String teamName = teamMap != null ? (String) teamMap.get("name") : "";
            boolean isHome = teamName != null && teamName.toLowerCase().contains(
                    match.getHomeTeam() != null ? match.getHomeTeam().toLowerCase() : "");

            var playerList = (List<Map<String, Object>>) teamData.get("players");
            if (playerList == null) continue;

            for (var p : playerList) {
                var playerInfo = (Map<String, Object>) p.get("player");
                var statsArr = (List<Map<String, Object>>) p.get("statistics");
                if (playerInfo == null || statsArr == null || statsArr.isEmpty()) continue;

                String name = EntityDictionaryUtil.normalizeEntities((String) playerInfo.get("name"));
                var s = statsArr.get(0);

                var gamesMap = (Map<String, Object>) s.get("games");
                var goalsMap = (Map<String, Object>) s.get("goals");
                var shotsMap = (Map<String, Object>) s.get("shots");
                var passesMap = (Map<String, Object>) s.get("passes");

                double rating = 0;
                if (gamesMap != null && gamesMap.get("rating") instanceof String r) {
                    try { rating = Double.parseDouble(r); } catch (NumberFormatException ignored) {}
                }
                int goals = goalsMap != null && goalsMap.get("total") instanceof Number n ? n.intValue() : 0;
                int assists = goalsMap != null && goalsMap.get("assists") instanceof Number n ? n.intValue() : 0;
                int shots = shotsMap != null && shotsMap.get("total") instanceof Number n ? n.intValue() : 0;
                double passAcc = passesMap != null && passesMap.get("accuracy") instanceof Number n ? n.doubleValue() : 0;
                int keyPasses = passesMap != null && passesMap.get("key") instanceof Number n ? n.intValue() : 0;

                if (rating > 0) players.add(new PlayerStat(name, rating, goals, assists, shots, passAcc, keyPasses, isHome));
            }
        }

        if (players.isEmpty()) return null;

        var topHome = players.stream().filter(PlayerStat::isHome)
                .max(Comparator.comparingDouble(PlayerStat::rating)).orElse(null);
        var topAway = players.stream().filter(p -> !p.isHome())
                .max(Comparator.comparingDouble(PlayerStat::rating)).orElse(null);
        var bestPasser = players.stream().filter(p -> p.keyPasses() > 0)
                .max(Comparator.comparingInt(PlayerStat::keyPasses)).orElse(null);

        if (topHome == null || topAway == null) return null;

        // Grok commentary
        String commentary = getStatsCommentary(homeRu, awayRu, match, topHome, topAway, bestPasser);

        var sb = new StringBuilder();
        sb.append("📈 Статистика матча\n\n");
        sb.append("🏴󠁧󠁢󠁥󠁮󠁧󠁿 ").append(homeRu).append(" ")
                .append(match.getHomeScore()).append(":").append(match.getAwayScore())
                .append(" ").append(awayRu).append("\n\n");

        sb.append("⭐ Лучшие игроки:\n\n");
        sb.append("🏠 ").append(homeRu).append(":\n");
        sb.append("👤 ").append(topHome.name()).append(" — ").append(String.format("%.1f", topHome.rating())).append("/10\n");
        sb.append("   ⚽ ").append(topHome.goals()).append("г ").append(topHome.assists()).append("п")
                .append(" | 👟 ").append(topHome.shots()).append(" удары")
                .append(" | 🎯 ").append((int) topHome.passAcc()).append("% пасы\n\n");

        sb.append("✈️ ").append(awayRu).append(":\n");
        sb.append("👤 ").append(topAway.name()).append(" — ").append(String.format("%.1f", topAway.rating())).append("/10\n");
        sb.append("   ⚽ ").append(topAway.goals()).append("г ").append(topAway.assists()).append("п")
                .append(" | 👟 ").append(topAway.shots()).append(" удары")
                .append(" | 🎯 ").append((int) topAway.passAcc()).append("% пасы\n");

        if (bestPasser != null) {
            sb.append("\n📊 Лучший пас:\n");
            sb.append("🎯 ").append(bestPasser.name()).append(" — ")
                    .append((int) bestPasser.passAcc()).append("% (").append(bestPasser.keyPasses()).append(" острых)\n");
        }

        if (commentary != null) sb.append("\n💬 ").append(commentary).append("\n");

        String homeTag = "#" + homeRu.replace(" ", "_");
        String awayTag = "#" + awayRu.replace(" ", "_");
        sb.append("\n#Статистика ").append(homeTag).append(" ").append(awayTag).append(" #апл");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String getStatsCommentary(String homeRu, String awayRu, MatchDay match,
                                       Object topHome, Object topAway, Object bestPasser) {
        try {
            String prompt = "Match: " + homeRu + " " + match.getHomeScore() + ":" + match.getAwayScore() + " " + awayRu
                    + ". Write 2 sentences of stats commentary in Russian. Respond ONLY with JSON: {\"stats_commentary\": \"...\"}";

            var body = objectMapper.writeValueAsString(Map.of(
                    "model", groqModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            ));

            var request = new Request.Builder()
                    .url(groqUrl)
                    .header("Authorization", "Bearer " + groqKey)
                    .post(RequestBody.create(body, MediaType.get("application/json")))
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var choices = (List<Map<String, Object>>) root.get("choices");
                if (choices == null || choices.isEmpty()) return null;
                var message = (Map<String, Object>) choices.get(0).get("message");
                String text = (String) message.get("content");
                text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                var parsed = objectMapper.readValue(text, Map.class);
                return (String) parsed.get("stats_commentary");
            }
        } catch (Exception e) {
            return null;
        }
    }
}
