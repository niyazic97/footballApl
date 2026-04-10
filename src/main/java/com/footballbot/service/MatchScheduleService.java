package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.MatchDay;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchScheduleService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;
    private final MatchCacheService matchCacheService;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    private static final List<Integer> LEAGUE_IDS = List.of(2021, 2001); // EPL, UCL


    public void postSchedule() {
        var matches = matchCacheService.getTodayMatches();
        if (matches.isEmpty()) {
            log.info("No matches today, skipping schedule post");
            return;
        }

        var post = formatSchedulePost(matches);
        telegramPublisherService.sendTextMessage(post);
        log.info("Match schedule posted: {} matches", matches.size());
    }

    @SuppressWarnings("unchecked")
    public List<MatchDay> getTodayMatches() {
        var result = new ArrayList<MatchDay>();
        var today = LocalDate.now().toString();

        for (var leagueId : LEAGUE_IDS) {
            try {
                var url = apiUrl + "/competitions/" + leagueId + "/matches?dateFrom=" + today + "&dateTo=" + today;
                var request = new Request.Builder()
                        .url(url)
                        .header("X-Auth-Token", apiKey)
                        .build();

                try (var response = httpClient.newCall(request).execute()) {
                    var root = objectMapper.readValue(response.body().string(), Map.class);
                    var matches = (List<Map<String, Object>>) root.get("matches");
                    if (matches == null) continue;

                    var league = leagueId == 2021 ? "EPL" : "UCL";
                    for (var match : matches) {
                        var homeTeam = (Map<String, Object>) match.get("homeTeam");
                        var awayTeam = (Map<String, Object>) match.get("awayTeam");
                        var utcDate = (String) match.get("utcDate");

                        if (homeTeam == null || awayTeam == null || utcDate == null) continue;

                        var kickoff = LocalDateTime.parse(utcDate, DateTimeFormatter.ISO_DATE_TIME)
                                .atZone(ZoneId.of("UTC"))
                                .withZoneSameInstant(ZoneId.of("Europe/Moscow"))
                                .toLocalDateTime();

                        Integer matchId = match.get("id") instanceof Number n ? n.intValue() : null;
                        Integer homeTeamId = homeTeam.get("id") instanceof Number n ? n.intValue() : null;
                        Integer awayTeamId = awayTeam.get("id") instanceof Number n ? n.intValue() : null;

                        result.add(MatchDay.builder()
                                .homeTeam((String) homeTeam.get("shortName"))
                                .awayTeam((String) awayTeam.get("shortName"))
                                .league(league)
                                .kickoff(kickoff)
                                .status((String) match.get("status"))
                                .matchId(matchId)
                                .homeTeamId(homeTeamId)
                                .awayTeamId(awayTeamId)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch matches for league {}: {}", leagueId, e.getMessage());
            }
        }

        result.sort(Comparator.comparing(MatchDay::getKickoff));
        return result;
    }

    public String formatSchedulePost(List<MatchDay> matches) {
        var sb = new StringBuilder();
        var date = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM", new Locale("ru")));
        sb.append("📅 Матчи сегодня — ").append(date).append("\n\n");

        var epl = matches.stream().filter(m -> "EPL".equals(m.getLeague())).collect(Collectors.toList());
        var ucl = matches.stream().filter(m -> "UCL".equals(m.getLeague())).collect(Collectors.toList());

        if (!epl.isEmpty()) {
            sb.append("🏴󠁧󠁢󠁥󠁮󠁧󠁿 Премьер-лига:\n");
            for (var m : epl) {
                sb.append("⚽ ").append(translateTeam(m.getHomeTeam()))
                        .append(" — ").append(translateTeam(m.getAwayTeam()))
                        .append("  |  ").append(m.getKickoff().format(DateTimeFormatter.ofPattern("HH:mm")))
                        .append(" МСК\n");
            }
            sb.append("\n");
        }

        if (!ucl.isEmpty()) {
            sb.append("🏆 Лига Чемпионов:\n");
            for (var m : ucl) {
                sb.append("⚽ ").append(translateTeam(m.getHomeTeam()))
                        .append(" — ").append(translateTeam(m.getAwayTeam()))
                        .append("  |  ").append(m.getKickoff().format(DateTimeFormatter.ofPattern("HH:mm")))
                        .append(" МСК\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    public String translateTeam(String name) {
        if (name == null) return "Unknown";
        return EntityDictionaryUtil.translate(name).orElse(name);
    }
}
