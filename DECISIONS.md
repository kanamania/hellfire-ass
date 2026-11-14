# HellFire Ass redesign: decisions log

Decisions made where `HANDOFF.md` was unclear or a screen was marked *not drawn*. Newest entries are added at the end of each step.

## General

- **Branch.** The brief says the worktree is on `ui-redesign`; the actual worktree branch is `feat/ui-redesign` (created for steps 1–2 earlier). Work stays on that branch; no new branch was created.
- **Fonts.** Inter and Space Grotesk are bundled as OFL variable fonts in `res/font` (licences in `assets/licenses`). Google downloadable fonts would add a runtime network call, which §1 forbids. Adds about 1 MB to the APK.
- **Icons.** The design's outline SVG icons are ported to `ImageVector`s (`ui/theme/Icons.kt`) instead of adding an icon library.
- **Dark theme.** Derived from the §3 tokens (not designed). Same roles, dark surfaces `#0E1013` / `#181B20`, lighter accents so text keeps at least 4.5:1.
- **No navigation library.** A saveable route stack (`Route`) plus `BackHandler` covers 5 tabs and the pushed screens. Avoids a new dependency for one use.
- **Glance.** `androidx.glance:glance-appwidget` is the only new dependency, because §8 names Jetpack Glance.

## Step 1 · Theme and components

- Extra neutral tokens found in the design files but not in the §3 table were added (`subtle #F7F8FA`, `fill #F1F3F5`, `track #E9ECEF`, `faint #8A929C`, `bodySecondary #3D444D`, `handle #D3D8DE`, `info #1D4ED8/#E8F0FF`, `onSuccessContainer #14532D`).
- Material 3 components used by older screens inherit the tokens through the colour scheme, so un-redesigned fields and buttons already look consistent.
- The table component pins the first column and scrolls the rest horizontally, as in the report mockups.

## Step 2 · Navigation

- **Routes.** Pushed screens are a `Route(screen, arg)` stack saved across process death. Tabs reset the stack. Back pops the stack, then returns to Home.
- **Deep links.** `hellfire://…` URIs (`ask?voice=1`, `money/cash`, `money/review`, `task/<id>`, `draft/<id>`, `note`, `settings/permissions`, `report/money?category=…`) are handled by the activity, which is now `singleTop` with `onNewIntent`. They are only sent as explicit intents (widgets, reminder notifications), so nothing new is exported. The assist gesture maps to `hellfire://ask`.
- **First run.** The app opens on Setup until the owner taps Continue or Skip (`ui/setup_done` preference). Previously it always opened on the Setup tab.
- **Status messages** from permission flows show as a dismissible banner over the current screen instead of a line at the top of every tab.
