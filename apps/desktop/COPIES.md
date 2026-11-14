# Desktop copies of Android files

Paths in the table below are relative to `apps/`.

The desktop app is the Android app's sibling. Most Android code is **shared** without a copy: it is the `apps/shared/` module (a Kotlin Multiplatform
build both apps include), so it cannot drift. This file is about the
other kind: files that live **twice in the repository**, once in `apps/android/kelo/app/src/...` and once in `apps/desktop/src/...`, because
the Android file also holds Android-only code (a view model, Android Compose, `Context`) or the screen had to be written for Compose
Desktop. Those copies can drift silently when the Android file changes. `CopiesTest` (`./gradlew test`) guards them.

## Classes

| class | meaning | what the test does |
|---|---|---|
| `identical` | the desktop file is the Android file; only the `package` line may differ | fails when the two files differ (any line but `package`) |
| `edits` | a copy or port with deliberate desktop edits, or an extract of declarations from the Android file(s) | fails when the Android original(s) changed since the recorded hash |
| `unrelated` | same file name, but the desktop file is its own code (it may mirror the phone's screen, but is not a copy) | nothing, but the file must be listed here so a new same-name file is looked at |

Currently there is **no `identical` pair**: every copy has at least a deliberate edit or is an extract. The check for `identical`
exists so a future exact copy can be added (and so a copy that is turned into a shared file can be removed here).

## When `CopiesTest` fails

- **`edits` row, "Android original changed"**: the Android file changed after the desktop copy was last checked. Read the Android
  change (`git log -p -- <android file>`), carry it into the desktop file when it belongs there (rules, wording, behaviour), then
  record the new hash: `python3 apps/desktop/tools/copies_hash.py --update <desktop file>`. Do not update the hash without re-checking.
  A pure move or reformat on Android still needs the hash updated (and the Android path in the row, if the file moved).
- **`identical` row, "differs"**: copy the Android file over the desktop one (keep the desktop `package` line).
- **"is not listed"**: a desktop file has the name of an Android file, or is a new hand copy in `src/main/kotlin/chat/kelo/app/`:
  add a row (class `unrelated` if it is its own code).
- Hash rule: SHA-256 of the Android file(s) in the listed order, concatenated, with `\r` removed, first 16 hex digits.
  `python3 apps/desktop/tools/copies_hash.py` prints the state of every row.

Not tracked here: desktop screens that say "as on the phone" but have no Android file of the same name (for example
`ChatPane.kt`, `MyPane.kt`); and the code both apps use, which is the `apps/shared/` module (package `chat.kelo.app`, no copy).

Rows are parsed by `CopiesTest`: keep one row per line, no `|` inside a cell, android originals separated by `, `.

## Copies

### Hand copies of plain Android code (`apps/desktop/src/main/kotlin/chat/kelo/app/`, same package as on Android)

| desktop file | Android original(s) | class | hash | why |
|---|---|---|---|---|

### Compose Desktop ports and same-name files (`apps/desktop/src/main/kotlin/chat/kelo/desktop/`)

| desktop file | Android original(s) | class | hash | why |
|---|---|---|---|---|
| desktop/src/main/kotlin/chat/kelo/desktop/core/AppContainer.kt | android/kelo/app/src/main/java/chat/kelo/app/AppContainer.kt | unrelated | - | the desktop own app container (Windows/macOS store, updates); only the name matches |

### Copies under another name

| desktop file | Android original(s) | class | hash | why |
|---|---|---|---|---|

### Tests

| desktop file | Android original(s) | class | hash | why |
|---|---|---|---|---|
| desktop/src/test/kotlin/chat/kelo/desktop/HandoverTest.kt | shared/src/desktopTest/kotlin/chat/kelo/app/chat/HandoverTest.kt | unrelated | - | the desktop own test (package chat.kelo.desktop); the Android HandoverTest itself runs here as a shared test |
| desktop/src/test/kotlin/chat/kelo/desktop/ModelCatalogTest.kt | shared/src/desktopTest/kotlin/chat/kelo/app/chat/ModelCatalogTest.kt | unrelated | - | desktop data-folder and UI checks of the shared ModelCatalog; the rules checks live in the shared test |
| desktop/src/test/kotlin/chat/kelo/desktop/SingleLineFieldsTest.kt | android/kelo/app/src/test/java/chat/kelo/app/ui/components/SingleLineFieldsTest.kt | unrelated | - | the desktop own test; only the name matches |
