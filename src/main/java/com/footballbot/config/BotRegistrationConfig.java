package com.footballbot.config;

import com.footballbot.service.TelegramPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import jakarta.annotation.PostConstruct;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BotRegistrationConfig {

    private final TelegramPublisherService telegramPublisherService;

    @PostConstruct
    public void registerBot() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(telegramPublisherService);
            log.info("Telegram bot registered for long polling");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: {}", e.getMessage());
        }
    }
}
