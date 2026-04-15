package com.footballbot.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EntityDictionaryUtil {

    // LinkedHashMap preserves insertion order — LONGER PHRASES MUST COME FIRST
    private static final Map<String, String> DICT = new LinkedHashMap<>();

    static {
        // === EPL CLUBS — full names first, then short ===
        DICT.put("manchester united", "Манчестер Юнайтед");
        DICT.put("manchester city", "Манчестер Сити");
        DICT.put("man united", "Манчестер Юнайтед");
        DICT.put("man utd", "Манчестер Юнайтед");
        DICT.put("man city", "Манчестер Сити");
        DICT.put("mufc", "Манчестер Юнайтед");
        DICT.put("mcfc", "Манчестер Сити");
        DICT.put("arsenal", "Арсенал");
        DICT.put("chelsea", "Челси");
        DICT.put("liverpool", "Ливерпуль");
        DICT.put("tottenham hotspur", "Тоттенхэм");
        DICT.put("tottenham", "Тоттенхэм");
        DICT.put("spurs", "Тоттенхэм");
        DICT.put("newcastle united", "Ньюкасл");
        DICT.put("newcastle", "Ньюкасл");
        DICT.put("aston villa", "Астон Вилла");
        DICT.put("west ham united", "Вест Хэм");
        DICT.put("west ham", "Вест Хэм");
        DICT.put("brighton", "Брайтон");
        DICT.put("brighton hove", "Брайтон");
        DICT.put("everton", "Эвертон");
        DICT.put("brentford", "Брентфорд");
        DICT.put("fulham", "Фулэм");
        DICT.put("wolverhampton", "Вулверхэмптон");
        DICT.put("wolves", "Вулверхэмптон");
        DICT.put("crystal palace", "Кристал Пэлас");
        DICT.put("nottingham forest", "Ноттингем Форест");
        DICT.put("nottingham", "Ноттингем Форест");
        DICT.put("bournemouth", "Борнмут");
        DICT.put("leicester city", "Лестер");
        DICT.put("leicester", "Лестер");
        DICT.put("ipswich town", "Ипсвич");
        DICT.put("ipswich", "Ипсвич");
        DICT.put("southampton", "Саутгемптон");
        DICT.put("sunderland", "Сандерленд");
        DICT.put("leeds united", "Лидс");
        DICT.put("leeds", "Лидс");
        DICT.put("burnley", "Бернли");

        // === UCL CLUBS ===
        DICT.put("real madrid", "Реал Мадрид");
        DICT.put("barcelona fc", "Барселона");
        DICT.put("barcelona", "Барселона");
        DICT.put("atletico madrid", "Атлетико");
        DICT.put("atletico", "Атлетико");
        DICT.put("paris saint-germain", "ПСЖ");
        DICT.put("psg", "ПСЖ");
        DICT.put("bayern munich", "Бавария");
        DICT.put("fc bayern", "Бавария");
        DICT.put("bayern", "Бавария");
        DICT.put("borussia dortmund", "Боруссия Дортмунд");
        DICT.put("dortmund", "Дортмунд");
        DICT.put("juventus", "Ювентус");
        DICT.put("ac milan", "Милан");
        DICT.put("inter milan", "Интер");
        DICT.put("benfica", "Бенфика");
        DICT.put("sporting cp", "Спортинг");
        DICT.put("porto", "Порту");

        // === MANAGERS ===
        DICT.put("mikel arteta", "Артета");
        DICT.put("arteta", "Артета");
        DICT.put("pep guardiola", "Гвардиола");
        DICT.put("guardiola", "Гвардиола");
        DICT.put("arne slot", "Слот");
        DICT.put("slot", "Слот");
        DICT.put("ruben amorim", "Аморим");
        DICT.put("amorim", "Аморим");
        DICT.put("enzo maresca", "Мареска");
        DICT.put("maresca", "Мареска");
        DICT.put("eddie howe", "Хоу");
        DICT.put("howe", "Хоу");
        DICT.put("oliver glasner", "Гласнер");
        DICT.put("glasner", "Гласнер");

        // === TOP PLAYERS — full name first, surname only after ===
        DICT.put("erling haaland", "Холанд");
        DICT.put("haaland", "Холанд");
        DICT.put("mohamed salah", "Салах");
        DICT.put("salah", "Салах");
        DICT.put("bukayo saka", "Сака");
        DICT.put("saka", "Сака");
        DICT.put("jude bellingham", "Беллингем");
        DICT.put("bellingham", "Беллингем");
        DICT.put("kevin de bruyne", "Де Брёйне");
        DICT.put("de bruyne", "Де Брёйне");
        DICT.put("marcus rashford", "Рэшфорд");
        DICT.put("rashford", "Рэшфорд");
        DICT.put("cole palmer", "Палмер");
        DICT.put("palmer", "Палмер");
        DICT.put("phil foden", "Фоден");
        DICT.put("foden", "Фоден");
        DICT.put("bruno fernandes", "Фернандеш");
        DICT.put("fernandes", "Фернандеш");
        DICT.put("virgil van dijk", "Ван Дейк");
        DICT.put("van dijk", "Ван Дейк");
        DICT.put("trent alexander-arnold", "Трент");
        DICT.put("alexander-arnold", "Трент");
        DICT.put("dominic solanke", "Соланке");
        DICT.put("solanke", "Соланке");
        DICT.put("ollie watkins", "Уоткинс");
        DICT.put("watkins", "Уоткинс");
        DICT.put("alexander isak", "Исак");
        DICT.put("isak", "Исак");
        DICT.put("son heung-min", "Сон");
        DICT.put("heung-min son", "Сон");
        DICT.put("son", "Сон");
        DICT.put("harry kane", "Кейн");
        DICT.put("kane", "Кейн");
        DICT.put("rodri", "Родри");
        DICT.put("ruben dias", "Диаш");
        DICT.put("dias", "Диаш");
        DICT.put("gabriel magalhaes", "Габриэл");
        DICT.put("gabriel", "Габриэл");
        DICT.put("declan rice", "Райс");
        DICT.put("rice", "Райс");
        DICT.put("martin odegaard", "Эдегор");
        DICT.put("odegaard", "Эдегор");
        DICT.put("lamine yamal", "Ямаль");
        DICT.put("yamal", "Ямаль");
        DICT.put("vinicius junior", "Винисиус");
        DICT.put("vinicius", "Винисиус");

        // === COMPETITIONS ===
        DICT.put("premier league", "Премьер-лига");
        DICT.put("champions league", "Лига Чемпионов");
        DICT.put("europa league", "Лига Европы");
        DICT.put("conference league", "Лига Конференций");
        DICT.put("fa cup", "Кубок Англии");
        DICT.put("carabao cup", "Кубок Лиги");
        DICT.put("league cup", "Кубок Лиги");

        // === COMMON TERMS ===
        DICT.put("hat-trick", "хет-трик");
        DICT.put("clean sheet", "сухой матч");
        DICT.put("own goal", "автогол");
    }

    /**
     * Exact lookup by lowercase key. Returns the Russian translation if found.
     */
    public static java.util.Optional<String> translate(String key) {
        if (key == null || key.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(DICT.get(key.toLowerCase().trim()));
    }

    // Unicode ranges that should never appear in Russian/English football text:
    // Hangul, Hiragana, Katakana, CJK Unified Ideographs, Arabic, Thai, Devanagari
    private static final Pattern UNEXPECTED_UNICODE = Pattern.compile(
            "[\uAC00-\uD7AF\u1100-\u11FF\u3040-\u309F\u30A0-\u30FF" +
            "\u4E00-\u9FFF\u3400-\u4DBF\u0600-\u06FF\u0E00-\u0E7F\u0900-\u097F]+"
    );

    /**
     * Strips unexpected Unicode characters (Hangul, CJK, Arabic, etc.) from text.
     * Handles cases where LLM mixes character sets mid-word (e.g. "Спор팅" → "Спор").
     */
    private static String stripUnexpectedUnicode(String text) {
        if (text == null) return null;
        return UNEXPECTED_UNICODE.matcher(text).replaceAll("");
    }

    /**
     * Replaces all known English entity names with their correct Russian translations.
     * Applied to Groq output to guarantee consistent naming.
     */
    public static String normalizeEntities(String text) {
        if (text == null || text.isBlank()) return text;
        String result = stripUnexpectedUnicode(text);
        for (Map.Entry<String, String> entry : DICT.entrySet()) {
            result = result.replaceAll(
                    "(?i)\\b" + Pattern.quote(entry.getKey()) + "\\b",
                    entry.getValue()
            );
        }
        return result;
    }

    /**
     * Generates Russian tags by finding which dictionary entities appear in the English title.
     */
    public static List<String> generateTags(String titleEn) {
        if (titleEn == null) return List.of();
        String lower = titleEn.toLowerCase();
        return DICT.entrySet().stream()
                .filter(e -> lower.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .limit(4)
                .collect(Collectors.toList());
    }
}
