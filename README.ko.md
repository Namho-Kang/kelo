<p align="center"><img src="docs/media/banner-ko.png" alt="Kelo — 서버도 모델도 내가 고르는 AI 비서" width="860"></p>

<p align="center">
  <b>원하는 곳에 둔 서버에서, 원하는 AI 모델로 돌아가는 나만의 AI 비서예요. 로컬 모델도 돼요.</b><br>
  휴대폰으로 말을 걸면 비서가 자기 브라우저와 연결한 서비스로 일을 처리하고, 되돌리기 어려운 일은 꼭 먼저 물어봐요.
</p>

<p align="center">
  <a href="https://kelo.chat/?lang=ko">kelo.chat</a> ·
  <a href="https://kelo.chat/install?lang=ko">설치</a> ·
  <a href="README.md">English</a>
</p>

---

## Kelo가 다른 점

**서버는 원하는 곳에.** Kelo 서버는 집에 있는 Mac, Linux 서버, Docker가 되는 미니 PC나 NAS, 클라우드 서버 어디에나 둘 수 있어요.
대화, 기억, 파일, 키는 그 서버에 남고, 휴대폰 앱은 리모컨이에요.

**모델은 원하는 걸로, 로컬 모델까지.** Claude, GPT, Gemini, DeepSeek, Qwen, Mistral, Grok, OpenRouter 등 약 30곳에서 고르거나,
LM Studio, Ollama 같은 OpenAI 호환 서버로 내 장비에서 직접 돌릴 수 있어요. 로컬 모델을 쓰면 대화가 밖으로 나가지 않아요.
기본 모델이 실패하면 다른 회사의 대체 모델이 이어받아요.

**먼저 물어봐요.** 찾아보는 일은 알아서 해요. 결제, 주문, 예약, 보내기, 삭제, 계정 변경처럼 무언가를 확정하는 일은 휴대폰으로
허락을 받은 다음에 해요. 사이트가 사람인지 확인하려고 하면, 우회하지 않고 브라우저를 사용자에게 넘겨줘요.

<p align="center">
  <img src="docs/media/kelo-selfhost-ko.gif" alt="Kelo 서버는 원하는 곳에" width="420">
  <img src="docs/media/kelo-models-ko.gif" alt="모델은 원하는 걸로, 로컬 모델까지" width="300">
</p>

## 할 수 있는 일

- **휴대폰으로 대화** — 글이나 말로, 사진과 문서(PDF, Word, Excel)도 함께. 다른 앱에서 "Kelo에게 보내기"로 넘길 수도 있어요.
- **자기 브라우저** — 사이트를 열어 읽고 비교하고, 양식을 채우고, 저장해 둔 로그인으로 들어가요. 실시간으로 보다가 직접 조작하고
  다시 맡길 수 있어요.
- **브라우저 없이 빠른 검색** — 가격, 비교, 사실 확인은 채팅 안에서 바로 웹 검색으로 답해요.
- **백그라운드 작업** — 오래 걸리는 일은 단계별로 보여 주며 이어서 하고, 필요한 질문은 채팅에 카드로 와요.
- **예약과 감시** — 정한 때에 일을 하고, 페이지에 특정 글자가 보이거나 숫자가 기준을 넘으면 알려 줘요.
- **다짐** — 목표를 적어 두면 계획, 단계, 숫자, 기한을 함께 챙겨요.
- **커넥터** — Gmail과 다른 메일함, 캘린더, GitHub, Notion, Linear, Todoist 등. 연결은 각 서비스의 로그인 화면에서 해요.
- **키체인과 카드** — 비밀번호와 키는 채팅이 아니라 입력 칸으로 넣고, 비서는 값을 볼 수 없어요. 카드는 내 휴대폰만 가진 키로
  잠글 수 있어요.
- **알림** — 일이 끝나거나 허락이 필요할 때. 푸시에는 내용이 담기지 않고, 앱이 내 서버에서 직접 가져와요.
- 앱은 **20개 언어**를 지원하고, Kelo는 사용자가 쓴 언어로 답해요.

<p align="center"><img src="docs/media/kelo-chat-approval-ko.gif" alt="요청, 결과, 허락 한 번" width="420"></p>

## 앱

| 플랫폼 | 상태 |
|---|---|
| Android | 네이티브 앱 (Kotlin + Jetpack Compose). 지금은 APK 직접 설치, Google Play 준비 중 |
| Windows / macOS 데스크톱 | 네이티브 앱 (Compose Multiplatform): 채팅, 허락, 실시간 브라우저, 트레이, 알림 |
| iOS | 네이티브 앱 (SwiftUI), 만드는 중 |

<p align="center">
  <img src="docs/media/ko-chat.webp" alt="채팅" width="200">
  <img src="docs/media/ko-work.webp" alt="작업" width="200">
  <img src="docs/media/ko-goal.webp" alt="다짐 계획" width="200">
  <img src="docs/media/ko-connectors.webp" alt="커넥터" width="200">
</p>
<p align="center"><sub>걷기 다짐 화면의 걸음 수는 Health Connect에서 읽어요. 걸음 수 연동은 APK 직접 설치 버전에서만 되고, Google Play 버전은 아직 지원하지 않아요.</sub></p>

## 서버 설치

**필요한 것:** Docker (macOS/Windows는 Docker Desktop, Linux는 Docker Engine)

| | RAM | CPU | 남은 디스크 |
|---|---|---|---|
| 최소 | 2 GB | 2코어 | 10 GB |
| 권장 | 4 GB | 4코어 | 20 GB |

한 줄 설치는 Kelo가 공개되는 날 열려요.

```bash
# macOS, Linux
curl -fsSL https://kelo.chat/install.sh | bash
```

```powershell
# Windows (PowerShell, WSL 2를 쓰는 Docker Desktop)
irm https://kelo.chat/install.ps1 | iex
```

소스 코드는 설치 프로그램과 함께 이 저장소에 공개할 예정이에요.

설치 프로그램이 Docker를 확인하고 Kelo를 복사한 다음, 4단계 안내 화면을 열어요: 서버 확인 → 크기 고르기 → Kelo 시작 → QR 코드로
휴대폰 연결. AI 모델은 그다음에 앱에서 골라요. 자세한 안내: [kelo.chat/install](https://kelo.chat/install?lang=ko)

### 휴대폰에서 서버에 닿는 방법

- **기본 제공 도메인** — 무료 `https://<이름>.kelo.chat` (`kelo connect kelo --name <이름>`)
- **Tailscale** — 공개 주소 없이 나만 접속
- **내 도메인** — DuckDNS나 갖고 있는 도메인


### 자주 쓰는 명령

```bash
kelo status            # 서버별 실행 여부와 주소
kelo phone add         # 다른 휴대폰 연결 (QR 코드)
kelo limits --choose   # Kelo가 쓸 RAM/CPU/디스크
kelo instance create alex   # 같은 서버에서 다른 사람용 Kelo (데이터와 키 따로)
```

## 구조

- **서버** — Docker 안의 Python(FastAPI). 격리된 Chromium 브라우저, 내부 주소로는 나가지 못하게 막는 프록시, 코드 실행용 작업실이
  따로 있어요. 서버마다 SQLite 데이터베이스 하나이고, 비밀값은 암호화해 저장해요.
- **앱** — 모든 플랫폼에서 네이티브: Kotlin + Compose(Android, 데스크톱), SwiftUI(iOS). 웹뷰를 쓰지 않아요.
- **모델** — Anthropic, OpenAI, Google, OpenRouter, Bedrock, Vertex, Azure, DeepSeek, Mistral, xAI, Groq, Together, LM Studio,
  OpenAI 호환 서버 등.


## 개인정보

데이터는 사용자가 운영하는 서버에 있어요. 고른 AI 회사는 답을 만드는 데 필요한 내용만 받아요. kelo.chat 운영자는 선택 기능인 중계
(로그인, 푸시, 기본 제공 도메인)만 운영하고, 사용자의 서버에는 접근할 수 없어요. 방침: [kelo.chat/privacy](https://kelo.chat/privacy?lang=ko)

## 프로젝트 상태

Kelo는 한 사람이 만드는 개인 프로젝트예요. 그래서 아직 다듬어지지 않은 부분이 있을 수 있어요.
문제를 알려 주시면 최선을 다해 고치겠지만, 본업과 함께 하는 일이라 언제까지 해결된다고 약속드리기는 어려워요.
이슈와 제안은 언제든 환영해요.

## 라이선스

Kelo는 [GNU Affero General Public License v3.0 이상](https://www.gnu.org/licenses/agpl-3.0.html)을 따르는 자유 소프트웨어예요. 고친 Kelo 서버를 다른 사람에게
서비스하면, 그 사람들에게 고친 소스를 공개해야 해요. Kelo 이름, 로고, 캐릭터는 라이선스에 포함되지 않아서, 포크는 자기 이름과
그림을 써야 해요.

문의: support@kelo.chat
