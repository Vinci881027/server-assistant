package com.linux.ai.serverassistant.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTokenizerTest {

    private CommandTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new CommandTokenizer();
    }

    @Test
    void unquotedHereString_shouldBeDetected() {
        assertTrue(tokenizer.containsUnquotedHereDocOperator("bash -i <<< 'id'"));
    }

    @Test
    void unquotedHereDoc_shouldBeDetected() {
        assertTrue(tokenizer.containsUnquotedHereDocOperator("cat <<EOF"));
        assertTrue(tokenizer.containsUnquotedHereDocOperator("cat <<-EOF"));
    }

    @Test
    void quotedOperators_shouldNotBeDetected() {
        assertFalse(tokenizer.containsUnquotedHereDocOperator("echo '<<'"));
        assertFalse(tokenizer.containsUnquotedHereDocOperator("echo \"<<<\""));
    }

    @Test
    void escapedOperators_shouldNotBeDetected() {
        assertFalse(tokenizer.containsUnquotedHereDocOperator("echo \\<\\<\\<"));
    }

    @Test
    void nonAdjacentInputRedirection_shouldNotBeDetected() {
        assertFalse(tokenizer.containsUnquotedHereDocOperator("cat < /tmp/file"));
        assertFalse(tokenizer.containsUnquotedHereDocOperator("cat < <(wc -l /tmp/file)"));
    }
}
