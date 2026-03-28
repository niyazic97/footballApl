package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeeklyRoundupService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;
    private final MatchScheduleService matchScheduleService;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    public void postRoundup() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Football API key not configured, skipping weekly roundup");
            return;
        }
        try {
            var standings = fetchStandings();
            var scorers = fetchTopScorers();
            var post = formatPost(standings, scorers);
            telegramPublisherService.sendTextMessage(post);
            log.info("Weekly roundup posted");
        } catch (Exception e) {
            log.warn("Weekly roundup failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchStandings() throws Exception {
        var request = new Request.Builder()
                .url(apiUrl + "/competitions/2021/standings")
                .header("X-Auth-Token", apiKey)
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var root = objectMapper.readValue(response.body().string(), Map.class);
            var standings = (List<Map<String, Object>>) root.get("standings");
            var total = standings.stream()
                    .filter(s -> "TOTAL".equals(s.get("type")))
                    .findFirst().orElse(null);
            if (total == null) return List.of();
            return (List<Map<String, Object>>) total.get("table");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTopScorers() throws Exception {
        var request = new Request.Builder()
                .url(apiUrl + "/competitions/2021/scorers?limit=5")
                .header("X-Auth-Token", apiKey)
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var root = objectMapper.readValue(response.body().string(), Map.class);
            return (List<Map<String, Object>>) root.get("scorers");
        }
    }

    private String formatPost(List<Map<String, Object>> table, List<Map<String, Object>> scorers) {
        var sb = new StringBuilder();
        sb.append("📊 Итоги недели — АПЛ\n\n");
        sb.append("🏆 Таблица (Топ-6):\n");

        int count = 0;
        for (var row : table) {
            if (count >= 6) break;
            int pos = (int) row.get("position");
            var team = (Map<String, Object>) row.get("team");
            String name = matchScheduleService.translateTeam((String) team.get("shortName"));
            int pts = (int) row.get("points");
            int gd = (int) row.get("goalDifference");
            sb.append(String.format("%2d. %-20s— %d %s  (%s%d)\n",
                    pos, name, pts, pointsWord(pts), gd >= 0 ? "+" : "", gd));
            count++;
        }

        sb.append("\n⚠️ Зона вылета:\n");
        for (var row : table) {
            int pos = (int) row.get("position");
            if (pos < 18) continue;
            var team = (Map<String, Object>) row.get("team");
            String name = matchScheduleService.translateTeam((String) team.get("shortName"));
            int pts = (int) row.get("points");
            int gd = (int) row.get("goalDifference");
            sb.append(String.format("%2d. %-20s— %d %s  (%s%d)\n",
                    pos, name, pts, pointsWord(pts), gd >= 0 ? "+" : "", gd));
        }

        if (scorers != null && !scorers.isEmpty()) {
            sb.append("\n⚽ Бомбардиры:\n");
            int i = 1;
            for (var s : scorers) {
                var player = (Map<String, Object>) s.get("player");
                var team = (Map<String, Object>) s.get("team");
                String playerName = (String) player.get("name");
                String teamName = matchScheduleService.translateTeam((String) team.get("shortName"));
                int goals = s.get("goals") instanceof Number n ? n.intValue() : 0;
                sb.append(String.format("%d. %s (%s) — %d %s\n",
                        i++, playerName, teamName, goals, goalsWord(goals)));
            }
        }

        sb.append("\n#апл #Таблица #Итоги");
        return sb.toString();
    }

    private String pointsWord(int pts) {
        if (pts % 100 >= 11 && pts % 100 <= 14) return "очков";
        return switch (pts % 10) {
            case 1 -> "очко";
            case 2, 3, 4 -> "очка";
            default -> "очков";
        };
    }

    private String goalsWord(int goals) {
        if (goals % 100 >= 11 && goals % 100 <= 14) return "голов";
        return switch (goals % 10) {
            case 1 -> "гол";
            case 2, 3, 4 -> "гола";
            default -> "голов";
        };
    }
}
