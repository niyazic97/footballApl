package com.footballbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballbot.util.EntityDictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class StandingsImageService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramPublisherService telegramPublisherService;

    @Value("${football.api.key:}")
    private String apiKey;

    @Value("${football.api.url:https://api.football-data.org/v4}")
    private String apiUrl;

    @Value("${hcti.user.id}")
    private String hctiUserId;

    @Value("${hcti.api.key}")
    private String hctiApiKey;

    // Club colors for bar chart
    private static final Map<String, String> CLUB_COLORS = Map.ofEntries(
            Map.entry("Арсенал",           "#EF0107"),
            Map.entry("Манчестер Сити",    "#6CABDD"),
            Map.entry("Ливерпуль",         "#C8102E"),
            Map.entry("Манчестер Юнайтед", "#DA291C"),
            Map.entry("Астон Вилла",       "#95BFE5"),
            Map.entry("Челси",             "#034694"),
            Map.entry("Тоттенхэм",         "#132257"),
            Map.entry("Ньюкасл",           "#241F20"),
            Map.entry("Брайтон",           "#0057B8"),
            Map.entry("Брентфорд",         "#e30613"),
            Map.entry("Фулэм",             "#CC0000"),
            Map.entry("Кристал Пэлас",     "#1B458F"),
            Map.entry("Вулверхэмптон",     "#FDB913"),
            Map.entry("Эвертон",           "#003399"),
            Map.entry("Ноттингем Форест",  "#DD0000"),
            Map.entry("Борнмут",           "#B50E12"),
            Map.entry("Вест Хэм",          "#7A263A"),
            Map.entry("Ипсвич",            "#0044a0"),
            Map.entry("Лестер",            "#003090"),
            Map.entry("Саутгемптон",       "#D71920")
    );

    public void postStandings() {
        try {
            var table = fetchStandings();
            String html = buildStandingsHtml(table);
            byte[] image = renderHtmlToImage(html, 880, 860);
            telegramPublisherService.sendPhotoBytes(image, "🏆 Таблица АПЛ");
            log.info("Standings image posted ({} teams)", table.size());
        } catch (Exception e) {
            log.warn("Failed to post standings image: {}", e.getMessage());
        }
    }

    public void postScorers() {
        try {
            var scorers = fetchTopScorers();
            String html = buildScorersHtml(scorers);
            byte[] image = renderHtmlToImage(html, 880, 480);
            telegramPublisherService.sendPhotoBytes(image, "⚽ Бомбардиры АПЛ");
            log.info("Scorers image posted");
        } catch (Exception e) {
            log.warn("Failed to post scorers image: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchStandings() throws Exception {
        var request = new Request.Builder()
                .url(apiUrl + "/competitions/2021/standings")
                .header("X-Auth-Token", apiKey)
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var root = objectMapper.readValue(response.body().string(), Map.class);
            var standings = (List<Map<String, Object>>) root.get("standings");
            var total = standings.stream()
                    .filter(s -> "TOTAL".equals(s.get("type")))
                    .findFirst().orElseThrow();
            return (List<Map<String, Object>>) total.get("table");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTopScorers() throws Exception {
        var request = new Request.Builder()
                .url(apiUrl + "/competitions/2021/scorers?limit=10")
                .header("X-Auth-Token", apiKey)
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var root = objectMapper.readValue(response.body().string(), Map.class);
            return (List<Map<String, Object>>) root.get("scorers");
        }
    }

    @SuppressWarnings("unchecked")
    private String buildStandingsHtml(List<Map<String, Object>> table) {
        var sb = new StringBuilder();
        sb.append("""
                <html><head><meta charset="UTF-8"><style>
                  * { box-sizing:border-box; margin:0; padding:0; }
                  html, body { height:fit-content; }
                  body { background:#1a1a2e; font-family:Arial,sans-serif; color:#fff; padding:16px; width:860px; }
                  h2 { text-align:center; font-size:20px; margin-bottom:12px; }
                  table { width:100%; border-collapse:collapse; }
                  th { background:#0f3460; color:#9ab; font-size:12px; padding:8px 6px; text-align:center; font-weight:normal; }
                  th.l { text-align:left; padding-left:10px; }
                  td { padding:7px 6px; text-align:center; font-size:13px; border-bottom:1px solid #252545; }
                  td.l { text-align:left; padding-left:4px; }
                  .club { display:flex; align-items:center; gap:8px; }
                  .club img { width:22px; height:22px; object-fit:contain; flex-shrink:0; }
                  .pos { color:#888; font-size:12px; width:28px; }
                  .pts { font-weight:bold; font-size:15px; }
                  .gd-pos { color:#4CAF50; }
                  .gd-neg { color:#f44336; }
                  .cl td:first-child { border-left:3px solid #4287f5; }
                  .el td:first-child { border-left:3px solid #ff9500; }
                  .conf td:first-child { border-left:3px solid #2ecc71; }
                  .rel td:first-child { border-left:3px solid #e74c3c; }
                  .div td { border-bottom:2px solid #3a3a6e; }
                  tr:nth-child(even) { background:rgba(255,255,255,0.02); }
                </style></head><body>
                <h2>🏆 Таблица АПЛ</h2>
                <table>
                  <tr>
                    <th>#</th><th class="l" style="min-width:160px">Клуб</th>
                    <th>И</th><th>В</th><th>Н</th><th>П</th>
                    <th>ЗМ</th><th>ПМ</th><th>РМ</th><th>О</th>
                  </tr>
                """);

        for (var row : table) {
            int pos    = (int) row.get("position");
            var team   = (Map<String, Object>) row.get("team");
            String nameEn = (String) team.get("shortName");
            String nameRu = EntityDictionaryUtil.translate(nameEn).orElse(nameEn);
            String crest  = (String) team.get("crest");
            int played = (int) row.get("playedGames");
            int won    = (int) row.get("won");
            int draw   = (int) row.get("draw");
            int lost   = (int) row.get("lost");
            int gf     = (int) row.get("goalsFor");
            int ga     = (int) row.get("goalsAgainst");
            int gd     = (int) row.get("goalDifference");
            int pts    = (int) row.get("points");

            String gdStr   = gd > 0 ? "+" + gd : String.valueOf(gd);
            String gdClass = gd > 0 ? "gd-pos" : gd < 0 ? "gd-neg" : "";
            String rowClass = pos <= 4 ? "cl" : pos == 5 ? "el" : pos == 6 ? "conf" : pos >= 18 ? "rel" : "";
            String divClass = (pos == 4 || pos == 5 || pos == 6 || pos == 17) ? " div" : "";

            sb.append(String.format("""
                    <tr class="%s%s">
                      <td class="pos">%d</td>
                      <td class="l"><div class="club">
                        <img src="%s" onerror="this.style.display='none'">
                        <span>%s</span>
                      </div></td>
                      <td>%d</td><td>%d</td><td>%d</td><td>%d</td>
                      <td>%d</td><td>%d</td>
                      <td class="%s">%s</td>
                      <td class="pts">%d</td>
                    </tr>
                    """, rowClass, divClass, pos, crest, nameRu,
                    played, won, draw, lost, gf, ga, gdClass, gdStr, pts));
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildScorersHtml(List<Map<String, Object>> scorers) {
        var sb = new StringBuilder();
        sb.append("""
                <html><head><meta charset="UTF-8"><style>
                  * { box-sizing:border-box; margin:0; padding:0; }
                  html, body { height:fit-content; }
                  body { background:#1a1a2e; font-family:Arial,sans-serif; color:#fff; padding:16px; width:860px; }
                  h2 { text-align:center; font-size:20px; margin-bottom:12px; }
                  table { width:100%; border-collapse:collapse; }
                  th { background:#0f3460; color:#9ab; font-size:12px; padding:8px 6px; text-align:center; font-weight:normal; }
                  th.l { text-align:left; padding-left:10px; }
                  td { padding:7px 6px; text-align:center; font-size:13px; border-bottom:1px solid #252545; }
                  td.l { text-align:left; padding-left:4px; }
                  .club { display:flex; align-items:center; gap:6px; }
                  .club img { width:20px; height:20px; object-fit:contain; }
                  .pos { color:#888; font-size:12px; width:24px; }
                  .goals { font-weight:bold; color:#4CAF50; font-size:14px; }
                  tr:nth-child(even) { background:rgba(255,255,255,0.02); }
                </style></head><body>
                <h2>⚽ Бомбардиры АПЛ</h2>
                <table>
                  <tr>
                    <th>#</th><th class="l">Игрок</th><th class="l">Клуб</th>
                    <th>Голы</th>
                  </tr>
                """);

        int i = 1;
        for (var s : scorers) {
            var player = (Map<String, Object>) s.get("player");
            var team   = (Map<String, Object>) s.get("team");
            String playerName = (String) player.get("name");
            String teamNameEn = (String) team.get("shortName");
            String teamNameRu = EntityDictionaryUtil.translate(teamNameEn).orElse(teamNameEn);
            String crest = (String) team.get("crest");
            int goals = s.get("goals") instanceof Number n ? n.intValue() : 0;

            sb.append(String.format("""
                    <tr>
                      <td class="pos">%d</td>
                      <td class="l">%s</td>
                      <td class="l"><div class="club">
                        <img src="%s" onerror="this.style.display='none'">
                        <span>%s</span>
                      </div></td>
                      <td class="goals">%d</td>
                    </tr>
                    """, i++, playerName, crest, teamNameRu, goals));
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    private byte[] renderHtmlToImage(String html, int viewportWidth, int viewportHeight) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of(
                "html", html,
                "viewport_width", viewportWidth,
                "viewport_height", viewportHeight,
                "device_scale", 2
        ));

        String credentials = java.util.Base64.getEncoder()
                .encodeToString((hctiUserId + ":" + hctiApiKey).getBytes());

        var request = new Request.Builder()
                .url("https://hcti.io/v1/image")
                .header("Authorization", "Basic " + credentials)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

        String imageUrl;
        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("hcti.io failed: " + response.code() + " " + response.body().string());
            }
            var result = objectMapper.readValue(response.body().string(), Map.class);
            imageUrl = (String) result.get("url");
            log.info("hcti.io image created: {}", imageUrl);
        }

        // Download PNG bytes
        var imgRequest = new Request.Builder().url(imageUrl).build();
        try (var imgResponse = httpClient.newCall(imgRequest).execute()) {
            return imgResponse.body().bytes();
        }
    }

}
