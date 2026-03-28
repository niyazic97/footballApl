package com.footballbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "published_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishedResult {
    @Id
    private String matchId;
    private String homeTeam;
    private String awayTeam;
    private String score;
    private LocalDateTime postedAt;
}
