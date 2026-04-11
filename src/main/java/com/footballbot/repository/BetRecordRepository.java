package com.footballbot.repository;

import com.footballbot.model.BetRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BetRecordRepository extends JpaRepository<BetRecord, String> {
    List<BetRecord> findByResultIn(List<String> results);
}
