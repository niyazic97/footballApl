# ⚽ Football News Telegram Bot — Java Spring Boot

## Overview

A Telegram channel bot that automatically:
1. Collects football news (EPL + Champions League) from English RSS feeds
2. Translates to Russian + generates AI commentary — all in ONE Gemini API call
3. Finds a relevant image via Unsplash API
4. Posts formatted message + image to a Telegram channel
5. Runs on a scheduler every 10 minutes

---

## Tech Stack

- **Language:** Java 21
- **Framework:** Spring Boot 3.2
- **Build Tool:** Maven
- **Telegram:** telegrambots-spring-boot-starter 6.9.7
- **Scheduler:** Spring @Scheduled (built-in, no extra dependency)
- **AI (Translation + Commentary):** Google Gemini API via OkHttp (FREE tier)
- **Images:** Unsplash API via OkHttp
- **RSS Parsing:** Rome 2.1.0
- **Database:** SQLite + Spring Data JPA + Hibernate
- **HTTP Client:** OkHttp 4.12.0
- **JSON Parsing:** Jackson (included in Spring Boot)
- **Config:** application.properties + environment variables

---

## Project Structure

```
football-bot/
├── pom.xml
├── src/
│   └── main/
│       ├── java/com/footballbot/
│       │   ├── FootballBotApplication.java       # Entry point
│       │   ├── config/
│       │   │   └── AppConfig.java                # Beans, OkHttp client
│       │   ├── model/
│       │   │   ├── NewsItem.java                 # Main data model
│       │   │   └── PublishedNews.java            # JPA Entity for DB
│       │   ├── repository/
│       │   │   └── PublishedNewsRepository.java  # Spring Data JPA
│       │   ├── service/
│       │   │   ├── RssParserService.java         # Fetch RSS feeds
│       │   │   ├── AiProcessorService.java       # Gemini API
│       │   │   ├── ImageFinderService.java       # Unsplash API
│       │   │   ├── FormatterService.java         # Format post text
│       │   │   └── TelegramPublisherService.java # Post to Telegram
│       │   ├── scheduler/
│       │   │   └── NewsScheduler.java            # Main loop every 10 min
│       │   └── util/
│       │       └── ScorerUtil.java               # News importance scoring
│       └── resources/
│           └── application.properties
```

---

## pom.xml Dependencies

```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>

    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- Telegram Bot -->
    <dependency>
        <groupId>org.telegram</groupId>
        <artifactId>telegrambots-spring-boot-starter</artifactId>
        <version>6.9.7</version>
    </dependency>

    <!-- RSS Parser -->
    <dependency>
        <groupId>com.rometools</groupId>
        <artifactId>rome</artifactId>
        <version>2.1.0</version>
    </dependency>

    <!-- HTTP Client -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>

    <!-- SQLite Driver -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.45.1.0</version>
    </dependency>

    <!-- Hibernate SQLite dialect -->
    <dependency>
        <groupId>org.hibernate.orm</groupId>
        <artifactId>hibernate-community-dialects</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Jackson (JSON) — included via Spring Boot -->
</dependencies>
```

---

## application.properties

```properties
# Telegram
telegram.bot.token=${TELEGRAM_BOT_TOKEN}
telegram.bot.username=${TELEGRAM_BOT_USERNAME}
telegram.channel.id=${TELEGRAM_CHANNEL_ID}

# Gemini
gemini.api.key=${GEMINI_API_KEY}
gemini.api.url=https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent

# Unsplash
unsplash.access.key=${UNSPLASH_ACCESS_KEY}
unsplash.api.url=https://api.unsplash.com/search/photos

# Database
spring.datasource.url=jdbc:sqlite:football_bot.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=update

# Scheduler
scheduler.interval.minutes=10
scheduler.max.posts.per.run=3
news.max.age.hours=3
news.min.score=4

# Logging
logging.level.com.footballbot=INFO
logging.file.name=bot.log
```

---

## RSS Feed Sources

```java
// In AppConfig.java or application.properties
public static final List<String> RSS_FEEDS = List.of(
    // EPL
    "https://www.skysports.com/rss/12040",
    "https://feeds.bbci.co.uk/sport/football/premier-league/rss.xml",
    "https://www.premierleague.com/news.rss",

    // Champions League
    "https://www.skysports.com/rss/12196",
    "https://feeds.bbci.co.uk/sport/football/european/rss.xml",
    "https://www.uefa.com/newsfiles/ucl/rss.xml"
);
```

---

## Data Models

### `model/NewsItem.java`
```java
@Data
@Builder
public class NewsItem {
    private String id;              // MD5 hash of URL
    private String titleEn;         // Original English title
    private String summaryEn;       // Original English summary
    private String titleRu;         // Translated Russian title
    private String summaryRu;       // Translated Russian summary
    private String aiCommentary;    // Gemini-generated commentary
    private String url;             // Source article URL
    private String source;          // Feed source name
    private LocalDateTime publishedAt;
    private String imageUrl;        // Unsplash image URL (nullable)
    private int importanceScore;    // Score 1-10
    private String league;          // "EPL" or "UCL"
    private List<String> tags;      // Player/club names, topic
}
```

### `model/PublishedNews.java`
```java
@Entity
@Table(name = "published_news")
@Data
public class PublishedNews {
    @Id
    private String id;

    private String title;
    private LocalDateTime publishedAt;
    private LocalDateTime postedAt;
}
```

---

## Service Specifications

### `service/RssParserService.java`

```
Method: List<NewsItem> fetchAllNews()

- Iterate over all RSS_FEEDS
- For each feed use Rome SyndFeedInput to parse
- Map SyndEntry to NewsItem:
    id = MD5(entry.link)
    titleEn = entry.title
    summaryEn = strip HTML tags from entry.description
    publishedAt = entry.publishedDate
    league = detect from feed URL ("EPL" or "UCL")
    source = feed domain name
- Filter items older than news.max.age.hours
- Return combined list from all feeds
- Log count of fetched items per feed
```

### `util/ScorerUtil.java`

```
Method: static int score(NewsItem item)

Rules (applied to titleEn.toLowerCase()):
+3 → contains any of: "transfer", "sign", "deal", "contract"
+3 → contains any of: "injury", "injured", "out", "return"
+3 → contains any of: "goal", "hat-trick", "score", "result"
+2 → contains any of: "sacked", "manager", "appointed"
+2 → contains any of: "record", "history", "first"
+1 → source contains: "premierleague.com" or "uefa.com"
-2 → contains any of: "kit", "merchandise", "ticket prices"

Return score clamped between 0 and 10
```

### `service/AiProcessorService.java` — ключевой сервис

```
Method: NewsItem process(NewsItem item)

1. Build JSON request body for Gemini API:

POST https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={API_KEY}

Request body:
{
  "contents": [{
    "parts": [{
      "text": "You are a football news editor for a Russian Telegram channel.
               Given this English football news, respond ONLY with valid JSON, no other text:
               {
                 \"title_ru\": \"translated title\",
                 \"summary_ru\": \"translated summary, 2-3 sentences max\",
                 \"commentary\": \"short emotional commentary in Russian, 2-3 sentences,
                                  like talking to a friend football fan, use football slang\",
                 \"tags\": [\"tag1\", \"tag2\", \"tag3\"]
               }
               News title: {titleEn}
               News summary: {summaryEn}"
    }]
  }]
}

2. Parse response:
   - Extract text from response.candidates[0].content.parts[0].text
   - Parse as JSON using ObjectMapper
   - Fill item.titleRu, item.summaryRu, item.aiCommentary, item.tags

3. Error handling:
   - If HTTP 429 (rate limit) → Thread.sleep(60000) → retry once
   - If any exception → log warning, keep titleEn/summaryEn, set empty commentary
   - Always return item (never throw)
```

### `service/ImageFinderService.java`

```
Method: String findImage(NewsItem item)  — returns URL or null

1. Build search query:
   - Take first 2 tags + league name
   - e.g. "Arsenal Saka Premier League football"

2. Call Unsplash API:
   GET {unsplash.api.url}?query={query}&orientation=landscape&per_page=5
   Header: Authorization: Client-ID {unsplash.access.key}

3. Parse response:
   - results[] array
   - Find photo with highest likes value
   - Return photo.urls.regular

4. Fallback:
   - If results empty → retry with "football match stadium"
   - If still empty → return null
```

### `service/FormatterService.java`

```
Method: String format(NewsItem item)

Output template:
---
{leagueEmoji} {leagueName}

<b>{titleRu}</b>

{summaryRu}

💬 {aiCommentary}

🔗 <a href="{url}">Источник</a> | {hashtags}
---

leagueEmoji + leagueName:
  "EPL" → "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Премьер-лига"
  "UCL" → "🏆 Лига Чемпионов"

hashtags: tags.stream()
  .map(t -> "#" + t.replace(" ", "_"))
  .collect(joining(" "))
  + " #" + league.toLowerCase()

Max total length: 1024 characters (Telegram caption limit)
If exceeds → truncate summaryRu with "..." suffix
```

### `service/TelegramPublisherService.java`

```
Extends TelegramLongPollingBot

Method: boolean publishNews(NewsItem item)

- Format post text using FormatterService
- If item.imageUrl != null:
    SendPhoto sendPhoto = new SendPhoto()
    sendPhoto.setChatId(channelId)
    sendPhoto.setPhoto(new InputFile(imageUrl))
    sendPhoto.setCaption(formattedText)
    sendPhoto.setParseMode("HTML")
    execute(sendPhoto)
- Else:
    SendMessage message = new SendMessage()
    message.setChatId(channelId)
    message.setText(formattedText)
    message.setParseMode("HTML")
    execute(message)

- Catch TelegramApiException → log error, return false
- Return true on success
```

### `scheduler/NewsScheduler.java` — главный цикл

```
@Scheduled(fixedDelayString = "PT10M", initialDelay = 5000)
Method: void runNewsJob()

Steps:
1. List<NewsItem> news = rssParserService.fetchAllNews()
2. Filter: !publishedNewsRepository.existsById(item.getId())
3. Filter: scorerUtil.score(item) >= news.min.score
4. Set importanceScore on each item
5. Sort by importanceScore DESC
6. Take first scheduler.max.posts.per.run items
7. For each item:
   a. item = aiProcessorService.process(item)
   b. item.setImageUrl(imageFinderService.findImage(item))
   c. boolean ok = telegramPublisherService.publishNews(item)
   d. If ok:
        save PublishedNews to repository
        Thread.sleep(5000)  // 5 sec between posts
8. Log "Published X / Y news items"
```

---

## Environment Variables

Set these before running (in IntelliJ: Run → Edit Configurations → Environment Variables):

```
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_BOT_USERNAME=your_bot_username
TELEGRAM_CHANNEL_ID=@your_channel
GEMINI_API_KEY=your_gemini_key
UNSPLASH_ACCESS_KEY=your_unsplash_key
```

---

## API Keys — Where to Get Them

| Service | URL | Free Tier |
|---|---|---|
| Telegram Bot | @BotFather in Telegram | Free forever |
| Google Gemini | aistudio.google.com/apikey | Free: 1500 req/day |
| Unsplash | unsplash.com/developers | Free: 50 req/hour |

**Итого в месяц: $0** 🎉

---

## Implementation Order for Claude Code

Build strictly in this order:

1. `pom.xml` — all dependencies
2. `application.properties`
3. `model/NewsItem.java` + `model/PublishedNews.java`
4. `repository/PublishedNewsRepository.java`
5. `config/AppConfig.java` — OkHttp bean, ObjectMapper bean
6. `service/RssParserService.java`
7. `util/ScorerUtil.java`
8. `service/AiProcessorService.java`
9. `service/ImageFinderService.java`
10. `service/FormatterService.java`
11. `service/TelegramPublisherService.java`
12. `scheduler/NewsScheduler.java`
13. `FootballBotApplication.java`

---

## Notes for Claude Code

- Use Java 21 features: records where appropriate, var for local variables
- Use Lombok @Data, @Builder, @Slf4j on all classes
- All Spring services annotated with @Service
- Use @Value("${property.name}") for injecting config values
- OkHttp calls must be synchronous (no async needed, scheduler handles timing)
- ObjectMapper must be configured to ignore unknown JSON fields
- SQLite file created automatically in project root
- Never hardcode API keys — always from environment variables
- Add null checks before using imageUrl and aiCommentary
