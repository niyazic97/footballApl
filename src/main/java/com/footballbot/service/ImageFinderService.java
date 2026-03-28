package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.NewsItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageFinderService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ArticleScraperService articleScraperService;

    @Value("${unsplash.access.key}")
    private String accessKey;

    @Value("${unsplash.api.url}")
    private String apiUrl;

    public byte[] findImageBytes(NewsItem item) {
        // Step 1: RSS image
        if (item.getRssImageUrl() != null) {
            byte[] bytes = downloadImageBytes(item.getRssImageUrl());
            if (bytes != null) {
                log.info("Using RSS image for '{}'", item.getTitleEn());
                return bytes;
            }
            log.warn("RSS image download failed, trying page scrape");
        }

        // Step 2: Scrape from article page
        String pageImageUrl = articleScraperService.extractImageFromPage(item.getUrl());
        if (pageImageUrl != null) {
            byte[] bytes = downloadImageBytes(pageImageUrl);
            if (bytes != null) {
                log.info("Using scraped page image for '{}'", item.getTitleEn());
                return bytes;
            }
        }

        // Step 3: Unsplash
        List<String> tags = item.getTags() != null ? item.getTags() : List.of();
        String query = buildQuery(tags, item.getLeague(), item.getTitleEn());
        String unsplashUrl = searchUnsplash(query);
        if (unsplashUrl != null) {
            byte[] bytes = downloadImageBytes(unsplashUrl);
            if (bytes != null) {
                log.info("Using Unsplash image for '{}'", item.getTitleEn());
                return bytes;
            }
        }

        // Fallback Unsplash
        String fallbackUrl = searchUnsplash("football match stadium");
        if (fallbackUrl != null) {
            return downloadImageBytes(fallbackUrl);
        }

        log.warn("No image found for '{}'", item.getTitleEn());
        return null;
    }

    private byte[] downloadImageBytes(String url) {
        try {
            var request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible)")
                    .header("Accept", "image/webp,image/jpeg,image/*")
                    .build();

            // Use a client with shorter timeout for image download
            var imageClient = httpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();

            try (var response = imageClient.newCall(request).execute()) {
                if (response.code() != 200) return null;
                var contentType = response.header("Content-Type", "");
                if (contentType != null && !contentType.startsWith("image/")) return null;
                var body = response.body();
                if (body == null) return null;
                var bytes = body.bytes();
                if (bytes.length > 10 * 1024 * 1024) return null; // 10MB limit
                return bytes;
            }
        } catch (Exception e) {
            log.warn("Image download failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String searchUnsplash(String query) {
        try {
            var url = apiUrl + "?query=" + query.replace(" ", "+") + "&orientation=landscape&per_page=5";
            var request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Client-ID " + accessKey)
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                var body = objectMapper.readValue(response.body().string(), Map.class);
                var results = (List<Map<String, Object>>) body.get("results");
                if (results == null || results.isEmpty()) return null;

                return results.stream()
                        .max(Comparator.comparingInt(r -> (int) r.getOrDefault("likes", 0)))
                        .map(r -> {
                            var urls = (Map<String, String>) r.get("urls");
                            return urls != null ? urls.get("regular") : null;
                        })
                        .orElse(null);
            }
        } catch (Exception e) {
            log.warn("Unsplash search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    private String buildQuery(List<String> tags, String league, String titleEn) {
        var sb = new StringBuilder();
        if (!tags.isEmpty()) {
            tags.stream().limit(2).forEach(t -> sb.append(t).append(" "));
        } else if (titleEn != null) {
            var words = titleEn.split("\\s+");
            var meaningful = java.util.Arrays.stream(words)
                    .filter(w -> w.length() > 4)
                    .filter(w -> !List.of("that", "this", "with", "from", "have", "will", "their", "about").contains(w.toLowerCase()))
                    .limit(2)
                    .toList();
            meaningful.forEach(w -> sb.append(w).append(" "));
        }
        sb.append(league.equals("UCL") ? "Champions League" : "Premier League");
        sb.append(" football");
        return sb.toString().trim();
    }
}
