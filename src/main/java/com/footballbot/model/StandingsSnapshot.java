package com.footballbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "standings_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandingsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int matchday;
    private String teamName;
    private int position;
    private int points;
    private LocalDateTime snapshotDate;
}
