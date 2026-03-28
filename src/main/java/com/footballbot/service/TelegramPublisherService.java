package com.footballbot.service;

import com.footballbot.model.NewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Service
@Slf4j
public class TelegramPublisherService extends TelegramLongPollingBot {

    private final FormatterService formatterService;
    private final ImageFinderService imageFinderService;
    private final BotCommandService botCommandService;
    private final String channelId;
    private final String botUsername;

    public TelegramPublisherService(
            FormatterService formatterService,
            ImageFinderService imageFinderService,
            @org.springframework.context.annotation.Lazy BotCommandService botCommandService,
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.channel.id}") String channelId,
            @Value("${telegram.bot.username}") String botUsername) {
        super(botToken);
        this.formatterService = formatterService;
        this.imageFinderService = imageFinderService;
        this.botCommandService = botCommandService;
        this.channelId = channelId;
        this.botUsername = botUsername;
    }

    public boolean publishNews(NewsItem item) {
        try {
            byte[] imageBytes = imageFinderService.findImageBytes(item);
            var text = formatterService.format(item, imageBytes != null);

            if (imageBytes != null) {
                var sendPhoto = new SendPhoto();
                sendPhoto.setChatId(channelId);
                sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(imageBytes), "photo.jpg"));
                sendPhoto.setCaption(text);
                sendPhoto.setParseMode("HTML");
                execute(sendPhoto);
            } else {
                var message = new SendMessage();
                message.setChatId(channelId);
                message.setText(text);
                message.setParseMode("HTML");
                execute(message);
            }

            log.info("Published: {}", item.getTitleRu() != null ? item.getTitleRu() : item.getTitleEn());
            return true;

        } catch (TelegramApiException e) {
            log.error("Failed to publish to Telegram: {}", e.getMessage());
            return false;
        }
    }

    public void sendTextMessage(String text) {
        try {
            var message = new SendMessage();
            message.setChatId(channelId);
            message.setText(text);
            execute(message);
            log.info("Text message sent to channel");
        } catch (TelegramApiException e) {
            log.error("Failed to send text message to Telegram: {}", e.getMessage());
        }
    }

    public void sendTextMessageToUser(String userId, String text) {
        try {
            var message = new SendMessage();
            message.setChatId(userId);
            message.setText(text);
            execute(message);
        } catch (TelegramApiException e) {
            log.warn("Failed to send message to user {}: {}", userId, e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        var msg = update.getMessage();
        String text = msg.getText().split("@")[0]; // strip @botname suffix
        String userId = msg.getFrom().getId().toString();

        if (!text.startsWith("/")) return;
        if (!botCommandService.isAdmin(userId)) {
            log.warn("Unauthorized command from user {}", userId);
            return;
        }

        String response = botCommandService.handleCommand(text);
        sendTextMessageToUser(userId, response);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }
}
