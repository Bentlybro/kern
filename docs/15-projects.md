# 15 — Projects

Implementation: `runtime/ProjectRepository.kt`, `ui/ProjectsScreen.kt`.

## Native, not through the editor

The projects screen reads the guest filesystem directly through `LinuxRuntime`. The
workbench is never consulted, and neither is a terminal. That matters for two reasons:

- The list is available before code-server has finished starting.
- Answering "what projects exist" is one shell round-trip, not a WebView round-trip.

The listing is deliberately a single command returning `name<TAB>isRepo`, so a directory
of twenty projects still costs one PRoot launch rather than twenty.

## Three ways into a workspace

| Action | What happens |
|---|---|
| **Create** | Makes `~/projects/<name>`, optionally `git init`s it, and opens it |
| **Clone** | `git clone --depth 1` into `~/projects/<name>`, then opens it |
| **Open** | Any listed folder, any recent, or the home directory |

Creating was added because the screen previously could only *open* something that already
existed — so starting a new project meant cloning something unrelated or making the
directory by hand in the terminal.

`git init` is on by default. Nearly every project wants a repository eventually, and
starting one at creation is the difference between having history and wishing you had it.

## Names are sanitised

Folder names come from two untrusted places — human typing, and the tail of a clone URL —
and neither is shell-safe. Both go through one sanitiser that strips anything outside
`[A-Za-z0-9._-]` and trims leading or trailing dots and dashes.

Clone and create share that sanitiser, and share one `Outcome` type, rather than carrying
near-identical copies that drift apart.

## Failure cases are named

An operation that cannot succeed says why, in terms that suggest what to do:

| Case | Message |
|---|---|
| Folder exists | *A folder named "x" already exists* |
| git missing | *git is not installed yet — check Status, it may still be setting up* |
| Anything else | The last non-empty line of git's own output |

The git-missing case is real rather than theoretical: the toolchain installs *after* the
IDE opens, so there is a window on first run where a user can reach the clone field
before git has arrived. Telling them it is on its way is more useful than reporting that
`git` was not found.

## Recents

The last eight opened paths are kept in preferences, along with the current folder, so
the workbench reopens where it was left.

`currentFolder()` falls back to the home directory unless the saved path starts with
`/root`. Paths saved by an older build pointed into Termux's prefix, which no longer
exists, and the workbench would otherwise open on a missing workspace.
