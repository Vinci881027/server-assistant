package com.linux.ai.serverassistant.service.command;

import java.nio.file.Path;

public record MountConfirmPayload(Path device, Path target, String fstype, String options) {}
