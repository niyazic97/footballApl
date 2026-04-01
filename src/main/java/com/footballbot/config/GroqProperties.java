package com.footballbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "groq")
@Getter
@Setter
public class GroqProperties {

    private Api api = new Api();
    private String model;
    private int gapSeconds = 90;

    @Getter
    @Setter
    public static class Api {
        private String key;
        private String key2 = "";
        private String url;
    }
}
