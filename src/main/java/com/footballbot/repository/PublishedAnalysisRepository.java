package com.footballbot.repository;
import com.footballbot.model.PublishedAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PublishedAnalysisRepository extends JpaRepository<PublishedAnalysis, String> {}
