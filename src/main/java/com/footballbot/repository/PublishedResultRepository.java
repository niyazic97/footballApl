package com.footballbot.repository;
import com.footballbot.model.PublishedResult;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PublishedResultRepository extends JpaRepository<PublishedResult, String> {}
