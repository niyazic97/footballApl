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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsPublishQueueService {

    private final TelegramPublisherService telegramPublisherService;
    private final PublishedNewsRepository publishedNewsRepository;
    private final HealthMonitorService healthMonitorService;

    @Value("${publish.delay.seconds:180}")
    private int delayBetweenPostsSeconds;

    private final LinkedBlockingQueue<NewsItem> queue = new LinkedBlockingQueue<>();
    private volatile boolean paused = false;

    public void pause() { paused = true; log.info("Publishing paused"); }
    public void resume() { paused = false; log.info("Publishing resumed"); }
    public boolean isPaused() { return paused; }

    @PostConstruct
    public void startPublisherThread() {
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

    private boolean isQuietHours() {
        LocalTime now = LocalTime.now(ZoneId.of("Europe/Moscow"));
        return now.isAfter(LocalTime.of(1, 0)) && now.isBefore(LocalTime.of(7, 0));
    }

    private void publishLoop() {
        while (true) {
            try {
                if (paused || isQuietHours()) {
                    if (isQuietHours()) log.debug("Quiet hours — publishing paused until 07:00 MSK");
                    TimeUnit.SECONDS.sleep(10);
                    continue;
                }
                NewsItem item = queue.take(); // blocks until item available
                if (paused || isQuietHours()) {
                    queue.offer(item); // put back
                    continue;
                }
                publish(item);
                if (!queue.isEmpty()) {
                    TimeUnit.SECONDS.sleep(delayBetweenPostsSeconds);
                }
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
        } else {
            healthMonitorService.incrementErrorCount();
            log.warn("Failed to publish: {}", item.getTitleRu());
        }
    }
}
