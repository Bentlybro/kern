# Kern documentation

Kern is a complete Ubuntu development environment and the VS Code workbench, running
inside a single Android app. These documents cover how it works, why it is built this
way, and what was tried and rejected on the way.

Two things worth knowing before you read:

- **The project changed shape partway through.** It began as a native shell around
  Termux, and became a self-contained app with its own embedded Linux. Documents written
  before that switch still describe the Termux design in places. Each is labelled below,
  and where an older document conflicts with a newer one, the newer one wins.
- **Claims here are measured, not assumed.** Where a document says something was
  verified, it was verified on a real device, and the failure it protects against is
  usually recorded alongside it. That is deliberate: most of the hard-won knowledge in
  this project is about *how* things fail, and the failures are rarely self-explanatory.

## Current — how the system works today

| Document | Covers |
|---|---|
| [11 — Embedded Linux](11-embedded-linux.md) | **Start here.** How a real Ubuntu runs inside an app at targetSdk 36: PRoot, the loader stub, fake root, and the flags that are load-bearing. |
| [12 — Setup and the installer](12-setup.md) | What first run actually does, why it is split in two, and where the time goes. |
| [13 — Git and GitHub](13-git-and-github.md) | Device-flow sign-in, the credential helper, and why driving `gh` needs a terminal emulator. |
| [14 — Storage](14-storage.md) | What the guest costs, why there is no hard cap, and the symlink trap in measuring it. |
| [15 — Projects](15-projects.md) | Creating, cloning and opening workspaces from native UI. |
| [16 — Development](16-development.md) | Building, deploying, and driving a real device from the harness. |
| [17 — Releases, CI and updates](17-releases.md) | Branches, the signing key, and in-app updates. |
| [18 — Terminal and agent](18-terminal-and-agent.md) | Multiple terminals, the agent's own terminal, and three bugs whose causes were nowhere near their symptoms. |
| [19 — What is next](19-next.md) | The honest backlog, ordered by what would change Kern most. |
| [09 — Building](09-building.md) | Toolchain, dependencies, and build configuration. |

## Design — the shape of the app

| Document | Covers | Status |
|---|---|---|
| [01 — Goals and requirements](01-goals-and-requirements.md) | What this is for and what it refuses to be | Current |
| [02 — Device](02-device.md) | The hardware it was designed against | Current |
| [03 — Android constraints](03-android-constraints.md) | W^X, the phantom process killer, background limits | Current |
| [04 — Architecture](04-architecture.md) | Component layout and process model | **Partly superseded** by 11 — the runtime is no longer Termux |
| [05 — UX](05-ux.md) | Postures, layouts, and input design | Current |
| [10 — Native UI requirements](10-native-ui-requirements.md) | What must be native rather than web, and why | Current |

## History — decisions, risks, and the road here

| Document | Covers |
|---|---|
| [08 — Decisions](08-decisions.md) | Numbered decisions with their reasoning. D4 and D5 are superseded by 11. |
| [07 — Risks](07-risks.md) | The risk register and what closed each one |
| [06 — Roadmap](06-roadmap.md) | Milestones M0–M6 and what each delivered |

## Research — what was investigated before committing

These are working notes from before and during the build. They are kept because the
reasoning is often more useful than the conclusion, but they are **snapshots, not
current documentation**.

- [`research/`](research/) — the device, the IDE landscape, VS Code's architecture,
  editor technology, foldable development, and runtime execution constraints.
- [`research2/`](research2/) — the harder questions: a native editor versus the
  workbench, what is lost without extensions, remote protocols, Kotlin LSP/DAP, an
  AndroidIDE post-mortem, and a deliberate devil's-advocate challenge to the whole plan.
- [`research3-terminal/`](research3-terminal/) — terminal approaches that were evaluated
  and largely abandoned once the app gained its own PTY.

## Conventions

- Code comments explain **why**, not what. If a line looks strange, the comment says what
  breaks without it.
- Failure modes are quoted verbatim where they are misleading — several of the bugs in
  this project reported something that had nothing to do with the actual cause.
- Anything described as verified has a device behind it.
