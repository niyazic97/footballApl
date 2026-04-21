package com.footballbot.service;

import com.footballbot.model.NewsItem;
import com.footballbot.model.PublishedNews;
import com.footballbot.repository.PublishedNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
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

    public List<NewsItem> filterDuplicates(List<NewsItem> items) {
        var now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));

        var recentPublished = publishedNewsRepository.findByPostedAtAfter(now.minusHours(6));

        var allRecentNormalized = recentPublished.stream()
                .map(p -> normalize(p.getTitle()))
                .toList();
        var allRecentOriginals = recentPublished.stream()
                .map(PublishedNews::getTitle)
                .collect(Collectors.toCollection(ArrayList::new));

        var result = new ArrayList<NewsItem>();
        var batchNormalized = new ArrayList<String>();
        var batchOriginals = new ArrayList<String>();

        for (var item : items) {
            var allNormalized = new ArrayList<>(allRecentNormalized);
            allNormalized.addAll(batchNormalized);

            var allOriginals = new ArrayList<>(allRecentOriginals);
            allOriginals.addAll(batchOriginals);

            if (!isDuplicate(item, allNormalized, allOriginals)) {
                result.add(item);
                batchNormalized.add(normalize(item.getTitleEn()));
                batchOriginals.add(item.getTitleEn());
            } else {
                log.info("Deduplicated: {}", item.getTitleEn());
            }
        }

        int filtered = items.size() - result.size();
        if (filtered > 0) log.info("Filtered {} duplicates from batch", filtered);
        return result;
    }

    private boolean isDuplicate(NewsItem candidate, List<String> recentNormalized, List<String> recentOriginals) {
        var normCandidate = normalize(candidate.getTitleEn());
        var wordsCandidate = getWords(normCandidate);

        // Step 1 & 2: keyword overlap (lowered to 0.35) + Levenshtein (6h window)
        for (var recentTitle : recentNormalized) {
            var wordsRecent = getWords(recentTitle);

            var shared = new HashSet<>(wordsCandidate);
            shared.retainAll(wordsRecent);
            var total = new HashSet<>(wordsCandidate);
            total.addAll(wordsRecent);
            if (!total.isEmpty() && (double) shared.size() / total.size() >= 0.35) {
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

        // Step 3: proper noun overlap (6h window + batch).
        // 2+ any proper nouns shared → duplicate.
        // 1 specific proper noun (6+ chars, e.g. player surname) → also duplicate.
        var nounsCandidate = extractProperNouns(candidate.getTitleEn());
        for (var recentOriginal : recentOriginals) {
            var nounsRecent = extractProperNouns(recentOriginal);
            var sharedNouns = new HashSet<>(nounsCandidate);
            sharedNouns.retainAll(nounsRecent);
            if (sharedNouns.size() >= 2) {
                log.debug("Dedup by proper nouns {}: {}", sharedNouns, candidate.getTitleEn());
                return true;
            }
            // Single specific noun (player/manager surname, 6+ chars) is enough
            boolean singleSpecific = sharedNouns.stream().anyMatch(n -> n.length() >= 6);
            if (singleSpecific) {
                log.debug("Dedup by specific noun {}: {}", sharedNouns, candidate.getTitleEn());
                return true;
            }
        }

        return false;
    }

    /**
     * Extracts proper nouns from an original (non-normalized) English title.
     * Capitalized words of 3+ chars are treated as proper nouns (names, clubs, cities).
     * No dictionary needed — works for any player or club name automatically.
     */
    private Set<String> extractProperNouns(String originalTitle) {
        if (originalTitle == null || originalTitle.isBlank()) return Set.of();
        var nouns = new HashSet<String>();
        String[] words = originalTitle.split("[\\s\\-/]+");
        for (int i = 0; i < words.length; i++) {
            String clean = words[i].replaceAll("[^a-zA-Z]", "");
            if (clean.length() < 3) continue;
            if (STOPWORDS.contains(clean.toLowerCase())) continue;
            // First word is capitalized by grammar rules — only include it if 5+ chars
            boolean isProperNoun = Character.isUpperCase(clean.charAt(0)) && (i > 0 || clean.length() >= 5);
            if (isProperNoun) {
                nouns.add(clean.toLowerCase());
            }
        }
        return nouns;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll("[^a-zа-я0-9 ]", " ").trim();
    }

    private Set<String> getWords(String normalized) {
        return Arrays.stream(normalized.split("\\s+"))
                .filter(w -> w.length() >= 4 && !STOPWORDS.contains(w))
                .collect(Collectors.toSet());
    }
}
