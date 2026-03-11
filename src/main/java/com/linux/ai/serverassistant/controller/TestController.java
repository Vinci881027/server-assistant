package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @GetMapping("/api/ping")
    public ApiResponse<Void> ping() {
        return ApiResponse.success("pong");
    }
}