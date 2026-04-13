# Imprint — Software Requirements Specification

**Product tagline:** Your work, recorded.

**Source:** Derived from *Imprint-POC.docx* (Phase 1 — Copilot Transcript POC).  
**Author (POC doc):** Santhosh — VP Software Engineering 
**Date:** April 13, 2026  
**Classification:** Internal / Confidential  

This document restates the POC as implementable requirements and extends them with explicit acceptance criteria, interfaces, and traceability to the described architecture.

---

## 1. Purpose and scope

### 1.1 Purpose

Imprint is a **Java-based automated work tracker** that ingests digital exhaust from an engineer’s working day (starting with GitHub Copilot chat transcripts), uses an LLM to extract structured work items, and produces **durable, timestamped summaries** suitable for manager conversations and promotion evidence—with **minimal or zero manual input** at steady state.

### 1.2 In scope (this SRS)

- **Phase 1 (mandatory detail):** Copilot transcript discovery, parsing (legacy JSON and JSONL), optional LLM extraction via a local OpenAI-compatible proxy, console and Markdown output, error handling and operational modes.
- **Phases 2–4 (summary):** Microsoft Graph (Outlook, Teams), merged timeline, scheduling, OneDrive, promotion-oriented weekly rollup—as stated in the POC roadmap.

### 1.3 Out of scope (Phase 1)

- Server-side hosting, multi-user SaaS, or central database.
- Direct calls to Anthropic or other cloud LLM APIs from the JAR (corporate constraint: proxy only).
- macOS/Linux parity unless explicitly added later (`%APPDATA%` and paths are Windows-centric in Phase 1).

---

## 2. Definitions and references

| Term | Definition |
|------|------------|
| **Session** | One Copilot chat thread, identified by UUID in filename, with metadata and ordered user/assistant turns. |
| **Mutation log** | VS Code v1.109+ `.jsonl` append-only sequence of operations replayed to reconstruct session JSON. |
| **Workspace** | VS Code workspace folder; MD5 hash directory under `workspaceStorage` maps to human path via `workspace.json`. |
| **LLM proxy** | HTTP service exposing OpenAI-compatible `POST /v1/chat/completions`; handles upstream auth (e.g. GitHub Models). |
| **Dry run** | Parse and inventory only; no HTTP calls to the LLM proxy. |

**Canonical storage (Phase 1):**  
`%APPDATA%\Code\User\workspaceStorage\<hash>\chatSessions\` and  
`%APPDATA%\Code\User\globalStorage\emptyWindowChatSessions\`.

**Note:** `state.vscdb` (SQLite) is metadata-only and **must not** be used as the primary ingestion source.

---

## 3. Stakeholders and users

| Stakeholder | Interest |
|-------------|----------|
| Individual engineer (VP/senior IC) | Accurate end-of-day/week record without manual reconstruction. |
| Engineering manager | Crisp 1:1s grounded in evidence (tasks, decisions, follow-ups, time mix). |
| HR / promotion process | Continuous, timestamped evidence of scope and impact. |
| Corporate IT / security | No admin install, user-scope execution, delegated OAuth in later phases, no unnecessary data exfiltration beyond configured outputs. |

---

## 4. System context (high level)

```text
[VS Code Copilot files] ──read──► [Imprint JAR]
                                        │
                                        ├──► (optional) [LLM proxy] ──► upstream model
                                        │
                                        └──► %USERPROFILE%\Imprint\*.md (+ stdout)
```

Future context (Phases 2–3): Outlook and Teams via **Microsoft Graph** and **MSAL4J** (device code flow), merged into one chronological timeline before summarization/reporting.

---

## 5. Design constraints (non-negotiable)

| ID | Constraint | Requirement |
|----|------------|-------------|
| C-01 | Windows, no admin privileges | Deliver a **portable JDK** model and **user-scope** Task Scheduler jobs (`schtasks`); no machine-wide install requirement for Phase 4. |
| C-02 | No direct Anthropic API from app | All LLM traffic goes to **configurable base URL** (`LLM_PROXY_URL`); default `http://localhost:8080`. |
| C-03 | No MCP for Outlook/Teams | Use **Microsoft Graph REST** + **MSAL4J** OAuth2 device code flow in Phases 2–3. |
| C-04 | No database (Phase 1–3 per POC) | Persist outputs as **JSON + Markdown** under `%USERPROFILE%\Imprint\`; optional OneDrive via Graph in Phase 4. |
| C-05 | Reliability for promotion-critical use | **Batch execution** (not a daemon); **dry-run** flag; **per-session** error isolation so one bad file does not abort the run. |

---

## 6. Technology requirements

| Component | Requirement |
|-----------|-------------|
| Language / runtime | **Java 25** (POC also states compatibility with Java 21+); use modern features where appropriate (records, sealed types, virtual threads as applicable). |
| Build | **Maven**; **maven-shade-plugin** produces a **single fat JAR** named `imprint-poc.jar` (finalName `imprint-poc`). |
| JSON | **Jackson** (`jackson-databind`, `jackson-datatype-jsr310`) for `.json` and `.jsonl`. |
| HTTP (LLM) | **`java.net.http.HttpClient`** only; OpenAI-compatible JSON request/response. |
| File discovery | **`java.nio.file`** (`Files`, `Path`, directory listing); optional future: `WatchService` for file watcher pipeline. |
| Scheduling (later) | Windows **Task Scheduler**; no always-on process required by architecture. |

---

## 7. Functional requirements — Phase 1

### 7.1 Workspace discovery (FR-DISC)

**FR-DISC-1:** The system SHALL resolve the VS Code user data root using the **`APPDATA`** environment variable and path segments `Code\User`.

**FR-DISC-2:** The system SHALL enumerate all subdirectories of `workspaceStorage` that contain a `chatSessions` directory.

**FR-DISC-3:** For each such workspace hash directory, the system SHALL attempt to read `workspace.json` and extract the `folder` field.

**FR-DISC-4:** The system SHALL normalize `folder` for display by stripping a leading `file:///` prefix and converting `/` to `\` on Windows.

**FR-DISC-5:** If `workspace.json` is missing or unreadable, the workspace label SHALL be **`unknown`**.

**FR-DISC-6:** The system SHALL include `globalStorage\emptyWindowChatSessions` when present, with workspace label **`[no workspace]`**.

**FR-DISC-7:** If `%APPDATA%` is unset, the system SHALL fail fast with a clear error indicating a non-Windows or misconfigured environment.

**FR-DISC-8:** Discovery SHALL be logged sufficiently to diagnose IT policy redirects (e.g. log root path used for search).

### 7.2 Session file selection and deduplication (FR-FILE)

**FR-FILE-1:** For each `chatSessions` directory, the system SHALL consider files ending in `.json` and `.jsonl`.

**FR-FILE-2:** Session identity SHALL be the **filename without extension** (UUID).

**FR-FILE-3:** When both `<uuid>.json` and `<uuid>.jsonl` exist, **`.jsonl` SHALL take precedence** (align with VS Code behavior).

**FR-FILE-4:** Unreadable or invalid session files SHALL produce a **[WARN]** message including UUID and error summary; processing SHALL continue for other sessions.

### 7.3 JSON snapshot parsing — pre VS Code v1.109 (FR-JSON)

**FR-JSON-1:** The parser SHALL load the full session object from `.json` using Jackson into the shared session builder (see §7.5).

### 7.4 JSONL mutation log parsing — v1.109+ (FR-JSONL)

**FR-JSONL-1:** Each non-empty line SHALL be parsed as a JSON object with at least `kind` (integer).

**FR-JSONL-2:** The system SHALL replay operations in file order into an in-memory structure equivalent to the session JSON object:

| kind | Semantics |
|------|-----------|
| 0 | **Seed:** `value` is an object whose fields populate the session state map. |
| 1 | **Set field:** `key` → `value`. |
| 2 | **Array push:** append `value` to the array named by `key`; create array if absent. |
| 3 | **Delete field:** remove `key` from state. |

**FR-JSONL-3:** Unknown `kind` values SHALL be ignored (no fatal error for forward compatibility).

**FR-JSONL-4:** After replay, the system SHALL synthesize a JSON object from the state map and pass it to the same builder as `.json`.

### 7.5 Session object model (FR-MODEL)

**FR-MODEL-1:** From the reconstructed/snapshot JSON root, the system SHALL read:

- `sessionId` (fallback: source filename),
- `customTitle` (fallback: `"Untitled"`),
- `creationDate` as `Instant` (numeric epoch ms, ISO-8601 text, or `Instant.EPOCH` if unknown/invalid),
- `requests` array.

**FR-MODEL-2:** For each request element, the system SHALL extract:

- `requestId`,
- **User message** from `message` (plain string or object with `value`),
- **Assistant text** from `response` array: concatenate textual parts; **skip** entries that have `toolId` or `toolInvocationSerialized`,
- **Model id** from `modelId`, else `agent`, else `"unknown"`.

**FR-MODEL-3:** Turns with **blank user message** SHALL be excluded from the in-memory turn list.

**FR-MODEL-4:** If after parsing there are **no user messages**, the session SHALL be **skipped** (no `CopilotSession` emitted, or null — equivalent to “stub/empty session”).

**FR-MODEL-5:** `CopilotSession` SHALL retain the absolute path to the source file for traceability.

**FR-MODEL-6:** `conversationText()` SHALL concatenate turns as labeled `USER:` / `ASSISTANT:` blocks suitable for LLM input.

### 7.6 Date filtering (FR-FILTER)

**FR-FILTER-1:** Default mode SHALL process only sessions whose `creationDate` maps to **today’s local date** (`ZoneId.systemDefault()`).

**FR-FILTER-2:** If `creationDate` is `Instant.EPOCH` (unknown), the session **SHALL be included** in the “today” filter (per POC behavior: do not drop unknown-dated sessions silently in default mode).

**FR-FILTER-3:** A CLI flag **`--all`** SHALL disable the date filter and include all parsed sessions.

### 7.7 CLI and runtime modes (FR-CLI)

**FR-CLI-1:** Entry point SHALL be `com.imprint.poc.ImprintPoc` (main class in shaded JAR).

**FR-CLI-2:** Supported flags:

| Flag | Behavior |
|------|----------|
| *(none)* | Today-only sessions |
| `--all` | All sessions |
| `--dry-run` | Parse + inventory only; **no** LLM HTTP calls |

**FR-CLI-3:** On startup, the system SHALL print a banner including mode (DRY RUN vs FULL) and filter (ALL vs TODAY).

**FR-CLI-4:** If no workspaces/sessions exist, the system SHALL print guidance including the expected path pattern under `%APPDATA%\Code\User\workspaceStorage\<hash>\chatSessions\`.

**FR-CLI-5:** After parsing, if the filtered set is empty, the system SHALL suggest running with `--all`.

**FR-CLI-6:** Per-workspace parse failures SHALL increment a **skipped workspace** counter; other workspaces continue.

### 7.8 LLM integration (FR-LLM)

**FR-LLM-1:** Environment variables:

| Variable | Purpose | Default |
|----------|---------|---------|
| `LLM_PROXY_URL` | Base URL for OpenAI-compatible API | `http://localhost:8080` |
| `LLM_MODEL` | Model name in request body | `gpt-4o` |

**FR-LLM-2:** If `conversationText()` length **&lt; 100** characters, the client SHALL **not** call the proxy and SHALL treat the session as skipped (“too short”).

**FR-LLM-3:** If conversation text **&gt; 12,000** characters, the system SHALL truncate to 12,000 characters and append a clear `...[truncated]` marker.

**FR-LLM-4:** HTTP request SHALL be `POST {baseUrl}/v1/chat/completions` with `Content-Type: application/json`, body including `model`, `max_tokens` (1000), and a single **user** message containing the structured extraction prompt.

**FR-LLM-5:** HTTP client SHALL use a **30s** connect timeout and **60s** request timeout (per POC).

**FR-LLM-6:** Non-200 responses SHALL surface as failures with status code and body snippet; **per-session** failure must not stop other sessions.

**FR-LLM-7:** Response parsing SHALL read `choices[0].message.content`, strip whitespace, strip optional **markdown fences** around JSON if present, then parse inner JSON.

**FR-LLM-8:** The LLM SHALL be instructed to return **only** a JSON object with these keys (arrays may be empty):

- `tasksCompleted` — finished in this session  
- `tasksInProgress` — started, not finished  
- `followUpItems` — explicit next steps / TODOs for the user  
- `decisionsMade` — technical or design decisions  
- `timeCategory` — one of: `coding`, `debugging`, `code-review`, `architecture`, `other`  
- `rawSummary` — one paragraph plain English  

**FR-LLM-9:** Parsed `SessionSummary` SHALL map `sessionTitle` from the session’s `customTitle`, and preserve `sessionId` and `workspacePath`.

**FR-LLM-10:** Missing or non-array list fields SHALL deserialize as empty lists; missing `timeCategory` SHALL default to `other`; missing `rawSummary` SHALL default to empty string.

### 7.9 Reporting (FR-REPORT)

**FR-REPORT-1:** The system SHALL print a **session inventory** before LLM processing: title (truncated), workspace (truncated), turn count, formatted creation timestamp or “date unknown”.

**FR-REPORT-2:** After summaries are produced, the system SHALL print a **daily work summary** to stdout with sections per session: category, summary, completed, in progress, follow-ups, decisions.

**FR-REPORT-3:** The system SHALL print a **time category breakdown** (count of sessions per `timeCategory`).

**FR-REPORT-4:** The system SHALL write Markdown to:

`%USERPROFILE%\Imprint\imprint-YYYY-MM-DD.md`  

where `YYYY-MM-DD` is the **run date** (`LocalDate` used in POC for naming).

**FR-REPORT-5:** Markdown structure SHALL include:

- H1: title and date  
- Per session: H2 title, bold workspace and category, narrative summary, optional H3 sections: Completed, In Progress, Follow-ups, Decisions  
- Horizontal rule between sessions  

**FR-REPORT-6:** If the output directory cannot be created or the file cannot be written, the system SHALL log **[WARN]** with reason; console output may still succeed.

---

## 8. Non-functional requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-01 | Portability | Single fat JAR runnable with `java -jar`; no VS Code dependency at runtime. |
| NFR-02 | Security | Phase 1 reads only user-local VS Code data and sends session text only to user-configured proxy; document data handling for corporate review. |
| NFR-03 | Performance | Batch run should complete in “interactive wait” for typical day (POC Phase 3 target: full end-to-day under **2 minutes** for full system; Phase 1 should be proportionally fast). |
| NFR-04 | Observability | Clear stdout/stderr distinction; WARN for recoverable parse issues; ERROR for workspace-level failures. |
| NFR-05 | Maintainability | Parser isolated from LLM client; models as records; minimal dependencies. |

---

## 9. Phase 2–4 requirements (summary)

### Phase 2 — Outlook + calendar (weeks 3–6)

- Integrate **Microsoft Graph Java SDK v6** with **MSAL4J** delegated OAuth (device code flow), silent refresh, user token cache.  
- Ingest **emails** and **calendar events** per POC architecture table.  
- Success criterion (POC): weekly report **matches manual validation** for a given day.

### Phase 3 — Teams + merged context (weeks 7–10)

- Ingest **Teams meeting transcripts** (Graph transcript APIs) and/or chat where transcripts unavailable.  
- **Context assembler** merges Copilot, Outlook, Teams into **one chronological timeline**.  
- **Daily digest + weekly rollup** Markdown reports.

### Phase 4 — Automation + promotion package (weeks 11–14)

- **Task Scheduler**: daily **6 PM** and Friday **5 PM** jobs.  
- **OneDrive upload** via Graph (optional path).  
- **Promotion evidence** framing: scope/impact narrative in weekly output.  
- Success criterion (POC): weekly report usable in manager 1:1 **without heavy editing**.

---

## 10. Acceptance criteria — Phase 1

1. **AC1 — Dry run on corporate Windows:** `java -jar imprint-poc.jar --dry-run --all` produces a **non-empty session inventory** when the machine has Copilot history, without LLM calls.  
2. **AC2 — Format coverage:** At least one `.json` and one `.jsonl` session (if available) both yield valid `CopilotSession` records with non-zero user turns when content exists.  
3. **AC3 — Dedup:** For the same UUID, `.jsonl` is chosen over `.json` when both exist.  
4. **AC4 — LLM path:** With proxy up, full run produces `SessionSummary` objects and a Markdown file under `%USERPROFILE%\Imprint\` for the run date.  
5. **AC5 — Resilience:** Intentionally corrupt one session file; run completes with WARN for that UUID; other sessions still processed.  
6. **AC6 — Short session:** Session with &lt;100 chars conversation text is skipped without HTTP call.

---

## 11. Risks and mitigations (requirements traceability)

| Risk | Req / behavior |
|------|----------------|
| Endpoint security blocks `%APPDATA%\Code\` reads | FR-CLI dry-run + FR-DISC logging; validate before Phase 2 spend. |
| JSONL format changes | FR-JSONL-3, FR-FILE-4 per-session isolation; consider future format version probe. |
| LLM returns non-JSON | FR-LLM-7 fence stripping; FR-LLM-6 per-session errors. |
| Azure AD app registration blocked | Phase 2 dependency; document delegated read-only permission set early. |
| Teams transcripts disabled | Phase 3 fallback to chat messages via `/me/chats` (POC). |

---

## 12. Immediate validation checklist (Phase 1)

1. Run **`--dry-run`** on target corporate machine; confirm inventory.  
2. Confirm **LLM proxy** reachable at `LLM_PROXY_URL`.  
3. Run **`--all`** full run; review Markdown accuracy vs memory.  
4. Confirm **Azure AD self-service app registration** policy for JPMC tenant (gates Phase 2).  
5. If Phase 1 passes, begin Phase 2 Graph integration per §3 architecture in POC.

---

## 13. Traceability matrix (POC sections → this SRS)

| POC section | SRS sections |
|-------------|----------------|
| Executive summary / problem | §1, §3 |
| Architecture / pipeline | §4, §9 |
| Constraints | §5 |
| Technology stack | §6 |
| Storage format / JSONL kinds | §7.4, §2 |
| Running the POC / CLI | §7.7 |
| Failure modes | §7.2–7.8, §11 |
| Source / packages | §6, §7 |
| Roadmap / success criteria | §9, §10 |
| Risks | §11 |

---

*End of document.*
