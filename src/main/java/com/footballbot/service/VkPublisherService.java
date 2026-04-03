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

    public boolean isEnabled() {
        return accessToken != null && !accessToken.isBlank() && groupId > 0;
    }

    public boolean publishNews(NewsItem item) {
        if (!isEnabled()) return false;
        try {
            String attachment = null;
            byte[] imageBytes = imageFinderService.findImageBytes(item);
            if (imageBytes != null) {
                attachment = uploadDocAttachment(imageBytes, item.getTitleRu());
            }
            postToWall(buildText(item), attachment);
            log.info("Published to VK{}: {}", attachment != null ? " (with image)" : "", item.getTitleRu());
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish to VK '{}': {}", item.getTitleRu(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String uploadDocAttachment(byte[] imageBytes, String title) throws Exception {
        // Step 1: get upload URL
        String serverUrl = VK_API + "docs.getWallUploadServer"
                + "?group_id=" + groupId
                + "&access_token=" + accessToken
                + "&v=" + VK_VERSION;
        var serverResp = objectMapper.readValue(get(serverUrl), Map.class);
        if (serverResp.containsKey("error")) {
            log.warn("VK docs upload server error: {}", serverResp.get("error"));
            return null;
        }
        String uploadUrl = (String) ((Map<?, ?>) serverResp.get("response")).get("upload_url");

        // Step 2: upload image as multipart
        var body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "image.jpg",
                        RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                .build();
        var uploadReq = new Request.Builder().url(uploadUrl).post(body).build();
        String uploadRespStr;
        try (var resp = httpClient.newCall(uploadReq).execute()) {
            var rb = resp.body();
            uploadRespStr = rb != null ? rb.string() : "{}";
        }
        var uploadResp = objectMapper.readValue(uploadRespStr, Map.class);
        String fileToken = (String) uploadResp.get("file");
        if (fileToken == null || fileToken.isBlank()) {
            log.warn("VK doc upload returned no file token");
            return null;
        }

        // Step 3: save document
        String saveUrl = VK_API + "docs.save"
                + "?file=" + URLEncoder.encode(fileToken, StandardCharsets.UTF_8)
                + "&title=" + URLEncoder.encode(title != null ? title : "image", StandardCharsets.UTF_8)
                + "&access_token=" + accessToken
                + "&v=" + VK_VERSION;
        var saveResp = objectMapper.readValue(get(saveUrl), Map.class);
        if (saveResp.containsKey("error")) {
            log.warn("VK docs.save error: {}", saveResp.get("error"));
            return null;
        }
        var responseObj = (Map<?, ?>) saveResp.get("response");
        // docs.save returns {type: "doc", doc: {id, owner_id, ...}}
        var doc = (Map<?, ?>) responseObj.get("doc");
        if (doc == null) {
            // fallback: might be a list
            var list = (List<?>) responseObj.get("doc");
            if (list != null && !list.isEmpty()) doc = (Map<?, ?>) list.get(0);
        }
        if (doc == null) {
            log.warn("VK docs.save returned unexpected structure: {}", saveResp);
            return null;
        }
        int ownerId = ((Number) doc.get("owner_id")).intValue();
        int docId = ((Number) doc.get("id")).intValue();
        return "doc" + ownerId + "_" + docId;
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

        // Separator + hashtags + source
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        // Hashtags from tags
        if (item.getTags() != null && !item.getTags().isEmpty()) {
            item.getTags().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .limit(4)
                    .forEach(t -> sb.append("#").append(t.replace(" ", "_")).append(" "));
            sb.append("#АПЛ\n");
        } else {
            sb.append("#АПЛ #английскийфутбол\n");
        }

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
