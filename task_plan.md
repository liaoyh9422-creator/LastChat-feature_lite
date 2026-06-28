# Workspace migration plan

## Goal
Migrate the workspace data-layer foundation from rikkahub-master into LastChat-feature_lite after the workspace module bootstrap is complete.

## Phases
- [complete] Phase 1: Inspect current DB/DI structure and source workspace data-layer files
- [complete] Phase 2: Add WorkspaceEntity and WorkspaceDAO to current project
- [complete] Phase 3: Register workspace table/DAO in AppDatabase and add DB migration
- [complete] Phase 4: Add WorkspaceRepository and DI providers (manager/installer/dao/repository)
- [in_progress] Phase 5: Bind Assistant/Conversation workspace fields and align cleanup logic
- [pending] Phase 6: Run Gradle validation and summarize remaining gaps

## Notes
- Current target project AppDatabase version is 39.
- Need a safe migration to create workspaces table, likely 39 -> 40 manual migration.
- This phase is data-layer only; UI/routes/viewmodels for workspaces come later.
