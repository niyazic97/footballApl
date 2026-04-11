package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.NewsItem;
import com.footballbot.repository.PublishedNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class BatchFilterService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PublishedNewsRepository publishedNewsRepository;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    @Value("${groq.model}")
    private String model;

    /**
     * Filters and deduplicates candidates in a single Groq call.
     * Uses Key 1 exclusively. Falls back to returning all candidates if AI fails.
     */
    public List<NewsItem> filterAndDeduplicate(List<NewsItem> candidates) {
        if (candidates.isEmpty()) return candidates;

        var recentTitles = publishedNewsRepository
                .findByPostedAtAfter(LocalDateTime.now(ZoneId.of("Europe/Moscow")).minusHours(6))
                .stream()
                .map(p -> p.getTitle())
                .toList();

        try {
            return callGroq(candidates, recentTitles);
        } catch (Exception e) {
            log.warn("Batch filter failed, passing all {} candidates through: {}", candidates.size(), e.getMessage());
            return candidates;
        }
    }

    @SuppressWarnings("unchecked")
    private List<NewsItem> callGroq(List<NewsItem> candidates, List<String> recentTitles) throws Exception {
        var prompt = buildPrompt(candidates, recentTitles);

        var requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0.1,
                "max_tokens", 400,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        var request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        String responseJson;
        try (var response = httpClient.newCall(request).execute()) {
            if (response.code() == 429) {
                log.warn("Batch filter rate limited — passing all candidates through");
                return candidates;
            }
            responseJson = response.body().string();
        }

        return parseResponse(candidates, responseJson);
    }

    private String buildPrompt(List<NewsItem> candidates, List<String> recentTitles) {
        var sb = new StringBuilder();
        sb.append("You are a strict content filter for a Russian Telegram channel about the English Premier League.\n\n");

        if (!recentTitles.isEmpty()) {
            sb.append("Recently published articles (for duplicate detection):\n");
            recentTitles.stream().limit(15).forEach(t -> sb.append("- ").append(t).append("\n"));
            sb.append("\n");
        }

        sb.append("New candidate articles:\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i).getTitleEn()).append("\n");
        }

        sb.append("""

                For each candidate respond with one of:
                - "keep": important EPL news — match result, transfer, injury, signing, sacking/appointment, suspension, award
                - "skip": press conference, fan poll, merchandise, quiz, predictions, non-EPL leagues, Scottish/other football, kit news, financial news
                - "duplicate": same event already in recently published (different wording, same story)

                Respond ONLY with a JSON array, no explanation:
                [{"id":1,"decision":"keep"},{"id":2,"decision":"skip"},...]
                """);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<NewsItem> parseResponse(List<NewsItem> candidates, String responseJson) {
        try {
            var root = objectMapper.readValue(responseJson, Map.class);
            var choices = (List<Map<String, Object>>) root.get("choices");
            var content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            var decisions = (List<Map<String, Object>>) objectMapper.readValue(content, List.class);
            var result = new ArrayList<NewsItem>();

            for (var d : decisions) {
                int idx = ((Number) d.get("id")).intValue() - 1;
                String decision = (String) d.get("decision");
                if (idx < 0 || idx >= candidates.size()) continue;

                if ("keep".equals(decision)) {
                    result.add(candidates.get(idx));
                } else {
                    log.info("Batch filter [{}]: {}", decision, candidates.get(idx).getTitleEn());
                }
            }

            log.info("Batch filter: kept {}/{}", result.size(), candidates.size());
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse batch filter response: {} — passing all through", e.getMessage());
            return candidates;
        }
    }
}
