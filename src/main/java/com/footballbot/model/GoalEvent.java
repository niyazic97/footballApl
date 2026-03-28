package com.footballbot.model;

import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class GoalEvent {
    private String matchId;
    private String homeTeam;       // Russian name
    private String awayTeam;       // Russian name
    private int homeScore;
    private int awayScore;
    private String scorerName;     // Russian name via EntityDictionaryUtil
    private int minute;
    private boolean isOwnGoal;
    private boolean isPenalty;
    private ZonedDateTime detectedAt;
}
