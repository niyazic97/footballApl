package com.footballbot.repository;

import com.footballbot.model.FixtureIdMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FixtureIdMappingRepository extends JpaRepository<FixtureIdMapping, String> {
}
