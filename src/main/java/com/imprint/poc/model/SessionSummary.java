package com.imprint.poc.model;

import java.util.List;

/**
 * Structured output returned by the LLM proxy after analysing a session.
 */
public record SessionSummary(
        String sessionId,
        String workspacePath,
        String sessionTitle,
        List<String> tasksCompleted,
        List<String> tasksInProgress,
        List<String> followUpItems,     // things YOU need to do
        List<String> decisionsMade,
        String timeCategory,            // e.g. "coding", "debugging", "code-review"
        String rawSummary               // one-paragraph human summary
) {}
