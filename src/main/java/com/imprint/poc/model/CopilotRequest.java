package com.imprint.poc.model;

/**
 * One request/response turn inside a CopilotSession.
 */
public record CopilotRequest(
        String requestId,
        String userMessage,
        String assistantResponse,   // markdown text, tool calls stripped
        String modelId              // e.g. "gpt-4o", "claude-3.5-sonnet"
) {}
