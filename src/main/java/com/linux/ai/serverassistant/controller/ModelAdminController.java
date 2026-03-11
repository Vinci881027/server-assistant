package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.entity.AiModelConfig;
import com.linux.ai.serverassistant.service.AiModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/models")
public class ModelAdminController {
    private final AiModelService service;

    public ModelAdminController(AiModelService service) {
        this.service = service;
    }

    @GetMapping
    public List<AiModelConfig> listModels() {
        return service.getAllModels();
    }

    @PostMapping
    public AiModelConfig saveModel(@Valid @RequestBody AiModelConfig model) {
        return service.saveModel(model);
    }

    @DeleteMapping("/{id}")
    public void deleteModel(@PathVariable String id) {
        service.deleteModel(id);
    }
}
