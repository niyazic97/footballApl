package com.footballbot.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
public class AppConfig {

    public static final List<String> RSS_FEEDS = List.of(
            // Sky Sports
            "https://www.skysports.com/rss/12040",   // EPL
            "https://www.skysports.com/rss/12196",   // UCL
            // BBC Sport
            "https://feeds.bbci.co.uk/sport/football/premier-league/rss.xml",
            "https://feeds.bbci.co.uk/sport/football/european/rss.xml",
            // The Guardian
            "https://www.theguardian.com/football/premierleague/rss",
            "https://www.theguardian.com/football/championsleague/rss",
            // ESPN Soccer (all leagues)
            "https://www.espn.com/espn/rss/soccer/news",
            // FourFourTwo
            "https://www.fourfourtwo.com/feeds.xml",
            // The Independent
            "https://www.independent.co.uk/sport/football/rss",
            // Mirror Football
            "https://www.mirror.co.uk/sport/football/rss.xml",
            // Manchester Evening News (active, EPL-focused)
            "https://www.manchestereveningnews.co.uk/sport/football/rss.xml",
            // Liverpool Echo (Liverpool + Everton focused)
            "https://www.liverpoolecho.co.uk/sport/football/rss.xml",
            // Birmingham Mail (Aston Villa + Birmingham focused)
            "https://www.birminghammail.co.uk/sport/football/rss.xml"
    );

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
