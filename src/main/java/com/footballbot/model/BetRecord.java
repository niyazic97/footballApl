package com.footballbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "bet_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BetRecord {
    @Id
    private String matchId;
    private String homeTeam;
    private String awayTeam;
    private String bet;           // "Победа хозяев", "Ничья", etc. or "пропускаю"
    private String confidence;    // "высокая" / "средняя" / "низкая"
    private String result;        // "WIN" / "LOSS" / "SKIP" / "PENDING"
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
