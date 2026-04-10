package com.footballbot.util;

import com.footballbot.model.NewsItem;
import com.footballbot.repository.PlayerNameRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScorerUtil {

    private final PlayerNameRepository playerNameRepository;

    // In-memory cache of player names → bonus, refreshed from DB
    private Map<String, Integer> playerScoreMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadPlayerNames() {
        refreshCache();
    }

    public int getCacheSize() { return playerScoreMap.size(); }

    public void refreshCache() {
        var map = new ConcurrentHashMap<String, Integer>();
        playerNameRepository.findAll().forEach(p -> map.put(p.getName(), p.getScoreBonus()));
        playerScoreMap = map;
        log.info("Player name cache loaded: {} entries", playerScoreMap.size());
    }

    public int score(NewsItem item) {
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
        if (containsAny(title, List.of("record", "history", "first ever"))) {
            score += 2;
        }

        // Low-value content (-3)
        if (containsAny(title, List.of("press conference", "preview", "interview", "face the press",
                "faces the press", "pre-match", "ahead of"))) {
            score -= 3;
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
        // Club prestige — mid-tier (+3)
        if (containsAny(title, List.of("newcastle", "aston villa", "west ham", "brighton", "everton"))) {
            score += 3;
        }
        // Club prestige — rest of EPL (+2)
        if (containsAny(title, List.of("fulham", "wolves", "wolverhampton", "brentford", "crystal palace",
                "nottingham forest", "bournemouth", "leicester", "ipswich", "southampton"))) {
            score += 2;
        }

        // Player prestige from DB
        if (!playerScoreMap.isEmpty()) {
            for (var entry : playerScoreMap.entrySet()) {
                if (title.contains(entry.getKey())) {
                    score += entry.getValue();
                    break; // one player match is enough
                }
            }
        } else {
            // Fallback if DB not loaded yet
            if (containsAny(title, List.of("haaland", "salah", "de bruyne", "saka", "bellingham",
                    "vinicius", "mbappe", "kane", "rashford", "palmer", "odegaard"))) {
                score += 3;
            }
        }

        // Source bonus (+1)
        String source = item.getSource() != null ? item.getSource() : "";
        if (source.contains("premierleague.com") || source.contains("uefa.com")) {
            score += 1;
        }

        // Freshness bonus
        if (item.getPublishedAt() != null) {
            long minutesAgo = ChronoUnit.MINUTES.between(item.getPublishedAt(), LocalDateTime.now(ZoneId.of("Europe/Moscow")));
            if (minutesAgo <= 30) {
                score += 2;
            } else if (minutesAgo <= 60) {
                score += 1;
            }
        }

        return Math.max(0, Math.min(20, score));
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
