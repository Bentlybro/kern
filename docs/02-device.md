# 02 — The Device: Samsung Galaxy Z Fold8

We verified every spec below independently against the Samsung newsroom, GSMArena, Wikipedia and hands-on reviews (Aug 2026). The full citations live in [research/device.md](research/device.md) and [research/verifications.md](research/verifications.md).

## Which model this is

The owner's phone is the **Galaxy Z Fold8 (standard "wide" model, SM-F971x)**, which Samsung announced on July 22, 2026 at Galaxy Unpacked London and released on Aug 7, 2026 at $1,899.99.

The "4:1 ratio" folklore resolves to Samsung's heavily marketed **4:3 inner display**, and nothing in the lineup is 4:1. This is Samsung's first *landscape-first* wide foldable: folded it is a squat passport-shaped phone, and unfolded it is a nearly-square-wide canvas. The Fold8 **Ultra**, SM-F976x, keeps the traditional tall shape with a 21:9 cover and a ~10:9 inner display; we support it through responsive layout but do not design for it.

## Specs that matter for this project

| Spec | Value | Why it matters |
|---|---|---|
| Inner display | 7.6" LTPO AMOLED, **2448×1848, 4:3**, 1–120Hz, 3000 nits, ~403 ppi, landscape-first | This is the main IDE canvas. It is wide enough to hold an editor and a panel side by side, and because it is nearly square, desktop-style horizontal layouts work |
| Cover display | 5.5", **1248×1972, 10:16** (squat), 120Hz, 428 ppi | This is a *short, wide-ish* phone screen, so it carries a one-handed companion UI rather than a mini IDE |
| Hinge | Vertical fold; tabletop + book postures via standard `FoldingFeature` | Tabletop mode, with the editor on top and the terminal plus key row below, is a flagship feature |
| SoC | Snapdragon 8 Elite Gen 5 for Galaxy: 2×4.74 GHz + 6×3.62 GHz Oryon V3, Adreno 840 | The CPU is laptop-class, so code-server, the language servers and the toolchain all sit comfortably within budget |
| RAM | 12 GB (16 GB on 1TB SKU), LPDDR5X — **owner's unit: 16GB / 1TB** | The code-server stack needs ~1.5–2.5 GB in total, which is trivial here |
| Kernel page size | **4096 bytes** (verified on-device 2026-08-06) | termux-packages binaries work unmodified, and the 16KB risk is retired |
| Storage | 256GB/512GB/1TB UFS 4.x | The toolchain bootstrap takes ~1–3 GB, and with the projects on top of it storage is still not a constraint |
| Battery | 4,800 mAh Si/C, 45W | Sustained compiles are bound by thermals and battery, so we plan for that rather than fight it |
| OS | **Android 17, One UI 9**, 7 OS upgrades promised | The planning horizon for targetSdk and behaviour changes is long, and every API named below is already present |
| DeX | Yes — One UI 9 DeX is rebuilt on Android's desktop windowing; adds top/bottom snapping | DeX is the "real work" escape hatch: a monitor and a keyboard turn Kern into a desktop IDE |
| S Pen | **Not supported** (digitizer removed since Fold 7) | Stylus precision cannot be a design pillar, so we design for touch, the magnifier and the keyboard only |
| Multitasking | One UI 9: 2-way and 3-way Split View, up to 4 apps with floating window | Our app must behave in splits, and running in a split is also our mitigation for background throttling |

## Layout planning numbers

- The unfolded inner screen should fall in the **Expanded** width class (≥840dp) in its landscape-first orientation, but the exact dp and density bucket is unpublished, so we **measure it on device in M0** with `WindowMetricsCalculator` and `Configuration.densityDpi`.
- The rule of thumb for 4:3 at ~7.6" is that two columns are comfortable — an editor plus a terminal or panel — while three get cramped, so the file tree becomes a collapsible rail or overlay rather than a persistent third column.
- The cover screen is roughly **Compact** width but also *short*, so vertical space is the scarce resource there: a single column, bottom-anchored controls and one-thumb flows.
- Densities are near-identical across the two panels, 403 against 428 ppi, so visual continuity across fold transitions comes easily; state continuity is on us.
- 16:9 content letterboxes on a 4:3 display, so our UI must fill the window edge to edge (Android 16/17 ignores orientation and resizability restrictions on large screens anyway — see docs/03).

## Device-specific behaviours to design around

1. **The inner display is landscape-first.** The Android Developers Blog explicitly tells developers to treat the Fold8 as an "ultra-wide display with landscape-first natural orientation" and to drop all orientation assumptions. Our layouts therefore derive from window size and posture, never from "phone vs tablet".
2. **Folding and unfolding is a configuration change.** Continuity comes from a ViewModel plus `rememberSaveable`, or from handling the configuration change manually. Continuing from the inner display onto the cover screen needs the user's per-app opt-in ("Continue apps on cover screen"), so onboarding must set it.
3. **Seamless Screen** on the Fold8 means the OS reflows the app on rotation, so our layouts must be rotation-agnostic on the inner panel.
4. **App screen zoom** (One UI 9 Labs, 5 levels) lets users rescale our app, so we test at "small" and "smallest".
5. **One UI pins background CPU work to the little cores.** Backgrounded CPU-heavy apps get pinned to the efficiency cores; this was observed on One UI 8.x, and we assume it persists. Heavy builds therefore want the app visible, and split-screen makes that natural. We verify it on One UI 9 in M0.
