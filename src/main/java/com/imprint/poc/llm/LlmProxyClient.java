package com.imprint.poc.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.imprint.poc.model.CopilotSession;
import com.imprint.poc.model.SessionSummary;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the local LLM proxy (OpenAI-compatible /v1/chat/completions endpoint).
 *
 * Configure via environment variables:
 *   LLM_PROXY_URL   — base URL, e.g. http://localhost:8080  (default: http://localhost:8080)
 *   LLM_MODEL       — model name to request               (default: gpt-4o)
 *
 * The proxy is assumed to handle authentication to GitHub Models internally.
 */
public class LlmProxyClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String model;

    public LlmProxyClient() {
        // Virtual threads — non-blocking I/O without thread pool overhead
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .executor(Runnable::run)  // virtual thread friendly
                .build();

        this.baseUrl = System.getenv().getOrDefault("LLM_PROXY_URL", "http://localhost:8080");
        this.model   = System.getenv().getOrDefault("LLM_MODEL", "gpt-4o");
    }

    /**
     * Sends a Copilot session to the LLM and returns a structured SessionSummary.
     * Returns null if the session is too short to be worth summarising.
     */
    public SessionSummary summarise(CopilotSession session) throws IOException, InterruptedException {
        var conversationText = session.conversationText();
        if (conversationText.length() < 100) return null; // too short, skip

        // Truncate to avoid token limit issues — ~12000 chars ≈ ~3000 tokens
        if (conversationText.length() > 12_000) {
            conversationText = conversationText.substring(0, 12_000) + "\n...[truncated]";
        }

        var prompt = buildPrompt(session, conversationText);
        var responseJson = callProxy(prompt);
        return parseResponse(responseJson, session);
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------
    private String buildPrompt(CopilotSession session, String conversationText) {
        return """
                You are analysing a GitHub Copilot chat session from a software engineer.
                Workspace: %s
                Session title: %s

                CONVERSATION:
                %s

                Extract the following and respond ONLY with a valid JSON object, no markdown, no preamble:
                {
                  "tasksCompleted":   ["..."],   // things that were finished in this session
                  "tasksInProgress":  ["..."],   // things started but not finished
                  "followUpItems":    ["..."],   // explicit next steps or TODOs mentioned
                  "decisionsMade":    ["..."],   // technical or design decisions reached
                  "timeCategory":     "...",     // one of: coding, debugging, code-review, architecture, other
                  "rawSummary":       "..."      // one paragraph, plain English summary
                }
                If a list is empty return []. Do not add any text outside the JSON object.
                """.formatted(session.workspacePath(), session.customTitle(), conversationText);
    }

    // -------------------------------------------------------------------------
    // HTTP call — OpenAI-compatible /v1/chat/completions
    // -------------------------------------------------------------------------
    private String callProxy(String userPrompt) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 1000);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(60))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("LLM proxy returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

    // -------------------------------------------------------------------------
    // Parse the proxy response into a SessionSummary
    // -------------------------------------------------------------------------
    private SessionSummary parseResponse(String rawResponse, CopilotSession session)
            throws IOException {
        JsonNode root = MAPPER.readTree(rawResponse);

        // Extract content from choices[0].message.content
        String content = root.path("choices").path(0)
                             .path("message").path("content").asText("");

        // Strip possible markdown fences the model might add despite instructions
        content = content.strip();
        if (content.startsWith("```")) {
            content = content.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }

        JsonNode parsed = MAPPER.readTree(content);

        return new SessionSummary(
                session.sessionId(),
                session.workspacePath(),
                session.customTitle(),
                toList(parsed.get("tasksCompleted")),
                toList(parsed.get("tasksInProgress")),
                toList(parsed.get("followUpItems")),
                toList(parsed.get("decisionsMade")),
                parsed.path("timeCategory").asText("other"),
                parsed.path("rawSummary").asText("")
        );
    }

    private List<String> toList(JsonNode node) {
        var result = new ArrayList<String>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                var text = n.asText("").strip();
                if (!text.isEmpty()) result.add(text);
            });
        }
        return result;
    }
}
