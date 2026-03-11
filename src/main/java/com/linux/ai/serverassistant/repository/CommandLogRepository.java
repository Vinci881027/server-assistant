package com.linux.ai.serverassistant.repository;

import com.linux.ai.serverassistant.entity.CommandLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {
    List<CommandLog> findByUsernameOrderByExecutionTimeDesc(String username);
    Page<CommandLog> findByUsernameOrderByExecutionTimeDesc(String username, Pageable pageable);

    long countByUsername(String username);

    @Modifying
    @Transactional
    @Query("DELETE FROM CommandLog c WHERE c.username = :username")
    int deleteAllByUsername(@Param("username") String username);

    @Modifying
    @Transactional
    @Query("DELETE FROM CommandLog c WHERE c.executionTime < :cutoff")
    int deleteByExecutionTimeBefore(@Param("cutoff") LocalDateTime cutoff);
}
