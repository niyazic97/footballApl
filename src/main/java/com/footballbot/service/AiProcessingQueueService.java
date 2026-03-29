package com.footballbot.service;

import com.footballbot.model.NewsItem;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiProcessingQueueService {

    private final AiProcessorService aiProcessorService;
    private final AiRankingService aiRankingService;
    private final NewsPublishQueueService newsPublishQueueService;

    // Groq free tier: ~100 tokens/sec, each request ~1800 tokens → 18s minimum gap
    private static final int GROQ_GAP_SECONDS = 20;

    private final LinkedBlockingQueue<NewsItem> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 3;

    public void enqueue(NewsItem item) {
        if (queue.stream().anyMatch(i -> i.getId().equals(item.getId()))) {
            return; // already queued
        }
        queue.offer(item);
        log.info("Added to AI queue: '{}' (queue size: {})", item.getTitleEn(), queue.size());
    }

    public int queueSize() {
        return queue.size();
    }

    @PostConstruct
    public void startProcessingThread() {
        Thread thread = new Thread(this::processLoop, "ai-processor");
        thread.setDaemon(true);
        thread.start();
        log.info("AI processing queue started (gap={}s between Groq calls)", GROQ_GAP_SECONDS);
    }

    private void processLoop() {
        while (true) {
            try {
                NewsItem item = queue.take(); // blocks until item available
                log.info("AI processing from queue: '{}'", item.getTitleEn());

                aiProcessorService.process(item);

                if (item.getTitleRu() != null && !item.getTitleRu().isBlank()) {
                    attempts.remove(item.getId());
                    item.setFinalScore(aiRankingService.getFinalScore(item));
                    newsPublishQueueService.enqueue(item);
                    log.info("AI done, enqueued for publish: '{}'", item.getTitleRu());
                } else {
                    int attempt = attempts.merge(item.getId(), 1, Integer::sum);
                    if (attempt < MAX_ATTEMPTS) {
                        queue.offer(item); // put back at end of queue
                        log.warn("AI failed for '{}' (attempt {}/{}) — requeued", item.getTitleEn(), attempt, MAX_ATTEMPTS);
                    } else {
                        attempts.remove(item.getId());
                        log.warn("AI failed for '{}' after {} attempts — dropped", item.getTitleEn(), MAX_ATTEMPTS);
                    }
                }

                if (!queue.isEmpty()) {
                    TimeUnit.SECONDS.sleep(GROQ_GAP_SECONDS);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
