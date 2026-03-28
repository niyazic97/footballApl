package com.footballbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "fixture_id_mapping")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixtureIdMapping {

    @Id
    private String footballDataId;

    private Integer apiFootballId;
    private String homeTeam;
    private LocalDate matchDate;
}
