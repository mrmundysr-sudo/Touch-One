# Claude instructions (Touch AutoBuild)

Branching, the build/repair loop and approvals follow the shared Touch AutoBuild process:
https://github.com/mrmundysr-sudo/touch-development/blob/main/AUTOBUILD.md

Short version: work only on a `claude/<slug>` branch, push to trigger the AutoBuild workflow, read failures,
repair up to 3 times, then report. Never merge to `main`, publish releases, touch signing material or secrets,
or delete anything without the owner's explicit approval. The existing `main` build workflows are unchanged.
