package com.footballbot.service;

import com.footballbot.model.NewsItem;
import com.footballbot.repository.PublishedNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeduplicationService {

    private final PublishedNewsRepository publishedNewsRepository;
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "as", "in", "at", "of", "and", "to", "for", "is", "are", "was", "were",
            "it", "on", "by", "an", "be", "this", "that", "with", "from", "but", "or", "not"
    );

    // Key entities — clubs and players. If 2+ match between two titles → same topic
    private static final Set<String> ENTITIES = Set.of(
            "arsenal", "chelsea", "liverpool", "city", "united", "tottenham", "newcastle",
            "aston villa", "west ham", "everton", "brighton", "fulham", "wolves", "forest",
            "brentford", "bournemouth", "palace", "southampton", "ipswich", "leicester",
            "real madrid", "barcelona", "bayern", "psg", "juventus",
            "haaland", "salah", "saka", "bellingham", "kane", "rashford", "palmer",
            "de bruyne", "foden", "fernandes", "van dijk", "trent", "isak", "son",
            "odegaard", "rice", "rodri", "mbappe", "vinicius", "arteta", "guardiola",
            "slot", "amorim", "maresca", "howe", "hillsborough", "kvaratskhelia"
    );

    public List<NewsItem> filterDuplicates(List<NewsItem> items) {
        var allRecentTitles = publishedNewsRepository
                .findByPostedAtAfter(LocalDateTime.now().minusHours(6))
                .stream()
                .map(p -> normalize(p.getTitle()))
                .collect(Collectors.toList());

        // For entity-based deduplication use a shorter window (2h) to avoid over-blocking
        var entityWindowTitles = publishedNewsRepository
                .findByPostedAtAfter(LocalDateTime.now().minusHours(2))
                .stream()
                .map(p -> normalize(p.getTitle()))
                .collect(Collectors.toList());

        var result = new ArrayList<NewsItem>();
        var batchTitles = new ArrayList<String>();

        for (var item : items) {
            var allTitles = new ArrayList<>(allRecentTitles);
            allTitles.addAll(batchTitles);
            var entityTitles = new ArrayList<>(entityWindowTitles);
            entityTitles.addAll(batchTitles);

            if (!isDuplicate(item, allTitles, entityTitles)) {
                result.add(item);
                batchTitles.add(normalize(item.getTitleEn()));
            } else {
                log.info("Deduplicated: {}", item.getTitleEn());
            }
        }

        int filtered = items.size() - result.size();
        if (filtered > 0) log.info("Filtered {} duplicates from batch", filtered);
        return result;
    }

    private boolean isDuplicate(NewsItem candidate, List<String> recentTitles, List<String> entityTitles) {
        var normCandidate = normalize(candidate.getTitleEn());
        var wordsCandidate = getWords(normCandidate);

        // Step 1 & 2: keyword overlap + Levenshtein (6h window)
        for (var recentTitle : recentTitles) {
            var wordsRecent = getWords(recentTitle);

            var shared = new HashSet<>(wordsCandidate);
            shared.retainAll(wordsRecent);
            var total = new HashSet<>(wordsCandidate);
            total.addAll(wordsRecent);
            if (!total.isEmpty() && (double) shared.size() / total.size() >= 0.5) {
                return true;
            }

            int maxLen = Math.max(normCandidate.length(), recentTitle.length());
            if (maxLen > 0) {
                int dist = LevenshteinDistance.getDefaultInstance().apply(normCandidate, recentTitle);
                if (1.0 - (double) dist / maxLen >= 0.75) {
                    return true;
                }
            }
        }

        // Step 3: entity overlap — 2h window, threshold=2
        var entitiesCandidate = extractEntities(normCandidate);
        for (var recentTitle : entityTitles) {
            var entitiesRecent = extractEntities(recentTitle);
            if (entitiesCandidate.size() >= 2 && entitiesRecent.size() >= 2) {
                var sharedEntities = new HashSet<>(entitiesCandidate);
                sharedEntities.retainAll(entitiesRecent);
                if (sharedEntities.size() >= 2) {
                    return true;
                }
            }
        }

        return false;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll("[^a-zа-я0-9 ]", " ").trim();
    }

    private Set<String> getWords(String normalized) {
        return Arrays.stream(normalized.split("\\s+"))
                .filter(w -> !w.isEmpty() && !STOPWORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private Set<String> extractEntities(String normalized) {
        return ENTITIES.stream()
                .filter(normalized::contains)
                .collect(Collectors.toSet());
    }
}
