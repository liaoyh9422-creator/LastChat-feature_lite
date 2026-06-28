# Workspace terminal parity plan

## Goal
Close the remaining workspace terminal parity gaps between `rikkahub-master` and `LastChat-feature_lite`, with priority on shell bind mounts, workspace runtime guidance, startup integrity cleanup, and upload/tool output ecosystem alignment — without introducing destructive startup cleanup that conflicts with LastChat's draft attachment model.

## Phases
- [complete] Phase 1: Rebaseline plan/files against `rikkahub-master` and capture verified parity gaps
- [complete] Phase 2: Align `ProotShellRunner` bind mounts for `/skills`, `/tool_outputs`, and `/upload`
- [complete] Phase 3: Align workspace prompt guidance and upload path semantics used by transformers/tools
- [complete] Phase 4: Add app-start workspace cleanup/integrity checks (and source-aligned tool output cleanup if still applicable)
- [complete] Phase 5: Close remaining runtime parity gaps (`tool_outputs` persistence / terminal environment consistency)
- [pending] Phase 6: Run targeted validation/build and summarize residual gaps

## Notes
- Source baseline: `/storage/emulated/0/整理归档/rikkahub-master`
- Target repo already contains the terminal UI/session implementation; the current work is runtime ecosystem parity, not first-time terminal UI creation.
- `FileFolders.UPLOAD` and `FileFolders.TOOL_OUTPUTS` already exist in target, so bind-mount and transformer parity can be completed locally.
- Full completion still requires runtime validation evidence; until then, progress claims stay at code-written level.
