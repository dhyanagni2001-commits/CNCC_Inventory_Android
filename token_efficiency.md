# Token-Efficient Development Instructions

## Goal and priority

Build C Nanjappa Cloth Center's Android app with minimal unnecessary model input/output and repeated work. Reduce waste, not required functionality, engineering judgement, correctness, accessibility, or testing.

Use this file alongside:

- `android_inventory_requirements.md` — product behaviour and acceptance targets.
- `agent.md` — implementation rules and project boundaries.
- `test.md` — smoke, regression, capacity and physical-device validation.

Read these authoritative files completely once at the start, in bounded sections if needed. Record a concise checklist of their requirements and latest decisions. Afterward, reread only changed sections or sections needed for the current task. A summary helps navigation; it does not replace the source when resolving a detail or conflict.

Explicitly load this file into the coding session. Do not assume that a tool automatically discovers arbitrary Markdown filenames.

## 1. Keep context small and relevant

- Search before reading. Use `rg` and `rg --files` to find relevant files/symbols, then read the needed sections and their dependencies.
- Do not repeatedly dump the entire repository, requirements, lockfiles, generated files, build output, or transaction datasets into context.
- Exclude build directories, caches, binaries, generated reports and dependency trees from broad searches unless diagnosing them specifically.
- Keep file paths, discovered symbols, assumptions and decisions in a short task summary. Reuse known information until evidence suggests it changed.
- Read a complete relevant function/class when needed; do not save tokens by overlooking surrounding validation or callers.
- Check targeted diffs after edits. Broaden the review if shared contracts, schema, lifecycle or cross-feature behaviour changed.
- Do not ask the user to repaste files already accessible in the workspace. Ask only when required information is genuinely missing.

## 2. Work in complete, reviewable increments

Plan a small number of coherent stages: data/mutations, existing-code linking and stock flows, camera scanning, exports/backup, then capacity/device validation. Reuse the plan instead of restating it after every tool call.

For each increment: identify the requirement, inspect existing implementation, choose the smallest sound change, implement it, run relevant checks, and record the result. Continue toward the full authorized scope without stopping after each stage to ask routine permission.

Do not rebuild working components or redesign the architecture merely because another approach is possible. Do not postpone data-integrity or offline checks until the final stage. Resolve important design questions early before they create expensive rewrites.

If a change fails, inspect the first relevant failure and underlying cause before trying another approach. Avoid repeated speculative patches, broad dependency upgrades, or loops of rerunning the same failing command without new evidence.

## 3. Reuse code and avoid unnecessary output

- Reuse existing UI controls, mutation paths, validation, fixture generators and export utilities where their behaviour fits.
- Prefer parameterized tests and shared test fixtures over copied tests for every company, size or code format.
- Avoid speculative interfaces, layers, modules, helper files and frameworks without a concrete need.
- Modify the relevant code instead of rewriting large files solely for formatting or style. Preserve unrelated user changes.
- Comment non-obvious invariants and tradeoffs; do not narrate every obvious statement.
- Generate code directly in project files. Do not print the same source again in chat unless the user asks.
- Report relevant paths and results rather than attaching huge source blocks, raw traces, tables or logs to every update.
- Keep dependency choices stable after compatibility/licence checks; revisit when a real defect, benchmark failure or requirement justifies it.

## 4. Efficient tools and diagnostics

Batch independent searches/reads where supported. Keep dependent edits, outcomes and recovery decisions sequential. Avoid agents or parallel workers unless the user or applicable project instructions authorize them; unnecessary delegation duplicates context and coordination.

Save complete logs/results locally, but return a bounded summary and exact relevant errors to the model. Filter by failed test, exception, task or trace marker. If the first excerpt is insufficient, expand it deliberately. Never truncate evidence so aggressively that the cause disappears.

Use local scripts to count, aggregate, reconcile and compare results. Preserve failure seeds and reproducible inputs. Do not manually reason over thousands of identical rows when a deterministic check can establish the result.

Distinguish actual model/tool limits from guesses. If a tool reports usage, record it; otherwise label estimates as estimates. Do not claim a guaranteed token budget or savings percentage.

## 5. Test efficiently without skipping required coverage

- After a focused change, run its relevant tests plus shared invariants it could affect. Run broader regression when shared schema/scanner/export/lifecycle contracts change and before release.
- Do not rerun an unchanged full suite or eight-hour soak after every cosmetic edit. Reuse valid prior evidence for unchanged code/configuration, while documenting which build it covered.
- Do not reuse old acceptance results when the candidate or environment changed materially. Never mark a test passed because it was written or because an older build passed.
- Use deterministic seeded data and parameterized cases. Restore identical fixtures for comparative benchmarks.
- Two independent checks of an important decision means requirements/platform validation plus a meaningful failure-case check, not identical test repetition.
- Keep full required smoke, regression, offline, restore/migration, capacity, battery/thermal and optical-camera validation in scope. Missing device tests stay BLOCKED/NOT RUN.
- Never weaken duplicate prevention, transactions, barcode identity, return eligibility, backup safety or green-after-commit behaviour to reduce code or token use.

## 6. Generate the 100,000-item workload locally

Write a reusable deterministic fixture generator. Have the computer produce 100,000 distinct variants and coherent large movement history in bounded batches. Use the reference model and ledger reconciliation from `test.md`.

Do not send those 100,000 records or a million transactions to the model. Inspect only counts, fixture digest, expected totals, selected samples and failing records. Compute percentiles, mismatch counts and resource summaries locally from complete raw evidence.

Fixture generation, repeated testing and profiling consume computer/device resources; they do not require one model call per item. Avoid recurring model calls inside the simulator/test loop. No API key or cloud AI service is needed for the app or local workload.

## 7. Concise communication and continuity

Provide short useful updates: what changed or was learned, what remains uncertain, and the next check. Avoid repeating the entire plan, explaining routine commands, or giving long status essays.

If context is getting long or work moves to a new session, retain a compact checkpoint containing:

- Objective and latest product decisions.
- Current stage and completed checklist items.
- Changed files and important symbols.
- Checks actually run, build/device/fixture IDs and report paths.
- Exact unresolved failure or missing requirement.
- Next concrete action and any authorization boundary.

Keep the full requirements and evidence on disk. Continue from the checkpoint; do not restart completed work or treat summaries as proof of passed tests. Where supported and practical, leave stable instruction content unchanged to avoid unnecessary repeated context changes; do not assume caching exists or controls cost in every tool.

## 8. Completion and reporting

Before handoff, verify the requirements checklist and required checks for the final candidate. Report briefly: completed behaviour, relevant validation, artifact locations and material limitations. Include unresolved failures/device checks explicitly.

Do not introduce paid services, weaken offline operation, push/publish without authorization, or remove useful features to hit an arbitrary token target. Ask a focused question only when needed information changes a consequential decision; otherwise proceed with reasonable documented choices.

**Optimize for completing the correct app with fewer repeated reads, rewrites and explanations—not merely producing fewer tokens in one response.**
