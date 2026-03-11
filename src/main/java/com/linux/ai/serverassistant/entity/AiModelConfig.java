package com.linux.ai.serverassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Table(name = "ai_model_configs")
@AllArgsConstructor
@NoArgsConstructor
public class AiModelConfig {
    @Id
    @NotBlank(message = "id 不能為空")
    @Size(max = 64, message = "id 長度不可超過 64")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "id 只能包含英數、點、底線與連字號")
    private String id; // Key used by the frontend, e.g., "70b"

    @NotBlank(message = "name 不能為空")
    @Size(max = 200, message = "name 長度不可超過 200")
    private String name; // Actual model name, e.g., "llama-3.3-70b..."

    @Min(value = 1, message = "tpm 必須大於 0")
    @Max(value = 500000, message = "tpm 不可超過 500000")
    private int tpm; // Token limit (Tokens Per Minute)

    @NotBlank(message = "label 不能為空")
    @Size(max = 100, message = "label 長度不可超過 100")
    private String label; // Display name

    @NotBlank(message = "category 不能為空")
    @Size(max = 64, message = "category 長度不可超過 64")
    private String category; // Category

    @Column(name = "is_enabled")
    private boolean enabled = true; // Whether the model is enabled
}
