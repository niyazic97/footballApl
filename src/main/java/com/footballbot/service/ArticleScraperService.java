package com.footballbot.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ArticleScraperService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_CHARS = 3000;

    public String scrapeArticle(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            // Remove noise elements
            doc.select("script, style, nav, footer, header, .ad, .advertisement, .related, .promo").remove();

            String text = null;

            // BBC Sport specific selectors (checked first)
            if (url != null && url.contains("bbc.co.uk")) {
                var bbc = doc.select(".ssrcss-uf6wea-RichTextComponentWrapper, .ssrcss-7uxr49-RichTextContainer");
                if (!bbc.isEmpty()) text = bbc.text();
                if (text == null || text.length() < 100) {
                    var bbcFallback = doc.select("[data-component='text-block']");
                    if (!bbcFallback.isEmpty()) text = bbcFallback.text();
                }
            }

            // Sky Sports specific
            if ((text == null || text.length() < 100) && url != null && url.contains("skysports.com")) {
                var sky = doc.select(".sdc-article-body, .article__body");
                if (!sky.isEmpty()) text = sky.text();
            }

            // Guardian specific
            if ((text == null || text.length() < 100) && url != null && url.contains("theguardian.com")) {
                var guardian = doc.select(".article-body-commercial-selector, .content__article-body");
                if (!guardian.isEmpty()) text = guardian.text();
            }

            // Generic selectors
            if (text == null || text.length() < 100) {
                var body = doc.select(".article-body, .story-body, .post-content, .entry-content, .article__body");
                if (!body.isEmpty()) text = body.text();
            }

            if (text == null || text.length() < 100) {
                var article = doc.select("article");
                if (!article.isEmpty()) text = article.text();
            }

            if (text == null || text.length() < 100) {
                var main = doc.select("main p");
                if (!main.isEmpty()) text = main.text();
            }

            if (text == null || text.length() < 100) {
                text = doc.select("p").text();
            }

            if (text == null || text.length() < 100) {
                log.info("Scraping failed (too short) for {}", url);
                return null;
            }

            text = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
            log.info("Scraped {} chars from {}", text.length(), url);
            return text;

        } catch (Exception e) {
            log.info("Scraping failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    public String extractImageFromPage(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            // Check og:image
            String img = doc.select("meta[property=og:image]").attr("content");
            if (isValidImageUrl(img)) return img;

            // Check twitter:image
            img = doc.select("meta[name=twitter:image]").attr("content");
            if (isValidImageUrl(img)) return img;

            // Check first article img
            var articleImg = doc.select("article img").first();
            if (articleImg != null) {
                img = articleImg.absUrl("src");
                if (isValidImageUrl(img)) return img;
            }

            return null;
        } catch (Exception e) {
            log.info("Image extraction failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private boolean isValidImageUrl(String url) {
        if (url == null || url.isBlank()) return false;
        if (!url.startsWith("http")) return false;
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg") ||
               lower.contains(".png") || lower.contains(".webp") ||
               lower.contains(".gif");
    }
}
