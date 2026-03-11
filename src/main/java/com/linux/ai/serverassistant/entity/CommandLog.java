package com.linux.ai.serverassistant.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "command_logs")
public class CommandLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username; // User who executed the command

    @Column(columnDefinition = "TEXT")
    private String command; // Content of the executed command

    private LocalDateTime executionTime = LocalDateTime.now();

    @Column(columnDefinition = "TEXT")
    private String output; // Execution result (or error message)

    private boolean success; // Whether the execution was successful (Exit Code == 0)

    private Integer exitCode; // Process exit code when available (nullable)

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CommandType commandType;
}
