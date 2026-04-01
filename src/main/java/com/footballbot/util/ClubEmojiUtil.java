package com.footballbot.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps club names to Telegram custom emoji IDs (club crests).
 * Fill in emoji IDs from a Telegram emoji pack — use @getidsbot to get IDs.
 * Format in HTML: <tg-emoji emoji-id="ID">⚽</tg-emoji>
 */
public class ClubEmojiUtil {

    // club keyword (lowercase) → custom_emoji_id from PremierLeagueeemoji pack
    private static final Map<String, Long> CLUB_EMOJI = new LinkedHashMap<>() {{
        put("arsenal",           5422820996350489768L);
        put("chelsea",           5404649204469479877L);
        put("liverpool",         5255946295536793972L);
        put("manchester city",   5402202254226765798L);
        put("man city",          5402202254226765798L);
        put("manchester united", 5402594243006968778L);
        put("man united",        5402594243006968778L);
        put("man utd",           5402594243006968778L);
        put("tottenham",         5431624910508145190L);
        put("newcastle",         5404807916395965357L);
        put("aston villa",       5422771853334689511L);
        put("west ham",          5404723086496904109L);
        put("brighton",          5402510680123259809L);
        put("everton",           5404541503869563550L);
        put("fulham",            5427395669096673342L);
        put("wolves",            5404676675080303082L);
        put("wolverhampton",     5404676675080303082L);
        put("nottingham forest", 5404645308934141232L);
        put("brentford",         5402423487992182299L);
        put("bournemouth",       5404460917398189418L);
        put("crystal palace",    5404391978878120422L);
        put("southampton",       5404806821179306605L);
    }};

    /**
     * Returns HTML custom emoji tag for the club found in title, or empty string if none.
     */
    public static String getEmojiHtml(String titleEn) {
        if (titleEn == null) return "";
        String lower = titleEn.toLowerCase();
        for (var entry : CLUB_EMOJI.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getValue() != 0L) {
                return "<tg-emoji emoji-id=\"" + entry.getValue() + "\">⚽</tg-emoji> ";
            }
        }
        return "";
    }
}
