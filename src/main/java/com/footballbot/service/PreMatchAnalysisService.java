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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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
    private final BetService betService;
    private final VkPublisherService vkPublisherService;

    @Value("${football.api.key:}")
    private String footballApiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String footballApiUrl;

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final String ESPN_API = "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1";

    private record AnalysisResult(String post, String bet, String betConfidence) {}

    public void generateAndPost(MatchDay match) {
        if (match.getMatchId() == null) return;
        String matchKey = String.valueOf(match.getMatchId());

        if (publishedAnalysisRepository.existsById(matchKey)) {
            log.info("Pre-match analysis already posted for match {}", matchKey);
            return;
        }

        try {
            var result = buildAnalysisPost(match);
            telegramPublisherService.sendTextMessage(result.post);
            vkPublisherService.publishText(result.post);
            betService.saveBet(matchKey, match.getHomeTeam(), match.getAwayTeam(),
                    result.bet, result.betConfidence);
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

    private AnalysisResult buildAnalysisPost(MatchDay match) throws Exception {
        String homeRu = matchScheduleService.translateTeam(match.getHomeTeam());
        String awayRu = matchScheduleService.translateTeam(match.getAwayTeam());
        String kickoffStr = match.getKickoff() != null
                ? match.getKickoff().format(DateTimeFormatter.ofPattern("HH:mm"))
                : "??:??";

        // Fetch all data
        var standings = fetchStandings();
        var homeStanding = findStanding(standings, match.getHomeTeamId());
        var awayStanding = findStanding(standings, match.getAwayTeamId());

        var homeForm = fetchTeamForm(match.getHomeTeamId(), match.getHomeTeam());
        var awayForm = fetchTeamForm(match.getAwayTeamId(), match.getAwayTeam());

        var homeFatigue = fetchFatigue(match.getHomeTeamId());
        var awayFatigue = fetchFatigue(match.getAwayTeamId());

        var homeCoach = fetchCoach(match.getHomeTeamId());
        var awayCoach = fetchCoach(match.getAwayTeamId());

        var seasonScorers = fetchSeasonScorers();
        var homeScorers = seasonScorers.getOrDefault(match.getHomeTeam(), List.of());
        var awayScorers = seasonScorers.getOrDefault(match.getAwayTeam(), List.of());

        String homeMotivation = calcMotivation(homeStanding, standings.size());
        String awayMotivation = calcMotivation(awayStanding, standings.size());

        var lineups = fetchLineups(match);

        String prompt = buildPrompt(homeRu, awayRu, kickoffStr,
                homeStanding, awayStanding,
                homeForm, awayForm,
                homeFatigue, awayFatigue,
                homeCoach, awayCoach,
                homeScorers, awayScorers,
                homeMotivation, awayMotivation,
                lineups);

        return callGroq(prompt, homeRu, awayRu, kickoffStr);
    }

    // ── football-data.org ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchStandings() {
        try {
            var request = new Request.Builder()
                    .url(footballApiUrl + "/competitions/2021/standings")
                    .header("X-Auth-Token", footballApiKey)
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var standingsList = (List<Map<String, Object>>) root.get("standings");
                if (standingsList == null) return List.of();
                var total = standingsList.stream()
                        .filter(s -> "TOTAL".equals(s.get("type")))
                        .findFirst().orElse(null);
                if (total == null) return List.of();
                return (List<Map<String, Object>>) total.get("table");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch standings: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findStanding(List<Map<String, Object>> table, Integer teamId) {
        if (teamId == null) return Map.of();
        return table.stream()
                .filter(row -> {
                    var team = (Map<String, Object>) row.get("team");
                    return team != null && teamId.equals(((Number) team.getOrDefault("id", -1)).intValue());
                })
                .findFirst().orElse(Map.of());
    }

    @SuppressWarnings("unchecked")
    private String fetchTeamForm(Integer teamId, String teamName) {
        if (teamId == null) return "";
        try {
            var url = footballApiUrl + "/teams/" + teamId + "/matches?status=FINISHED&limit=5&competitions=2021";
            var request = new Request.Builder()
                    .url(url)
                    .header("X-Auth-Token", footballApiKey)
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var matches = (List<Map<String, Object>>) root.get("matches");
                if (matches == null || matches.isEmpty()) return "";

                var sb = new StringBuilder();
                for (var m : matches) {
                    var home = (Map<String, Object>) m.get("homeTeam");
                    var away = (Map<String, Object>) m.get("awayTeam");
                    var score = (Map<String, Object>) m.get("score");
                    var ft = score != null ? (Map<String, Object>) score.get("fullTime") : null;
                    if (home == null || away == null || ft == null) continue;

                    int hg = ft.get("home") instanceof Number n ? n.intValue() : 0;
                    int ag = ft.get("away") instanceof Number n ? n.intValue() : 0;

                    String homeName = (String) home.getOrDefault("shortName", home.get("name"));
                    boolean isHome = teamName.equalsIgnoreCase(homeName);
                    int teamGoals = isHome ? hg : ag;
                    int oppGoals = isHome ? ag : hg;
                    String opp = isHome
                            ? (String) away.getOrDefault("shortName", away.get("name"))
                            : homeName;

                    String result = teamGoals > oppGoals ? "В" : teamGoals == oppGoals ? "Н" : "П";
                    sb.append(result).append(" ").append(teamGoals).append(":").append(oppGoals)
                            .append(" (").append(opp).append("), ");
                }
                return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "";
            }
        } catch (Exception e) {
            log.warn("Failed to fetch team form for {}: {}", teamName, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchFatigue(Integer teamId) {
        if (teamId == null) return "";
        try {
            var url = footballApiUrl + "/teams/" + teamId + "/matches?status=FINISHED&limit=5";
            var request = new Request.Builder()
                    .url(url)
                    .header("X-Auth-Token", footballApiKey)
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var matches = (List<Map<String, Object>>) root.get("matches");
                if (matches == null || matches.isEmpty()) return "";

                // Most recent match is last in list
                var lastMatch = matches.get(matches.size() - 1);
                String utcDate = (String) lastMatch.get("utcDate");
                if (utcDate == null) return "";

                LocalDate lastMatchDate = LocalDateTime.parse(utcDate, DateTimeFormatter.ISO_DATE_TIME)
                        .atZone(ZoneId.of("UTC")).withZoneSameInstant(MOSCOW).toLocalDate();
                long daysSince = ChronoUnit.DAYS.between(lastMatchDate, LocalDate.now(MOSCOW));

                // Count matches in last 14 days
                LocalDate twoWeeksAgo = LocalDate.now(MOSCOW).minusDays(14);
                long recentCount = matches.stream().filter(m -> {
                    String d = (String) m.get("utcDate");
                    if (d == null) return false;
                    LocalDate md = LocalDateTime.parse(d, DateTimeFormatter.ISO_DATE_TIME)
                            .atZone(ZoneId.of("UTC")).withZoneSameInstant(MOSCOW).toLocalDate();
                    return md.isAfter(twoWeeksAgo);
                }).count();

                String fatigue = daysSince <= 3 ? "мало отдыха (" + daysSince + " дн.)" : "отдохнули (" + daysSince + " дн.)";
                return fatigue + ", " + recentCount + " матчей за 14 дней";
            }
        } catch (Exception e) {
            log.warn("Failed to fetch fatigue for team {}: {}", teamId, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchCoach(Integer teamId) {
        if (teamId == null) return "";
        try {
            var request = new Request.Builder()
                    .url(footballApiUrl + "/teams/" + teamId)
                    .header("X-Auth-Token", footballApiKey)
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var coach = (Map<String, Object>) root.get("coach");
                return coach != null ? (String) coach.getOrDefault("name", "") : "";
            }
        } catch (Exception e) {
            log.warn("Failed to fetch coach for team {}: {}", teamId, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> fetchSeasonScorers() {
        try {
            var request = new Request.Builder()
                    .url(footballApiUrl + "/competitions/2021/scorers?limit=50")
                    .header("X-Auth-Token", footballApiKey)
                    .build();
            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var scorers = (List<Map<String, Object>>) root.get("scorers");
                if (scorers == null) return Map.of();

                Map<String, List<String>> byTeam = new HashMap<>();
                for (var s : scorers) {
                    var team = (Map<String, Object>) s.get("team");
                    var player = (Map<String, Object>) s.get("player");
                    if (team == null || player == null) continue;
                    String teamName = (String) team.get("shortName");
                    String playerName = (String) player.get("name");
                    int goals = s.get("goals") instanceof Number n ? n.intValue() : 0;
                    byTeam.computeIfAbsent(teamName, k -> new ArrayList<>())
                            .add(playerName + " " + goals + "г");
                }
                // Keep top 3 per team
                byTeam.replaceAll((k, v) -> v.stream().limit(3).collect(Collectors.toList()));
                return byTeam;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch season scorers: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── ESPN lineups ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String fetchLineups(MatchDay match) {
        try {
            // Find ESPN event ID by date
            String date = match.getKickoff() != null
                    ? match.getKickoff().atZone(MOSCOW).toLocalDate().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    : LocalDate.now(MOSCOW).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            var scoreboardReq = new Request.Builder()
                    .url(ESPN_API + "/scoreboard?dates=" + date)
                    .build();

            String eventId = null;
            try (var response = httpClient.newCall(scoreboardReq).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var events = (List<Map<String, Object>>) root.get("events");
                if (events == null) return "";

                for (var event : events) {
                    String name = (String) event.getOrDefault("name", "");
                    // Match by checking if both team names appear in ESPN event name
                    if (name.toLowerCase().contains(match.getHomeTeam().toLowerCase().split(" ")[0]) ||
                        name.toLowerCase().contains(match.getAwayTeam().toLowerCase().split(" ")[0])) {
                        eventId = String.valueOf(event.get("id"));
                        break;
                    }
                }
            }

            if (eventId == null) return "";

            var summaryReq = new Request.Builder()
                    .url(ESPN_API + "/summary?event=" + eventId)
                    .build();

            try (var response = httpClient.newCall(summaryReq).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var rosters = (List<Map<String, Object>>) root.get("rosters");
                if (rosters == null) return "";

                var sb = new StringBuilder();
                for (var teamData : rosters) {
                    var teamMap = (Map<String, Object>) teamData.get("team");
                    String teamName = teamMap != null ? (String) teamMap.get("displayName") : "?";
                    var players = (List<Map<String, Object>>) teamData.get("roster");
                    if (players == null) continue;

                    var starters = players.stream()
                            .filter(p -> Boolean.TRUE.equals(p.get("starter")))
                            .toList();
                    if (starters.isEmpty()) continue;

                    sb.append(teamName).append(": ");
                    var names = starters.stream()
                            .map(p -> {
                                var athlete = (Map<String, Object>) p.get("athlete");
                                return athlete != null ? (String) athlete.get("displayName") : "?";
                            })
                            .collect(Collectors.joining(", "));
                    sb.append(names).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch lineups from ESPN: {}", e.getMessage());
            return "";
        }
    }

    // ── Motivation ───────────────────────────────────────────────────────────

    private String calcMotivation(Map<String, Object> standing, int totalTeams) {
        if (standing.isEmpty()) return "";
        int pos = standing.get("position") instanceof Number n ? n.intValue() : 0;
        int pts = standing.get("points") instanceof Number n ? n.intValue() : 0;

        if (pos == 1) return "лидер, борьба за чемпионство";
        if (pos <= 3) return pos + "-е место (" + pts + " очков), гонка за чемпионством";
        if (pos <= 4) return "4-е место, борьба за Лигу Чемпионов";
        if (pos <= 6) return pos + "-е место, борьба за еврокубки";
        if (pos >= totalTeams - 2) return pos + "-е место, зона вылета — каждое очко на вес золота";
        if (pos >= totalTeams - 5) return pos + "-е место, опасная близость к зоне вылета";
        return pos + "-е место (" + pts + " очков), середина таблицы";
    }

    // ── Groq ─────────────────────────────────────────────────────────────────

    private String buildPrompt(String homeRu, String awayRu, String kickoffStr,
                                Map<String, Object> homeStanding, Map<String, Object> awayStanding,
                                String homeForm, String awayForm,
                                String homeFatigue, String awayFatigue,
                                String homeCoach, String awayCoach,
                                List<String> homeScorers, List<String> awayScorers,
                                String homeMotivation, String awayMotivation,
                                String lineups) {
        var sb = new StringBuilder();
        sb.append("Напиши превью матча АПЛ. Ты ставишь собственные деньги — анализируй честно.\n");
        sb.append("Ответь ТОЛЬКО валидным JSON:\n");
        sb.append("{\n");
        sb.append("  \"analysis\": \"3-4 предложения аналитики — форма, мотивация, усталость, опасные игроки\",\n");
        sb.append("  \"key_factor\": \"одно ключевое обстоятельство которое решит матч\",\n");
        sb.append("  \"bet\": \"конкретная ставка: одно из — 'Победа хозяев', 'Победа гостей', 'Ничья', 'Обе забьют', 'Тотал больше 2.5', 'Тотал меньше 2.5', 'Победа хозяев -1', 'Победа гостей -1' — или 'пропускаю' если нет уверенности\",\n");
        sb.append("  \"bet_confidence\": \"высокая / средняя / низкая — только если не пропускаешь\",\n");
        sb.append("  \"bet_reasoning\": \"1-2 предложения почему именно эта ставка — только если не пропускаешь\"\n");
        sb.append("}\n\n");

        sb.append("МАТЧ: ").append(homeRu).append(" vs ").append(awayRu)
                .append(", ").append(kickoffStr).append(" МСК\n\n");

        // Standings
        sb.append("ТАБЛИЦА:\n");
        appendStanding(sb, homeRu, homeStanding, homeMotivation, homeCoach);
        appendStanding(sb, awayRu, awayStanding, awayMotivation, awayCoach);

        // Form
        if (!homeForm.isBlank() || !awayForm.isBlank()) {
            sb.append("\nФОРМА (последние 5 матчей АПЛ):\n");
            if (!homeForm.isBlank()) sb.append(homeRu).append(": ").append(homeForm).append("\n");
            if (!awayForm.isBlank()) sb.append(awayRu).append(": ").append(awayForm).append("\n");
        }

        // Scorers
        if (!homeScorers.isEmpty() || !awayScorers.isEmpty()) {
            sb.append("\nБОМБАРДИРЫ СЕЗОНА:\n");
            if (!homeScorers.isEmpty()) sb.append(homeRu).append(": ").append(String.join(", ", homeScorers)).append("\n");
            if (!awayScorers.isEmpty()) sb.append(awayRu).append(": ").append(String.join(", ", awayScorers)).append("\n");
        }

        // Fatigue
        if (!homeFatigue.isBlank() || !awayFatigue.isBlank()) {
            sb.append("\nУСТАЛОСТЬ:\n");
            if (!homeFatigue.isBlank()) sb.append(homeRu).append(": ").append(homeFatigue).append("\n");
            if (!awayFatigue.isBlank()) sb.append(awayRu).append(": ").append(awayFatigue).append("\n");
        }

        // Lineups
        if (!lineups.isBlank()) {
            sb.append("\nСТАРТОВЫЕ СОСТАВЫ:\n").append(lineups);
        }

        return sb.toString();
    }

    private void appendStanding(StringBuilder sb, String teamRu, Map<String, Object> standing,
                                 String motivation, String coach) {
        if (standing.isEmpty()) return;
        int pos = standing.get("position") instanceof Number n ? n.intValue() : 0;
        int pts = standing.get("points") instanceof Number n ? n.intValue() : 0;
        int won = standing.get("won") instanceof Number n ? n.intValue() : 0;
        int draw = standing.get("draw") instanceof Number n ? n.intValue() : 0;
        int lost = standing.get("lost") instanceof Number n ? n.intValue() : 0;
        int gf = standing.get("goalsFor") instanceof Number n ? n.intValue() : 0;
        int ga = standing.get("goalsAgainst") instanceof Number n ? n.intValue() : 0;
        int played = standing.get("playedGames") instanceof Number n ? n.intValue() : 0;

        sb.append(teamRu).append(": ").append(pos).append("-е место, ").append(pts).append(" очков")
                .append(" (").append(won).append("В ").append(draw).append("Н ").append(lost).append("П)")
                .append(" | голы: ").append(gf).append("-").append(ga)
                .append(" | ").append(motivation);
        if (!coach.isBlank()) sb.append(" | тренер: ").append(coach);
        sb.append("\n");
    }

    @SuppressWarnings("unchecked")
    private AnalysisResult callGroq(String prompt, String homeRu, String awayRu, String kickoffStr) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of(
                "model", groqProperties.getModel(),
                "temperature", 0.7,
                "max_tokens", 600,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Ты профессиональный футбольный аналитик. " +
                                "Твой прогноз — это твоя личная ставка. Представь что ставишь собственные деньги. " +
                                "Ошибёшься — потеряешь. Анализируй данные честно и строго. " +
                                "Если матч непредсказуем и ты не видишь чёткого преимущества — честно скажи 'пропускаю'. " +
                                "Только валидный JSON, без markdown."),
                        Map.of("role", "user", "content", prompt)
                )
        ));

        var httpRequest = new Request.Builder()
                .url(groqProperties.getApi().getUrl())
                .header("Authorization", "Bearer " + groqProperties.getApi().getKey())
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

        String responseJson = groqRateLimiter.submit(
                "pre-match:" + homeRu + "vs" + awayRu,
                () -> {
                    try (var response = httpClient.newCall(httpRequest).execute()) {
                        var rb = response.body();
                        return rb != null ? rb.string() : "";
                    }
                }
        ).get();

        var root = objectMapper.readValue(responseJson, Map.class);
        var choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("No choices from Groq");

        var message = (Map<String, Object>) choices.get(0).get("message");
        var text = (String) message.get("content");
        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        var parsed = objectMapper.readValue(text, Map.class);
        String analysis = (String) parsed.getOrDefault("analysis", "");
        String keyFactor = (String) parsed.getOrDefault("key_factor", "");
        String bet = (String) parsed.getOrDefault("bet", "");
        String betConfidence = (String) parsed.getOrDefault("bet_confidence", "");
        String betReasoning = (String) parsed.getOrDefault("bet_reasoning", "");

        boolean skip = bet.toLowerCase().contains("пропускаю");

        var sb = new StringBuilder();
        sb.append("🏟 ").append(homeRu).append(" — ").append(awayRu).append("\n");
        sb.append("🕐 Начало в ").append(kickoffStr).append(" МСК\n\n");
        if (!analysis.isBlank()) sb.append(analysis).append("\n\n");
        if (!keyFactor.isBlank()) sb.append("⚡ ").append(keyFactor).append("\n\n");

        if (skip) {
            sb.append("💸 Ставка: пропускаю этот матч\n");
        } else if (!bet.isBlank()) {
            String confidenceEmoji = "высокая".equals(betConfidence) ? "🔒" :
                                     "средняя".equals(betConfidence) ? "🟡" : "⚠️";
            sb.append("💸 Ставка: ").append(bet).append(" ").append(confidenceEmoji).append(" ").append(betConfidence).append("\n");
            if (!betReasoning.isBlank()) sb.append("└ ").append(betReasoning).append("\n");
        }

        sb.append("\n\nПрогноз основан на статистике и не является рекомендацией к ставке");

        String statsLine = betService.getStatsLine();
        if (!statsLine.isBlank()) sb.append("\n📊 ").append(statsLine);

        return new AnalysisResult(sb.toString(), bet, betConfidence);
    }
}
