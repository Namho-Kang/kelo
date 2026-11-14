# Desktop catch-up with the Android app

The desktop app was frozen on 2026-11-09 (about 11:40) while the Android app (`apps/android/kelo`, the reference) moved on. This list is
what Android gained since then (CHANGELOG "Unreleased" plus a file-by-file diff of `apps/android/kelo/app/src/main/java/chat/kelo/app/**`
against `apps/desktop/src/main/kotlin/**`, including every string the Android screens use that no desktop screen uses), with where it
stands on the desktop. Same screens, flows, wording and states as the phone, adapted to a computer: mouse and keyboard, wider windows,
desktop notifications.

`[x]` done (with its test), `[ ]` left, `N/A` phone-only (the desktop says "do this on your phone", as the wallet's card adding does).

Screenshots of the new screens: `CatchupShotsTest` writes `catchup-*.png` to `$KELO_SHOTS` (else `build/parity`), against
`tools/mock_server.py` with `KELO_MOCK_CATCHUP=1` (`tools/mock_parts/catchup_desktop.py`; `KELO_MOCK_CATCHUP=pad` for the keypad).

## Build and tests

`./gradlew test`: 587 tests, all passing on macOS (before the catch-up: 538, one failing on Mondays).

- [x] **Compile fix**: the shared `chat/ChatItems.kt` and `chat/TurnEvents.kt` use `agent.TaskGrantAsk`, which lived in the Android
      screen file `agent/TaskGrantCard.kt`. Its plain part (the model, `CardLimitLine`, `TaskGrantRules`) moved unchanged to the new
      Android file `agent/TaskGrantModels.kt` (no Android imports), which is in the `apps/shared/` module. Same split for
      `agent/ScheduleGrants.kt` → `agent/ScheduleGrantModels.kt` (the desktop's hand copy `chat/kelo/app/agent/ScheduleGrant.kt` is
      gone). Android behaviour unchanged (a pure move).
- [x] **MyPagesTest on Mondays**: the mock's journal is dated yesterday and Records opens on this week (Monday to Sunday); on a Monday
      the test now goes back one week (the "previous week" button) before looking for the entry.
- [x] **PairingLinksTest** (changed on Android at 16:51) calls `chat.kelo.app.start.ConnectViewModel.looksLikeCode`; the desktop has a
      plain copy under that name (`src/main/kotlin/chat/kelo/app/start/ConnectViewModel.kt`) so the shared test runs here too.
- [x] New shared files: `agent/TaskGrantModels.kt`, `agent/ScheduleGrantModels.kt`, `chat/PaymentStepCalls.kt`. New shared tests:
      `agent/TaskGrantCardTest`, `chat/PaymentStepTest`, `chat/ToolFoldTest`, `chat/BriefingCardTest`,
      `my/BriefingSettingsRulesTest`, `my/BriefingSourcesRulesTest`.
- Hand copies of plain parts that still sit in Android screen files (keep in step): `chat/kelo/app/chat/BriefingRules.kt`
  (from `chat/BriefingCard.kt`), `chat/kelo/app/my/BriefingModels.kt` (from `my/BriefingSettings.kt` + `my/BriefingSources.kt`),
  `chat/kelo/app/start/ConnectViewModel.kt`, `chat/kelo/app/connectors/ConnectorRules.kt` (re-copied: CalDAV).

## Chat (priority 1)

- [x] **Tool chips fold** — `chat/ChatStatusRows.kt` `ToolChip`/`FoldedToolChip`, `ChatReducer.foldTools` (shared). Calls of one kind in a
      row are one chip that updates in place ("⏳ 메일 읽기 · 2/3", "✓ 메일 2개 읽음 · ✗ 1"); a click lists each call.
      Desktop: `chat/ChatCards.kt`, rows folded in `chat/ChatPane.kt`. Shot: `catchup-cards-*`.
- [x] **One browser card per reply and tab** — reducer shared; the card says how many sites ("… · 7 sites", `chat_browser_step_sites`).
      Desktop: `BrowserCard` in `chat/ChatCards.kt`. Shot: `catchup-cards-*`.
- [x] **Permission for many items** (`items` on approval_request) — `ApprovalItems` in `chat/ChatCards.kt`, `agent/ApprovalParts.kt`.
      "See the list (37)" opens the numbered list (scrolls, with a scrollbar on the desktop) in the chat card, the waiting-card list
      and the Work page. Shot: `catchup-chat-*-list`.
- [x] **step_limit** — reducer (`TurnEvent.StepLimit` with `next`) shared; `chat_error_step_limit` shown as on the phone. Nothing
      else on the phone's screen for it.
- [x] **Payment step card** — `chat/PaymentStepUi.kt`, `chat/PaymentStepCalls.kt` (shared), `ChatViewModel` payment steps.
      Desktop: `chat/PaymentStepCard.kt`, state in `chat/ChatModel.kt` (`payment_step` events, `GET /api/payment-steps` while the
      agent works, while one is open, and on reload). The code large (click to copy), the QR code, the time left with its bar,
      "Approve it in the card or bank app on your phone, then click “I approved it” here", expired → "get a new code", one-time code
      field, passkey, finishing line, ended line. The **PIN keypad** kind shows the page's keypad picture live and a mouse click is a
      tap at the same spot (dots, the page's clear key). Shots: `catchup-chat-*-step`, `catchup-chat-ko-keypad-*`, `catchup-cards-*`.
- N/A **Payment PIN release** (`PinNeedCard`, biometric) — only the phone that saved the PIN can release it. Desktop `PinNeedCard`
      says "Open the Kelo app on your phone to release the PIN …" with the time left, no button. An approval with `pin` says that
      Allow makes the phone ask (`desktop_pin_allow_note`).
- [x] **Order approval card** — the one final order card was already on the desktop (`chat/OrderParts.kt`, 10:38); verified the same
      parts (items, ship to, pay with, coupon, total, why it asks).
- [x] **Task setup card** (`task_allow`) and the urgent line — `agent/TaskGrantCard.kt`. Desktop: `chat/TaskGrantCard.kt`, used by the
      chat's approval card and both waiting-card bodies (chat and Work). Shot: `catchup-chat-*-grant`.
- [x] **Morning briefing card** — `chat/BriefingCard.kt`. Desktop: `chat/BriefingCard.kt` (+ `BriefingRules` copy); the history now
      keeps `briefing` (it was dropped by the desktop's `toItem`). Each item opens its place (the chat at a follow-up, Work, a job's
      task, Goals, a mail question in the composer); an event opens nothing on a computer (no phone calendar); the hours suggestion
      at its bottom answers with the same calls. Shot: `catchup-chat-*-briefing`.
- [x] **Fill-in cards** for keys/logins were already on the desktop (`chat/SecretEntry.kt`); the new **payment PIN** fill-in
      (`request_payment_pin`) shows its title, where it is kept and "Payment PINs are kept only on your phone. Enter it in the Kelo
      app on your phone…"; cards were already "on your phone".
- [x] **No-model message** — `chat_error_pick_model` no longer takes the agent's name (desktop passed one).
- [x] **Nothing floats over a message** — `chat/ChatOverlayClear.kt` (Android, shared as is: plain Compose) with
      `ChatOverlayRulesTest` (shared): the chat list is not drawn under the floating agent and its name (an all but hard 4 dp
      edge just below the tag), and the "↓" sits on the composer card's top border at the end side, centred over 보내기 and kept
      within the composer's 860 dp width (`JumpButtonState`: the list says when it shows, the pane draws it after the composer);
      the field has 6 dp more room above its first line so the button never covers text. No band is cut out of the list.
- [x] **Reactions** — verified (desktop has `ReactionChip`, `ReactionShotsTest`; the smaller chip from 09:56 was in).

## My, Work, Connectors (priority 2)

- [x] **Contact hours: weekday and weekend** — `my/NotifyPage.kt` `HoursBlock`. Desktop `pages/my/NotifyPage.kt`: its own "알림 시간"
      section with a row per day type, where the hours came from, "배운 시간 … 적용", "follows weekdays", reset, the suggestion per
      day type, "adjust automatically" (manual hours untouched). `ProactiveSettings` gained the weekend fields. Shots:
      `catchup-notify-*-hours`, `-weekend`.
- [x] **Contact-hours suggestions** — weekend wording on the suggestion card, Records shows the weekend one too
      (`ContactHoursRules.open`).
- [x] **Morning briefing settings** — `my/BriefingSettings.kt`, `my/BriefingSources.kt`. Desktop `pages/my/BriefingSettings.kt`: on/off,
      when (start of the hours + offset, early open, fixed time), home area search, sections, quiet days, weather service and own
      keys, "get one now". Shots: `catchup-notify-*-briefing`.
- [x] **"What it reads" (읽는 계정) as its own section** — `my/BriefingSources.kt`, `my/NotifyPage.kt` (Android, 17:12): every connected
      calendar and mailbox with a switch, all/only, problems, check again, at the bottom of My → 알림 after the heads-up section,
      shown whether the briefing is on or off; its text says it applies to the briefing and the heads-ups. Desktop
      `BriefingSourcesBlock` in `pages/my/BriefingSettings.kt`, placed by `pages/my/NotifyPage.kt`. Shots: `catchup-notify-*-sources`,
      `catchup-notify-ko-sources-briefing-off-*` (the test turns the briefing off and checks the section stays).
- [x] **Account labels on the briefing card** — each event and mail carries `source` when more than one account is read; the desktop
      card shows it as a small tag (`SourceTag`, same as the phone). Shot: `catchup-chat-*-briefing`.
- N/A **Phone's city on travel days** (ACCESS_COARSE_LOCATION) — the switch shows its state and can be turned off here; it is turned
      on in the Kelo app on the phone (`desktop_briefing_phone_place`).
- [x] **Grants on the watcher and job pages** (`TaskGrantsBlock`), grant rows for messages and changes, how many are left, "ends
      after that", the card — `agent/ScheduleGrants.kt`. Desktop `pages/work/ScheduleGrants.kt`, `JobPages.kt`, `WatchPages.kt`,
      `WorkModel.kt`. Shots: `catchup-grants-watcher-ko-*`, `catchup-grants-job-ko-*`.
- [x] **Usage**: the "briefing" kind in "where tokens went".
- [x] **Desktop notifications**: an urgent approval is titled "Urgent: please confirm" and shown as a warning; the briefing's own
      title (`notify_briefing`). (Payment steps reach the desktop as urgent approval notifications.)
- [x] **Model "not chosen" labels** — My's model row says "Not chosen yet" on a server without a model; the setup page already hid the
      server default there and showed "choose a provider".
- [x] **Keychain: payment PINs** — `my/PaymentPinSection.kt`. Desktop `pages/my/PaymentPins.kt`: the list (name, site, digits; never the
      PIN) and delete; adding is on the phone (`desktop_pin_add_on_phone`).
- [x] **CalDAV calendar connector** — `connectors/CalDavConnect.kt`. Desktop `pages/connectors/CalDavConnect.kt` (address, user name,
      app password, checked by the server; accounts with disconnect). The calendar work on server/Android was still moving while this
      was copied: re-check against Android when it settles.
- [x] **Sign-in code typed in the key field** (`start/ConnectViewModel.kt`, 16:47; a multi-use review or pairing code such as
      ABCD-EFGH-JKMN-PQRS from store review notes) — "Connect with an access key" checks the key with GET /api/profile; on a 401,
      when the text looks like a code (`ConnectViewModel.looksLikeCode`/`codeOf`: 16–128 characters, dashes and single spaces
      allowed, the spaces taken out before sending; the server compares it without case), it is tried once through POST /api/pair like a pasted link; a refused code says
      "wrong key". No new strings. Desktop: `core/KeySignIn.kt`, used by `ui/PairScreen.kt`; test `KeySignInTest` (key, code,
      refused code, non-codes never sent).
- [x] **Storage warnings + cleanup**, **resources page states**, **server update card (owner)** — already on the desktop
      (`CleanupPage`, `ResourcesUsage`, `ServerUpdatePage`; `StorageShotsTest`, `ResourcesShotsTest`, `ServerUpdateShotsTest`); no
      Android change after the freeze.
- [x] **Mascot idle/night rules** — shared `chat/AgentActivity.kt` (weekend hours, day types, 5 minutes idle); the desktop shell
      already calls `AgentActivityRules.nightAt` with the server's hours, so it follows.
- [x] **MCP connector notes / fallback, Outlook granted scope** — the desktop connect panel was updated at 11:27 together with Android
      `DeviceConnect`/`ConnectorModels` (granted scope shown, sign-in fallback); verified the same strings.
- [x] **Privacy wording** — the in-app text comes from the Android strings (synced); the site pages are not part of the app.

## Phone-only (N/A on desktop)

- N/A **Card scanning** (`wallet/CardScan*.kt`) — the card form is on the phone only; the desktop wallet already says to add cards
  on the phone.
- N/A **Biometric PIN release / PIN vault** (`wallet/PinVault.kt`, `wallet/PinForms.kt`) — see the payment PIN items above.
- N/A **Coarse location** (`device/PhonePlace.kt`) — see the briefing settings.
- N/A **Phone calendar / device connectors, Health Connect, voice, camera, share sheet, QR scan, wallet lock screen** — unchanged
  phone-only parts (`device/*`, `voice/*`, `start/QrScanScreen.kt`, `wallet/WalletPage.kt` lock).

## Left / to check

- [ ] **Android model sheet** (`chat/ModelSheet.kt`: main + fallback with Apply) — the desktop keeps its own design (the chooser in the
      composer for the main model, the fallback under My); not changed here.
- [ ] **Live view and browser pages extras** (`agent/LivePage.kt`: viewers count, on-screen keys, slow mode; `agent/BrowserPages.kt`:
      open a new tab) — older gaps, not from this catch-up window.
- [x] Re-copied after the calendar work on Android finished (17:12): `BriefingModels` (`python3 apps/desktop/tools/copy_briefing_models.py`),
      `BriefingRules`, `CalDavConnect` and `ConnectorRules` compared (unchanged), the sources block moved (above).
- [x] Narrow (520 dp) shots in German, Arabic (right to left) and Thai: `catchup-narrow-{de,ar,th}-chat-{asks,grant,step,list,briefing}`
      and `catchup-narrow-{de,ar,th}-notify-{hours,weekend,briefing,sources}` (`CatchupShotsTest`). Found and fixed on the way: the
      down-chevron of the desktop's select fields (ModelSetup `SelectField`, the notify page's picker, the shipping form) pointed up in
      Arabic (a mirrored "›" turned 90°); it now turns the other way in right-to-left.
