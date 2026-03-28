package com.footballbot.util;

import com.footballbot.model.NewsItem;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
public class RelevanceFilterUtil {

    private static final Set<String> BLOCKED_WOMEN_TERMS = Set.of(
            "women", "woman", "wsl", "women's super league", "women's champions league",
            "uwcl", "women's world cup", "women's euro", "lionesses",
            "female", "girls", "ladies", "fawsl", "barclays wsl",
            "женской", "женская", "женщины", "женский"
    );

    public static final Set<String> EPL_CLUBS = Set.of(
            "arsenal", "chelsea", "liverpool", "manchester city", "man city",
            "manchester united", "man united", "man utd", "tottenham", "spurs",
            "newcastle", "aston villa", "west ham", "brighton", "everton",
            "brentford", "fulham", "wolves", "wolverhampton", "crystal palace",
            "nottingham forest", "bournemouth", "leicester", "ipswich",
            "southampton", "luton", "sheffield united", "burnley",
            "mufc", "mcfc", "cfc", "afc", "lfc", "thfc", "avfc", "whu", "nufc"
    );

    private static final Set<String> BLOCKED_COMPETITIONS = Set.of(
            "la liga", "serie a", "bundesliga", "ligue 1", "eredivisie",
            "mls", "saudi", "pro league", "turkish", "scottish",
            "soccer tournament", "the soccer tournament", "tsc",
            "copa del rey", "dfb pokal", "coupe de france",
            "atletico", "real madrid", "barcelona", "psg", "juventus",
            "bayern", "dortmund", "milan", "inter", "roma", "napoli",
            "benfica", "porto", "ajax", "sevilla", "valencia"
    );

    private static final List<String> NON_FOOTBALL_TERMS = List.of(
            "american football", "nfl", "nba", "rugby", "cricket",
            "tennis", "golf", "tsc", "sneaky fc"
    );

    private static final List<String> FOOTBALL_SIGNALS = List.of(
            "premier league", "epl", "champions league", "transfer",
            "goal", "match", "manager", "squad"
    );

    public static boolean isRelevant(NewsItem item) {
        String text = (item.getTitleEn() + " " + (item.getSummaryEn() != null ? item.getSummaryEn() : "")).toLowerCase();

        // RULE 0 — Women's football: ALWAYS block, no exceptions
        for (String term : BLOCKED_WOMEN_TERMS) {
            if (text.contains(term)) {
                log.info("FILTERED women's football: {}", item.getTitleEn());
                return false;
            }
        }

        // RULE 3 — Block non-football content
        for (String term : NON_FOOTBALL_TERMS) {
            if (text.contains(term)) {
                log.info("FILTERED non-football: {}", item.getTitleEn());
                return false;
            }
        }

        boolean hasEplClub = EPL_CLUBS.stream().anyMatch(text::contains);
        boolean hasBlockedTerm = BLOCKED_COMPETITIONS.stream().anyMatch(text::contains);

        // RULE 1 — Explicit block: contains blocked competition/club AND no EPL club
        if (hasBlockedTerm && !hasEplClub) {
            log.info("FILTERED irrelevant (non-EPL): {}", item.getTitleEn());
            return false;
        }

        // RULE 4 — Must have some football signal
        boolean hasFootballSignal = FOOTBALL_SIGNALS.stream().anyMatch(text::contains);
        if (!hasFootballSignal && !hasEplClub) {
            log.info("FILTERED no football signal: {}", item.getTitleEn());
            return false;
        }

        return true;
    }
}
