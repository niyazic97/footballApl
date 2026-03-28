package com.footballbot.service;

import com.footballbot.model.NewsItem;
import org.springframework.stereotype.Service;

@Service
public class AiRankingService {

    public int getFinalScore(NewsItem item) {
        // Level 1 counts 40%, Level 2 (AI) counts 60%
        // Level 1 is 0-20, Level 2 is 1-10 (multiply by 2 to normalize to 0-20)
        double l1 = item.getImportanceScore() * 0.4;
        double l2 = item.getAiInterestScore() * 2.0 * 0.6;
        return (int) (l1 + l2);
    }
}
