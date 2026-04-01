package com.footballbot.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class NewsItem {
    private String id;
    private String titleEn;
    private String summaryEn;
    private String titleRu;
    private String summaryRu;
    private String aiCommentary;
    private String quote;           // Direct quote from article, if any
    private String url;
    private String source;
    private LocalDateTime publishedAt;
    private String rssImageUrl;
    private String fullTextEn;
    private int importanceScore;
    private int aiInterestScore;    // Level 2 AI score (1-10)
    private Boolean rateLimited;    // true if last Groq call got 429
    private int finalScore;         // Combined score for sorting
    private String interestReason;  // AI explanation (for logs)
    private String league;
    private List<String> tags;
}
