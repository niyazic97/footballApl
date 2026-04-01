package com.footballbot.service;

import com.footballbot.model.NewsItem;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiProcessingQueueService {

    private final AiProcessorService aiProcessorService;
    private final AiRankingService aiRankingService;
    private final NewsPublishQueueService newsPublishQueueService;

    private final LinkedBlockingQueue<NewsItem> queue = new LinkedBlockingQueue<>();

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
    @SuppressWarnings("unused") // called by Spring via @PostConstruct
    public void startProcessingThread() {
        Thread thread = new Thread(this::processLoop, "ai-processor");
        thread.setDaemon(true);
        thread.start();
        log.info("AI processing queue started (gap enforced by GroqRateLimiter)");
    }

    private void processLoop() {
        while (true) {
            try {
                NewsItem item = queue.take(); // blocks until item available
                log.info("AI processing from queue: '{}'", item.getTitleEn());

                aiProcessorService.process(item);

                if (item.getTitleRu() != null && !item.getTitleRu().isBlank()) {
                    item.setFinalScore(aiRankingService.getFinalScore(item));
                    newsPublishQueueService.enqueue(item);
                    log.info("AI done, enqueued for publish: '{}'", item.getTitleRu());
                } else if (Boolean.TRUE.equals(item.getRateLimited())) {
                    // Groq rate limited — re-queue and wait 60s for cooldown
                    log.warn("Groq rate limited for '{}' — cooling down 60s, re-queuing", item.getTitleEn());
                    item.setRateLimited(null);
                    TimeUnit.SECONDS.sleep(60);
                    queue.offer(item);
                } else {
                    log.warn("AI failed for '{}' — dropped", item.getTitleEn());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
