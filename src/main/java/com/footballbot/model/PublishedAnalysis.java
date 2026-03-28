package com.footballbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "published_analyses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishedAnalysis {
    @Id
    private String matchId;
    private String homeTeam;
    private String awayTeam;
    private LocalDateTime postedAt;
}
