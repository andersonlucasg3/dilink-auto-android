# DiLink-Auto

Espelhe os apps do seu celular na tela embutida da central do carro, com interação completa por toque. Código aberto, sem exigir os serviços do Google.

Este é o **repositório original** do DiLink-Auto ([andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android)). Em novembro de 2026 ele incorporou de volta os 252 commits do fork de longa data [ID-VerNe/dilink-auto-android](https://github.com/ID-VerNe/dilink-auto-android), que manteve o projeto vivo enquanto o upstream estava parado. O desenvolvimento continua focado em **ROMs chinesas de celular + centrais BYD DiLink**, com ajuste dedicado para a **DiLink 4.0 de entrada**, para que ela deixe de ser uma "apresentação de slides" e fique utilizável em hardware fraco.

**Documentation / Documentação / 文档 / Документация / Documentation / Құжаттама / Документація / Hujjatlar**

O GitHub não mostra automaticamente a documentação no seu idioma. Escolha um:

| Idioma | README | Setup | Arquitetura | Cliente | Servidor | Protocolo | Progresso |
|----------|--------|-------|--------------|--------|--------|----------|----------|
| Português (BR) | **esta página** | [Setup](./setup.md) | [Arquitetura](./architecture.md) | [Cliente](./client.md) | [Servidor](./server.md) | [Protocolo](./protocol.md) | [Progresso](./progress.md) |
| English | [README](../../README.md) | [Setup](../setup.md) | [Arch](../architecture.md) | [Client](../client.md) | [Server](../server.md) | [Proto](../protocol.md) | [Progress](../progress.md) |
| 简体中文 | [README](../zh-CN/README.md) | — | — | — | — | — | — |
| Français | [README](../fr/README.md) | — | — | — | — | — | — |
| Русский | [README](../ru/README.md) | — | — | — | — | — | — |
| Беларуская | [README](../be/README.md) | — | — | — | — | — | — |
| Қазақша | [README](../kk/README.md) | — | — | — | — | — | — |
| Українська | [README](../uk/README.md) | — | — | — | — | — | — |
| Oʻzbekcha | [README](../uz/README.md) | — | — | — | — | — | — |

Um travessão significa que aquele idioma hoje tem apenas o README; os guias aprofundados ainda são somente em inglês e estão sendo traduzidos.

Versão atual: **0.19.0** (`versionCode 26111724`). Downloads: [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).

O versionamento segue o esquema semver deste repositório: `app.versionName` é `MAJOR.MINOR.PATCH` (pré-releases acrescentam `-dev-N`) e `app.versionCode` é um inteiro monotônico mantido à mão. As tags são `v0.19.0` (release) e `v0.19.1-dev-3` (pré-release).

O problema que ele resolvia originalmente: celulares com ROM chinesa (Xiaomi HyperOS etc.) não conseguem instalar o Android Auto, e a central só fala Android Auto — nada faz a ponte. O DiLink-Auto funciona com qualquer celular Android 10+, com ou sem serviços do Google.

---

## Hardware testado

**BYD Qin PLUS DM-i 2023, versão Champion 55KM Leading.** A central é a DiLink 4.0 de entrada — Snapdragon 439 (8x Cortex-A53, todos núcleos pequenos), Adreno 505, 4 GB de RAM, 16 GB de eMMC, tela 1280x800, Wi-Fi só 2.4 GHz, Android 9 / API 28, decodificação H.264 por hardware limitada a 1080p. Todo o ajuste de desempenho foi feito contra esse hardware.

---

## O que mudou desde a v0.18.0-dev

A versão curta da evolução entre o ponto do fork (`84df5cb`, upstream/main) e a `main` atual. Cada item corresponde a commits reais, não a planos; a cronologia detalhada está em [docs/progress.md](../progress.md).

### 1. Arquitetura de streaming direto do VD

O celular não faz mais relay de vídeo, toque nem áudio. O VD server faz bind em `9638` (vídeo), `9639` (entrada) e `9640` (áudio, aberto após uma negociação em duas vias) e conversa direto com o receptor. Isso elimina 4 sockets + 2 trocas de contexto de processo por frame, sobrando 2 sockets + 0 trocas de contexto. O celular passa a ser um orquestrador puro: handshake e negociação de capacidades, ciclo de vida do VD (`VD_PORTS_BOUND`) e roteamento do log da central. O protocolo ganhou `FrameCodec.readFrameBlocking()`, `MessageType.VD_PORTS_BOUND` e `AudioPayload`, e o `VirtualDisplayClient` ficou reduzido ao ciclo de vida.

### 2. Ajuste para a DiLink 4.0 de entrada (trabalho central)

Contra o hardware acima:

- **Tamanho de codificação limitado a 1920x1080.** O celular ampliava o VD até os pixels físicos (1080x2340 → codificação 2340x1658) para evitar o corte do IME; isso passava do limite de decodificação por hardware do 439, caía para decodificação por software, e os 8x A53 rodavam H.264 acima de 1080p a poucos fps. Corrigido desacoplando o tamanho do VD do tamanho de codificação — o VD continua ampliado (mantendo a correção do IME), o tamanho de codificação segue a viewport informada pelo carro e é limitado (clamp) a 1920x1080.
- **Bitrate 8 Mbps → 4 Mbps.** A taxa sustentada dentro de um ambiente de carro com 2.4 GHz mediu 5-15 Mbps, então 8 Mbps não deixava folga. O fallback adaptativo também foi apertado: piso de 1.5 Mbps, janela de recuperação de 2 s, passos de 0.5 Mbps (antes ficava oscilando entre 8 e 2 Mbps).
- **Taxa de quadros 30 → 24 → 20.** O padrão agora é 20 fps (50 ms por frame). Veja a issue #71: 2.5 h de navegação deixavam o celular quente, e baixar a taxa de quadros é a segunda válvula de alívio, ao lado do limite do upscale do VD.
- **TextureView → SurfaceView.** Pula a composição GL de tela cheia por frame do Adreno 505 (cerca de 2-5 ms/frame). Um gate `outputSurfaceValid` evita renderizar em uma surface destruída quando o decodificador é escondido/mostrado ao longo da navegação.
- **Prioridades de thread.** O A53 não tem núcleos grandes, então dicas de escalonamento importam: as threads de encoder/decoder/drenagem de socket usam `THREAD_PRIORITY_URGENT_DISPLAY`, o `LifeWriter` usa `THREAD_PRIORITY_BACKGROUND`.
- **Padrões de log.** Em builds release o `carLogEnabled` vem desligado por padrão (antes vinha ligado, e os frames de log competiam com o vídeo pelo link de 2.4 GHz); `ConcurrentLinkedQueue.size()` (O(n)) virou um `AtomicInteger`.
- **Limpeza do AppIconCache na desconexão.** Cerca de 80-150 PNGs se acumulavam para sempre no eMMC de 16 GB (5-9 MB de heap). Um novo `clear()` roda em `handleDisconnect`, e `prepareAll` decodifica em paralelo com um `Semaphore(4)`, reciclando bitmaps intermediários.
- **`Debug.getPss()` removido.** Essa chamada binder obsoleta rodava em toda conexão e custava 100-500 ms no A53; agora ela é coletada só em caso de crash.
- **`@Immutable AppTileData`.** O `AppInfo` carrega um `ByteArray`, o que tornava o `AppTile` não pulável e forçava recomposição completa a cada reatribuição de `appList`. Um wrapper imutável que exclui o `iconPng` deixa a grade pular a recomposição.

### 3. Compatibilidade com central API 28

Na central real da BYD (`k65v1_64_bsp`, Android 9 / API 28), `MediaCodecInfo.isHardwareAccelerated()` (API 29+) lança `NoSuchMethodError` e derruba o processo. O seletor especulativo de decodificador por hardware foi removido e `createDecoderByType` foi restaurado (`REGULAR_CODECS` já ordena decodificadores de hardware primeiro; na BYD ele escolhe `OMX.qcom.video.decoder.avc`). O `minSdk` subiu de 24 → 26 no `app-server` e no `protocol`, e o gate de lint NewApi foi reativado. `am display move-stack` (API 29+) agora é condicionado a `SDK_INT` para API 26-28 e registra log em vez de tratar uma falha de shell como sucesso.

### 4. UI e interação

- **Redesenho da barra de navegação:** reduzida a Ejetar (desconectar) / Home / Voltar. O botão de notificações, a barra de recentes, o relógio e as informações de rede foram removidos, junto com código morto em quatro módulos (`NotificationScreen` e `RecentAppsState` no lado carro; `NotificationService` no lado celular; os tipos de mensagem `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*`). **Encaminhamento de notificações não existe neste código.**
- **Fixar apps:** toque longo no ícone do app para Fixar no topo / Desafixar, persistido em SharedPreferences.
- **Lista de permissão de apps:** uma nova `AllowlistScreen` deixa o usuário escolher quais apps são enviados para a central; os não selecionados são filtrados em `sendAppList`, encolhendo o payload da rede e o trabalho de decodificação de ícones na central. Apps de navegação comuns já vêm selecionados na primeira execução. Alternar envia `ACTION_ALLOWLIST_UPDATED` para que um serviço em execução reenvie a lista e a grade da central atualize na hora.
- **Suporte a apps em modo retrato:** o cálculo dinâmico de DPI (`VideoConfig.calculateOptimalDpi`) impede que apps retrato (Amap, WeChat) fiquem espremidos numa faixa fina dentro do VD paisagem; o IME é restaurado no fim da sessão (antes o celular mantinha o IME do linkpc); índice de injeção de multitouch corrigido.
- **Sobrescrita de DPI na inicialização do lado carro:** o handshake ganhou um campo `dpiOverride` (0 = automático, [120, 480] = valor do usuário), alimentado pela configuração `startup_dpi` da central e enviado nas duas orientações. Corrige o DPI padrão pequeno demais em 1280x800.
- **Tokens de cor e ajuste de tipografia:** `Color.Gray` / `0xFF888888` espalhados foram movidos para `onSurfaceVariant`, vermelhos de erro duplicados foram unificados, rótulos de 12sp → 14sp. "WiFi Direct Mode" foi renomeado para "WiFi ADB Mode"; o texto de `adb tcpip` do lado carro foi removido.
- **Correção da tela preta na rotação:** `onCarViewportChanged` coloca o estado em CONNECTING e refaz o deploy do VD na rotação, mas o gate do layout de streaming usava `isConnected` (falso durante os ~2 s de redeploy), então o `CarShell` caía em `CarLaunchScreen`, o `TextureView` saía da composição e a nova surface não conseguia reiniciar o decodificador. O gate agora também aceita `state == CONNECTING && appList.isNotEmpty()`, mantendo o layout de streaming (e seu overlay de espera de vídeo) no lugar durante a rotação.

### 5. Estabilidade da sessão

Uma sessão ativa nunca é morta por uma tentativa de reconexão — antes, uma nova tentativa do gateway Wi-Fi ou uma conexão manual podiam matar uma sessão em andamento. O laço de reconexão para depois de 3 falhas consecutivas de ADB. O ADB TCP reconecta quando o IP do celular muda e cai automaticamente para ADB TCP quando o USB não está disponível.

### 6. Estabilização do PipelineServer

O `VirtualDisplayServer` (1287 linhas, 9 threads, 2 filas) virou `PipelineServer` (thread única, 0 filas): relógio de frame → render GL → drenagem do encoder → escrita TCP, com controle de fluxo natural (uma escrita TCP travada bloqueia o próximo `eglSwapBuffers`). Também corrigiu: uma textura GL criada em um contexto EGL temporário e depois destruída (a thread do pipeline ficava com a textura 0); o `app_process` sendo morto quando o shell não interativo saía (corrigido com `shellBackground`, que mantém o stream do shell anexado); e a ordem de restauração do painel físico (antes chamava `input keyevent 224` depois que o shell já tinha fechado).

### 7. Chave do log de diagnóstico

Configurações → Debug ganhou uma chave de log de diagnóstico. Desligada, há zero escritas em disco. O estado é propagado para a central pela mensagem de dados `LOG_TOGGLE` e, desde a issue #72, também é sincronizado automaticamente após cada handshake, para que o log da central siga a chave do celular em vez de ser silenciosamente redefinido na reconexão. Padrões: ligado em builds debug/pré-release, desligado em release; uma escolha explícita do usuário persiste.

### 8. Interface em chinês simplificado

Cliente e servidor trazem `values-zh-rCN` / `values-zh`. Existem strings de app em inglês, português (BR), francês, russo, bielorrusso, cazaque, ucraniano, uzbeque e chinês.

### 9. Saúde do código

Várias rodadas de auditorias SRP/DRY e refatorações: constantes compartilhadas entre módulos (`AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp`, `AppIconLimits`) e colaboradores extraídos.

- **`protocol-core` / `protocol`:** `NioWriter` (escrita em socket-channel com spin e timeout), `BackoffPolicy` (backoff exponencial parametrizado), `DecodeLivenessTracker`, `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator`, `AppIconLimits`; código morto de `UsbAdbConnection` removido.
- **`vd-server`:** `AsyncSocketSender`, `AudioEncoderSelector`, `ProcessWatchdog`, `VideoEncoderSelector`, `PortAcceptor`, `ActivityDumpParser`, `VdDisplayHealer`.
- **`app-client`:** `ConnectionService` enxugado; `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository`, `SessionState` extraídos; `InputInjectionService` marcado como obsoleto.
- **`app-server`:** `CarConnectionService` reduzido a ~407 linhas; a máquina de estados foi dividida em `CarSessionCoordinator` (máquina de estados multicanal 9637/9638/9639/9640 e re-handshake), `CarAdbTracker` (rastreamento de ADB USB/TCP) e `CarAppListReceiver` (lista de apps e limites de segurança de ícones).
- **`app-desktop`:** consome `AppIconLimits`, `DecodeLivenessTracker`, `BackoffPolicy`, alinhado às primitivas mais recentes do protocolo.

### 10. Correção de vazamento de VD, limpeza idempotente, auto-recuperação de tela preta

**Causa raiz:** toda reconexão vazava um VirtualDisplay. O código antigo usava `pkill -9`, que ignora o hook de shutdown da JVM, então `PipelineServer.cleanup()` nunca rodava — o VD não era liberado, o painel físico continuava desligado e a configuração `screen_off_timeout` era perdida (o snapshot capturava a sentinela `2147483647` que o próprio código havia gravado). Depois de algumas reconexões a tela da central ficava preta.

**Correção (12 arquivos):** uma sentinela de pedido de parada (`/sdcard/DiLinkAuto/stop-request`) que o watchdog do motor consome para sair pelo caminho normal e rodar o `cleanup()` completo (a ART não roda hooks de shutdown em um SIGTERM puro); uma sonda baseada em `pgrep -f` (o toybox rejeita `pkill -0`); um fallback de `kill -9` que localiza o pid com `pgrep -f` em vez de `pkill -f` (este último também mata o shell wrapper e torna o resultado do ADB ilegível); um `cleanupGuard` do tipo `AtomicBoolean` para que os 7 pontos de chamada não rodem `cleanupSession()` repetidamente (uma desconexão chegava a disparar 9 chamadas "Force-waking physical display"); `waitForVdServerExit()` dos dois lados para que dois motores nunca disputem o mesmo VD e as mesmas portas; `PhoneDisplayRestorer` em escopo próprio de tempo de vida do processo com `NonCancellable`; um `cleanup()` reordenado; validação do valor em `DisplayPowerController.restoreSetting()`; e `BlackScreenDetector` (`BLACK_SCREEN_SUSTAIN_MS = 3000`) movido para `protocol-core` para central e desktop compartilharem, com `rehandshakeForBlackScreen()` limitado a no máximo uma tentativa por sessão.

A automação de regressão fica em `scripts/`: `verify-blackscreen-fix.sh`, `e2e-reconnect-cleanup.sh`, `verify-reconnect-cleanup.sh` (32 verificações conduzidas pela UI real do desktop; última execução completa 32/32, 2m48s).

### 11. Receptor Windows `:app-desktop`

Além da central, o mesmo protocolo atende um **programa de desktop Windows** como receptor: a tela do celular é espelhada numa janela do PC e o mouse vira toque. O módulo fica em `app-desktop/` (Kotlin/JVM + Compose Desktop 1.6.2, JDK 17) e reutiliza o `protocol-core` — nenhuma mudança no lado celular.

- **Transporte:** três conexões TCP direto com o celular — 9637 (controle + dados), 9638 (vídeo), 9639 (entrada) — igual à central, sem relay.
- **Divisão do deploy:** o `connectionMethod` na resposta do handshake decide quem faz o deploy do VD server. Com Shizuku é o celular; caso contrário o desktop faz o deploy com o `adb.exe` local (`connect` → matar o motor antigo **e esperar que ele realmente saia** → `exec app_process`), o que exige `"dev_mode": true` no `config.json`.
- **Decodificação:** decodificação por software com JavaCV/FFmpeg mais decodificação por hardware D3D11VA, com fallback automático quando o hardware falha na inicialização ou produz zero frames.
- **UI:** três páginas de navegação — Mirror / Apps (launcher) / Display (sobrescrita de DPI, chave de decodificação por hardware, "Apply and reconnect", manter acordado, tela física do celular).
- **Auto-recuperação:** 10 s de tela preta contínua reconecta automaticamente, no máximo duas vezes por processo.
- **Configuração e logs:** `%APPDATA%\DiLinkAuto` (`config.json` / `desktop.log`), alterável via `DILINK_DESKTOP_HOME`; `DILINK_ADB` define o caminho do adb.

```bash
# App-image autocontido (JRE embutido): copie build/compose/binaries/main/app/DiLinkAuto
./gradlew :app-desktop:createDistributable

# Sonda headless: valida só handshake/VD/fps; sem Shizuku, defina DILINK_DEV_MODE=1 para deploy por ADB
app-desktop --probe
```

> Status: o caminho Shizuku é equivalente ao do lado carro; o **caminho de deploy por ADB ainda não foi verificado em dispositivo real**. O módulo não faz parte dos assets de release (sua dependência JavaCV/FFmpeg é GPL, enquanto este projeto é MIT).

### 12. Testes unitários e endurecimento de segurança

**Cobertura de testes:** dois módulos não tinham source set de teste nenhum (`vd-server` não tinha `src/test`, e o do `protocol` estava vazio). A partir de `8157c86`, testes unitários JUnit4 foram adicionados nos 6 módulos, cobrindo a lógica de maior risco que não tinha teste (framing do protocolo, primitivas de criptografia do ADB, argumentos de deploy do VD, bitrate adaptativo, gate de quoting de shell, gates de versão, classificação de status de instalação, formato do relatório de crash). Uma rodada extra de "costuras de testabilidade" que preservam comportamento adicionou mais 44 casos. Hoje o repositório tem 1.200+ casos `@Test` nos 6 módulos (JUnit4 + kotlinx-coroutines-test, sem Robolectric/Mockito/MockK; os módulos Android usam `isReturnDefaultValues=true` com fakes escritos à mão). O inventário, as invariantes entre módulos travadas e as questões abertas estão em [docs/IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md).

**Endurecimento de peers (`a7d2b72`):** todo peer de socket era tratado como confiável — um frame malformado de 6 bytes podia matar o processo do celular ou o VD server com UID de shell. Os cinco decodificadores agora fazem checagem com `require()` antes do `getX()` cru; as corrotinas de leitura capturam `Throwable`, registram a causa e desconectam em vez de morrer; o `MAX_PAYLOAD_SIZE` caiu de 128 MB para 16 MB e o buffer do `NioReader` tem um teto rígido recuperado nos limites de frame.

**Autorização do canal de VD (`8601fb1`):** as portas 9638/9639 aceitavam qualquer host na mesma rede Wi-Fi, permitindo que qualquer um injetasse toques e rodasse `am start` / `pm uninstall` como shell. Agora elas aceitam apenas conexões do receptor que está sendo atendido; peers rejeitados são fechados e o servidor continua esperando.

**Quoting de shell (`5eae3e7`):** as linhas de comando do `VdDeploy` passam por `shellQuote`, então strings controladas por peers nunca chegam cruas ao `sh`.

**Desktop (`1f6836a`):** `adbPort` / `jarPath` vindos da resposta do handshake são validados antes do uso (antes iam direto para o deploy, ou seja, execução de código arbitrário a partir de qualquer peer); processos filhos do adb não vazam mais.

**Sessão do carro (`bc2634c` / `35d7821`):** as sessões usam um latch de geração para que o `cleanupGuard` não seja consumido cedo pelo caminho de mudança de rede; a recuperação de tela preta não chama mais `stop()` no decodificador (ninguém o reiniciava, então o caminho de recuperação garantia uma tela preta permanente).

**Cadeia de suprimentos do release:** o keystore de release era comitado; agora ele é só referenciado, com os secrets de assinatura no CI (o histórico do git ainda precisa ser limpo). O CI não roda mais código de PR em runner self-hosted (`9517205`) nem produz APKs unsigned / com strip excessivo e artefatos órfãos (`efde81f`). Todos os workflows rodam em `ubuntu-latest` (GitHub-hosted).

---

## Situação atual das funcionalidades

**Funcionando:**

- Espelhamento de vídeo H.264, 20 fps padrão, 4 Mbps CBR, perfil Main (ajustado para a DiLink 4.0 de entrada; funciona em centrais mais fortes também)
- Espelhamento de áudio: AAC-LC 48 kHz estéreo a 128 kbps em uma conexão TCP dedicada (9640), buffer de jitter de 150 ms, preenchimento constante de silêncio a 46.875 fps, negociação bidirecional de capacidade `FEATURE_AUDIO`, chave de áudio persistida no cliente
- Entrada de toque completa (multitouch, pinça para zoom)
- Launcher de apps: busca, ordem alfabética, ícones de 64dp, menu de toque longo (desinstalar, informações do app, fixar)
- Lista de permissão de apps: só os apps selecionados são enviados para a central
- Sobrescrita de DPI na inicialização do lado carro (corrige o DPI padrão pequeno em 1280x800)
- Acompanhamento da orientação: o VD é recriado quando a central gira, apps retrato não ficam mais espremidos numa faixa
- Suporte a Shizuku: deploy do VD server sem ADB USB e autoatualização do app da central com `pm install` silencioso
- Autoatualização do app da central: o celular compara `versionCode` e atualiza o app da central por Wi-Fi ADB
- Tela do celular desligada durante o streaming (economiza bateria); o painel físico é restaurado na desconexão
- Onboarding guiado de permissões
- Interface em chinês simplificado mais 8 outros idiomas de UI
- Arquitetura de VD direto: sem relay no celular; vídeo, toque e áudio passam direto
- Correção do vazamento de VD: parada graciosa por sentinela (`cleanup()` completo, `kill -9` só como fallback de timeout), guarda de limpeza idempotente, espera pela saída do VD
- Auto-recuperação de tela preta: a central refaz o VD (re-handshake) depois de 3 s de tela preta contínua; o receptor Windows usa o mesmo detector com janela de 10 s
- Receptor Windows (`app-desktop`): janela de espelhamento, mouse como toque, launcher de apps, painel de DPI/decodificação por hardware, app-image via `jpackage`; o caminho de deploy por adb está implementado mas **ainda não foi verificado em aparelho real**

**Removido (em relação ao upstream pré-merge):**

- Encaminhamento de notificações (notificações do celular → tela da central): botão de notificações da barra de navegação, lista de notificações e as mensagens de protocolo `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` não existem mais
- Barra de recentes, relógio, informações de rede

**Não implementado:**

- Controles de mídia
- Widgets de navegação (o espelhamento de áudio está completo)

**Limitações conhecidas:**

- O Samsung Auto Blocker precisa ser desativado para o ADB USB funcionar (Configurações → Segurança → Auto Blocker → desligar).
- A gestão de bateria da Samsung precisa de uma isenção explícita.
- O Samsung Knox pode mostrar um aviso de segurança na primeira vez que o display virtual é acessado.
- O hotspot do celular precisa ser ligado manualmente (restrição do Android 16).
- Artefatos visuais ocasionais por uma corrida de reinício do decodificador; o próximo keyframe (~1 s) recupera.
- A latência do streaming fica em torno de 100-200 ms sob carga.
- Alguns apps não preenchem a tela (letterbox / somente retrato). O DiLink-Auto espelha um display virtual paisagem, então apps que não suportam paisagem mostram faixas pretas — isso é decisão do app.
- Em centrais API 26-28, se a reflexão de `DisplayControl` falhar, o fallback de shell `cmd display power-reset` / `power-off` também é API 29+, então o painel físico pode não voltar (ele registra log em vez de falhar em silêncio).
- O progresso de instalação não aparece em idiomas de UI diferentes do inglês: `InstallStatus.parse` classifica as strings de status localizadas por substrings em inglês, então chinês (e outros) caem em `IDLE`. Travado por teste, não corrigido ([C-6](../IMPLEMENTATION_REPORT_TESTING.md)).
- A primeira instalação do app da central poderia ser pulada com um número de versão extremo: `readInstalledVersion` retorna `"0"` quando o app da central está ausente, então um `versionName` embutido `0` / `0.0` / `0.0.0` compara como "atualizado". O `versionName` atual é `0.19.0`, então isso não dispara ([C-5](../IMPLEMENTATION_REPORT_TESTING.md)).

---

## Como funciona

1. **Ligue o hotspot** — ative o hotspot Wi-Fi do celular; a central se conecta nele.
2. **Plugue** — conecte o celular na porta USB da central (ou entre na mesma rede Wi-Fi).
3. **Instalação automática** — o celular instala o app da central por Wi-Fi ADB (só na primeira vez, com um toque).
4. **Conexão automática** — quatro fluxos TCP Wi-Fi: controle (9637), vídeo (9638), toque (9639), áudio (9640, aberto após negociação).
5. **Use seus apps** — abra qualquer app no launcher da central; ele roda no celular, aparece na tela da central e recebe o toque.

O celular roda os apps em um display virtual e os codifica em H.264, que é enviado para a central. Os eventos de toque da tela da central são injetados de volta no celular como eventos de toque reais. A tela física do celular fica desligada para economizar bateria e continua utilizável de forma independente.

Arquitetura, fluxo de conexão e decisões de projeto: [docs/architecture.md](../architecture.md).

---

## Requisitos

**Celular:**

- Android 10+ (`minSdk = 29`)
- Depuração USB habilitada (Opções do desenvolvedor)
- Permissão de acesso a todos os arquivos (guiada no primeiro uso)

**Carro:**

- BYD DiLink 3.0+, verificado no **BYD Qin PLUS DM-i 2023 Champion 55KM Leading** (DiLink 4.0 de entrada, Snapdragon 439 / Android 9 / 1280x800)
- Android 8.0+ (`minSdk = 26`)
- Uma porta USB-A livre (ou a mesma rede Wi-Fi)

**O hotspot do celular precisa estar ligado** — a central se conecta nele. Sem código de pareamento, sem conta Google.

**Não é preciso conexão com a internet.** O DiLink-Auto transmite localmente pelo hotspot do celular; celular e central conversam direto. A internet só é necessária para os apps que rodam no seu celular.

---

## Instalação

Baixe o APK em [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest), ou compile você mesmo:

1. **Build:** `./gradlew :app-client:assembleDebug`
2. **Instale no celular:** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **Ative a depuração USB** (Configurações → Opções do desenvolvedor)
4. **Abra o DiLink-Auto** e siga o onboarding de acesso a todos os arquivos
5. **Ligue o hotspot e plugue no carro** — o app da central é instalado automaticamente na primeira execução por Wi-Fi ADB

O APK da central e o JAR do VD server são ambos embutidos no APK do celular, então nada precisa ser instalado separadamente na central e instalar o app da central não precisa de internet.

> **Aviso sobre troca de assinatura:** sair de um build assinado com debug para um assinado oficialmente muda o certificado — **desinstale o pacote antigo primeiro**, senão a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Regras completas de release e assinatura: [docs/release.md](../release.md).

---

## Estrutura do projeto

O APK do celular (`app-client`) embute o APK da central (`app-server`) e o JAR do VD server (`vd-server`). O receptor é substituível: o mesmo protocolo roda na central (`app-server`) ou no Windows (`app-desktop`).

```
DiLink-Auto/
├── protocol-core/  biblioteca compartilhada JVM pura (frames, mensagens, constantes, sequência de deploy do VD; sem dependência de Android)
├── protocol/       adaptação de plataforma (ADB, hooks de Android, conexões USB/TCP)
├── app-client/     APK do celular — orquestração, deploy do VD, autoatualização da central, lista de permissão de apps
├── app-server/     APK da central — UI, máquina de estados da conexão, decodificador de vídeo
├── app-desktop/    receptor Windows — janela de espelhamento, decodificação FFmpeg, deploy por adb, jpackage
├── vd-server/      servidor VirtualDisplay (compilado em JAR, com deploy feito pelo celular)
├── scripts/        automação (verificação de log, reconexão/limpeza ponta a ponta)
├── docs/           documentação (ponto de entrada multilíngue)
└── gradle/         sistema de build
```

| Módulo | Papel | minSdk |
|--------|------|--------|
| `protocol-core` | Biblioteca de protocolo compartilhada JVM pura (Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector) | — |
| `protocol` | Biblioteca de protocolo compartilhada (UsbAdbConnection, AdbProtocol, VideoConfig, ConnectionFactory, ConnectionKeepAlive, UsbAdbKeyStore) | 26 |
| `app-client` | App do celular (ConnectionService, ShizukuVdDeployer, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository, FileLog) | 29 |
| `app-server` | App da central (CarSessionCoordinator, CarAdbTracker, CarAppListReceiver, AudioPlayer, VideoDecoder, CarShell, HomeScreen, PersistentNavBar) | 26 |
| `app-desktop` | Receptor Windows (DesktopApp, DesktopConnectionService, VideoDecodePipeline, SwingVideoView) | — |
| `vd-server` | Processo com UID de shell (PipelineServer, AudioPipeline, AudioEncoderSelector, PortAcceptor, TouchInjector, GlPipeline, DisplayPowerController) | 29 |

---

## Build

```bash
# Compila o APK do celular (roda buildVdServer + embedServerApk, embutindo vd-server.jar e app-server.apk nos assets do cliente)
./gradlew :app-client:assembleDebug

# Local do APK:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (celular, embute o APK da central)

# Testes unitários (6 módulos, JUnit4 + kotlinx-coroutines-test, sem Robolectric/Mockito/MockK)
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

Requer JDK 17 e o Android SDK (compileSdk 34). Builds de release precisam de `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` (`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` opcionais); uma variável obrigatória ausente falha na fase de configuração, em vez de gerar silenciosamente um APK unsigned que não pode ser instalado por cima do oficial. O próprio keystore nunca é comitado.

No Windows os comandos equivalentes usam `.\gradlew.bat`.

---

## Documentação

A documentação completa (setup, arquitetura, protocolo, cliente, servidor, progresso) começa em [docs/README.md](../README.md). As normas de processo ficam em [AGENTS.md](../../AGENTS.md), [docs/ISSUE_GUIDE.md](../ISSUE_GUIDE.md), [docs/PR_GUIDE.md](../PR_GUIDE.md) e [docs/release.md](../release.md).

---

## Contribuição

Pull requests são bem-vindos. O desenvolvimento acontece em `main` e `develop` (git-flow: features saem de `develop`, releases entram em `main` e retornam por back-merge). Antes de abrir um PR, confirme que `./gradlew :app-client:assembleDebug` e os comandos de teste unitário acima passam.

---

## Licença

MIT, veja [LICENSE](../../LICENSE). Criado originalmente por [andersonlucasg3](https://github.com/andersonlucasg3); agradecimentos ao autor original e a todos que mantiveram o projeto vivo no fork.
