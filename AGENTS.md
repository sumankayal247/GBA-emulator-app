# Agent instructions — GBA Emulator App

These instructions apply to every human and AI coding agent working in this repository, including Arena.ai agents.

## Required reading

Before planning or editing, read:
1. This file.
2. `docs/IMPLEMENTATION_PLAN.md`.
3. All relevant Architecture Decision Records under `docs/adr/`.
4. The affected source, tests, and existing project conventions.

If the implementation conflicts with the plan, do not silently choose one. Explain the conflict and propose an ADR/plan change.

## Non-negotiable engineering standard

- Deliver complete, production-quality vertical slices. Do not leave stubs, fake behaviour, placeholder success states, TODO-only implementations, or buttons that do nothing.
- A feature is not complete because it compiles, a screen looks right, or a mock test passes. Implement real behaviour and provide automated plus device evidence appropriate to the feature.
- Do not claim tests, benchmarks, device checks, licence checks, or security reviews were performed unless they were actually run. Include exact commands and outcomes.
- Do not mark anything DONE in the implementation plan without meeting every acceptance criterion and linking its evidence/PR. Only the repository owner approves DONE.
- If a full implementation is blocked, stop at a safe boundary, mark IN PROGRESS or BLOCKED, document the missing dependency and next action, and do not pretend it is complete.
- Do not silently reduce scope, replace real behaviour with mocks, suppress warnings, disable failing tests, or ignore native crashes.
- Preserve existing behaviour and user data. Persistent database/save changes require migration tests, backup strategy, and explicit review.
- Avoid unrelated refactors, formatting churn, and dependency upgrades in feature PRs.
- Do not add a second competing implementation without a documented reason and migration/removal plan.

## Architecture and pull requests

Never push agent work directly to `main`. Use a dedicated branch and pull request.

For every major architecture change — emulator core/API, native build, module boundaries, threading/lifecycle, rendering/audio, ROM identity/storage/save formats, controller abstraction, Android API/ABI support, permissions/security/privacy, dependency licensing, or release/distribution — the agent MUST:

1. Write or update an ADR under `docs/adr/` before implementation. Record context, options, decision, trade-offs, risks, migration and rollback.
2. Implement on a dedicated branch.
3. Add tests, documentation, and migration handling in the same branch.
4. Run relevant checks and provide exact command output/results in the PR.
5. Open a PR at the end of the architecture task. Include the ADR, scope, rationale, test evidence, UI screenshots/video where applicable, data compatibility, security/licence review, and rollback plan.
6. Leave the PR open. Do not merge, squash, close, or self-approve it. Wait for repository-owner review.
7. Propose status changes in the PR, but do not self-certify plan items as DONE.

Any feature spanning multiple modules or changing persistent user data must also use a PR. Smaller changes should follow the same branch/PR discipline.

## Emulator-specific rules

- The native emulator core is the source of emulation state. Do not simulate gameplay in Compose or WebView.
- Do not call native core APIs from composables or the main thread. Respect the single-owner emulator session and worker-thread contract.
- Verify actual capabilities of the pinned core revision before exposing UI. Unsupported options/features must not appear.
- Treat ROMs, firmware, save states, cheat input, and SAF content as untrusted data.
- Keep SRAM, save states, ROM identity, and source URI semantics separate and documented.
- Never bundle or commit commercial ROMs, proprietary BIOS files, copyrighted game assets, user save files, signing keys, credentials, or personal data.
- Do not assume SAF content URIs are filesystem paths.
- Handle pause, resume, rotation, surface recreation, audio focus, process death, and controller disconnects explicitly.

## Quality gates

Before requesting review:
- Run formatting, lint, unit tests, native tests/build, and relevant instrumented/device tests.
- Add regression tests for bug fixes where feasible.
- Test negative/error paths, not just the happy path.
- Verify a clean checkout build.
- Update docs, ADRs, changelog/release notes where applicable, and the plan's live status table.
- Report what was not tested and why.
- Ensure no secrets or generated user data entered the diff.

A PR with failing required checks, missing acceptance evidence, unreviewed data migration, or incomplete production behaviour is not ready for merge.
