-- chat_messages
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(255),
    username        VARCHAR(255) NOT NULL,
    message_type    VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP
);

CREATE INDEX idx_chat_msg_conv_created_id ON chat_messages (conversation_id, created_at, id);
CREATE INDEX idx_chat_messages_username_created_at ON chat_messages (username, created_at);
CREATE INDEX idx_chat_messages_created_at ON chat_messages (created_at);

-- command_logs
CREATE TABLE command_logs (
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(255),
    command        TEXT,
    execution_time TIMESTAMP,
    output         TEXT,
    success        BOOLEAN      NOT NULL,
    exit_code      INTEGER,
    command_type   VARCHAR(20)
);

CREATE INDEX idx_command_logs_username_execution_time ON command_logs (username, execution_time);
CREATE INDEX idx_command_logs_execution_time ON command_logs (execution_time);

-- ai_model_configs
CREATE TABLE ai_model_configs (
    id         VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    tpm        INTEGER      NOT NULL,
    label      VARCHAR(100) NOT NULL,
    category   VARCHAR(64)  NOT NULL,
    is_enabled BOOLEAN      NOT NULL DEFAULT TRUE
);
