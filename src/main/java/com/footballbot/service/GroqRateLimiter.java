package com.footballbot.service;

import com.footballbot.config.GroqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized rate limiter for ALL Groq API calls.
 * Single-threaded executor ensures only one Groq call at a time with enforced gaps.
 * All services (news, match results, pre-match, post-match stats) MUST go through here.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroqRateLimiter {

    private final GroqProperties groqProperties;

    // Single-threaded: guarantees sequential execution with gap
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "groq-rate-limiter");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private volatile long lastCallAt = 0;

    /**
     * Submits a Groq API call to the rate-limited queue.
     * Returns a Future — caller can block on it or fire-and-forget.
     *
     * @param taskName  Human-readable name for logging
     * @param task      The actual HTTP call (returns raw JSON response string)
     */
    public Future<String> submit(String taskName, Callable<String> task) {
        return executor.submit(() -> {
            enforceGap(taskName);
            try {
                log.debug("Groq call #{} starting: {}", totalRequests.incrementAndGet(), taskName);
                String result = task.call();
                lastCallAt = System.currentTimeMillis();
                return result;
            } catch (Exception e) {
                lastCallAt = System.currentTimeMillis();
                throw e;
            }
        });
    }

    private void enforceGap(String taskName) {
        long elapsed = System.currentTimeMillis() - lastCallAt;
        long gapMs = groqProperties.getGapSeconds() * 1000L;
        if (lastCallAt > 0 && elapsed < gapMs) {
            long waitMs = gapMs - elapsed;
            log.info("Groq rate limiter: waiting {}s before '{}' (gap enforcement)",
                    waitMs / 1000, taskName);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
