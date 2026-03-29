package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.NewsItem;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiProcessorService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ArticleScraperService articleScraperService;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    @Value("${groq.model}")
    private String model;

    private static final int MAX_INPUT_CHARS = 3000;

    public NewsItem process(NewsItem item) {
        try {
            String fullText = articleScraperService.scrapeArticle(item.getUrl());
            item.setFullTextEn(fullText);

            String rawText = (fullText != null && fullText.length() > 200) ? fullText : item.getSummaryEn();
            boolean usingFullText = fullText != null && fullText.length() > 200;

            // Truncate at sentence boundary so Groq doesn't receive broken text
            String textForAi;
            if (rawText != null && rawText.length() > MAX_INPUT_CHARS) {
                String cut = rawText.substring(0, MAX_INPUT_CHARS);
                int lastSentence = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf(".\n"));
                textForAi = lastSentence > MAX_INPUT_CHARS / 2
                        ? cut.substring(0, lastSentence + 1)
                        : cut;
            } else {
                textForAi = rawText;
            }

            log.info("AI processing '{}' using {} ({} chars)",
                    item.getTitleEn(), usingFullText ? "full text" : "RSS summary",
                    textForAi != null ? textForAi.length() : 0);

            return callGroq(item, textForAi, usingFullText);
        } catch (Exception e) {
            log.warn("AI processing failed for '{}': {}", item.getTitleEn(), e.getMessage());
            return item;
        }
    }

    private NewsItem callGroq(NewsItem item, String textForAi, boolean usingFullText) throws Exception {
        var requestBody = buildRequestBody(item, textForAi, usingFullText);

        var request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (response.code() == 429) {
                log.warn("Groq rate limit hit for '{}' — retrying in 10s", item.getTitleEn());
                Thread.sleep(10000);
                try (var retry = httpClient.newCall(request).execute()) {
                    if (retry.code() == 429) {
                        log.warn("Groq still rate limited for '{}' — will retry next run", item.getTitleEn());
                        item.setTitleRu(null);
                        return item;
                    }
                    return parseGroqResponse(item, retry.body().string());
                }
            }
            return parseGroqResponse(item, response.body().string());
        }
    }

    private static final String SYSTEM_PROMPT = """
            You are a passionate Russian football journalist writing for a Telegram channel about the English Premier League. \
            Write like a real football fan — emotional, knowledgeable, sometimes sarcastic. \
            Write commentary as a neutral observer, never as a fan of any specific club — no 'у нас', 'наши', 'мы'. \
            NEVER use: 'великий', 'все будут следить', 'интересно посмотреть'. \
            Always respond ONLY with valid JSON, no markdown, no backticks.

            MANDATORY name translations — use EXACTLY these, never translate names as Russian words:
            Clubs: Arsenal→Арсенал, Chelsea→Челси, Liverpool→Ливерпуль, Man City/Manchester City→Манчестер Сити, \
            Man United/Manchester United→Манчестер Юнайтед, Tottenham→Тоттенхэм, Newcastle→Ньюкасл, \
            Aston Villa→Астон Вилла, West Ham→Вест Хэм, Everton→Эвертон, Brighton→Брайтон, \
            Fulham→Фулэм, Wolves→Вулверхэмптон, Nottingham Forest→Ноттингем Форест, \
            Brentford→Брентфорд, Bournemouth→Борнмут, Crystal Palace→Кристал Пэлас, \
            Southampton→Саутгемптон, Ipswich→Ипсвич, Leicester→Лестер, \
            Real Madrid→Реал Мадрид, Barcelona→Барселона, Bayern→Бавария, PSG→ПСЖ.
            Players: Haaland→Холанд, Salah→Салах, Saka→Сака, Bukayo Saka→Сака, \
            Bellingham→Беллингем, De Bruyne→Де Брёйне, Kane→Кейн, Rashford→Рэшфорд, \
            Palmer→Палмер, Foden→Фоден, Fernandes→Фернандеш, Van Dijk→Ван Дейк, \
            Alexander-Arnold→Трент, Watkins→Уоткинс, Isak→Исак, Son→Сон, \
            Odegaard→Эдегор, Rice→Райс, Gabriel→Габриэл, Dias→Диаш.
            Managers: Arteta→Артета, Guardiola→Гвардиола, Slot→Слот, Amorim→Аморим, \
            Maresca→Мареска, Howe→Хоу.""";

    private String buildUserPrompt(NewsItem item, String textForAi, boolean usingFullText) {
        String textLabel = usingFullText ? "News text" : "News summary";
        return """
                Given this English football news about the Premier League, respond with JSON:
                {
                  "title_ru": "translated title in Russian, punchy and specific",
                  "summary_ru": "3-4 sentences in Russian with ALL key facts: who, what, numbers. No generic phrases. Write like a journalist.",
                  "quote": "ONLY if the article contains a verbatim direct quote — extract it in Russian with speaker prefix like 'Артета: ' or 'Холанд: '. If no direct quote exists return exactly null. Do NOT explain, do NOT paraphrase.",
                  "commentary": "2-3 sentences. Neutral but emotional observer opinion. Be specific about what this means for the club or title race.",
                  "tags": ["2-4 entity names from the article in any language — will be translated automatically"],
                  "audience_interest": <1-10>,
                  "interest_reason": "one sentence"
                }
                News title: %s
                %s: %s""".formatted(item.getTitleEn(), textLabel, textForAi);
    }

    private String buildRequestBody(NewsItem item, String textForAi, boolean usingFullText) throws Exception {
        var body = Map.of(
                "model", model,
                "temperature", 0.7,
                "max_tokens", 2500,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", buildUserPrompt(item, textForAi, usingFullText))
                )
        );
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private NewsItem parseGroqResponse(NewsItem item, String responseJson) {
        try {
            var root = objectMapper.readValue(responseJson, Map.class);
            var choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("Groq no choices. Response: {}", responseJson);
                return item;
            }
            var message = (Map<String, Object>) choices.get(0).get("message");
            var text = (String) message.get("content");

            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            var parsed = objectMapper.readValue(text, Map.class);
            item.setTitleRu(EntityDictionaryUtil.normalizeEntities((String) parsed.get("title_ru")));
            item.setSummaryRu(EntityDictionaryUtil.normalizeEntities((String) parsed.get("summary_ru")));
            item.setAiCommentary(EntityDictionaryUtil.normalizeEntities((String) parsed.get("commentary")));
            var rawQuote = (String) parsed.get("quote");
            if (isValidQuote(rawQuote)) {
                item.setQuote(EntityDictionaryUtil.normalizeEntities(rawQuote));
            }
            // Tags generated from English title via dictionary — not trusted from Groq
            var dictTags = EntityDictionaryUtil.generateTags(item.getTitleEn());
            item.setTags(dictTags.isEmpty()
                    ? (List<String>) parsed.getOrDefault("tags", Collections.emptyList())
                    : dictTags);
            if (parsed.get("audience_interest") instanceof Integer aiScore) {
                item.setAiInterestScore(aiScore);
            } else if (parsed.get("audience_interest") instanceof Number n) {
                item.setAiInterestScore(n.intValue());
            }
            item.setInterestReason((String) parsed.get("interest_reason"));

            if (item.getTitleRu() == null || item.getTitleRu().isBlank()) {
                throw new RuntimeException("Groq returned empty title_ru — response was likely truncated");
            }

            log.info("AI ranked '{}' as {}/10 — {}", item.getTitleEn(), item.getAiInterestScore(), item.getInterestReason());
            log.info("AI processed: {}", item.getTitleRu());
        } catch (Exception e) {
            log.warn("Failed to parse Groq response: {}", e.getMessage());
            // Mark item as AI-failed so scheduler skips publishing
            item.setTitleRu(null);
        }
        return item;
    }

    // Valid quote must have "Name: text" format and be long enough to be real
    private boolean isValidQuote(String quote) {
        if (quote == null || quote.isBlank() || quote.equalsIgnoreCase("null")) return false;
        if (quote.length() < 20) return false;
        if (!quote.contains(":")) return false;
        // Reject AI explanations like "Артета не цитируется..."
        String lower = quote.toLowerCase();
        if (lower.contains("не цитируется") || lower.contains("нет цитаты") ||
            lower.contains("no quote") || lower.contains("not quoted") ||
            lower.contains("не упоминается")) return false;
        return true;
    }
}
