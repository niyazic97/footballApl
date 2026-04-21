package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.model.NewsItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkPublisherService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ImageFinderService imageFinderService;

    @Value("${vk.access.token:}")
    private String accessToken;

    @Value("${vk.group.id:0}")
    private long groupId;

    private static final String VK_API = "https://api.vk.com/method/";
    private static final String VK_VERSION = "5.131";

    public boolean isDisabled() {
        return accessToken == null || accessToken.isBlank() || groupId <= 0;
    }

    public void publishText(String text) {
        if (isDisabled()) return;
        try {
            postToWall(text, null);
            log.info("Published text to VK ({} chars)", text.length());
        } catch (Exception e) {
            log.warn("Failed to publish text to VK: {}", e.getMessage());
        }
    }

    public boolean publishNews(NewsItem item) {
        if (isDisabled()) return false;
        try {
            postToWall(buildText(item), null);
            log.info("Published to VK{}: {}", attachment != null ? " (with image)" : "", item.getTitleRu());
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish to VK '{}': {}", item.getTitleRu(), e.getMessage());
            return false;
        }
    }

    private String uploadPhotoAttachment(byte[] imageBytes) throws Exception {
        // Step 1: get wall photo upload server
        String serverUrl = VK_API + "photos.getWallUploadServer"
                + "?group_id=" + groupId
                + "&access_token=" + accessToken
                + "&v=" + VK_VERSION;
        var serverResp = objectMapper.readValue(get(serverUrl), Map.class);
        if (serverResp.containsKey("error")) {
            log.warn("VK photos upload server error: {}", serverResp.get("error"));
            return null;
        }
        String uploadUrl = (String) ((Map<?, ?>) serverResp.get("response")).get("upload_url");

        // Step 2: upload photo as multipart
        var body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("photo", "image.jpg",
                        RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                .build();
        var uploadReq = new Request.Builder().url(uploadUrl).post(body).build();
        String uploadRespStr;
        try (var resp = httpClient.newCall(uploadReq).execute()) {
            var rb = resp.body();
            uploadRespStr = rb != null ? rb.string() : "{}";
        }
        var uploadResp = objectMapper.readValue(uploadRespStr, Map.class);
        String server = String.valueOf(uploadResp.get("server"));
        String photo = (String) uploadResp.get("photo");
        String hash = (String) uploadResp.get("hash");
        if (photo == null || photo.isBlank() || photo.equals("[]")) {
            log.warn("VK photo upload returned no photo token");
            return null;
        }

        // Step 3: save wall photo
        String saveUrl = VK_API + "photos.saveWallPhoto"
                + "?group_id=" + groupId
                + "&server=" + server
                + "&photo=" + URLEncoder.encode(photo, StandardCharsets.UTF_8)
                + "&hash=" + URLEncoder.encode(hash, StandardCharsets.UTF_8)
                + "&access_token=" + accessToken
                + "&v=" + VK_VERSION;
        var saveResp = objectMapper.readValue(get(saveUrl), Map.class);
        if (saveResp.containsKey("error")) {
            log.warn("VK photos.saveWallPhoto error: {}", saveResp.get("error"));
            return null;
        }
        var savedList = (List<?>) saveResp.get("response");
        if (savedList == null || savedList.isEmpty()) {
            log.warn("VK photos.saveWallPhoto returned empty response");
            return null;
        }
        var savedPhoto = (Map<?, ?>) savedList.get(0);
        int ownerId = ((Number) savedPhoto.get("owner_id")).intValue();
        int photoId = ((Number) savedPhoto.get("id")).intValue();
        return "photo" + ownerId + "_" + photoId;
    }

    private String buildText(NewsItem item) {
        var sb = new StringBuilder();
        var title = item.getTitleRu() != null ? item.getTitleRu() : item.getTitleEn();
        var summary = item.getSummaryRu() != null ? item.getSummaryRu() : item.getSummaryEn();

        // Title with category emoji
        String titleEmoji = detectEmoji(item);
        sb.append(titleEmoji).append(" ").append(title.toUpperCase()).append("\n\n");

        // Summary
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append("\n\n");
        }

        // Direct quote
        if (item.getQuote() != null && !item.getQuote().isBlank()) {
            sb.append("💬 ").append(item.getQuote()).append("\n\n");
        }

        // Separator + source
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        sb.append("🔗 ").append(item.getUrl());

        return sb.toString().trim();
    }

    private String detectEmoji(NewsItem item) {
        String title = item.getTitleEn() != null ? item.getTitleEn().toLowerCase() : "";
        if (containsAny(title, "transfer", "sign", "deal", "bid", "fee", "loan")) return "💰";
        if (containsAny(title, "sacked", "fired", "resign", "appointed")) return "🚨";
        if (containsAny(title, "injury", "injured", "out", "miss", "fitness")) return "🏥";
        if (containsAny(title, "goal", "hat-trick", "brace", "score", "result", "win", "beat")) return "⚽";
        if (containsAny(title, "champions league", "ucl", "europa")) return "🏆";
        if (containsAny(title, "preview", "preview", "press conference", "interview")) return "🎙";
        if (containsAny(title, "ban", "suspended", "red card")) return "🟥";
        return "📰";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) if (text.contains(k)) return true;
        return false;
    }

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
            var body = resp.body();
            return body != null ? body.string() : "{}";
        }
    }

}
