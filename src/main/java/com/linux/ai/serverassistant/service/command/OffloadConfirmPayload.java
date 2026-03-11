package com.linux.ai.serverassistant.service.command;

import java.nio.file.Path;

public record OffloadConfirmPayload(Path source, Path targetRoot) {}
