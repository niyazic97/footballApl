package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.StandingsSnapshot;
import com.footballbot.repository.StandingsSnapshotRepository;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchdayStandingsService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MatchCacheService matchCacheService;
    private final TelegramPublisherService telegramPublisherService;
    private final StandingsSnapshotRepository snapshotRepository;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    private static final Map<String, String> CLUB_COLORS = Map.ofEntries(
            Map.entry("Arsenal", "🔴"), Map.entry("Man United", "🔴"),
            Map.entry("Liverpool", "🔴"), Map.entry("Man City", "🔵"),
            Map.entry("Chelsea", "🔵"), Map.entry("Tottenham", "⚪"),
            Map.entry("Fulham", "⚪"), Map.entry("Everton", "🔵"),
            Map.entry("Newcastle", "⚫"), Map.entry("Wolves", "🟡")
    );

    public void checkAndPostStandings() {
        var todayMatches = matchCacheService.getTodayMatches();
        if (todayMatches.isEmpty()) return;

        long total = todayMatches.size();
        long finished = todayMatches.stream().filter(m -> "FINISHED".equals(m.getStatus())).count();

        if (finished < total) return; // not all done yet

        log.info("All {} matches finished — posting standings", total);
        postStandings();
    }

    @SuppressWarnings("unchecked")
    private void postStandings() {
        if (apiKey == null || apiKey.isBlank()) return;
        try {
            var url = apiUrl + "/competitions/2021/standings";
            var request = new Request.Builder()
                    .url(url)
                    .header("X-Auth-Token", apiKey)
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var season = (Map<String, Object>) root.get("season");
                int matchday = season != null && season.get("currentMatchday") instanceof Number n
                        ? n.intValue() : 0;

                var standings = (List<Map<String, Object>>) root.get("standings");
                if (standings == null || standings.isEmpty()) return;

                var table = (List<Map<String, Object>>) standings.get(0).get("table");
                if (table == null) return;

                // Load previous snapshot
                var previousSnapshot = snapshotRepository.findByMatchday(matchday - 1)
                        .stream().collect(Collectors.toMap(StandingsSnapshot::getTeamName, StandingsSnapshot::getPosition));

                // Build current snapshot
                var newSnapshots = new ArrayList<StandingsSnapshot>();
                var top6 = new ArrayList<String>();
                var bottom3 = new ArrayList<String>();

                for (int i = 0; i < table.size(); i++) {
                    var row = table.get(i);
                    var team = (Map<String, Object>) row.get("team");
                    String shortName = team != null ? (String) team.getOrDefault("shortName", team.get("name")) : "?";
                    String nameRu = EntityDictionaryUtil.normalizeEntities(shortName);
                    if (nameRu.equals(shortName)) nameRu = shortName; // keep original if not in dict

                    int pos = row.get("position") instanceof Number n ? n.intValue() : i + 1;
                    int pts = row.get("points") instanceof Number n ? n.intValue() : 0;
                    int gd = row.get("goalDifference") instanceof Number n ? n.intValue() : 0;

                    newSnapshots.add(StandingsSnapshot.builder()
                            .matchday(matchday)
                            .teamName(shortName)
                            .position(pos)
                            .points(pts)
                            .snapshotDate(LocalDateTime.now())
                            .build());

                    String color = CLUB_COLORS.getOrDefault(shortName, "⚫");
                    Integer prevPos = previousSnapshot.get(shortName);
                    String arrow = "";
                    if (prevPos != null) {
                        int diff = prevPos - pos;
                        if (diff >= 2) arrow = " ↑↑";
                        else if (diff == 1) arrow = " ↑";
                        else if (diff == -1) arrow = " ↓";
                        else if (diff <= -2) arrow = " ↓↓";
                    }
                    String gdStr = gd >= 0 ? "(+" + gd + ")" : "(" + gd + ")";
                    String line = String.format("%2d. %s %-20s %d %s%s", pos, color, nameRu, pts, gdStr, arrow);

                    if (pos <= 6) top6.add(line);
                    if (pos >= 18) bottom3.add(line);
                }

                // Save new snapshot
                snapshotRepository.deleteByMatchday(matchday);
                snapshotRepository.saveAll(newSnapshots);

                // Format post
                var sb = new StringBuilder();
                sb.append("📊 Таблица АПЛ — ").append(matchday).append("-й тур\n\n");
                sb.append("🔝 Топ-6:\n");
                top6.forEach(l -> sb.append(l).append("\n"));
                sb.append("\n⚠️ Зона вылета:\n");
                bottom3.forEach(l -> sb.append(l).append("\n"));
                sb.append("\n#АПЛ #Таблица");

                telegramPublisherService.sendTextMessage(sb.toString());
                log.info("Matchday standings posted for matchday {}", matchday);
            }
        } catch (Exception e) {
            log.warn("Failed to post matchday standings: {}", e.getMessage());
        }
    }
}
