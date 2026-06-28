# Findings

- Source project has independent workspace data layer: `WorkspaceEntity`, `WorkspaceDAO`, `WorkspaceRepository`, and `AppDatabase.workspaceDao`; target has already caught up on this foundation.
- Source `RepositoryModule` configures `ProotShellRunner` with explicit bind mounts for `/skills`, `/tool_outputs`, and `/upload`; target currently constructs `ProotShellRunner` with only `nativeLibraryDir`.
- Target terminal UI/session code is already close to source parity (`WorkspaceTerminalPage` + `WorkspaceTerminalSession` using `TerminalSession`/`TerminalView` + proot), so the main gap is runtime ecosystem parity rather than page-level implementation.
- Source `WorkspaceReminderTransformer` documents `/skills` usage and `/upload` as read-only; target currently only describes `/workspace`, available tools, and optional cwd.
- Source app startup runs `WorkspaceManager.cleanupAllTempDirs()` and `WorkspaceRepository.checkIntegrity()`; target `LastChatApp` currently does not call either.
- `FileFolders.UPLOAD` and `FileFolders.TOOL_OUTPUTS` already exist in target, which means bind-mount and tool-output parity can be added without introducing new storage constants.
- Target already wires `DocumentAsPromptTransformer` into chat and scheduled task pipelines, but its current implementation still reads documents into prompt text without the source project's `/upload/<file>` workspace path bridge.
- Source `GenerationHandler` persists oversized tool output into `tool_outputs` and points shell usage back to `/tool_outputs/...`; target now has a first parity pass for workspace-shell JSON output truncation/persistence, but it still needs compile/runtime verification.
- Target project currently has no `FilesManager` implementation under `data/files`, so the source project's `syncManagedFiles()` startup parity was intentionally not migrated as part of this pass.
