package com.footballbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Ensures only one instance of the bot runs at a time.
 * On startup: reads old PID from file, kills old process, writes current PID.
 * On shutdown: deletes PID file.
 */
@Component
@Slf4j
public class SingleInstanceGuard {

    private static final Path PID_FILE = Path.of("/tmp/apl-bot.pid");

    @PostConstruct
    public void ensureSingleInstance() {
        killOldInstance();
        writeCurrentPid();
    }

    private void killOldInstance() {
        if (!Files.exists(PID_FILE)) return;
        try {
            long oldPid = Long.parseLong(Files.readString(PID_FILE).trim());
            long currentPid = ProcessHandle.current().pid();
            if (oldPid == currentPid) return;

            ProcessHandle.of(oldPid).ifPresent(process -> {
                if (!process.isAlive()) return;
                log.info("SingleInstanceGuard: killing old instance PID {}", oldPid);
                process.destroy();
                try {
                    process.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                log.info("SingleInstanceGuard: old instance killed");
            });
        } catch (Exception e) {
            log.warn("SingleInstanceGuard: could not kill old instance: {}", e.getMessage());
        }
    }

    private void writeCurrentPid() {
        try {
            Files.writeString(PID_FILE, String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            log.warn("SingleInstanceGuard: could not write PID file: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            Files.deleteIfExists(PID_FILE);
        } catch (IOException ignored) {}
    }
}
