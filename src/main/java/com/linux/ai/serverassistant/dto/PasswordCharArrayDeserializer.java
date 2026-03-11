package com.linux.ai.serverassistant.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Arrays;

public class PasswordCharArrayDeserializer extends JsonDeserializer<char[]> {

    @Override
    public char[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        if (token != JsonToken.VALUE_STRING) {
            return (char[]) context.handleUnexpectedToken(char[].class, parser);
        }

        char[] source = parser.getTextCharacters();
        int offset = parser.getTextOffset();
        int length = parser.getTextLength();
        if (source == null) {
            return (char[]) context.handleUnexpectedToken(char[].class, parser);
        }
        return Arrays.copyOfRange(source, offset, offset + length);
    }
}
