# 02 — The Device: Samsung Galaxy Z Fold8

All specs below were independently verified across Samsung newsroom, GSMArena, Wikipedia,
and hands-on reviews (Aug 2026). Full citations in [research/device.md](research/device.md)
and [research/verifications.md](research/verifications.md).

## Which model this is

The owner's phone: **Galaxy Z Fold8 (standard "wide" model, SM-F971x)** — announced
July 22, 2026 at Galaxy Unpacked London, released Aug 7, 2026, $1,899.99.

The "4:1 ratio" folklore resolves to Samsung's heavily marketed **4:3 inner display**.
Nothing in the lineup is 4:1. This is Samsung's first *landscape-first* wide foldable:
folded it's a squat passport-shaped phone; unfolded it's a nearly-square-wide canvas.
(The Fold8 **Ultra**, SM-F976x, keeps the traditional tall shape — 21:9 cover, ~10:9
inner. We support it via responsive layout but do not design for it.)

## Specs that matter for this project

| Spec | Value | Why it matters |
|---|---|---|
| Inner display | 7.6" LTPO AMOLED, **2448×1848, 4:3**, 1–120Hz, 3000 nits, ~403 ppi, landscape-first | The main IDE canvas. Wide enough for editor+panel side by side; ~square means desktop-style horizontal layouts work |
| Cover display | 5.5", **1248×1972, 10:16** (squat), 120Hz, 428 ppi | A *short, wide-ish* phone screen — one-handed companion UI, not a mini IDE |
| Hinge | Vertical fold; tabletop + book postures via standard `FoldingFeature` | Tabletop = editor top / terminal+keys bottom is a flagship feature |
| SoC | Snapdragon 8 Elite Gen 5 for Galaxy: 2×4.74 GHz + 6×3.62 GHz Oryon V3, Adreno 840 | Laptop-class CPU. code-server + language servers + toolchain is comfortably within budget |
| RAM | 12 GB (16 GB on 1TB SKU), LPDDR5X — **owner's unit: 16GB / 1TB** | code-server stack needs ~1.5–2.5 GB total — trivial here |
| Kernel page size | **4096 bytes** (verified on-device 2026-08-06) | termux-packages binaries work unmodified; 16KB risk retired |
| Storage | 256GB/512GB/1TB UFS 4.x | Toolchain bootstrap (~1–3 GB) + projects: no constraint |
| Battery | 4,800 mAh Si/C, 45W | Sustained compiles are thermal/battery-bound; plan for it, don't fight it |
| OS | **Android 17, One UI 9**, 7 OS upgrades promised | targetSdk/behavior planning horizon is long; APIs below all present |
| DeX | Yes — One UI 9 DeX is rebuilt on Android's desktop windowing; adds top/bottom snapping | "Real work" escape hatch: monitor + keyboard = desktop IDE |
| S Pen | **Not supported** (digitizer removed since Fold 7) | Stylus precision cannot be a design pillar. Touch + magnifier + keyboard only |
| Multitasking | One UI 9: 2-way and 3-way Split View, up to 4 apps w/ floating window | Our app must behave in splits; also our mitigation for background throttling |

## Layout planning numbers

- Unfolded inner screen ≈ **Expanded** width class (≥840dp) in landscape-first
  orientation; exact dp/density bucket is unpublished — **measure on device in M0**
  (`WindowMetricsCalculator`, `Configuration.densityDpi`).
- Rule of thumb from 4:3 at ~7.6": two comfortable columns (editor + terminal/panel),
  three gets cramped — file tree as collapsible rail/overlay, not a persistent third
  column.
- Cover screen ≈ **Compact** width, but *short*: vertical space is the scarce resource;
  single-column, bottom-anchored controls, one-thumb flows.
- Densities are near-identical across panels (403 vs 428 ppi) — visual continuity across
  fold transitions is easy; state continuity is on us.
- 16:9 content letterboxes on 4:3; our UI must fill the window edge-to-edge
  (Android 16/17 ignores orientation/resizability restrictions on large screens anyway —
  see docs/03).

## Device-specific behaviors to design around

1. **Landscape-first inner display**: Android Developers Blog explicitly tells devs to
   treat Fold8 as an "ultra-wide display with landscape-first natural orientation" and
   drop all orientation assumptions. Our layouts derive from window size + posture, never
   from "phone vs tablet".
2. **Fold/unfold is a configuration change**: continuity via ViewModel +
   `rememberSaveable` (or handle config changes manually). Inner→cover continuation
   requires the user's per-app opt-in ("Continue apps on cover screen") — onboarding
   must set this.
3. **Seamless Screen** (Fold8): OS reflows on rotation; our layouts must be
   rotation-agnostic on the inner panel.
4. **App screen zoom** (One UI 9 Labs, 5 levels): users can rescale our app; test at
   "small" and "smallest".
5. **One UI background CPU pinning**: backgrounded CPU-heavy apps get pinned to little
   cores (observed on One UI 8.x, assume it persists) — heavy builds want the app
   visible; split-screen makes that natural. Verify on One UI 9 in M0.
