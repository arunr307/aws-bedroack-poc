package com.example.bedrock.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single message in a conversation.
 *
 * <p>Maps to the Bedrock Converse API message schema:
 * <pre>{@code
 * {
 *   "role": "user" | "assistant",
 *   "content": "Hello, how are you?"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** Originator of the message. */
    private Role role;

    /** Plain-text message content. */
    private String content;

    public enum Role {
        USER("user"),
        ASSISTANT("assistant");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static Role from(String value) {
            for (Role r : values()) {
                if (r.value.equalsIgnoreCase(value)) return r;
            }
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }
}
