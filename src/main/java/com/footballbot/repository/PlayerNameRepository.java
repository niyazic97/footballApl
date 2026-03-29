package com.footballbot.repository;

import com.footballbot.model.PlayerName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerNameRepository extends JpaRepository<PlayerName, String> {
}
