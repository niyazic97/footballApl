package com.footballbot.service;

import com.footballbot.model.NewsItem;
import com.footballbot.model.PublishedNews;
import com.footballbot.repository.PublishedNewsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsPublishQueueService {

    private final TelegramPublisherService telegramPublisherService;
    private final VkPublisherService vkPublisherService;
    private final PublishedNewsRepository publishedNewsRepository;
    private final HealthMonitorService healthMonitorService;

    @Value("${publish.delay.seconds:180}")
    private int delayBetweenPostsSeconds = 180;

    private final LinkedBlockingQueue<NewsItem> queue = new LinkedBlockingQueue<>();
    private volatile boolean paused = false;
    private volatile long lastPublishedAt = 0;

    public void pause() { paused = true; log.info("Publishing paused"); }
    public void resume() { paused = false; log.info("Publishing resumed"); }
    public boolean isPaused() { return paused; }

    @PostConstruct
    public void startPublisherThread() {
        // Restore last publish time from DB so restarts don't break the delay
        publishedNewsRepository.findTopByOrderByPostedAtDesc().ifPresent(last -> {
            lastPublishedAt = last.getPostedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            log.info("Restored lastPublishedAt from DB: {}", last.getPostedAt());
        });

        Thread publisher = new Thread(this::publishLoop, "news-publisher");
        publisher.setDaemon(true);
        publisher.start();
        log.info("News publisher thread started (delay={}s between posts)", delayBetweenPostsSeconds);
    }

    public void enqueue(NewsItem item) {
        queue.offer(item);
        log.info("Enqueued for publishing: {} (queue size: {})", item.getTitleRu(), queue.size());
    }

    public int queueSize() {
        return queue.size();
    }

    private void publishLoop() {
        while (true) {
            try {
                if (paused) {
                    TimeUnit.SECONDS.sleep(10);
                    continue;
                }
                NewsItem item = queue.take(); // blocks until item available
                if (paused) {
                    queue.offer(item); // put back
                    continue;
                }

                // Enforce minimum gap between posts
                long secondsSinceLast = (System.currentTimeMillis() - lastPublishedAt) / 1000;
                if (lastPublishedAt > 0 && secondsSinceLast < delayBetweenPostsSeconds) {
                    long waitSeconds = delayBetweenPostsSeconds - secondsSinceLast;
                    log.info("Waiting {}s before next publish (rate limit)", waitSeconds);
                    TimeUnit.SECONDS.sleep(waitSeconds);
                }

                publish(item);
                lastPublishedAt = System.currentTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void publish(NewsItem item) {
        boolean ok = telegramPublisherService.publishNews(item);
        if (ok) {
            publishedNewsRepository.save(PublishedNews.builder()
                    .id(item.getId())
                    .title(item.getTitleEn())
                    .publishedAt(item.getPublishedAt())
                    .postedAt(LocalDateTime.now())
                    .build());
            healthMonitorService.incrementPostCount();
            vkPublisherService.publishNews(item);
        } else {
            healthMonitorService.incrementErrorCount();
            log.warn("Failed to publish: {}", item.getTitleRu());
        }
    }
}
