package com.footballbot;

import com.footballbot.model.NewsItem;
import com.footballbot.service.AiProcessorService;
import com.footballbot.service.ArticleScraperService;
import com.footballbot.service.FormatterService;
import com.footballbot.service.TelegramPublisherService;
import com.footballbot.util.ScorerUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

@SpringBootTest
@Slf4j
public class ArticlePipelineTest {

    @Autowired ArticleScraperService scraperService;
    @Autowired AiProcessorService aiProcessorService;
    @Autowired FormatterService formatterService;
    @Autowired TelegramPublisherService telegramPublisherService;

    @Test
    void testBbcArticle() {
        String url = "https://www.bbc.com/sport/football/articles/cg73rve0y33o?at_medium=RSS&at_campaign=rss";
        String realTitle = "Are the first signs of Arsenal's reliance on Bukayo Saka starting to show?";
        String realSummary = "Arsenal's defeat by Manchester City in the Carabao Cup final raised questions about their reliance on Bukayo Saka.";

        // Step 1 — Scrape
        log.info("========== SCRAPING ==========");
        String fullText = scraperService.scrapeArticle(url);
        log.info("Scraped chars: {}", fullText != null ? fullText.length() : 0);

        // Step 2 — Build NewsItem with real RSS title
        var item = NewsItem.builder()
                .id("test-bbc-001")
                .titleEn(realTitle)
                .summaryEn(realSummary)
                .fullTextEn(fullText)
                .url(url)
                .source("bbc.co.uk")
                .league("EPL")
                .publishedAt(LocalDateTime.now())
                .build();

        // Step 3 — Score
        int score = ScorerUtil.score(item);
        log.info("Level1 score: {}", score);

        // Step 4 — AI processing
        log.info("========== GROQ AI ==========");
        var processed = aiProcessorService.process(item);
        log.info("titleRu:    {}", processed.getTitleRu());
        log.info("summaryRu:  {}", processed.getSummaryRu());
        log.info("quote:      {}", processed.getQuote());
        log.info("commentary: {}", processed.getAiCommentary());
        log.info("tags:       {}", processed.getTags());
        log.info("AI score:   {}/10 — {}", processed.getAiInterestScore(), processed.getInterestReason());

        // Step 5 — Publish to Telegram
        log.info("========== PUBLISHING TO TELEGRAM ==========");
        boolean ok = telegramPublisherService.publishNews(processed);
        log.info("Published: {}", ok);
    }
}
