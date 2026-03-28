package com.footballbot.repository;

import com.footballbot.model.StandingsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StandingsSnapshotRepository extends JpaRepository<StandingsSnapshot, Long> {
    List<StandingsSnapshot> findByMatchday(int matchday);
    void deleteByMatchday(int matchday);
}
