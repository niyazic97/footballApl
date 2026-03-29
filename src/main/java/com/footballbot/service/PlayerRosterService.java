package com.footballbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.PlayerName;
import com.footballbot.repository.PlayerNameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlayerRosterService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PlayerNameRepository playerNameRepository;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    // EPL = 2021
    private static final List<Integer> COMPETITION_IDS = List.of(2021);

    public void refreshRosters() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Football API key not configured, skipping roster refresh");
            return;
        }

        var players = new ArrayList<PlayerName>();

        for (int competitionId : COMPETITION_IDS) {
            try {
                var url = apiUrl + "/competitions/" + competitionId + "/teams";
                var request = new Request.Builder()
                        .url(url)
                        .header("X-Auth-Token", apiKey)
                        .build();

                String body;
                try (var response = httpClient.newCall(request).execute()) {
                    body = response.body().string();
                }

                JsonNode root = objectMapper.readTree(body);
                JsonNode teams = root.path("teams");

                for (JsonNode team : teams) {
                    // Coach
                    JsonNode coach = team.path("coach");
                    if (!coach.isMissingNode()) {
                        extractName(coach.path("name").asText()).ifPresent(name ->
                                players.add(PlayerName.builder().name(name).scoreBonus(2).build()));
                    }

                    // Players
                    for (JsonNode player : team.path("squad")) {
                        extractName(player.path("name").asText()).ifPresent(name ->
                                players.add(PlayerName.builder().name(name).scoreBonus(2).build()));
                    }
                }

                log.info("Loaded {} players/coaches from competition {}", players.size(), competitionId);
                Thread.sleep(6000); // respect 10 req/min limit

            } catch (Exception e) {
                log.warn("Failed to load roster for competition {}: {}", competitionId, e.getMessage());
            }
        }

        if (!players.isEmpty()) {
            playerNameRepository.deleteAll();
            playerNameRepository.saveAll(players);
            log.info("Saved {} player names to DB", players.size());
        }
    }

    private java.util.Optional<String> extractName(String fullName) {
        if (fullName == null || fullName.isBlank()) return java.util.Optional.empty();
        String[] parts = fullName.trim().split("\\s+");
        // Take last word (surname) if name has multiple parts
        String surname = parts[parts.length - 1].toLowerCase();
        if (surname.length() < 3) return java.util.Optional.empty();
        return java.util.Optional.of(surname);
    }
}
