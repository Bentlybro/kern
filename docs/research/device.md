# device

## Summary
Samsung announced the Galaxy Z Fold8 lineup at Galaxy Unpacked ("A New Shape Unfolds") in London on July 22, 2026, with US retail availability August 7, 2026. The standard Galaxy Z Fold8 (SM-F971x, $1,899.99) is a new wide-format foldable: a squat, passport-like body (folded 81.9 x 123.9 mm) with a 5.5" 10:16 cover screen and a landscape-first 7.6" inner display at a 4:3 aspect ratio (2448 x 1848). The Galaxy Z Fold8 Ultra (SM-F976x, $2,099.99) keeps the traditional tall Fold shape: 6.5" 21:9 cover screen and 8.0" ~10:9 inner display (2504 x 2256). Nothing in the lineup is literally 4:1 — the owner's "4:1 ratio fold phone" almost certainly means the 4:3 inner display of the standard (wide) Z Fold8. Both run Snapdragon 8 Elite Gen 5 for Galaxy, Android 17 / One UI 9, support DeX (with new top/bottom window snapping), and neither supports S Pen.

## Key facts
- No display in the Z Fold8 lineup is 4:1; the standard Z Fold8's inner display is 4:3 (Samsung's own marketing term), so "a 4:1 ratio fold phone" almost certainly = standard Galaxy Z Fold8 (wide model), with "4:1" a garbling of "4:3".
- Standard Galaxy Z Fold8 (SM-F971B/U/U1): 7.6" inner Dynamic LTPO AMOLED 2X, 2448x1848, 4:3, 1-120Hz, 3,000 nits peak, ~403 ppi; landscape-first natural orientation.
- Z Fold8 cover display: 5.5", 1248x1972, 10:16 aspect (squat/wide, passport/BlackBerry-Passport-like; Engadget likens it to the original Pixel Fold cover), 428 ppi, LTPO 120Hz.
- Galaxy Z Fold8 Ultra (SM-F976B/U/U1): 8.0" inner display 2256x2504 (~10:9), ~422 ppi, 120Hz, 3,000 nits; 6.5" cover 1080x2520 (21:9), 422 ppi.
- Dimensions — Fold8: folded 81.9 x 123.9 x 9.7 mm, unfolded 161.4 x 123.9 x 4.5 mm, 201 g. Ultra: folded 158.4 x 72.8 x 8.9 mm, unfolded 158.4 x 143.2 x 4.1 mm, 215 g (Samsung's thinnest foldable).
- Both use Qualcomm Snapdragon 8 Elite Gen 5 for Galaxy (SM8850-1-AD, 3nm): 2x 4.74 GHz Oryon V3 Phoenix L + 6x 3.62 GHz Oryon V3 Phoenix M, Adreno 840 GPU at 1.3 GHz.
- Memory: 12GB or 16GB LPDDR5X with 256GB/512GB/1TB UFS 4.x; Samsung lists 12/256, 12/512, 16/1TB for both models.
- Batteries are silicon-carbon (Si/C) dual cells: Fold8 4,800 mAh (63% in 30 min at 45W wired), Ultra 5,000 mAh (67% in 30 min); both 20W wireless + 4.5W reverse wireless.
- Both launch on Android 17 with One UI 9, promised up to 7 major OS upgrades; both support Samsung DeX, and One UI 9 DeX adds top/bottom window snapping in addition to left/right.
- Neither the Z Fold8 nor the Z Fold8 Ultra supports S Pen (Samsung FAQ explicitly: "No, Galaxy Z Fold8 does not support S Pen"), continuing the Z Fold 7's removal.
- Announced July 22, 2026 at Galaxy Unpacked London; pre-orders same day; US retail release August 7, 2026 (SamMobile alone says Aug 5). Prices: Fold8 $1,899.99/£1,699/€1,999; Ultra $2,099.99/£1,899/€2,119.
- Multitasking in One UI 9: two-way and three-way Split View with one-tap window switching, up to 4 apps if one is a floating window, drag-and-drop between windows; "Seamless Screen" (Fold8-specific) reflows content on portrait/landscape rotation.
- Developer guidance (Android Developers Blog): treat the Fold8 as an ultra-wide, landscape-first display; use Window Size Classes + Jetpack WindowManager, Compose BOM 2026.04.01 (new Grid, FlexBox, MediaQuery APIs), ViewModel for fold/unfold continuity, CameraX; Google published dedicated "trifolds and landscape foldables" guidance.
- Per-app scaling: One UI 9 adds an "app screen zoom" setting (Samsung Labs beta) with five levels, default "small" and one smaller "smallest" tier; 16:9 video is letterboxed on the 4:3 inner panel though with slightly more viewable area than the taller Fold 7-style screen.

## Details
## Lineup and the "4:1" question
Announced at Galaxy Unpacked, London, July 22, 2026 ("A New Shape Unfolds"): Galaxy Z Fold8 (new wide form factor), Galaxy Z Fold8 Ultra (traditional tall Fold), Galaxy Z Flip8. Pre-orders opened July 22; US retail availability **August 7, 2026** (Wikipedia, Forbes; SamMobile says Aug 5 — see confidence notes).

Aspect ratios in the lineup: Fold8 inner **4:3**; Fold8 cover **10:16** (~16:10 landscape); Ultra inner **~10:9**; Ultra cover **21:9**. **Nothing is 4:1.** A "4:1 ratio fold phone" maps most plausibly to the standard Z Fold8's 4:3 inner display (Samsung marketing repeatedly says "4:3 wide screen"); the squat BlackBerry-Passport-like description also matches the standard Fold8, not the Ultra.

## Galaxy Z Fold8 (standard, wide)
| Spec | Value |
|---|---|
| Model numbers | SM-F971B, SM-F971B/DS, SM-F971U, SM-F971U1 |
| Inner display | 7.6" Foldable Dynamic LTPO AMOLED 2X, **2448x1848 (4:3)**, 1–120Hz adaptive, HDR10+, 3,000 nits peak, ~403 ppi, anti-reflective coating (-50% glare vs Fold 7) |
| Cover display | 5.5" Dynamic LTPO AMOLED 2X, **1248x1972 (10:16)**, 1–120Hz, 428 ppi, Gorilla Glass Ceramic 3 |
| SoC | Snapdragon 8 Elite Gen 5 for Galaxy (SM8850-1-AD, 3nm); 2x4.74 GHz Oryon V3 Phoenix L + 6x3.62 GHz Phoenix M; Adreno 840 @1.3 GHz |
| RAM/Storage | 12GB/256GB, 12GB/512GB, 16GB/1TB (GSMArena also lists 12GB/1TB); LPDDR5X, UFS 4.x |
| Battery | 4,800 mAh Si/C dual battery; 45W wired (63% in 30 min), 20W wireless, 4.5W reverse |
| OS | Android 17, One UI 9; 7 major OS upgrades |
| Dimensions | Folded 81.9 x 123.9 x 9.7 mm; unfolded 161.4 x 123.9 x 4.5 mm; 201 g; IP48 |
| Cameras | 50MP wide f/1.8 OIS + 50MP ultrawide f/1.9; 10MP f/2.2 on each display; 8K30 |
| S Pen | **No** (Samsung FAQ explicit) |
| DeX | Yes |
| Price | $1,899.99 / £1,699 / €1,999 (256GB) |

## Galaxy Z Fold8 Ultra
| Spec | Value |
|---|---|
| Model numbers | SM-F976B, SM-F976B/DS, SM-F976U, SM-F976U1 |
| Inner display | 8.0" Foldable Dynamic LTPO AMOLED 2X, **2256x2504 (~10:9)**, 120Hz, HDR10+, 3,000 nits, ~422 ppi |
| Cover display | 6.5" Dynamic LTPO AMOLED 2X, **1080x2520 (21:9)**, 120Hz, 422 ppi |
| SoC | Same Snapdragon 8 Elite Gen 5 for Galaxy / Adreno 840 |
| RAM/Storage | 12GB/256GB, 12GB/512GB, 16GB/1TB (GSMArena also lists 16GB/512GB) |
| Battery | 5,000 mAh Si/C; 45W wired (67% in 30 min), 20W wireless, 4.5W reverse |
| OS | Android 17, One UI 9; 7 major upgrades |
| Dimensions | Folded 158.4 x 72.8 x 8.9 mm; unfolded 158.4 x 143.2 x **4.1 mm** (Samsung's thinnest foldable); 215 g; IP48 |
| Cameras | 200MP f/1.7 OIS + 50MP ultrawide + 10MP 3x tele; 10MP on each display; Wi-Fi 7, BT 6.0 |
| S Pen | **No** (widely criticized omission) |
| DeX | Yes |
| Price | $2,099.99 / £1,899 / €2,119 (256GB) |

Both use "Flex Titanium" (titanium-alloy film + enhanced titanium plate) to reduce crease.

## Software / multitasking / DeX
- One UI 9 multitasking: **two-way and three-way Split View**, one-tap window switching, up to **4 apps** (one as floating window), drag-and-drop between windows; layouts "optimised for the wider canvas."
- **Seamless Screen** (Fold8-specific): auto-adjusts content when rotating portrait/landscape.
- **DeX**: supported; One UI 9 adds window snapping **top and bottom** in addition to left/right, plus general DeX improvements.
- "Now Nudge" AI feature bridges cover and main screens.

## Developer-facing
- Android Developers Blog ("Optimize your apps for the next generation of Samsung Galaxy devices"): Fold8 is an "ultra-wide display with landscape-first natural orientation"; drop orientation/size assumptions; use **Window Size Classes** + Jetpack WindowManager (app window rarely equals physical screen in multi-window); Compose BOM **2026.04.01** with new Grid, FlexBox, MediaQuery (posture/window/keyboard) APIs; ViewModel for fold/unfold continuity; keep content off the hinge; CameraX/PreviewView for camera rotation-scaling; new dedicated docs for "trifolds and landscape foldables"; Gemini Nano 4 on-device via ML Kit Prompt API.
- Per-app scaling: One UI 9 **"app screen zoom"** (Samsung Labs beta) — five levels, default "small," one smaller "smallest" (e.g., YouTube goes from 2 to 3 recommendation rows).
- Letterboxing: 16:9 video is pillarboxed/letterboxed on the 4:3 inner panel; Engadget notes it still yields slightly *more* viewable 16:9 area than the narrower Fold-style panel, and 4:3 content fits perfectly; 9to5Google pushed back on Samsung's "full-screen video" claim. Reviewers note portrait use of the wide inner screen is awkward ("home screen gets all jumbled up"). Instagram/Booking.com cited as still unoptimized for foldable ratios.
- Cover screen: a full 5.5" 10:16 Android display — all apps run natively (no Flip-style cover-app restriction), but its squat ratio is another layout target; densities are near-identical across panels (403–428 ppi), easing continuity. Exact default dp density buckets were not published in any consulted source.

## Confidence notes
Conflicts and unverified items: (1) Fold8 inner resolution — Samsung newsroom, Wikipedia, SamMobile, and GSMArena's news article say 1848x2448; GSMArena's spec page says 1828x2448 and in one place "12.05:9" while elsewhere "4:3" — majority and Samsung-official value is 1848x2448 (4:3); treat 1828 as a GSMArena spec-page error. (2) Release date — Wikipedia and Forbes say US release Aug 7, 2026; SamMobile says on sale Aug 5, 2026; GSMArena spec pages say "Released 2026, July 22," which matches the pre-order date, not shelf date; Aug 7 is best-supported for US retail (task premise agrees). (3) RAM/storage matrices differ slightly by source (GSMArena adds 12GB/1TB for Fold8 and 16GB/512GB for Ultra vs Samsung's three official tiers) — likely market-dependent. (4) Charging %-in-30-min: Samsung says 63% (Fold8) and 67% (Ultra); Wikipedia gives a generic "65%". (5) DeX support is confirmed by SamMobile and One UI 9 feature coverage (top/bottom snapping), but Samsung's press release and US product page did not explicitly mention DeX — high confidence it's present, lower confidence on full change list. (6) The "4:1" mapping to 4:3/standard Z Fold8 is my inference — no source uses "4:1"; a lesser possibility is the user loosely describing the squat 10:16 cover or ~10:9 Ultra inner, but the 4:3 wide Fold8 fits both the phrase and the Passport-like description far better. (7) Default Android density (dpi bucket / dp dimensions) for either model was not published anywhere I checked — only physical ppi (403/428/422). (8) Ultra peak brightness 3,000 nits is from Samsung newsroom; per-review measured values not collected.

## Sources
- Samsung Global Newsroom — Galaxy Z Fold8 Ultra, Fold8 and Flip8: Foldables Perfected: https://news.samsung.com/global/samsung-galaxy-z-fold8-ultra-fold8-and-flip8foldables-perfected-for-every-way-of-living
- SamMobile — Samsung Galaxy Z Fold 8: Everything you need to know: https://www.sammobile.com/news/samsung-galaxy-z-fold-8-everything-to-know/
- GSMArena — Samsung Galaxy Z Fold8 full specifications: https://www.gsmarena.com/samsung_galaxy_z_fold_wide_5g-14673.php
- GSMArena — Samsung Galaxy Z Fold8 Ultra full specifications: https://www.gsmarena.com/samsung_galaxy_z_fold8_ultra_5g-14802.php
- GSMArena news — Galaxy Z Fold8 goes wide, Fold8 Ultra aims for the foldable crown: https://www.gsmarena.com/samsung_galaxy_z_fold8_goes_wide_fold8_ultra_aims_for_the_foldable_crown-news-73825.php
- Wikipedia — Samsung Galaxy Z Fold 8: https://en.wikipedia.org/wiki/Samsung_Galaxy_Z_Fold_8
- Android Developers Blog — Optimize your apps for the next generation of Samsung Galaxy devices: https://developer.android.com/blog/posts/optimize-your-apps-for-the-next-generation-of-samsung-galaxy-devices
- 9to5Google — Galaxy Z Fold 8 gives apps new scaling options for its large displays: https://9to5google.com/2026/07/29/galaxy-z-fold-8-feature-changes-app-content-size/
- Engadget — Samsung Galaxy Z Fold 8 review: Wider really is better: https://www.engadget.com/2225231/samsung-galaxy-z-fold-8-review/
- Samsung US — Galaxy Z Fold8 product page: https://www.samsung.com/us/smartphones/galaxy-z-fold8/

