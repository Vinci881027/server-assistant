package com.linux.ai.serverassistant.service.command;

import java.io.IOException;

/**
 * Abstracts OS process creation so that tests can inject stub implementations
 * without subclassing {@link CommandExecutionService}.
 */
interface ProcessFactory {
    Process startCommand(boolean forceRoot, String user, String command) throws IOException;
    Process startSudoAuth(String user) throws IOException;
    ProcessBuilder createSudoAuthProcessBuilder(String user);
}
