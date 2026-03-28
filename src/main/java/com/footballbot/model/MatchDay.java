package com.footballbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchDay {
    private String homeTeam;
    private String awayTeam;
    private String league;
    private LocalDateTime kickoff;
    private String venue;
    private String status;
    private Integer matchId;
    private Integer homeTeamId;
    private Integer awayTeamId;
    private Integer homeScore;
    private Integer awayScore;
}
