<p align="center"><img src="docs/media/banner-ko.png" alt="Kelo — 서버도 모델도 내가 고르는 AI 비서" width="860"></p>

<p align="center">
  <b>원하는 곳에 둔 서버에서, 원하는 AI 모델로 돌아가는 나만의 AI 비서예요. 로컬 모델도 돼요.</b><br>
  휴대폰으로 말을 걸면 비서가 자기 브라우저와 연결한 서비스로 일을 처리하고, 되돌리기 어려운 일은 꼭 먼저 물어봐요.
</p>

<p align="center">
  <a href="https://kelo.chat"><img src="https://img.shields.io/badge/docs-kelo.chat-5B4BDB?style=for-the-badge" alt="문서: kelo.chat"></a>
  <a href="https://kelo.chat/#install"><img src="https://img.shields.io/badge/install-Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker로 설치"></a>
  <a href="https://www.gnu.org/licenses/agpl-3.0.html"><img src="https://img.shields.io/badge/license-AGPL--3.0-8BC34A?style=for-the-badge" alt="라이선스: AGPL-3.0"></a>
  <a href="https://kelo.chat/#get-app"><img src="https://img.shields.io/badge/apps-Android%20%C2%B7%20iOS%20%C2%B7%20Windows%20%C2%B7%20macOS-E0675A?style=for-the-badge" alt="앱"></a>
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-E8834A?style=for-the-badge" alt="Read in English"></a>
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


### 팁: Kelo를 무료로 운영하기

서버와 모델에 돈을 쓰지 않고도 Kelo를 돌릴 수 있어요. Oracle Cloud의 Always Free에는 계속 무료인 Arm 서버가 들어 있고,
[FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi)는 여러 모델 회사의 무료 사용량을 OpenAI 호환 주소 하나로 묶어 줘요.
Kelo 이미지는 x86뿐 아니라 Arm(linux/arm64)으로도 빌드돼요. Chromium도 포함이에요.

1. **서버 만들기.** [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/)에 가입하고 **홈 리전**(Always Free는 홈 리전에서만
   돼요)에 VM을 만들어요. Shape는 **VM.Standard.A1.Flex**(Ampere, Arm), **2 OCPU · 메모리 12 GB**, 이미지는 **Ubuntu**(aarch64).
   Always Free A1 한도 전부예요. 기본 부트 볼륨이면 충분해요(블록 스토리지는 합쳐서 200 GB까지 무료). "Out of host capacity"가
   나오면 지금 남은 무료 Arm 서버가 없다는 뜻이니, 다른 가용성 도메인을 고르거나 나중에 다시 해 보세요.
2. **Kelo 설치.** SSH로 접속해 위의 설치 프로그램을 실행해요. Docker 설치도 제안하고, 이어서 안내 화면이 열려요. 이 VM은 Kelo
   전용이니 크기는 **제한 없음**이 알맞아요. 무료 `https://<이름>.kelo.chat` 주소를 고르세요. 바깥으로 나가는 터널로 연결돼서
   Oracle 방화벽에 포트를 열 필요가 없어요.
3. **같은 VM에 FreeLLMAPI 추가.** Kelo의 Docker 네트워크에 붙여서, 인터넷에는 닫아 둔 채 Kelo가 이름으로 찾아가게 해요.

   ```bash
   curl -fsSL https://freellmapi.co/install.sh | bash
   cd ~/freellmapi
   cat > docker-compose.override.yml <<'EOF'
   services:
     freellmapi:
       networks: [default, kelo]
   networks:
     kelo:
       name: kelo_agent   # Kelo가 ~/kelo에 있을 때의 네트워크 이름 (`docker network ls`로 확인)
       external: true
   EOF
   docker compose up -d
   ```

   대시보드는 노트북에서 SSH로 열어요(`ssh -L 3001:127.0.0.1:3001 ubuntu@<서버 주소>` 후 `http://localhost:3001`). 가입해 둔
   무료 API 키를 넣고, 통합 키(unified key)를 복사해요.
4. **Kelo에 연결.** 앱의 마이 → 모델 → **Custom endpoint**에서 서버 주소 `http://freellmapi:3001/v1`, API 키는 통합 키, 모델은
   `auto`로 정해요(FreeLLMAPI가 무료 모델을 고르고, 한 곳이 한도에 걸리면 다음 곳으로 넘겨요).

알아 둘 점:

- **놀고 있는 서버는 회수될 수 있어요.** Oracle은 7일 동안 CPU(95번째 백분위수), 네트워크, 메모리 사용률이 모두 20% 미만인
  Always Free 인스턴스를 회수할 수 있어요. 메시지를 기다리는 비서는 대부분 한가해서 이 조건에 걸릴 수 있어요. Oracle은 계정을
  Pay As You Go로 업그레이드해도 Always Free 자원에는 요금을 받지 않는다고 밝히고 있어요(무료 한도를 넘는 사용량만 청구).
  업그레이드하면 회수되지 않는다는 경험담이 많지만, Oracle 문서가 약속하는 내용은 아니에요. 업그레이드한다면 예산 알림을
  설정해 두세요.
- **무료 모델은 도구 사용이 약해요.** 무료 모델은 보통 유료 모델보다 느리고, 도구 사용(브라우저 조작, 양식 입력, 여러 단계 작업)이
  덜 안정적이에요. 하루 한도가 바닥날 수도 있어요. 중요한 일에는 유료 모델을 기본이나 대체 모델로 두는 편이 안전해요.
- **각 서비스의 약관이 적용돼요.** FreeLLMAPI는 한 사람이 직접 쓰도록 만든 별도의 MIT 라이선스 프로젝트예요. Oracle 약관과 모델
  회사마다의 무료 사용 약관(한도, 데이터 사용, 허용 용도)도 그대로 적용돼요.
- **권하지 않는 곳:** Google Cloud의 무료 e2-micro(RAM 1 GB로 Kelo 최소 사양에 못 미쳐요), AWS(2025년 7월부터 새 계정에는 계속
  무료인 서버가 없고 크레딧과 6개월 체험뿐이에요. Lambda로는 Kelo의 상시 실행 컨테이너와 브라우저를 돌릴 수 없어요).

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

**당신의 정보는 당신의 서버에만 있어요.** 대화, 메모, 저장한 키와 카드는 모두 당신이 운영하는 Kelo 서버에 저장돼요.
개발자에게는 그 서버에 들어갈 방법이 없고, 당신의 대화를 따로 받아 보관하지도 않아요.

kelo.chat은 몇 가지 일만 대신해 줘요.
- **기본 제공 도메인:** `이름.kelo.chat` 주소를 만들어 줘요. 접속은 Cloudflare를 거쳐 당신의 서버로 바로 가요. 이 경로가 싫다면 Tailscale이나 직접 가진 도메인으로 연결할 수 있어요.
- **알림:** 휴대폰에 알림을 보낼 때 "새 소식이 있음"이라는 신호만 전달해요. 내용은 앱이 당신의 서버에서 직접 가져와요.
- **Google·GitHub 연결:** 로그인을 중계하고 토큰을 발급받는 일을 해요. 토큰은 저장하지 않아요.

대화는 당신이 고른 AI 서비스(예: Anthropic, Google)로 보내져 답을 받아요. 그 처리는 각 회사의 정책을 따라요.
소스 코드가 공개되어 있어서, 위 내용을 누구나 직접 확인할 수 있어요. 방침: [kelo.chat/privacy](https://kelo.chat/privacy?lang=ko)

## 프로젝트 상태

Kelo는 한 사람이 만드는 개인 프로젝트예요. 그래서 아직 다듬어지지 않은 부분이 있을 수 있어요.
문제를 알려 주시면 최선을 다해 고치겠지만, 본업과 함께 하는 일이라 언제까지 해결된다고 약속드리기는 어려워요.
이슈와 제안은 언제든 환영해요.

## 라이선스

Kelo는 [GNU Affero General Public License v3.0 이상](https://www.gnu.org/licenses/agpl-3.0.html)을 따르는 자유 소프트웨어예요. 고친 Kelo 서버를 다른 사람에게
서비스하면, 그 사람들에게 고친 소스를 공개해야 해요. Kelo 이름, 로고, 캐릭터는 라이선스에 포함되지 않아서, 포크는 자기 이름과
그림을 써야 해요.

문의: support@kelo.chat
