package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.MatchDay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchCacheService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final List<Integer> LEAGUE_IDS = List.of(2021, 2001);

    private List<MatchDay> todayMatches = new ArrayList<>();
    private LocalDate cacheDate = null;

    public void refreshTodayMatches() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Football API key not configured, skipping cache refresh");
            return;
        }

        var result = new ArrayList<MatchDay>();
        var today = LocalDate.now(MOSCOW).toString();

        for (var leagueId : LEAGUE_IDS) {
            try {
                var url = apiUrl + "/competitions/" + leagueId + "/matches?dateFrom=" + today + "&dateTo=" + today;
                var request = new Request.Builder()
                        .url(url)
                        .header("X-Auth-Token", apiKey)
                        .build();

                try (var response = httpClient.newCall(request).execute()) {
                    var root = objectMapper.readValue(response.body().string(), Map.class);
                    @SuppressWarnings("unchecked")
                    var matches = (List<Map<String, Object>>) root.get("matches");
                    if (matches == null) continue;

                    var league = leagueId == 2021 ? "EPL" : "UCL";
                    for (var match : matches) {
                        var md = parseMatch(match, league);
                        if (md != null) result.add(md);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch matches for league {}: {}", leagueId, e.getMessage());
            }
        }

        result.sort(Comparator.comparing(MatchDay::getKickoff, Comparator.nullsLast(Comparator.naturalOrder())));
        this.todayMatches = result;
        this.cacheDate = LocalDate.now(MOSCOW);
        log.info("Loaded {} matches for today into cache", result.size());
    }

    public List<MatchDay> getTodayMatches() {
        if (cacheDate == null || !cacheDate.equals(LocalDate.now(MOSCOW))) {
            refreshTodayMatches();
        }
        return Collections.unmodifiableList(todayMatches);
    }

    @SuppressWarnings("unchecked")
    public void refreshMatchStatus(Integer matchId) {
        if (matchId == null || apiKey == null || apiKey.isBlank()) return;
        try {
            var url = apiUrl + "/matches/" + matchId;
            var request = new Request.Builder()
                    .url(url)
                    .header("X-Auth-Token", apiKey)
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var root = objectMapper.readValue(response.body().string(), Map.class);
                var updated = parseMatch(root, null);
                if (updated == null) return;

                for (int i = 0; i < todayMatches.size(); i++) {
                    if (matchId.equals(todayMatches.get(i).getMatchId())) {
                        todayMatches.set(i, updated);
                        log.info("Refreshed status for match {}: {}", matchId, updated.getStatus());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to refresh match status for {}: {}", matchId, e.getMessage());
        }
    }

    public boolean isActiveHours() {
        var now = ZonedDateTime.now(MOSCOW).toLocalDateTime();
        int hour = now.getHour();

        // Check if any match has unusual early kickoff
        for (var match : todayMatches) {
            if (match.getKickoff() != null) {
                long minutesUntil = java.time.temporal.ChronoUnit.MINUTES.between(now, match.getKickoff());
                if (minutesUntil >= 0 && minutesUntil <= 210) {
                    return true; // within 30 min before to 3.5h after kickoff
                }
            }
        }

        return hour >= 17; // default active window 17:00-23:59 MSK
    }

    public boolean hasMatchesNearKickoff() {
        var now = LocalDateTime.now(MOSCOW);
        return todayMatches.stream()
                .filter(m -> m.getKickoff() != null)
                .anyMatch(m -> {
                    long min = java.time.temporal.ChronoUnit.MINUTES.between(now, m.getKickoff());
                    return min >= 0 && min <= 70;
                });
    }

    @SuppressWarnings("unchecked")
    private MatchDay parseMatch(Map<String, Object> match, String leagueOverride) {
        try {
            var homeTeam = (Map<String, Object>) match.get("homeTeam");
            var awayTeam = (Map<String, Object>) match.get("awayTeam");
            var utcDate = (String) match.get("utcDate");
            if (homeTeam == null || awayTeam == null || utcDate == null) return null;

            var kickoff = LocalDateTime.parse(utcDate, DateTimeFormatter.ISO_DATE_TIME)
                    .atZone(ZoneId.of("UTC"))
                    .withZoneSameInstant(MOSCOW)
                    .toLocalDateTime();

            String league = leagueOverride;
            if (league == null) {
                var competition = (Map<String, Object>) match.get("competition");
                if (competition != null) {
                    var id = competition.get("id");
                    league = id instanceof Number n && n.intValue() == 2001 ? "UCL" : "EPL";
                } else {
                    league = "EPL";
                }
            }

            Integer matchId = match.get("id") instanceof Number n ? n.intValue() : null;
            Integer homeTeamId = homeTeam.get("id") instanceof Number n ? n.intValue() : null;
            Integer awayTeamId = awayTeam.get("id") instanceof Number n ? n.intValue() : null;

            var scoreMap = (Map<String, Object>) match.get("score");
            var fullTime = scoreMap != null ? (Map<String, Object>) scoreMap.get("fullTime") : null;
            Integer homeScore = fullTime != null && fullTime.get("home") instanceof Number n ? n.intValue() : null;
            Integer awayScore = fullTime != null && fullTime.get("away") instanceof Number n ? n.intValue() : null;

            return MatchDay.builder()
                    .homeTeam((String) homeTeam.getOrDefault("shortName", homeTeam.get("name")))
                    .awayTeam((String) awayTeam.getOrDefault("shortName", awayTeam.get("name")))
                    .league(league)
                    .kickoff(kickoff)
                    .status((String) match.get("status"))
                    .matchId(matchId)
                    .homeTeamId(homeTeamId)
                    .awayTeamId(awayTeamId)
                    .homeScore(homeScore)
                    .awayScore(awayScore)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse match: {}", e.getMessage());
            return null;
        }
    }
}
