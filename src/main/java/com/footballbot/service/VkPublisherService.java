package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.NewsItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkPublisherService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${vk.access.token:}")
    private String accessToken;

    @Value("${vk.group.id:0}")
    private long groupId;

    private static final String VK_API = "https://api.vk.com/method/";
    private static final String VK_VERSION = "5.131";

    public boolean isEnabled() {
        return accessToken != null && !accessToken.isBlank() && groupId > 0;
    }

    public boolean publishNews(NewsItem item) {
        if (!isEnabled()) return false;
        try {
            postToWall(buildText(item), null);
            log.info("Published to VK: {}", item.getTitleRu());
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish to VK '{}': {}", item.getTitleRu(), e.getMessage());
            return false;
        }
    }

    private String buildText(NewsItem item) {
        var sb = new StringBuilder();
        var title = item.getTitleRu() != null ? item.getTitleRu() : item.getTitleEn();
        var summary = item.getSummaryRu() != null ? item.getSummaryRu() : item.getSummaryEn();

        sb.append(title).append("\n\n");

        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append("\n\n");
        }

        if (item.getQuote() != null && !item.getQuote().isBlank()) {
            sb.append("🗣 ").append(item.getQuote()).append("\n\n");
        }

        if (item.getAiCommentary() != null && !item.getAiCommentary().isBlank()) {
            sb.append(item.getAiCommentary()).append("\n\n");
        }

        sb.append("🔗 ").append(item.getUrl());

        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private void postToWall(String text, String attachment) throws Exception {
        String url = VK_API + "wall.post?owner_id=-" + groupId
                + "&from_group=1"
                + "&message=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + (attachment != null ? "&attachments=" + URLEncoder.encode(attachment, StandardCharsets.UTF_8) : "")
                + "&access_token=" + accessToken + "&v=" + VK_VERSION;

        var resp = objectMapper.readValue(get(url), Map.class);
        if (resp.containsKey("error")) {
            var err = (Map<?, ?>) resp.get("error");
            throw new RuntimeException("VK error " + err.get("error_code") + ": " + err.get("error_msg"));
        }
        log.info("VK post_id: {}", ((Map<?, ?>) resp.get("response")).get("post_id"));
    }

    private String get(String url) throws Exception {
        try (var resp = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            return resp.body().string();
        }
    }

}
