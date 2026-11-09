<p align="center"><img src="docs/media/banner-en.png" alt="Kelo — Your server. Your model. Your assistant." width="860"></p>

<p align="center">
  <b>A personal AI assistant that runs on a server you choose, with the AI model you choose — local models included.</b><br>
  You talk to it from your phone. It does the work in its own browser and the services you connect, and it asks before anything hard to undo.
</p>

<p align="center">
  <a href="https://kelo.chat/download/android"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fkelo.chat%2Fdownload%2Flatest.json&query=%24.android.version&label=Android&logo=android&logoColor=white&color=3DDC84" alt="Android"></a>
  <a href="https://kelo.chat/download/mac"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fkelo.chat%2Fdownload%2Flatest.json&query=%24.mac.version&label=macOS&logo=apple&logoColor=white&color=555555" alt="macOS"></a>
  <a href="https://kelo.chat/download/windows"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fkelo.chat%2Fdownload%2Flatest.json&query=%24.windows.version&label=Windows&logo=windows&logoColor=white&color=0078D4" alt="Windows"></a>
  <a href="https://kelo.chat/install"><img src="https://img.shields.io/badge/server-self--hosted%20%C2%B7%20Docker-2496ED?logo=docker&logoColor=white" alt="Self-hosted with Docker"></a>
  <a href="https://www.gnu.org/licenses/agpl-3.0.html"><img src="https://img.shields.io/badge/license-AGPL--3.0-blue" alt="License: AGPL-3.0"></a>
  <a href="https://github.com/kelo-chat/kelo/stargazers"><img src="https://img.shields.io/github/stars/kelo-chat/kelo?style=flat&logo=github&label=stars" alt="GitHub stars"></a>
</p>

<p align="center">
  <a href="https://kelo.chat">kelo.chat</a> ·
  <a href="https://kelo.chat/install">Install</a> ·
  <a href="README.ko.md">한국어</a>
</p>

---

## Why Kelo

**Your server, where you want it.** The Kelo server runs on a Mac at home, a Linux box, a mini PC or NAS with Docker, or a cloud
server. Chats, memory, files and keys stay on that server. The phone app is the remote.

**Any model you want — even a local one.** Pick from about 30 AI providers (Claude, GPT, Gemini, DeepSeek, Qwen, Mistral, Grok,
OpenRouter and more), or run a model on your own hardware with LM Studio, Ollama or any OpenAI-compatible server. With a local
model, your conversation never leaves your hardware. A fallback model from a different provider takes over when the main one fails.

**It asks first.** Looking things up just happens. Anything that commits something for you — paying, ordering, booking, sending,
deleting, changing an account — waits for your OK on the phone. When a site wants to check that you're a person, Kelo hands the
browser to you instead of trying to get around it.

<p align="center">
  <img src="docs/media/kelo-selfhost-en.gif" alt="The Kelo server runs where you choose" width="420">
  <img src="docs/media/kelo-models-en.gif" alt="Pick any model, local ones included" width="300">
</p>

## What it does

- **Chat from your phone** — text or voice, photos and documents (PDF, Word, Excel), or share from any app with "Send to Kelo".
- **Its own browser** — opens sites, reads and compares, fills forms and signs in with logins you saved. Watch it live, take over,
  and hand it back.
- **Quick look-ups without a browser** — web search and page reading for prices, comparisons and facts, right in the chat.
- **Background work** — long jobs keep going with every step shown; questions they need come to the chat as cards.
- **Schedules and watchers** — run things at set times; get told when a page shows certain words or a number crosses your limit.
- **Goals** — write down a goal; Kelo keeps the plan, steps, numbers and due dates with you.
- **Connectors** — Gmail and other mailboxes, calendars, GitHub, Notion, Linear, Todoist and many more, each connected on the
  service's own sign-in page.
- **Keychain and cards** — passwords and keys go in through a form, never the chat, and the agent never sees them. Cards can be
  locked with a key only your phone holds.
- **Notifications** — when a task finishes or needs your OK. Pushes carry no content; the app fetches it from your own server.
- **20 languages** in the apps; Kelo answers in the language you write in.

<p align="center"><img src="docs/media/kelo-chat-approval-en.gif" alt="A request, a result and one approval in the chat" width="420"></p>

## Apps

| Platform | Status |
|---|---|
| Android | Native app (Kotlin + Jetpack Compose). Sideload APK now; Google Play coming. |
| Windows / macOS desktop | Native app (Compose Multiplatform): chat, approvals, live browser, tray, notifications. |
| iOS | Native app (SwiftUI), in progress. |

<p align="center">
  <img src="docs/media/en-chat.webp" alt="Chat" width="200">
  <img src="docs/media/en-work.webp" alt="Work" width="200">
  <img src="docs/media/en-goal.webp" alt="Goal plan" width="200">
  <img src="docs/media/en-connectors.webp" alt="Connectors" width="200">
</p>
<p align="center"><sub>The "Walk 30 minutes a day" goal reads step counts from Health Connect. Step tracking is in the sideload APK only; the Google Play version does not support it yet.</sub></p>

## Install the server

**Needs:** Docker (Docker Desktop on macOS/Windows, Docker Engine on Linux).

| | RAM | CPU | Free disk |
|---|---|---|---|
| Minimum | 2 GB | 2 cores | 10 GB |
| Recommended | 4 GB | 4 cores | 20 GB |

The one-line installer opens when Kelo is published:

```bash
# macOS and Linux
curl -fsSL https://kelo.chat/install.sh | bash
```

```powershell
# Windows (PowerShell, Docker Desktop with WSL 2)
irm https://kelo.chat/install.ps1 | iex
```

The source code will be published here together with the installer.

The installer checks Docker, copies Kelo, and opens a four-step guided setup: check the server, pick a size, start Kelo, connect
your phone with a QR code. The AI model is chosen in the app afterwards. Full guide: [kelo.chat/install](https://kelo.chat/install).

### Reaching your server from the phone

- **Default address** — a free `https://<name>.kelo.chat` address (`kelo connect kelo --name <name>`).
- **Tailscale** — private, no public address.
- **Your own domain** — DuckDNS or any domain you own.


### Tip: run Kelo for free

You can run Kelo without paying for a server or a model: Oracle Cloud's Always Free tier includes an Arm server that stays free,
and [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) pools the free tiers of many model providers behind one
OpenAI-compatible address. Kelo's images build for Arm (linux/arm64) as well as x86, Chromium included.

1. **Make the server.** Sign up for [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/) and create a VM in your
   **home region** (Always Free works only there): shape **VM.Standard.A1.Flex** (Ampere, Arm) with **2 OCPUs and 12 GB of
   memory**, image **Ubuntu** (aarch64). That is the whole Always Free A1 allowance; the default boot volume is enough (200 GB of
   block storage is free in total). "Out of host capacity" means no free Arm machine is available right now: try another
   availability domain, or try again later.
2. **Install Kelo.** SSH in and run the installer above. It offers to install Docker, then opens the guided setup; **No limit**
   is a good size here, since the VM is only for Kelo. Choose the free `https://<name>.kelo.chat` address: it works through an
   outgoing tunnel, so you don't open any ports in Oracle's firewall.
3. **Add FreeLLMAPI on the same VM**, joined to Kelo's Docker network so Kelo reaches it by name while it stays closed to the
   internet:

   ```bash
   curl -fsSL https://freellmapi.co/install.sh | bash
   cd ~/freellmapi
   cat > docker-compose.override.yml <<'EOF'
   services:
     freellmapi:
       networks: [default, kelo]
   networks:
     kelo:
       name: kelo_agent   # Kelo's network when Kelo lives in ~/kelo (`docker network ls` shows it)
       external: true
   EOF
   docker compose up -d
   ```

   Open its dashboard from your laptop through SSH (`ssh -L 3001:127.0.0.1:3001 ubuntu@<server address>`, then
   `http://localhost:3001`), add the free API keys you signed up for, and copy the unified key.
4. **Point Kelo at it.** In the app, My → Model → **Custom endpoint**: server address `http://freellmapi:3001/v1`, API key = the
   unified key, model `auto` (FreeLLMAPI picks a free model and moves to the next one when a provider hits its limit).

Keep in mind:

- **Idle servers can be reclaimed.** Oracle may reclaim an Always Free instance that looks idle: over 7 days, 95th-percentile CPU,
  network and memory use all under 20%. An assistant waiting for messages is mostly idle, so this can happen. Oracle states that
  it does not charge for Always Free resources after you upgrade the account to Pay As You Go (only usage above the free limits is
  billed); upgrading is the commonly reported way to avoid reclaiming, though Oracle's page does not promise it. If you upgrade,
  set a budget alert.
- **Free models are weaker at tools.** Free-tier models are usually slower and less reliable at tool use (browsing, filling in
  forms, multi-step tasks) than paid ones, and their daily limits can run out. A paid model as main or fallback is safer for
  important work.
- **Their terms apply.** FreeLLMAPI is a separate MIT-licensed project meant for one person's own use. Oracle's terms and each
  model provider's free-tier terms (limits, data use, allowed use) still apply.
- **Not recommended:** Google Cloud's free e2-micro (1 GB of RAM, below Kelo's minimum) and AWS (no always-free server for new
  accounts since July 2025, only credits and a 6-month trial; Lambda can't run Kelo's long-running containers and browser).

### Useful commands

```bash
kelo status            # each copy: running or not, its address
kelo phone add         # sign another phone in (QR code)
kelo limits --choose   # how much RAM/CPU/storage Kelo may use
kelo instance create alex   # another person on the same server, with their own data and keys
```

## How it's built

- **Server** — Python (FastAPI) in Docker, with a sandboxed Chromium browser, an egress proxy that refuses private addresses, and a
  separate work room for running code. One SQLite database per copy; secrets are encrypted at rest.
- **Apps** — native on every platform: Kotlin + Compose (Android, desktop), SwiftUI (iOS). No web views.
- **Models** — Anthropic, OpenAI, Google, OpenRouter, Bedrock, Vertex, Azure, DeepSeek, Mistral, xAI, Groq, Together, LM Studio,
  any OpenAI-compatible server, and more.


## Privacy

**Your data stays on your server.** Your chats, notes, saved keys and cards are stored only on the Kelo server you run.
The developer has no way into it and does not collect your conversations.

kelo.chat helps with only a few things:
- **Default address:** it hands out free `name.kelo.chat` addresses. Traffic goes through Cloudflare straight to your server; use Tailscale or your own domain if you'd rather avoid this.
- **Notifications:** push notifications carry only an ID that something new happened. The app fetches the content from your server.
- **Google and GitHub sign-in:** it brokers the sign-in and the token exchange. Tokens pass through but are never stored.

Your messages are sent to the AI provider you choose (for example Anthropic or Google), under that provider's terms.
The source is public, so anyone can verify all of this. Policy: [kelo.chat/privacy](https://kelo.chat/privacy).

## Project status

Kelo is a solo project, built and maintained by one person, so some parts may still be rough.
I'll do my best to fix reported issues, but since I work on Kelo alongside a full-time job, I can't promise how quickly that will happen.
Issues and suggestions are always welcome.

## License

Kelo is free software under the [GNU Affero General Public License v3.0 or later](https://www.gnu.org/licenses/agpl-3.0.html). If you run a modified Kelo server
for other people, the AGPL asks you to offer them your source. The Kelo name, logo and mascot are not covered by the licence —
forks need their own.

Questions: support@kelo.chat
