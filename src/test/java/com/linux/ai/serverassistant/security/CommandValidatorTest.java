package com.linux.ai.serverassistant.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class CommandValidatorTest {

    private CommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CommandValidator();
    }

    // ========== Null / Empty ==========

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void nullOrEmpty_shouldBeInvalid(String command) {
        CommandValidator.ValidationResult result = validator.validate(command);
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    // ========== Safe commands ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "ls -la",
        "whoami",
        "df -h",
        "free -h",
        "uptime",
        "cat /etc/hostname",
        "pwd",
        "ps aux | grep java",
        "netstat -tuln | head -20"
    })
    void safeCommands_shouldBeValid(String command) {
        CommandValidator.ValidationResult result = validator.validate(command);
        assertTrue(result.isValid(), "Expected valid for: " + command);
        assertFalse(result.requiresConfirmation());
    }

    // ========== Dangerous characters ==========

    @Nested
    class DangerousCharacters {

        @ParameterizedTest
        @ValueSource(strings = {
            "ls ; rm -rf /",
            "echo hello & echo world",
            "echo `whoami`",
            "echo $HOME",
            "echo hello > /etc/passwd",
            "cat < /dev/null",
        })
        void dangerousChars_shouldBeInvalid(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertFalse(result.isValid(), "Should block: " + command);
            assertTrue(result.getErrorMessage().contains("危險字符"));
        }

        @Test
        void newline_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("ls\nrm -rf /");
            assertFalse(result.isValid());
        }

        @Test
        void carriageReturn_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("ls\rrm -rf /");
            assertFalse(result.isValid());
        }

        @Test
        void nullByte_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("ls\0rm");
            assertFalse(result.isValid());
        }
    }

    // ========== Command chaining ==========

    @Nested
    class CommandChaining {

        @Test
        void doubleAmpersand_shouldBeBlocked() {
            // '&' is caught by dangerous chars before chain detection
            CommandValidator.ValidationResult result = validator.validate("ls && rm -rf /");
            assertFalse(result.isValid());
        }

        @Test
        void doublePipe_shouldBeBlocked() {
            // '||' is caught by command chain pattern (no dangerous chars here)
            CommandValidator.ValidationResult result = validator.validate("false || true");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("命令鏈"));
        }
    }

    // ========== Pipe whitelist ==========

    @Nested
    class PipeWhitelist {

        @ParameterizedTest
        @ValueSource(strings = {
            "ps aux | grep java",
            "cat /var/log/syslog | tail -20",
            "ls -la | sort",
            "du -sh * | head -10",
            "echo test | wc -l",
            "ls | grep test | head -5",
            "printf 'a\\n' | awk 'NR==1{print \"x\"}'",
            "printf 'a\\n' | sed -n '1p'",
            "cat /tmp/a | xargs -n1 echo",
            "printf 'a\\tb\\n' | paste -sd ','",
            "printf 'a\\na\\n' | sort | comm -12 - -"
        })
        void whitelistedPipeCommands_shouldBeValid(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Expected valid pipe: " + command);
        }

        @Test
        void nonWhitelistedPipeCommand_shouldBeBlocked() {
            // "python" is not in pipe whitelist
            // Note: "python" doesn't contain dangerous chars, but pipe check should catch it
            CommandValidator.ValidationResult result = validator.validate("ls | python -c 'import os'");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("白名單"));
        }

        @Test
        void xargsDelegatingHighRiskCommand_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("printf '/srv/test\\n' | xargs rm -rf");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("xargs"));
        }

        @Test
        void xargsReplaceOptionDelegatingHighRiskCommand_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("printf '/srv/test\\n' | xargs --replace rm -rf {}");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("xargs"));
        }

        @Test
        void awkCommandExecutionPrimitive_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("printf 'a\\n' | awk 'BEGIN{system(\"id\")}'");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("awk"));
        }

        @Test
        void sedExecutionPrimitive_shouldBeBlocked() {
            CommandValidator.ValidationResult result = validator.validate("printf 'a\\n' | sed '1e id'");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("sed"));
        }

        @Test
        void pipeInjection_shouldBeBlocked() {
            // pipe followed by dangerous chars
            CommandValidator.ValidationResult result = validator.validate("ls |;rm");
            assertFalse(result.isValid());
        }

        @Test
        void regexAlternationInsideQuotedSegment_shouldNotBeTreatedAsPipe() {
            String command = "cut -d: -f1,7 /etc/passwd "
                    + "| grep -Ev ':(/usr/sbin/nologin|/sbin/nologin|/bin/false|/usr/bin/false)' "
                    + "| grep -E ':(/bin/.*|/sbin/.*|/usr/bin/.*|/usr/sbin/.*|/usr/local/bin/.*)' "
                    + "| cut -d: -f1";
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Expected valid command with quoted regex alternation");
        }

        @Test
        void alternationInsideQuotedRegexWithoutPipe_shouldBeValid() {
            CommandValidator.ValidationResult result = validator.validate("grep -E 'foo|bar' /etc/passwd");
            assertTrue(result.isValid());
        }
    }

    // ========== Quote balancing ==========

    @Nested
    class QuoteBalancing {

        @Test
        void unbalancedSingleQuote_shouldBeInvalid() {
            CommandValidator.ValidationResult result = validator.validate("echo 'hello");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("引號不配對"));
        }

        @Test
        void unbalancedDoubleQuote_shouldBeInvalid() {
            CommandValidator.ValidationResult result = validator.validate("echo \"hello");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("引號不配對"));
        }

        @Test
        void balancedQuotes_shouldBeValid() {
            CommandValidator.ValidationResult result = validator.validate("echo 'hello world'");
            assertTrue(result.isValid());
        }

        @Test
        void escapedQuote_shouldNotCount() {
            // A backslash-escaped quote shouldn't count toward balance
            CommandValidator.ValidationResult result = validator.validate("echo \\'hello\\'");
            assertTrue(result.isValid());
        }
    }

    // ========== High-risk commands ==========

    @Nested
    class HighRiskCommands {

        @ParameterizedTest
        @ValueSource(strings = {
            "rm -rf /data/test",
            "rmdir /data/old",
            "mv /tmp/a /tmp/b",
            "cp -a /tmp/a /tmp/b",
            "rsync -a /tmp/a /tmp/b",
            "tar -czf /tmp/a.tgz /tmp/a",
            "zip -r /tmp/a.zip /tmp/a",
            "unzip /tmp/a.zip -d /tmp/a",
            "systemctl restart nginx",
            "service nginx restart",
            "chmod 600 /tmp/a",
            "chown root:root /tmp/a",
            "usermod -aG sudo testuser",
            "groupdel oldgroup",
            "passwd testuser",
            "mount /dev/sdb1 /mnt",
            "crontab -r",
            "apt install nginx -y",
            "yum update -y",
            "dnf upgrade -y"
        })
        void highRiskCommands_shouldRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertTrue(result.requiresConfirmation(), "Should require confirmation: " + command);
        }

        @Test
        void highRiskCommand_returnsCommandName() {
            CommandValidator.ValidationResult result = validator.validate("rm -rf /data/test");
            assertEquals("rm", result.getCommandName());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "rm\t-rf /data/test",
                "/bin/rm -rf /data/test",
                "env rm -rf /data/test",
                "sudo -u root rm -rf /data/test",
                "sudo -iu root rm -rf /data/test",
                "sudo -iuroot rm -rf /data/test",
                "command -p rm -rf /data/test",
                "time -p rm -rf /data/test"
        })
        void wrappedOrTabbedRm_shouldStillRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertTrue(result.requiresConfirmation(), "Should require confirmation: " + command);
            assertEquals("rm", result.getCommandName());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "dd if=/dev/zero of=/tmp/test",
                "mkfs.ext4 /dev/sdb1",
                "fdisk /dev/sdb",
                "reboot",
                "shutdown -h now",
                "halt",
                "poweroff",
                "init 6",
                "iptables -L",
                "ip6tables -L",
                "ufw deny 22",
                "sudo iptables -L",
                "env ip6tables -S",
                "sudo reboot",
                "systemctl reboot",
                "systemctl poweroff",
                "systemctl --job-mode replace-irreversibly reboot",
                "bash -lc \"reboot\"",
                "sudo -iu alice bash -lc \"id\"",
                "sudo -iualice bash -lc \"id\"",
                "sh -c 'docker stop web'",
                "python3 -c \"__import__('os').system('id')\"",
                "perl -e 'system(\"id\")'",
                "node -e \"require('child_process').exec('id')\""
        })
        void blockedCommands_shouldBeRejectedDirectly(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertFalse(result.isValid(), "Should reject: " + command);
            assertFalse(result.requiresConfirmation(), "Should not enter confirmation flow: " + command);
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().contains("命令已停用"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "docker rm web",
                "docker stop web",
                "docker restart web",
                "docker start web",
                "docker create --name web nginx:latest",
                "docker run -d --name web nginx:latest",
                "docker exec -it web sh",
                "docker --context prod stop web",
                "docker --host tcp://127.0.0.1:2375 restart web",
                "docker container stop web",
                "docker container start web",
                "docker container restart web",
                "docker container kill web",
                "docker pull nginx:latest",
                "docker build -t myapp .",
                "docker system prune -f",
                "docker compose -f docker-compose.yml down",
                "docker compose up -d",
                "docker volume rm data",
                "docker compose down"
        })
        void dockerHighRiskCommands_shouldRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertTrue(result.requiresConfirmation(), "Should require confirmation: " + command);
            assertNotNull(result.getCommandName());
            assertTrue(result.getCommandName().startsWith("docker"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "docker\tstop web",
                "/usr/bin/docker stop web",
                "env docker stop web",
                "sudo /usr/bin/docker restart web",
                "command -p /usr/bin/docker stop web",
                "time -p /usr/bin/docker restart web",
                "docker 'stop' web",
                "docker \"restart\" web"
        })
        void wrappedOrTabbedDockerRiskCommands_shouldStillRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertTrue(result.requiresConfirmation(), "Should require confirmation: " + command);
            assertNotNull(result.getCommandName());
            assertTrue(result.getCommandName().startsWith("docker"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "sh -c 'docker stop web'",
                "bash -lc \"docker run -d --name web nginx:latest\"",
                "sudo -iu alice bash -lc \"docker run -d --name web nginx:latest\"",
                "zsh -c \"docker compose up -d\"",
                "dash -c '/usr/bin/docker restart web'"
        })
        void shellWrappedCommands_shouldBeRejected(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertFalse(result.isValid(), "Should reject: " + command);
            assertFalse(result.requiresConfirmation(), "Should not require confirmation: " + command);
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().contains("命令已停用"));
        }

        @Test
        void heredocShellWrapper_shouldBeRejected() {
            CommandValidator.ValidationResult result = validator.validate("bash -i <<< 'id'");
            assertFalse(result.isValid());
            assertFalse(result.requiresConfirmation());
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().contains("heredoc"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "docker ps",
                "docker images",
                "docker info",
                "docker logs web",
                "docker inspect web",
                "docker --context prod ps",
                "docker container ls",
                "docker image ls",
                "docker compose ps",
                "docker compose -f docker-compose.yml ps"
        })
        void dockerNonHighRiskCommands_shouldNotRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertFalse(result.requiresConfirmation(), "Should not require confirmation: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "systemctl status nginx",
                "systemctl --no-pager status nginx",
                "systemctl list-units --type=service",
                "service nginx status"
        })
        void systemdReadOnlyCommands_shouldNotRequireConfirmation(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertTrue(result.isValid(), "Should be valid: " + command);
            assertFalse(result.requiresConfirmation(), "Should not require confirmation: " + command);
        }
    }

    // ========== Protected paths ==========

    @Nested
    class ProtectedPaths {

        @ParameterizedTest
        @ValueSource(strings = {
            "rm -rf /",
            "rm -rf /etc",
            "rm -rf /bin",
            "rm /usr",
            "rm -rf /home",
            "rm -rf /var",
            "rmdir /boot",
            "rm -rf /etc/",   // trailing slash
            "rm -rf /etc/*",  // wildcard under protected path
            "rm -rf /var/../etc", // traversal
            "rm -rf /var/log/*",
            "rm -rf /tmp/test",
            "/bin/rm -rf /etc",
            "env rm -rf /var",
            "sudo rm -rf /tmp"
        })
        void protectedPaths_shouldBeRejected(String command) {
            CommandValidator.ValidationResult result = validator.validate(command);
            assertFalse(result.isValid(), "Should reject: " + command);
            assertTrue(result.getErrorMessage().contains("系統保護目錄"));
        }

        @Test
        void nonProtectedPath_shouldRequireConfirmation() {
            CommandValidator.ValidationResult result = validator.validate("rm -rf /srv/test");
            assertTrue(result.isValid());
            assertTrue(result.requiresConfirmation());
        }

        @Test
        void homeDescendantPath_shouldRequireConfirmation() {
            CommandValidator.ValidationResult result = validator.validate("rm -rf /home/liang/test.txt");
            assertTrue(result.isValid());
            assertTrue(result.requiresConfirmation());
        }

        @Test
        void quotedProtectedPathWithSpaces_shouldBeRejected() {
            CommandValidator.ValidationResult result = validator.validate("rm -rf \"/etc/my file.txt\"");
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("系統保護目錄"));
        }

        @Test
        void quotedSafePathWithSpaces_shouldRequireConfirmation() {
            CommandValidator.ValidationResult result = validator.validate("rm -rf \"/srv/my file.txt\"");
            assertTrue(result.isValid());
            assertTrue(result.requiresConfirmation());
        }

        @Test
        void wrapperOptionPath_shouldNotBeMistakenAsRmTarget() {
            CommandValidator.ValidationResult result = validator.validate("env -C /etc rm -rf /srv/test");
            assertTrue(result.isValid());
            assertTrue(result.requiresConfirmation());
        }

        @Test
        void relativeTraversalToProtectedPath_shouldBeRejected() {
            Path cwd = Paths.get("").toAbsolutePath().normalize();
            Path target = Paths.get("/etc");
            Path relative = cwd.relativize(target);
            String relativeArg = relative.toString();
            if (relativeArg.isBlank()) {
                relativeArg = ".";
            }
            CommandValidator.ValidationResult result = validator.validate("rm -rf " + relativeArg);
            assertFalse(result.isValid());
            assertTrue(result.getErrorMessage().contains("系統保護目錄"));
        }

        @Test
        void relativeSafePath_shouldStillRequireConfirmation() {
            Path cwd = Paths.get("").toAbsolutePath().normalize();
            Path safeTarget = Paths.get("/srv/test");
            String relativeArg = cwd.relativize(safeTarget).toString();
            CommandValidator.ValidationResult result = validator.validate("rm -rf " + relativeArg);
            assertTrue(result.isValid());
            assertTrue(result.requiresConfirmation());
        }
    }

    // ========== isHighRiskCommand ==========

    @Test
    void isHighRiskCommand_shouldReturnTrue_forRm() {
        assertTrue(validator.isHighRiskCommand("rm -rf /srv/foo"));
    }

    @Test
    void isHighRiskCommand_shouldReturnFalse_forLs() {
        assertFalse(validator.isHighRiskCommand("ls -la"));
    }

    @Test
    void isHighRiskCommand_shouldReturnTrue_forDockerRm() {
        assertTrue(validator.isHighRiskCommand("docker rm web"));
    }

    @Test
    void isHighRiskCommand_shouldReturnFalse_forBlockedCommand() {
        assertFalse(validator.isHighRiskCommand("iptables -L"));
        assertFalse(validator.isHighRiskCommand("reboot"));
    }

    @Test
    void isHighRiskCommand_shouldReturnTrue_forWrappedRm() {
        assertTrue(validator.isHighRiskCommand("env /bin/rm -rf /srv/foo"));
    }

    @Test
    void isHighRiskCommand_shouldReturnTrue_forWrappedDockerStop() {
        assertTrue(validator.isHighRiskCommand("sudo /usr/bin/docker stop web"));
    }

    @Test
    void isHighRiskCommand_shouldReturnFalse_forSystemctlStatus() {
        assertFalse(validator.isHighRiskCommand("systemctl status nginx"));
    }

    @Test
    void isHighRiskCommand_shouldReturnFalse_forShellWrappedDockerStop() {
        assertFalse(validator.isHighRiskCommand("sh -c 'docker stop web'"));
    }

    @Test
    void isHighRiskCommand_shouldReturnFalse_forNull() {
        assertFalse(validator.isHighRiskCommand(null));
    }

    // ========== getHighRiskCommands ==========

    @Test
    void getHighRiskCommands_shouldReturnDefensiveCopy() {
        var commands = validator.getHighRiskCommands();
        assertTrue(commands.contains("rm"));
        assertTrue(commands.contains("cp"));
        assertTrue(commands.contains("apt"));
        assertTrue(commands.contains("crontab"));
        assertTrue(commands.contains("chmod"));
        assertFalse(commands.contains("reboot"));
        assertFalse(commands.contains("iptables"));
        // Modifying returned set should not affect validator
        commands.clear();
        assertTrue(validator.getHighRiskCommands().contains("rm"));
    }

    @Test
    void getBlockedCommands_shouldReturnDefensiveCopy() {
        var commands = validator.getBlockedCommands();
        assertTrue(commands.contains("iptables"));
        assertTrue(commands.contains("reboot"));
        assertTrue(commands.contains("dd"));
        assertTrue(commands.contains("mkfs.*"));
        assertTrue(commands.contains("bash"));
        assertTrue(commands.contains("systemctl reboot"));
        commands.clear();
        assertTrue(validator.getBlockedCommands().contains("iptables"));
    }

    // ========== ValidationResult toString ==========

    @Test
    void validationResult_toString() {
        assertEquals("Valid", CommandValidator.ValidationResult.valid().toString());
        assertTrue(CommandValidator.ValidationResult.invalid("err").toString().contains("err"));
        assertTrue(CommandValidator.ValidationResult.requiresConfirmation("rm").toString().contains("rm"));
    }
}
