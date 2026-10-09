# AI_CONTEXT.md - handoff guide for the Flashcards Android app

Read this whole file before changing anything. It tells you what the app is, how it is built, how the user works, and the mistakes to avoid.

---

## 0. READ FIRST: how the user works (very important)

- The user is a **complete beginner**. No coding or GitHub experience. They **cannot run commands, install Android Studio, or read stack traces**.
- Explain steps simply, one at a time, say exactly what to click.
- **Always give WHOLE replacement files**, never "change line 42" snippets. Name the exact file path. If several files change, list them all.
- The user builds only through **GitHub Actions**:
  1. Pastes/replaces files in `Documents\GitHub\flashcard` (a clone of the repo) on Windows.
  2. GitHub Desktop: Summary -> "Commit to main" -> "Push origin".
  3. Repo -> Actions tab -> wait ~5 min for the green tick.
  4. Downloads artifact `flashcards-apk` (contains `debug/app-debug.apk` and `release/app-release.apk`), installs `app-release.apk` over the old app (same signing key, so data is kept).
  5. If the run shows a red cross: user opens run -> `build` -> failed step and sends a screenshot of the red `e: file:///...` lines. **Fix exactly those errors** with whole replacement files.
- **No AI has ever been able to compile or run this app.** Every change is untested until the user builds it. Keep each change small, double-check imports and syntax, and expect the user to report build errors. Never claim something "works" - say it "should work" and list how to test it.
- Keep changes **small and focused**. Do not rewrite or reformat files you were not asked to change.
- Before risky changes tell the user to use menu -> **Export backup** first.

## 1. Project overview

A lightweight, offline, Anki-like Android flashcard app for students (physics, chemistry, biology, maths). Cards have **up to 5 faces**; each face holds any mix of **text, LaTeX (KaTeX, offline), images, voice recordings, handwriting (stylus ink)**. Cards are studied face by face with swipe gestures and spaced repetition. Import/export via **TSV** (designed for AI-generated cards pasted from the clipboard) and ZIP backups (Drive/OneDrive via the Android file picker).

- Package / applicationId: `com.flashcards.app`
- Repo: `https://github.com/ujjwalsharma260-alt/flashcard` (public)
- minSdk 26, compileSdk/targetSdk 34
- Stack: Kotlin 1.9.24, Jetpack Compose (BOM 2024.06.00, compiler extension 1.5.14, **material3 1.2.1**), Room 2.6.1 + KSP 1.9.24-1.0.20, AGP 8.5.2, Gradle 8.7 (installed by the workflow, there is no gradle wrapper), JDK 17. KaTeX bundled in `app/src/main/assets/katex/` and shown in a WebView. `androidx.exifinterface` for photo rotation. Icons: **`material-icons-core` only** (no extended icons: no Mic, no Bookmark, no StarBorder, no Undo icons - use emoji/text instead).
- Build: GitHub Actions `.github/workflows/build.yml` runs `gradle assembleDebug` then `gradle assembleRelease` (release step has `continue-on-error`). Release uses R8 (minify + shrink resources) and is signed with the **committed fixed key `app/debug.keystore`** (password `android`, alias `androiddebugkey`) so updates install over old versions.
- Last known status: **builds green** (GitHub run "Update Browse.kt"). It contains the swipe study screen, themes, resume, Help search, deck-tile changes and the ink preview fix. The only manual fix after the AI wrote it was a missing `import androidx.compose.ui.Alignment` in Browse.kt (already included here). The user numbers their GitHub commits differently from the AI's zip names; ignore the numbers. The code in this file is exactly what is in the repo.

## 2. Architecture

**Navigation.** No navigation library. `AppRoot` (App.kt) keeps `stack = mutableStateListOf<Screen>(Screen.Home)`; `sealed class Screen` = `Home, DeckS(id, q), Edit(deckId, cardId, ink), Review(deckId, mode), Import(deckId, text), Settings, Help`. Only the top screen is composed. `rememberSaveableStateHolder` + `SaveableStateProvider` keeps `rememberSaveable` state (search text, lazy-list scroll) of screens below the top; `pop()` removes that state. System back = `BackHandler` -> `pop()`.

Flow: **Home** (decks; tap = start studying; long-press / 3 dots = browse, rename, delete; search; shortcuts; menu with Settings/Help/TSV/backup) -> **Review** (study) or **DeckS** (card browser, `id = 0` means "all cards") -> **Edit** (card editor, also handwriting dialog) / **Import** (TSV preview) / **Settings** / **Help**.

**State.** Plain Compose state (`remember`, `mutableStateOf`) inside screens. Room `Flow`s collected with `collectAsState`. Global user options live in `object AppSettings` (Settings.kt, `mutableStateOf` fields persisted in SharedPreferences `"settings"`, theme in `"p"`). Other prefs: `"drafts"` (editor autosave), `"resume"` (study progress + remembered choice per deck). No ViewModels, no DI. The DB is a singleton: `Db.get(context)`, DAO via `db.dao()`.

**Database (Room, version 4, `exportSchema=false`, migrations 1->2, 2->3, 3->4 in Data.kt).**

| Table | Columns |
|---|---|
| `Deck` | `id` PK auto, `name` |
| `Flashcard` | `id` PK auto, `deckId`, `tags` (space separated, each starts with `#`), `due` (ms), `interval` (days, Double), `ease` (Double, 2.5), `reps`, `lapses`, `lastReview` (ms), `state` (0 New, 1 Learning, 2 Review, 3 Relearning), `step`, `fav` (0/1), `suspended` (0/1), `created` (ms), `bookmark` (0/1), `streak`, `misses`. Indexes: `deckId`, `due`. |
| `Item` | `id` PK auto, `cardId`, `face` (0-based), `pos` (order inside the face), `type`, `data`. Index `cardId`. |
| `ReviewLog` | `id` PK auto, `cardId`, `time` (ms), `rating` (0 Again, 1 Hard, 2 Good, 3 Easy), `interval` |

`Item.type` values: `TEXT` (raw text, may contain LaTeX), `LATEX` (formula only; shown wrapped in `$$`), `IMAGE` (data = file name in `filesDir/images/`), `AUDIO` (data = file name in `filesDir/audio/`, .m4a), `INK` (data = compact stroke text, see Ink.kt: `i1,W,H|color,width,x,y,p,x,y,p...|...`, coordinates x10, pressure x100). New content types can be added without schema change.

**Adding a DB column (follow exactly):** add the field to the entity with `@ColumnInfo(defaultValue = "0")` (or matching default); bump `version`; add `Migration(n, n+1)` with `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...`; register it in `addMigrations(...)`; include the field in `Backup.export`/`restore` JSON (use `optInt(..., default)` so old backups load); update any raw SQL (`CardSearch.BASE`) and POJOs (`CardRow`) that need it. Never use destructive migration - it would wipe the user's cards.

**Study logic.**
- `Session.build` (Session.kt) builds one "set" of card ids. Modes: 0 due+new, 1 bookmarked, 2 favorites, 3 weak, 4 missed today. Set size from `AppSettings.sessionSize` (0 = all). Optional carry-over mixes e.g. 25% older "weak" cards (`misses>0 AND streak<N`) into each set.
- `Scheduler` (Data.kt): SM-2 style per-card `ease`; New -> Learning (1 min, 10 min steps) -> Review; failed Review -> Relearning (10 min). Optional streak bonus multiplies the interval after N correct answers in a row. `Scheduler.statsOnly` is used when a card is studied outside its schedule (e.g. bookmarked session) - it only updates streak/misses.
- `Review.kt`: black study screen. Order list + `answered` index set + `pos` pointer; wrong cards are appended to the end (if `retryMissed`). Swipe up = Good (rating 2), down = Again (0), right = next card, left = go back / undo last answer (restores the card row and deletes the ReviewLog row). Setting `ratingStyle=1` shows Again/Hard/Good/Easy buttons instead. Next 3 cards are prefetched into a cache so the WebView is never destroyed between cards. Progress is saved after each action (`Resume`); on re-entry the user is asked to continue or start over (choice can be remembered per deck).
- Card layout while studying: rounded black card, text centered (done in the WebView CSS). Animations only change `graphicsLayer` properties (translation, rotation, alpha).

**Search language** (`CardSearch` in Search.kt, compiles to SQL for a `@RawQuery`): words search faces/LaTeX/tags/deck name; operators `tag:x deck:x has:image has:audio has:formula has:ink faces:3 faces:3+ favorite bookmarked suspended due overdue state:new|learning|review|relearning added:today|yesterday|7d|30d`. All terms are ANDed. Add new operators in `CardSearch.build`.

**TSV** (Tsv.kt, ImportScreen.kt): tab separated, quoted fields allowed, header row auto-detected, 2-5 columns -> faces, rows with <2 columns or >5 are reported as problems and skipped, empty cells dropped. A cell that is entirely `$$...$$` becomes a `LATEX` item, everything else a `TEXT` item kept exactly. Export writes text and formulas only (no media).

**Backup** (Backup.kt): ZIP with `backup.json` (decks, cards incl. scheduling fields, items) + `audio/` + `images/`. Restore **adds** decks as new decks and never deletes. ReviewLog history is NOT in the backup yet.

**Formulas / text rendering** (FaceView.kt + `assets/katex/shell.js`): one WebView per FaceView, the HTML shell is loaded once and each face is pushed with `evaluateJavascript("setContent(...)")`. KaTeX auto-render delimiters: `$$..$$`, `$..$`, `\(..\)`, `\[..\]`, `\begin{equation|align|gather}`. Text without delimiters that looks like raw LaTeX (has a `\command` and at most one long word) is rendered as a display formula. Raw text is HTML-escaped; newlines kept with CSS `white-space: pre-wrap`.

**Handwriting** (Ink.kt): vector strokes, smoothing = low-pass filter + quadratic Bezier through midpoints, pressure-based width, partial eraser, undo/redo, palm rejection, full-screen dialog. A new page takes the aspect ratio of the writing area. Two stacked Canvases: finished strokes (own graphics layer) and live stroke. `InkView` always scales to fit and clips (never draws outside its box).

**Themes** (Theme.kt): `AppSettings.theme` 0 System, 1 Light, 2-6 = Charcoal, Slate, Sand, Forest, Plum (muted dark palettes built with `darkColorScheme` using only classic parameters). Use `cardC()` for Card colors. The Review screen always uses `darkPalette(0)`.

## 3. File map (`app/src/main/java/com/flashcards/app/`)

| File | Purpose |
|---|---|
| `MainActivity.kt` | Entry point; loads `AppSettings`, requests the highest refresh rate, picks the theme, shows `AppRoot` |
| `App.kt` | `Screen`, `AppRoot` navigation, helpers (`toast`, clipboard, `normTags`, `BackIcon`, `EItem`), and the **card editor** (`EditorScreen`) |
| `Browse.kt` | `HomeScreen` (deck tiles), `DeckScreen` (card browser: search, chips, multi-select, study panel), `CardRowItem` |
| `Review.kt` | Study screen (swipes, animations, resume), `Resume`, `SavedSession` |
| `Session.kt` | Builds a study set; `startOfTodayMs()` |
| `Data.kt` | Room entities, DAO, migrations, database, `Scheduler` |
| `Search.kt` | Search language -> SQL |
| `Bulk.kt` | Bulk card operations (favorite, bookmark, suspend, tags, move, delete, duplicate, merge decks) |
| `Tsv.kt`, `ImportScreen.kt` | TSV parser/exporter and the import preview screen |
| `Backup.kt` | ZIP backup / restore |
| `Media.kt` | Image saving (resize/rotate/JPEG), clipboard image, `ImageThumb`, `ZoomDialog` |
| `Audio.kt` | Recorder, Player, `AudioItem` (editor), `AudioPlayButton` (study) |
| `Ink.kt` | Handwriting model, smoothing, eraser, `InkView`, `InkEditorDialog` |
| `FaceView.kt` | KaTeX WebView renderer |
| `Draft.kt` | Editor autosave drafts |
| `Settings.kt` | `AppSettings` + `SettingsContent` / `SettingsScreen` |
| `Help.kt` | In-app help (18 simple sections) with live search + highlight |
| `Theme.kt` | Theme names, five dark palettes, `cardC()` |

Other: `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `AndroidManifest.xml` (only permission: `RECORD_AUDIO`), `app/src/main/assets/katex/` (KaTeX + `shell.js`), `app/debug.keystore`, `.github/workflows/build.yml`.

## 4. How to do common changes

- **New screen:** add an object/data class to `sealed class Screen`, a branch in `AppRoot`'s `when`, a `@Composable fun XScreen(...)`, and a way to open it (`stack.add(Screen.X)`).
- **New setting:** add a `mutableStateOf` field in `AppSettings`, load it in `init`, write it in `save`, reset it in `reset`, add a control in `SettingsContent` (`Section` + `Choices`/`OnOff`), and mention it in the Help text.
- **New card content type:** new `Item.type` string; handle it in the editor item loop (App.kt), in `Review.kt` (visuals), `Tsv`/`Backup` if it has files; media files must be copied in `Bulk.copyMedia` and added to backups.
- **Help text:** edit the `helpSections` list in `Help.kt` (plain strings; escape `$` as `\$` and backslashes as `\\`). Keep the language simple enough for an 8-year-old, with examples.
- Always update `Help.kt` when behavior visible to the user changes.

## 5. Pitfalls learned the hard way (avoid repeating them)

- `Modifier.weight(...)` only works directly inside a `Row`/`Column` scope. `Modifier.align` only inside `Box`.
- In `graphicsLayer { }`, `density` is the layer scope's Float. Do **not** name an outer variable `density` (name it `dens`).
- `import` is a Kotlin keyword: do not name functions `import` (use `restore`).
- A `$` inside a Kotlin string must be written `\$` unless you want a template.
- Experimental APIs need `@file:OptIn(...)` at the top of the file: `ExperimentalMaterial3Api`, `ExperimentalFoundationApi` (combinedClickable), `ExperimentalComposeUiApi` (`PointerInputChange.historical`).
- Name clashes: do not call your own classes `Card`, `Stroke`, `Dao`, `Settings` etc. when Compose/Room/Android types of the same name are imported (`InkStroke`, `Flashcard`, `AppDao` exist for this reason).
- material3 1.2.1: `LinearProgressIndicator(progress = { ... })` takes a lambda. Avoid `ColorScheme` parameters added later (`surfaceContainer*`).
- `kotlin.math.removeLast` / `List.removeLast()` crashes on newer JDKs: use `removeAt(lastIndex)`.
- Room `@Query` with `IN (:ids)`: chunk lists to <=500 ids (see `Bulk`).
- A `when` / `if` branch that removes a composable (e.g. "Loading...") destroys its WebView: keep `FaceView` composed to avoid flicker/lag.
- Never delete media files from inside the editor (the user may leave without saving); orphan files are acceptable.
- Never use destructive Room migrations.
- Release build runs R8: if you add reflection-based libraries, add keep rules.
- A missing import is the most common build error (it happened with `Alignment` in Browse.kt). When a file starts using a new type (`Alignment`, `Color`, `clip`, `alpha`, `RoundedCornerShape`...), add its import in that file.

## 6. Known issues / unverified

- Nothing has been compiled or run by an AI. Unverified on a real device: handwriting smoothness/latency, 3D flip smoothness (rotating a WebView layer), clipboard image paste on all phones, release/R8 build, swipe feel, resume flow.
- Bulk selection exists only inside a deck / "All cards", not in home search results.
- Deleting cards leaves their image/audio files on the phone (no cleanup tool yet).
- Review history (`ReviewLog`) is not included in backups.
- TSV export has no images/audio/handwriting.
- The ink page is a fixed rectangle; no pinch-zoom while writing.
- Duplicating a card from the editor duplicates the **saved** version.

## 7. Not implemented yet (user specification "V2", remaining rounds)

Done so far: favorites, bookmarks, suspend, state labels, search operators, filter chips, duplicate card/deck, merge decks, move cards, multi-select bulk ops, TSV export of selection/results, custom study (bookmarked/favorites/weak/missed), set size, carry-over, streak bonus, swipe study, resume, themes, Help with search.

Remaining:
- **Round B:** next-interval labels on answer buttons (`Again <10m, Hard 1d, Good 4d, Easy 10d` taken from the scheduler); Due/New/Learning counts on screen; per-card difficulty label (Easy/Normal/Hard/Very hard, manual override); home-screen shortcuts block ("Today's Study", Favorites, Weak, Missed, Recently added); "Cards I got wrong" for last session / 7 days / custom period.
- **Round C (stats/history):** today stats (cards studied, reviews, new, accuracy), per-deck counts, reviews per day, history by day with tap-for-details, optional gentle streak ("no punishment for missing a day"), simple calendar of study days. Keep the UI simple, no giant dashboards.
- **Round D (editor/media):** reorder faces; hide unused faces; "Preview card" exactly as in study; TSV column mapping (choose which column becomes which face); clipboard suggestion buttons in the editor (image -> "Paste image", TSV -> "Import N rows", text -> "Paste text", LaTeX -> "Paste formula"); double-tap zoom + reset on images; audio pause/seek, duration display, optional auto-play when a face opens; media storage screen (total size, largest, unused media cleanup); formula size setting (small/medium/large); undo/redo in the text editor; duplicate card with media.
- **Round E (safety/scale):** backups including review history; automatic daily/weekly backups keeping several generations (never silently overwrite the only backup); confirmations for restore and other destructive actions; **subdecks** (optional, simple decks must stay simple); performance with 5,000-10,000 cards and many images/audio; don't hold all media in memory.
- **Not wanted yet:** cloud sync, Anki sync, cloze deletion, image occlusion, video, widgets, social features, in-app AI.

## 8. The user's design intentions

- "A lightweight, modern, much simpler Anki designed for AI-assisted studying." Fast, simple, reliable, offline, no account.
- Clean minimal **black/dark** look, muted neutral themes only. Study screen modelled on a reference app: pure black, back arrow top-left, gear top-right, text **centered**, bottom row `bookmark + "3 of 112"`, `card icon + "Correct: 100%"`, `star + "Streak: 0"`.
- Study card: black with a thin rounded border (toggle in settings).
- Smooth like an iPhone / 120 Hz app: animate only transform/opacity, no jank, no flicker.
- **Every behavior must be toggleable in Settings.** Settings live behind one gear icon on the study screen.
- Formulas: **no formula-building buttons**. The user pastes LaTeX from anywhere and it must render.
- Opening a deck list shows cards from the **start** (oldest first by default).
- A Help section anyone (even a 7-year-old) can follow, with detailed examples of every setting. Keep it updated.
- Does **not** want: fake buttons, placeholder features, clutter on the main study screen, unnecessary settings on the main screen, punishing streaks, accounts, unstable experiments.

## 9. How to answer the user

1. Briefly restate what you will change (in plain words).
2. Give the **complete content of every changed file**, each in its own code block, with its full path.
3. Give the 4 install steps (replace files -> commit -> push -> wait for green tick) only if the user seems unsure.
4. Say what to test afterwards (2-4 concrete steps).
5. Remind the user that if the build fails they should send a screenshot of the red error lines.
6. If a requested change could break the data or cannot be done on Android, say so honestly and offer the closest option.
