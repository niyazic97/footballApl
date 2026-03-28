package com.footballbot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "published_news")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishedNews {
    @Id
    private String id;

    private String title;
    private LocalDateTime publishedAt;
    private LocalDateTime postedAt;
}
