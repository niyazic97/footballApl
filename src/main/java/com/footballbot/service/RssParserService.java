package com.footballbot.service;

import com.footballbot.config.AppConfig;
import com.footballbot.model.NewsItem;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RssParserService {

    private final OkHttpClient httpClient;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Value("${news.max.age.hours}")
    private int maxAgeHours;

    public List<NewsItem> fetchAllNews() {
        var result = new ArrayList<NewsItem>();

        for (var feedUrl : AppConfig.RSS_FEEDS) {
            try {
                var items = parseFeed(feedUrl);
                log.info("Fetched {} items from {}", items.size(), feedUrl);
                result.addAll(items);
            } catch (Exception e) {
                log.warn("Failed to parse feed {}: {}", feedUrl, e.getMessage());
            }
        }

        return result;
    }

    private List<NewsItem> parseFeed(String feedUrl) {
        try {
            var request = new Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", USER_AGENT)
                    .build();
            byte[] body;
            try (var response = httpClient.newCall(request).execute()) {
                body = response.body().bytes();
            }
            var input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(body)));
            var cutoff = LocalDateTime.now(ZoneId.of("Europe/Moscow")).minusHours(maxAgeHours);
            var league = detectLeague(feedUrl);
            var source = extractDomain(feedUrl);

            return feed.getEntries().stream()
                    .filter(e -> e.getPublishedDate() != null)
                    .filter(e -> toLocalDateTime(e.getPublishedDate()).isAfter(cutoff))
                    .map(e -> mapToNewsItem(e, league, source))
                    .toList();
        } catch (Exception e) {
            log.warn("Error parsing {}: {}", feedUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    private NewsItem mapToNewsItem(SyndEntry entry, String league, String source) {
        var description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
        return NewsItem.builder()
                .id(md5(entry.getLink()))
                .titleEn(entry.getTitle())
                .summaryEn(stripHtml(description))
                .url(entry.getLink())
                .source(source)
                .publishedAt(toLocalDateTime(entry.getPublishedDate()))
                .league(league)
                .rssImageUrl(extractEnclosureImage(entry))
                .build();
    }

    private String extractEnclosureImage(SyndEntry entry) {
        if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
            return entry.getEnclosures().stream()
                    .filter(e -> e.getType() == null || e.getType().startsWith("image/"))
                    .map(SyndEnclosure::getUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private String detectLeague(String url) {
        return url.contains("12196") || url.contains("european") || url.contains("ucl")
                || url.contains("championsleague") || url.contains("champions")
                ? "UCL" : "EPL";
    }

    private String extractDomain(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.of("Europe/Moscow")).toLocalDateTime();
    }

    private String stripHtml(String html) {
        return html != null ? html.replaceAll("<[^>]+>", "").trim() : "";
    }

    private String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            var bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
