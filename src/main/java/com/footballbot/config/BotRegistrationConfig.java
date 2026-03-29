package com.footballbot.config;

import com.footballbot.service.TelegramPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import jakarta.annotation.PostConstruct;
import java.util.List;

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
            registerCommands();
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: {}", e.getMessage());
        }
    }

    private void registerCommands() {
        try {
            telegramPublisherService.execute(new SetMyCommands(List.of(
                    new BotCommand("status", "Статус бота и статистика"),
                    new BotCommand("queue", "Размер очереди публикаций"),
                    new BotCommand("pause", "Приостановить публикацию"),
                    new BotCommand("resume", "Возобновить публикацию"),
                    new BotCommand("logs", "Последние 5 строк логов"),
                    new BotCommand("refreshrosters", "Обновить ростеры игроков")
            ), null, null));
            log.info("Bot commands registered");
        } catch (TelegramApiException e) {
            log.error("Failed to register bot commands: {}", e.getMessage());
        }
    }
}
