package com.footballbot.repository;

import com.footballbot.model.PublishedNews;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PublishedNewsRepository extends JpaRepository<PublishedNews, String> {
    List<PublishedNews> findByPostedAtAfter(LocalDateTime time);

    boolean existsByPostedAtAfter(LocalDateTime time);
}
