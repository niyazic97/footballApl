package com.footballbot.service;

import com.footballbot.model.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class FormatterService {

    private static final int MAX_LENGTH_CAPTION = 1024;
    private static final int MAX_LENGTH_TEXT = 4096;

    public String format(NewsItem item) {
        return format(item, false);
    }

    public String format(NewsItem item, boolean hasImage) {
        int maxLength = hasImage ? MAX_LENGTH_CAPTION : MAX_LENGTH_TEXT;
        var title = item.getTitleRu() != null ? item.getTitleRu() : item.getTitleEn();
        var summary = item.getSummaryRu() != null ? item.getSummaryRu() : item.getSummaryEn();
        var category = detectCategory(item.getTitleEn(), item.getLeague());

        var sb = new StringBuilder();
        sb.append("<b>").append(category).append(" ").append(title).append("</b>").append("\n\n");
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append("\n\n");
        }

        if (item.getQuote() != null && !item.getQuote().isBlank()) {
            sb.append("<blockquote>🗣 ").append(item.getQuote()).append("</blockquote>\n\n");
        }

        sb.append("🔗 <a href=\"").append(item.getUrl()).append("\">").append(formatSourceName(item.getSource())).append("</a>");

        var result = sb.toString();
        if (result.length() > maxLength) {
            result = truncate(category, title, summary, item.getUrl(), item.getSource(), maxLength);
        }

        return result;
    }

    private String truncate(String category, String title, String summary, String url, String source, int maxLength) {
        var sourceName = formatSourceName(source);
        var fixedPart = "<b>" + category + " " + title + "</b>" + "\n\n"
                + "🔗 <a href=\"" + url + "\">" + sourceName + "</a>";

        var remaining = maxLength - fixedPart.length() - 3;
        var truncatedSummary = summary != null && summary.length() > remaining
                ? summary.substring(0, Math.max(0, remaining)) + "..."
                : (summary != null ? summary : "");

        return "<b>" + category + " " + title + "</b>" + "\n\n"
                + truncatedSummary + "\n\n"
                + "🔗 <a href=\"" + url + "\">" + sourceName + "</a>";
    }

    private String formatSourceName(String source) {
        if (source == null) return "Источник";
        return switch (source) {
            case "www.skysports.com" -> "Sky Sports";
            case "feeds.bbci.co.uk", "www.bbc.co.uk", "www.bbc.com" -> "BBC Sport";
            case "www.theguardian.com" -> "The Guardian";
            case "www.espn.com" -> "ESPN";
            case "www.fourfourtwo.com" -> "FourFourTwo";
            case "www.independent.co.uk" -> "The Independent";
            case "www.mirror.co.uk" -> "Mirror";
            case "www.manchestereveningnews.co.uk" -> "Manchester Evening News";
            case "www.liverpoolecho.co.uk" -> "Liverpool Echo";
            case "www.birminghammail.co.uk" -> "Birmingham Mail";
            case "www.dailymail.co.uk" -> "Daily Mail";
            case "www.thesun.co.uk" -> "The Sun";
            case "www.telegraph.co.uk" -> "The Telegraph";
            default -> "Источник";
        };
    }

    private String detectCategory(String titleEn, String league) {
        if ("UCL".equals(league)) return "🏆";

        if (titleEn == null) return "⚽";
        String t = titleEn.toLowerCase();

        if (containsAny(t, List.of("sacked", "fired", "resign", "appointed", "banned", "suspended", "red card")))
            return "🚨";
        if (containsAny(t, List.of("transfer", "sign", "deal", "contract", "bid", "fee", "loan", "move", "join")))
            return "💰";
        if (containsAny(t, List.of("injury", "injured", "out", "fitness", "doubt", "miss", "return")))
            return "🤕";
        if (containsAny(t, List.of("goal", "score", "result", "win", "loss", "draw", "beat", "thrash", "vs", "preview")))
            return "⚽";
        if (containsAny(t, List.of("record", "history", "greatest", "best", "ranked", "stats", "top")))
            return "📊";
        if (containsAny(t, List.of("press conference", "interview", "says", "reacts", "responds", "hints", "admits")))
            return "🎙";

        return "⚽";
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
