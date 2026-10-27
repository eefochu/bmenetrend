# Testing workflow

The user prefers the primary agent to delegate test authoring, execution and validation to a less powerful model through T3 Code's V2 `delegate_task` orchestration. Discover the live catalog with `orchestrator_capabilities` and choose an available lighter model, such as `gpt-6-luna`; set its advertised model options explicitly.

The delegated testing agent should execute its assigned tests directly, without recursively delegating them. Coordinate shared-checkout edits and Gradle runs so implementation agents do not overwrite test files or run conflicting builds. Preserve unrelated uncommitted work.
