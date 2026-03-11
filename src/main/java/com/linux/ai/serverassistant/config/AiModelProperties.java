package com.linux.ai.serverassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiModelProperties {
    private String defaultModelKey;
    private String defaultModelName;
    private List<String> groqApiKeys;
    private Map<String, ModelConfig> models;

    public String getDefaultModelKey() {
        return defaultModelKey;
    }

    public void setDefaultModelKey(String defaultModelKey) {
        this.defaultModelKey = defaultModelKey;
    }

    public String getDefaultModelName() {
        return defaultModelName;
    }

    public void setDefaultModelName(String defaultModelName) {
        this.defaultModelName = defaultModelName;
    }

    public List<String> getGroqApiKeys() {
        return groqApiKeys;
    }

    public void setGroqApiKeys(List<String> groqApiKeys) {
        this.groqApiKeys = groqApiKeys;
    }

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    public static class ModelConfig {
        private String name;
        private int tpm;
        private String label;
        private String category;
        private boolean enabled = true;
        private boolean available = true;
        private String suggestAlternative;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getTpm() { return tpm; }
        public void setTpm(int tpm) { this.tpm = tpm; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public String getSuggestAlternative() { return suggestAlternative; }
        public void setSuggestAlternative(String suggestAlternative) { this.suggestAlternative = suggestAlternative; }
    }
}
