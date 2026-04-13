# Imprint — Architecture diagrams

This document uses [Mermaid](https://mermaid.js.org/) (renders in GitHub, many IDEs, and Markdown preview). It reflects the POC / requirements: **Phase 1** is Copilot-only; **Phases 2–4** add Microsoft Graph, merging, and scheduling.

---

## 1. High-level system context (end-state vision)

Context for all phases: local execution, no central server in the baseline design.

```mermaid
flowchart TB
    subgraph Sources["Data sources (digital exhaust)"]
        CP["GitHub Copilot chat<br/>%APPDATA%\\Code\\User\\…\\chatSessions"]
        OL["Microsoft Outlook<br/>Graph: mail, calendar"]
        TM["Microsoft Teams<br/>Graph: transcripts / chat"]
    end

    subgraph Runtime["On-device runtime"]
        IMP["Imprint<br/>(fat JAR, batch)"]
    end

    subgraph External["External services"]
        PROXY["LLM proxy<br/>(OpenAI-compatible)"]
        LLM["Upstream model<br/>(e.g. via GitHub Models)"]
        GRAPH["Microsoft Graph API"]
    end

    subgraph Outputs["Outputs"]
        MD["Markdown reports<br/>%USERPROFILE%\\Imprint\\"]
        OD["Optional: OneDrive<br/>(Graph upload)"]
    end

    CP -->|read files| IMP
    OL -->|OAuth + REST| GRAPH
    TM -->|OAuth + REST| GRAPH
    GRAPH -->|fetch| IMP
    IMP -->|HTTP POST /v1/chat/completions| PROXY
    PROXY --> LLM
    IMP --> MD
    IMP -.->|Phase 4| OD
```

---

## 2. Phase 1 — logical components (inside the JAR)

```mermaid
flowchart LR
    subgraph Entry["Entry"]
        MAIN["ImprintPoc<br/>(main)"]
    end

    subgraph Parse["Ingestion & parse"]
        WD["WorkspaceDiscovery"]
        SP["SessionParser<br/>.json + .jsonl"]
    end

    subgraph Model["Domain models"]
        CS["CopilotSession"]
        CR["CopilotRequest"]
        SS["SessionSummary"]
    end

    subgraph LLM["Summarization"]
        LLC["LlmProxyClient"]
    end

    subgraph IO["I/O"]
        FS[("VS Code<br/>session files")]
        OUT[("~/Imprint<br/>*.md")]
    end

    MAIN --> WD
    WD --> FS
    WD --> SP
    SP --> FS
    SP --> CS
    CS --> CR
    MAIN --> LLC
    LLC --> SS
    MAIN --> OUT
```

---

## 3. Full pipeline (roadmap) — layered view

Matches the POC processing pipeline; **Phase 1** implements the left branch through LLM + report for Copilot only.

```mermaid
flowchart TB
    subgraph L1["1. Acquisition"]
        A1["File read / scheduled batch"]
        A2["Graph: Outlook + Teams (later)"]
    end

    subgraph L2["2. Normalize"]
        N1["SessionParser + WorkspaceDiscovery"]
        N2["Graph DTOs → timeline events (later)"]
    end

    subgraph L3["3. Assemble context"]
        C1["Per-source structures"]
        C2["Context assembler: single chronological timeline (Phase 3)"]
    end

    subgraph L4["4. Reason"]
        R1["LlmProxyClient (per session / per window)"]
    end

    subgraph L5["5. Publish"]
        P1["Console + Markdown digest"]
        P2["Weekly rollup + optional OneDrive (Phase 4)"]
    end

    L1 --> L2 --> L3 --> L4 --> L5
```

---

## 4. Sequence — Phase 1 full run (with LLM)

Assumes sessions exist for **today** (default) or **--all**; `--dry-run` stops after inventory.

```mermaid
sequenceDiagram
    autonumber
    actor User as User / Task Scheduler
    participant POC as ImprintPoc
    participant WD as WorkspaceDiscovery
    participant FS as Local filesystem
    participant SP as SessionParser
    participant LLM as LlmProxyClient
    participant PX as LLM proxy
    participant OUT as ~/Imprint/*.md

    User->>POC: java -jar imprint-poc.jar [flags]
    POC->>WD: discover()
    WD->>FS: list workspaceStorage/*/chatSessions
    WD->>FS: read workspace.json (per hash)
    WD->>FS: list globalStorage/emptyWindowChatSessions
    WD-->>POC: List of WorkspaceEntry

    loop Each workspace
        POC->>SP: parseAll(chatSessionsDir, label)
        SP->>FS: list *.json / *.jsonl
        alt UUID has .jsonl
            SP->>FS: read .jsonl, replay mutations
        else UUID has .json only
            SP->>FS: read .json snapshot
        end
        SP-->>POC: List of CopilotSession (skip empty)
    end

    POC->>POC: filter by date unless --all
    POC->>User: print session inventory

    alt --dry-run
        POC-->>User: exit (no LLM)
    else full run
        loop Each CopilotSession
            POC->>LLM: summarise(session)
            alt conversation too short
                LLM-->>POC: null (skip HTTP)
            else
                LLM->>PX: POST /v1/chat/completions
                PX-->>LLM: choices[0].message.content (JSON)
                LLM->>LLM: strip fences, parse SessionSummary
                LLM-->>POC: SessionSummary
            end
        end
        POC->>User: print daily summary + category breakdown
        POC->>OUT: write imprint-YYYY-MM-DD.md
    end
```

---

## 5. Sequence — per-session LLM extraction (detail)

```mermaid
sequenceDiagram
    participant SP as SessionParser (prior step)
    participant CS as CopilotSession
    participant LLC as LlmProxyClient
    participant PX as LLM proxy
    participant M as Upstream model

    SP-->>CS: built session (turns, title, dates)
    CS->>LLC: summarise(session)
    LLC->>CS: conversationText()
    CS-->>LLC: USER/ASSISTANT transcript

    alt transcript shorter than 100 chars
        LLC-->>CS: return null
    else transcript longer than 12000 chars
        LLC->>LLC: truncate plus marker
    end

    LLC->>LLC: buildPrompt(workspace, title, text)
    LLC->>PX: HttpClient POST JSON<br/>model, max_tokens, messages
    PX->>M: forward (auth at proxy)
    M-->>PX: completion
    PX-->>LLC: OpenAI-shaped JSON body
    LLC->>LLC: extract content; strip ```json fences
    LLC->>LLC: readTree → SessionSummary record
    LLC-->>CS: SessionSummary (or throw per session)
```

---

## 6. Sequence — error isolation (workspace / session / LLM)

```mermaid
sequenceDiagram
    participant POC as ImprintPoc
    participant WD as WorkspaceDiscovery / parser
    participant LLM as LlmProxyClient

    POC->>WD: parse workspace W1
    WD-->>POC: OK, sessions

    POC->>WD: parse workspace W2
    WD-->>POC: throws
    Note over POC: log [ERROR], skipped++, continue

    POC->>WD: parse session file (bad UUID)
    WD-->>POC: [WARN] skip file, continue

    POC->>LLM: summarise(session S1)
    LLM-->>POC: HTTP 502 / invalid JSON
    Note over POC: log failure, continue S2…Sn
```

---

## 7. Future — Phase 2 & 3 (Microsoft Graph + merged digest)

High-level only; OAuth is device-code flow with MSAL4J, token cache on disk.

```mermaid
sequenceDiagram
    actor User
    participant IMP as Imprint (batch)
    participant MSAL as MSAL4J
    participant G as Microsoft Graph
    participant ASM as Context assembler
    participant LLM as LlmProxyClient

    User->>IMP: scheduled run
    IMP->>MSAL: acquire / refresh token (delegated)
    MSAL-->>IMP: access token

    par Copilot (existing)
        IMP->>IMP: parse local sessions
    and Outlook
        IMP->>G: GET /me/messages, /me/calendarView
        G-->>IMP: mail + events
    and Teams
        IMP->>G: transcripts / chats
        G-->>IMP: messages
    end

    IMP->>ASM: merge to chronological timeline
    ASM-->>IMP: unified day window
    IMP->>LLM: summarization windows (batched policy TBD)
    LLM-->>IMP: structured summaries
    IMP->>User: daily.md + weekly rollup
```

---

## 8. Deployment — Phase 4 scheduling (no admin baseline)

```mermaid
flowchart LR
    subgraph Win["Windows (user session)"]
        TS["Task Scheduler<br/>user task, logon"]
        JDK["Portable JDK<br/>%USERPROFILE%"]
        JAR["imprint-poc.jar<br/>or future imprint.jar"]
    end

    TS -->|"trigger daily 6PM / Fri 5PM"| JDK
    JDK -->|java -jar| JAR
    JAR -->|read| VS["VS Code AppData"]
    JAR -->|optional HTTP| PROXY["LLM proxy"]
    JAR -->|write| OUT["%USERPROFILE%\\Imprint"]
```

---

## Diagram index

| # | Type | What it shows |
|---|------|----------------|
| 1 | Flow | End-state data sources, Graph, proxy, outputs |
| 2 | Flow | Phase 1 packages inside the JAR |
| 3 | Flow | Roadmap pipeline layers |
| 4 | Sequence | Phase 1 run from CLI to Markdown |
| 5 | Sequence | Single-session summarization and HTTP |
| 6 | Sequence | Failure handling across workspaces / LLM |
| 7 | Sequence | Future Graph + merge + LLM |
| 8 | Flow | User-scope scheduling + portable JDK |

To edit: change Mermaid blocks in this file; keep `participant` names short for narrow layouts.
