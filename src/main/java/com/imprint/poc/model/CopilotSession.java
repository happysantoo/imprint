package com.imprint.poc.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents a single fully-reconstructed Copilot chat session.
 * Works for both .json (pre-v1.109) and .jsonl (v1.109+) after parsing.
 */
public record CopilotSession(
        String sessionId,
        String customTitle,
        Instant creationDate,
        String workspacePath,       // human-readable project path from workspace.json
        String sourceFile,          // absolute path to the .json or .jsonl file
        List<CopilotRequest> requests
) {
    /** Flat text of all user messages concatenated — used as LLM input */
    public String conversationText() {
        var sb = new StringBuilder();
        for (var req : requests) {
            sb.append("USER: ").append(req.userMessage()).append("\n");
            if (req.assistantResponse() != null && !req.assistantResponse().isBlank()) {
                sb.append("ASSISTANT: ").append(req.assistantResponse()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
