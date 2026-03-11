package com.linux.ai.serverassistant.repository;

import com.linux.ai.serverassistant.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, String> {
}