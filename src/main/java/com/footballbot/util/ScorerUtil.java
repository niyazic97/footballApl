package com.footballbot.util;

import com.footballbot.model.NewsItem;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class ScorerUtil {

    public static int score(NewsItem item) {
        String title = item.getTitleEn() != null ? item.getTitleEn().toLowerCase() : "";
        int score = 0;

        // Breaking/high-impact (+6)
        if (containsAny(title, List.of("sacked", "fired", "resign", "appointed", "hat-trick", "brace",
                "banned", "suspended", "red card", "final", "title", "champion", "relegated", "promoted"))) {
            score += 6;
        }

        // Transfer news (+5)
        if (containsAny(title, List.of("transfer", "sign", "deal", "contract", "bid", "fee",
                "loan", "world record", "most expensive"))) {
            score += 5;
        }

        // Match results (+4)
        if (containsAny(title, List.of("goal", "score", "result", "win", "loss", "draw",
                "beat", "thrash", "comeback", "upset", "shock"))) {
            score += 4;
        }

        // Fitness news (+3)
        if (containsAny(title, List.of("injury", "injured", "out", "return", "fitness", "doubt", "miss"))) {
            score += 3;
        }

        // Other football (+2)
        if (containsAny(title, List.of("record", "history", "first ever", "preview", "press conference", "interview"))) {
            score += 2;
        }

        // Awards (+5)
        if (containsAny(title, List.of("player of the month", "manager of the month", "goalkeeper of the month",
                "goal of the month", "award", "nominee", "nominees", "shortlist", "golden boot", "golden glove"))) {
            score += 5;
        }

        // Low-interest (-4)
        if (containsAny(title, List.of("kit", "shirt", "merchandise", "ticket prices", "ticket sales", "season ticket", "stadium tour",
                "academy", "u21", "u18", "reserve", "financial", "revenue", "profit", "sponsorship"))) {
            score -= 4;
        }

        // Club prestige — top clubs (+4)
        if (containsAny(title, List.of("arsenal", "chelsea", "liverpool", "man city", "manchester city",
                "man united", "manchester united", "tottenham"))) {
            score += 4;
        }
        // Club prestige — European elite (+4)
        if (containsAny(title, List.of("real madrid", "barcelona", "bayern", "psg", "juventus"))) {
            score += 4;
        }
        // Club prestige — mid-tier (+2)
        if (containsAny(title, List.of("newcastle", "aston villa", "west ham", "brighton", "everton"))) {
            score += 2;
        }

        // Player prestige (+3)
        if (containsAny(title, List.of("haaland", "salah", "de bruyne", "saka", "bellingham",
                "vinicius", "mbappe", "kane", "rashford", "palmer"))) {
            score += 3;
        }
        // Player prestige (+2)
        if (containsAny(title, List.of("trent", "alisson", "ederson", "martial", "sterling"))) {
            score += 2;
        }

        // Source bonus (+1)
        String source = item.getSource() != null ? item.getSource() : "";
        if (source.contains("premierleague.com") || source.contains("uefa.com")) {
            score += 1;
        }

        // Freshness bonus
        if (item.getPublishedAt() != null) {
            long minutesAgo = ChronoUnit.MINUTES.between(item.getPublishedAt(), LocalDateTime.now());
            if (minutesAgo <= 30) {
                score += 2;
            } else if (minutesAgo <= 60) {
                score += 1;
            }
        }

        return Math.max(0, Math.min(20, score));
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
