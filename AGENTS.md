# Testing workflow

The user prefers the primary agent to delegate test authoring, execution and validation to a less powerful model through T3 Code's V2 `delegate_task` orchestration. Their current selected target is **Gemini 3.8 Flash Medium in Antigravity** (`providerInstanceId: antigravity`, `model: gemini-3.8-flash-medium`). Discover the live catalog with `orchestrator_capabilities` and use that target when available; respect any newer user selection and set advertised model options explicitly. If the requested target is unavailable, report the capability mismatch before substituting a different model.

The delegated testing agent should execute its assigned tests directly, without recursively delegating them. Coordinate shared-checkout edits and Gradle runs so implementation agents do not overwrite test files or run conflicting builds. Preserve unrelated uncommitted work.
