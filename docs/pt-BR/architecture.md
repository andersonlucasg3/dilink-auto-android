# Arquitetura

## Visão geral

DiLink-Auto é um projeto Gradle de seis módulos que espelha a VirtualDisplay de um celular em uma central multimídia BYD DiLink por WiFi. O celular executa os apps do launcher em uma VirtualDisplay com shell UID; o servidor VD codifica os frames renderizados em H.264, codifica o áudio do sistema em AAC-LC e os transmite diretamente ao receptor. O celular é um orquestrador puro — ele não retransmite vídeo, áudio nem toque. O próprio receptor é substituível: o app da central (`app-server`) e o cliente desktop Windows (`app-desktop`) falam o mesmo protocolo nos mesmos canais diretos (quatro portas: 9637 controle+dados, 9638 vídeo, 9639 entrada de toque, 9640 áudio).

```
DiLink-Auto/
├── protocol-core/   Pure JVM library -- framing/messages/constants/VD deploy sequence  (no Android)
├── protocol/        Android library -- shared by all three Android apps (Gradle module) minSdk 26
├── app-client/      Android application -- runs on the phone                           minSdk 29
├── app-server/      Android application -- runs on the car                             minSdk 26
├── app-desktop/     Kotlin/JVM + Compose Desktop -- runs on Windows                    JDK 17
├── vd-server/       Android library -- shell-privileged process, compiled to a JAR     minSdk 29
└── docs/            documentation
```

Testado em um BYD Qin PLUS DM-i 2023 versão Champion 55KM Leading — uma central multimídia DiLink 4.0 de baixa especificação (Snapdragon 439, 8x Cortex-A53, Adreno 505, 4GB de RAM, 16GB eMMC, 1280x800, WiFi apenas 2.4GHz, Android 9 / API 28, decodificação de hardware H.264 limitada a 1080p). O pipeline de streaming é ajustado para esse hardware; veja a seção de desempenho do DiLink 4.0 para a justificativa.

## Arquitetura de tela virtual

O **celular** cria uma VirtualDisplay no tamanho de VD negociado e um processo com shell UID (`vd-server`) que é dono do encoder. Dois tamanhos diferentes estão em jogo e não devem ser confundidos: o **tamanho do VD** é o viewport da central com anti-crop escalado até a borda longa física *real* do celular (`VdDimensions.compute`, compartilhado em `:protocol-core`), enquanto o **tamanho de codificação** é o viewport nativo da central limitado a 1920x1080. O servidor VD faz bind direto de `9638` (vídeo), `9639` (entrada) e `9640` (áudio, quando negociado); o receptor se conecta a essas portas no IP do celular e troca H.264 + toque + áudio AAC-LC com o servidor VD, sem retransmissão pelo app do celular no caminho crítico. O papel do app do celular é a orquestração: handshake e negociação de recursos (`FEATURE_AUDIO`), ciclo de vida do VD (a mensagem de controle `VD_PORTS_BOUND`) e roteamento de log da central. O `VirtualDisplayClient` no celular é somente de ciclo de vida — ele é dono do canal de comandos localhost, não de vídeo, áudio ou toque.

**O receptor é substituível.** Nada nesse contrato é específico da central: o handshake (`HandshakeRequest`/`HandshakeResponse`), as quatro portas (9637 controle+dados, 9638 vídeo, 9639 entrada, 9640 áudio) e a sequência de deploy são protocolo, não plataforma. O `app-desktop` é um segundo receptor que fala os mesmos bytes:

```
Phone (app-client)                        Receiver (app-server OR app-desktop)
  ConnectionService  9637 <-- handshake --->  connection state machine
  VD server      --- 9638 -- H.264 ------>    decoder -> SurfaceView / Swing canvas
                 --- 9639 -- touch <------    touch encoder (mouse or car panel)
                 --- 9640 -- audio ------>    AudioPlayer -> AudioTrack (car)
                   19647 <-- lifecycle ----   (phone-local only)
```

Quem faz o deploy do servidor VD depende do celular: com Shizuku o próprio celular faz; caso contrário, `HandshakeResponse.connectionMethod` instrui o receptor a fazer via ADB (a central usa ADB por USB/TCP, o desktop chama o `adb.exe` local). Ambos os receptores param no mesmo ponto — o `exec app_process` precisa permanecer anexado a um stream de shell vivo, porque o adbd mata o motor no momento em que esse stream fecha.

Um canal de ciclo de vida separado em `localhost:19647` carrega `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` entre o celular e o servidor VD. O servidor VD se reconecta ao celular nessa porta (NIO, não bloqueante). O listener do celular faz bind **apenas em `127.0.0.1`** e recusa pares que não sejam loopback (auditoria A-M10) — o canal é local ao celular por definição, então nada no WiFi pode se inserir nele.

```
+- Phone -----------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Physical Screen      |  Independent -- user can use phone      |
|  | (shows DiLink Auto   |  normally while streaming               |
|  |  app or anything)    |                                          |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 1 NIO TCP server     |      | Deployed by phone or receiver  |  |
|  |  9637: control+data  | 19647| Reverse-connects lifecycle     |  |
|  |  Handshake           |  NIO | VirtualDisplay (car viewport)  |  |
|  |  VD lifecycle        |      | GL render -> H.264 encoder     |  |
|  |  Car-app install     |      | TCP write 9638 (video)         |  |
|  |  Car log routing     |      | Touch read 9639 (input)        |  |
|  |  Allowlist filter    |      | Audio write 9640 (audio)       |  |
|  +----------+-----------+      | Display power + IME restore    |  |
|  +----------+-----------+      | App launcher (am start)        |  |
|             |                  | Input injector (IInputManager) |  |
|             |                  +--------------------------------+  |
+-------------+----------------------+-------------------------------+
              | WiFi TCP (control)   | WiFi TCP (direct VD <-> car)
              | 9637: control + data | 9638: H.264 video
              |                      | 9639: touch input
              |                      | 9640: AAC-LC audio
              v                      v
+- Car (BYD DiLink 4.0) --------------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Parallel connection model: WiFi control + WiFi direct video/input|
|  + USB track (ADB) and TCP ADB fallback                           |
|                                                                   |
|  Track A (WiFi control):                                          |
|  +-- Gateway IP + mDNS discovery                                  |
|  +-- Control connect (9637) -> handshake (viewport+DPI+FPS)       |
|  +-- Awaits VD_PORTS_BOUND -> connect direct channels (9638/9639/9640) |
|  +-- Receives H.264 video -> VideoDecoder -> SurfaceView          |
|  +-- Receives AAC-LC audio -> AudioPlayer (150ms buffer) -> AudioTrack |
|  +-- Sends touch events via input connection (9639)               |
|                                                                   |
|  Track B (USB ADB / TCP ADB):                                     |
|  +-- Used to deploy the VD server onto the phone (shell UID)      |
|  +-- USB host mode OR TCP ADB to phone:5555                       |
|  +-- Launch phone app (am start)                                  |
|                                                                   |
|  UI: CarLaunchScreen -> (app icons arrive) -> CarShell + NavBar   |
|  Two modes: launch (connection-focused, no nav) and streaming     |
|  Nav bar (rail or bottom): Eject / Home / Back only               |
|  40dp icons                                                       |
+-------------------------------------------------------------------+
```

### Streaming direto do VD (sem retransmissão pelo celular)

Versões anteriores roteavam o vídeo pelo app do celular: o servidor VD gravava os frames no localhost e o celular os encaminhava para a central por WiFi. Isso eram quatro operações de socket e duas trocas de contexto de processo por frame. A arquitetura atual faz bind de `9638`, `9639` e `9640` no próprio servidor VD (`0.0.0.0`); a central se conecta ao IP do celular nessas portas diretamente. Duas operações de socket, zero trocas de contexto. O `VirtualDisplayClient` do celular foi simplificado para somente ciclo de vida — ele aceita a conexão reversa do servidor VD em `localhost:19647`, lê `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` e envia `CMD_STOP` no teardown. Vídeo, áudio e toque não passam mais pelo app do celular.

### Arquitetura do pipeline de áudio

O espelhamento de áudio completa um ciclo ponta a ponta operando na porta dedicada `9640`:
- **Captura**: `AudioRecord` inicializado com `MediaRecorder.AudioSource.REMOTE_SUBMIX` captura o áudio do sistema renderizado na VirtualDisplay.
- **Codificação**: Feita por `AudioPipeline` e `AudioEncoderSelector` configurando o `MediaCodec` para AAC-LC (48000 Hz, estéreo, 128 kbps). O `csd-0` do AAC-LC (AudioSpecificConfig) é enviado fora de banda, antes dos dados do stream (`AudioPayload.kt`).
- **Cadência e silêncio sintético**: AAC-LC a 48kHz produz 1024 amostras por frame = 21.333ms por frame = 46.875 fps (`AudioSpec.kt`, `AudioPacing.kt`). Quando o stream de captura fica em silêncio ou sem dados, o pipeline injeta continuamente frames de silêncio sintético em uma cadência precisa de 46.875 fps. Isso evita underflow do buffer de jitter e picos de latência de enfileiramento a jusante.
- **Transmissão**: `SocketAudioSink` e `PortAcceptor` fazem bind da porta 9640 (aceitando apenas o `CAR_HOST` fixado) usando `AsyncSocketSender`.
- **Receptor e reprodução**: `CarSessionCoordinator` inicia a conexão da porta 9640 ao receber `VD_PORTS_BOUND` (quando negociada via `isAudioNegotiated`). O `AudioPlayer` gerencia a decodificação AAC-LC, um buffer de jitter de 150ms (configurável 60~200ms) e a reprodução via `AudioTrack`. O `AudioPlayer` registra periodicamente métricas de frames de silêncio (`silenceFrames`/`totalFrames`), cumprindo o contrato da S23 contra verificação falso-verde.
- **Negociação**: Negociação de handshake bidirecional via `FEATURE_AUDIO` em `HandshakeRequest` e `HandshakeResponse`. As configurações do cliente oferecem uma chave de áudio persistida nas preferências; o receptor da central esmaece a chave de áudio se o celular não suportar ou não reportar `FEATURE_AUDIO`.

### Por que shell UID para o servidor VD

O `app_process` precisa rodar como shell UID (2000) para criar VirtualDisplays que possam hospedar apps de terceiros e para fazer reflection em `InputManager` para injeção de toque. O celular faz o deploy de `vd-server.jar` em `/sdcard/DiLinkAuto/` e o inicia via `CLASSPATH=jar app_process / com.dilinkauto.vdserver.PipelineServer W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]` (a cauda de argv é montada por `VdDeployArgs.format`; o slot final `CAR_HOST` de fixação de par é opcional e `-` significa aceitar qualquer par). A cauda de argv, as linhas de comando de kill/launch (`VdDeploy.buildDeployPlan`) e a orquestração kill → esperar saída → launch (`vdRunDeploySequence`) têm fonte única em `:protocol-core` e são compartilhadas por todos os pontos de deploy: Shizuku no celular, ADB USB da central, ADB TCP da central e o caminho `adb.exe` do desktop.

## Fronteiras de confiança (postura de segurança)

O modelo completo — incluindo a superfície de ameaça do Shizuku, o tratamento de keystore e o armazenamento das chaves ADB da central — está em `docs/setup.md` ("Modelo de segurança (Fronteiras de confiança)"). O que importa para esta arquitetura:

- **A fronteira de confiança é o fio, e atualmente ele é texto claro.** Todas as quatro portas são TCP puro, sem segredo de pareamento e sem verificação de integridade, então a premissa do produto é "rede local confiável / hotspot privado". Um MITM na mesma LAN pode assistir à tela espelhada e injetar entrada. Pareamento/nonce é um acompanhamento registrado, não uma mitigação já entregue.
- **O motor com shell UID é a joia da coroa.** Qualquer coisa que consiga acionar `9638`/`9639` pode executar `am start`, `pm uninstall`, injetar toque e desligar o painel físico do celular — como shell UID. A rodada de endurecimento de 2026-10-09 (`a7d2b72` protocolo de rede, `8601fb1` servidor VD, `1f6836a` desktop, `5eae3e7` quoting do deploy) reduziu a superfície sem mudar a premissa de confiança:
  - o motor ainda faz bind em `0.0.0.0`, mas **recusa accepts de qualquer IP que não seja o receptor para o qual foi iniciado** (slot de argv `CAR_HOST`, preenchido pelo deployer do celular a partir do endereço remoto da conexão de controle ativa; `-` = comportamento legado de aceitar qualquer um);
  - todo nome de pacote/componente que chega a `am start` / `pm uninstall` tem a forma validada (`requirePackageName` / `requireComponentName`) e recebe quoting de shell (`VdDeploy.shellQuote`) — strings de pares nunca viram sintaxe de shell;
  - o desktop aplica allowlist na entrada de deploy fornecida pelo par do celular (`adbPort` deve ser `5555`, `vdServerJarPath` deve ficar sob `/sdcard/DiLinkAuto/`, dimensões do VD limitadas) antes de sequer montar um comando `adb`;
  - a decodificação é fail-closed: frames malformados lançam `ProtocolDecodeException` no decodificador, os payloads são limitados a 16 MB e o leitor *desconecta* em vez de derrubar o processo;
  - o listener de ciclo de vida é somente loopback e os caminhos de falha do servidor VD não deixam mais vazar um processo zumbi com shell UID segurando a VirtualDisplay e os dois binds.
- **O Shizuku é deliberadamente não endurecido.** Ele concede privilégios equivalentes aos de shell ao app do celular por design; qualquer coisa que consiga se vincular ao Shizuku pode direcionar comandos de shell. Use-o apenas com apps em que você confia.

## Responsabilidades dos módulos

### protocol-core (Biblioteca JVM pura)

Tudo no protocolo que não toca o Android. Três plataformas o compartilham: o app da central (`app-server`), o receptor Windows (`app-desktop`) e — por meio do `api(project(":protocol-core"))` do `:protocol` — o app do celular e o servidor VD. Manter isso livre de Android é o que torna o `app-desktop` possível, e é a razão pela qual a lógica pura (framing, sequenciamento, linhas de comando de deploy, política de tela preta) pode ser testada em unidade sem um dispositivo.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| Ports | `Ports.kt` | A definição única das portas de rede — `DEFAULT_PORT = 9637` (controle + dados), `VIDEO_PORT = 9638`, `INPUT_PORT = 9639`, `AUDIO_PORT = 9640`, `LIFECYCLE_PORT = 19647`, `ADB_PORT = 5555`. O `Discovery` apenas encaminha aliases obsoletos para ela |
| Connection | `Connection.kt` | Conexão TCP com heartbeat/watchdog (intervalo de 3s / timeout de 10s, injetável para testes), fila de escrita limitada (64 frames, suspende sob backpressure) com `sendDispatcher` = `Dispatchers.IO.limitedParallelism(1)` + `sendMutex` → ordenação estrita de envio, despacho inline de INPUT/CONTROL para que toque e comandos mantenham a ordem, despacho assíncrono apenas para DATA. Carência de flush em EOF gracioso (500ms) antes de o writer ser cancelado |
| NioWriter | `NioWriter.kt` | Escrita spin-blocking em SocketChannel com timeout configurável e acompanhamento de progresso de escrita parcial |
| NioReader | `NioReader.kt` | Leitor não bloqueante baseado em Selector com buffer que cresce sob demanda (teto rígido `MAX_READ_UNIT`), `maybeShrink` de volta à capacidade padrão após um frame grande, prazo de estagnação no meio do frame (um par half-open silencioso) e tratamento explícito de EOF/close |
| FrameCodec | `FrameCodec.kt` | Framing binário (`HEADER_SIZE = 6`, `MAX_PAYLOAD_SIZE` = 16MB), buffer de header `ThreadLocal` para o caminho crítico, `validatePayloadSize` compartilhado para que todo caminho de leitura rejeite frames grandes demais da mesma forma |
| AudioPayload | `AudioPayload.kt` | Framing e serialização para o CONFIG de áudio AAC-LC (`csd-0`) e para os frames do stream de áudio |
| BackoffPolicy | `BackoffPolicy.kt` | Cálculo parametrizado de backoff exponencial com jitter e limites superiores |
| DecodeLivenessTracker | `DecodeLivenessTracker.kt` | Rastreamento unificado do estado de vivacidade de decodificação e gerenciamento do timestamp de linha de base entre receptores |
| AppIconLimits | `AppIconLimits.kt` | Limites seguros para ícones do launcher de apps: `MAX_ICON_DIM = 512`, `MAX_ICON_BYTES = 1048576` |
| Ipv4Validator | `Ipv4Validator.kt` | Rotinas de validação estrita e permissiva de endereços IPv4 |
| Messages | `Messages.kt` | Classes de dados serializáveis (handshake, lista de apps, toque). Todo decodificador valida seus limites (`require`) e lança `ProtocolDecodeException`; `requirePackageName` / `requireComponentName` rejeitam nomes inseguros na fronteira de decodificação |
| ProtocolConstants | `ProtocolConstants.kt` | `PROTOCOL_VERSION = 1` + `requireSupportedProtocolVersion` (pares que anunciam outra versão são rejeitados em vez de decodificados em offsets errados), modos de exibição, feature flags, métodos de conexão |
| Canais / tipos de mensagem | `Channel.kt`, `MessageType.kt` | IDs de canal (controle, vídeo, áudio, dados, entrada) — cada um em uma conexão TCP dedicada — e as constantes de byte por canal. `VD_PORTS_BOUND` (0x31) sinaliza ao receptor para conectar vídeo/entrada diretamente ao servidor VD; `LOG_TOGGLE` ativa-desativa o log do lado da central; `SET_DISPLAY_POWER` (0x32) carrega a chave manual do painel físico pelo canal de entrada |
| VdDeploy | `VdDeploy.kt` | Construtores de comandos de kill/stop/launch do `app_process`: `gracefulStopCommand` (sentinela de parada), `probeCommand` / `probeExitCodeCommand` (vivacidade baseada em `pgrep -f` — o toybox 0.8.11-android rejeita `pkill -0`), e `killCommand` / `killCommandForce` (**`pgrep` + `kill <pid>`**, não `pkill -f`: o padrão com colchetes é um truque de regex que só o `pgrep` respeita). Além disso, `shellQuote` (aspas simples POSIX) para caminhos fornecidos por pares e `buildDeployPlan` montando a cauda de argv + a linha de launch |
| VdDeployArgs | `VdDeployArgs.kt` | Cauda de argv do `app_process` compartilhada por todo caminho de deploy: `W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST [ALLOWED]]`, escala que preserva a proporção até o limite de codificação de 1920x1080, faixa de override de DPI `120..480` + `coerceDpiOverride`, `sanitizeCarHost` para o slot de fixação de par, `formatAllowedPackages`/`parseAllowedPackages` para a allowlist de pacotes visível na central (issue #21) |
| VdDeploySequence | `VdDeploySequence.kt` | A definição única de "parar → esperar até que ele realmente não exista mais (duas sondagens GONE consecutivas; UNKNOWN aceito imediatamente; force-kill no timeout)", dividida em dois pontos de entrada sobre uma implementação: `vdRunDeploySequence` (parar → esperar → **launch**) para os quatro pontos de deploy, e `vdStopEngine` (parar → esperar → fallback `killCommandForce`, **sem launch**) para os caminhos de teardown — o fechamento de sessão do receptor desktop é o primeiro chamador. O `VdDeployExecutor` abstrai um canal de shell; clock e sleeper são injetáveis |
| BlackScreenDetector | `BlackScreenDetector.kt` | Política de tela preta sustentada compartilhada pelo decodificador da central e pelo pipeline do desktop: uma sequência de I-frames minúsculos significa que o encoder não produz nada. Alerta após N frames, escala apenas após uma janela sustentada (uma rajada curta durante o aquecimento é normal) — 3s por padrão, o desktop a amplia via `setSustainWindow(10s)`. O clock é injetado |
| ScreenMode / CaptureModePolicy | `ScreenMode.kt` | Estratégia de captura com prioridade no VD e a escada de degradação documentada `VIRTUAL_DISPLAY → PROJECTION_SCREEN_OFF → PROJECTION_MAIN → (none)`; funções puras, sem dependência de plataforma (ver `docs/FEATURE_DUAL_MODE_RECEIVER.md`) |
| H264NalParser | `H264NalParser.kt` | Percorredor de NAL — `isKeyFrame` (tipo 5) comanda "descartar P-frames até o primeiro IDR" |
| RecentsParser | `RecentsParser.kt` | Parsing de `dumpsys activity recents` + seleção de alvo de troca rápida por trás do comando GO_RECENT: apenas tarefas vivas (`hasTask=true`), extração de componente condicionada à mesma whitelist de injeção de shell (`COMPONENT_REGEX`), alvo = tarefa mais recente que não seja o VD-top atual / o app de controle / o launcher / o systemui (2026-10-10) |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS = 20`, `DEFAULT_BITRATE` (4Mbps) e os limites de bitrate em camadas (UI / deploy / piso adaptativo de 1.5Mbps), `calculateOptimalDpi(width, height, reportedDpi)` |
| VdDimensions | `VdDimensions.kt` | Matemática pura de viewport: viewport da central (alinhado a par) + escala anti-crop para o IME de ROMs chinesas que fixa a largura + override de DPI vs. calibração automática. Alimenta tanto o tamanho do VD quanto `vdWidth`/`vdHeight` da resposta de handshake |
| PlatformLog / ThreadPriority | `PlatformLog.kt`, `ThreadPriority.kt` | Hooks de plataforma para que o código compartilhado possa logar e definir prioridade de thread sem importar `android.util.Log` |

### protocol (Biblioteca Android, minSdk 26)

Compartilhado pelos três módulos Android (`app-client`, `app-server`, `vd-server`). Zero dependências de UI. Este módulo agora é **apenas cola específica de Android**: ele reexporta `:protocol-core` por meio de `api(project(":protocol-core"))`, então o formato de rede, o framing, as constantes e as linhas de comando de deploy vivem todos em `protocol-core` (foram movidos para lá para que o desktop possa compartilhá-los). O que resta aqui é a parte que realmente precisa do framework Android: os clientes ADB (USB + TCP), a descoberta mDNS, a instalação dos hooks de plataforma e o notificador do lado da central.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| AndroidPlatformHooks | `AndroidPlatformHooks.kt` | Instala as implementações de `PlatformLog` / `ThreadPriority` do `:protocol-core` (`android.util.Log`, `Process.setThreadPriority`) em cada processo que hospeda código compartilhado |
| Discovery | `Discovery.kt` | Registro/descoberta de serviço mDNS (`NsdManager`) para `_dilinkauto._tcp`. Apenas respondedores cujo nome de serviço e porta correspondem ao protocolo são oferecidos como alvos de conexão (um impostor na mesma LAN costumava aparecer como um celular). As constantes de porta são aliases obsoletos de `Ports` |
| ForegroundNotifier | `ForegroundNotifier.kt` | Notificação de serviço em primeiro plano com `NotificationCompat`, roteada pelo próprio `core-ktx` do app para que o módulo permaneça `compileOnly` em relação ao androidx |
| DeviceInfo | `DeviceInfo.kt` | O bloco compartilhado de diagnóstico "Informações do dispositivo" usado pelo log de inicialização do celular e pelo relatório de crash da central (seção de memória opcional), para que as duas cópias não divirjam |
| ConnectionFactory | `ConnectionFactory.kt` | Criação e configuração centralizadas de canais de socket TCP |
| ConnectionKeepAlive | `ConnectionKeepAlive.kt` | Auxiliar de configuração de keepalive TCP |
| UsbAdbKeyStore | `adb/UsbAdbKeyStore.kt` | Wrapper do Android Keystore para chaves de autenticação ADB |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | Protocolo ADB sobre USB (CNXN, AUTH, OPEN, WRTE), callback `logSink`; código morto de sync/push removido |
| AdbProtocol | `adb/AdbProtocol.java` | Constantes e serialização de mensagens ADB (parse de header validado, verificação de CRC) |
| AdbCrypto | `adb/AdbCrypto.java` | `AUTH_SIGNATURE` pré-hasheado (`NONEwithRSA` + prefixo DigestInfo SHA-1), codificação base64 de ANDROID_PUBKEY — JVM pura, coberta por testes unitários |
| TcpAdbConnection | `adb/TcpAdbConnection.kt` | ADB persistente sobre TCP — um único socket reutilizado para todos os comandos de shell, negociação de payload e leituras com verificação de CRC. Usado pelo `RemoteAdbController` do lado da central. (O `dadb` ainda é usado pelo `CarAppInstaller` do lado do celular para instalar o APK da central.) |

### app-client (Aplicação do celular, minSdk 29)

Gerencia handshake, ciclo de vida do VD, instalação do app da central, filtragem por allowlist, negociação do recurso de áudio e `FileLog`.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Orquestrador enxuto: aceita a 9637, negocia recursos (`FEATURE_AUDIO`), gerencia o ServerSocket de ciclo de vida em `127.0.0.1:19647` e delega o deploy ao `ShizukuVdDeployer`, a instalação ao `CarAppInstallHost`, o rastreamento de rede ao `ClientNetworkMonitor` e os apps permitidos ao `AllowlistRepository`. Gerações de sessão e `cleanupGuard` evitam teardown redundante |
| ShizukuVdDeployer | `service/ShizukuVdDeployer.kt` | Faz o deploy do servidor VD via shell do Shizuku quando disponível, executando `vdRunDeploySequence` |
| ClientNetworkMonitor | `service/ClientNetworkMonitor.kt` | Monitora mudanças de conectividade de rede e trata eventos de mudança de IP sem terminar prematuramente sessões ativas |
| CarAppInstallHost | `service/CarAppInstallHost.kt` | Encapsula o fluxo de instalação do APK da central e a orquestração de estado |
| AllowlistRepository | `service/AllowlistRepository.kt` | Persistência e filtragem dos apps de launcher permitidos |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | Somente ciclo de vida. `startListening()` abre o ServerSocket de forma síncrona em **loopback** e recusa pares que não sejam loopback; `acceptConnection()` espera a conexão reversa do servidor VD em `127.0.0.1:19647`, lê `MSG_DISPLAY_READY` (displayId + flag de injeção direta), repassa `MSG_STACK_EMPTY` e envia `CMD_STOP` |
| VdDimensions | `protocol-core/.../VdDimensions.kt` (compartilhado) | Matemática pura de viewport (movida para fora do app-client em 2026-10-09): viewport da central (alinhado a par) + escala anti-crop para o IME de ROMs chinesas que fixa a largura + override de DPI vs. calibração automática. O chamador precisa passar o tamanho físico REAL do celular (`maximumWindowMetrics`/`getRealMetrics`), não `displayMetrics` escalado por compatibilidade |
| AssetDeployer / CarInstallCoordinator | `service/AssetDeployer.kt`, `service/CarInstallCoordinator.kt`, `service/CarInstaller.kt` | Extrai `vd-server.jar` dos assets por trás de uma costura `AssetSource` com verificação de frescor por CRC, e executa a máquina de estados de instalação do APK da central por meio de um transporte `CarInstaller` injetável. Ambos existem para que a lógica pura seja testável em unidade — o comportamento não mudou |
| AppListBuilder | `service/AppListBuilder.kt` | Monta a lista de apps visível na central, filtrada pela allowlist do usuário. PNGs de ícone enviados uma vez por pacote por sessão (suprimidos por hash) |
| AllowlistScreen | `AllowlistScreen.kt` | Seletor no celular de quais apps do launcher chegam à central. Ativar-desativar uma linha dispara `ACTION_ALLOWLIST_UPDATED` para que o serviço em execução reenvie a lista ao vivo |
| CarAppInstaller | `service/CarAppInstaller.kt` | Instala o `app-server.apk` embutido na central via `dadb` por WiFi (timeout de conexão de 15s) |
| CarIpLocator | `service/CarIpLocator.kt` | Localiza o serviço ADB-over-WiFi da central (porta 5555): IP remoto da conexão de controle, enumeração de sub-rede, ARP, cache de vizinhos, varredura paralela da /24, gateway |
| PhoneDisplayRestorer | `service/PhoneDisplayRestorer.kt` | Restaura o display físico e o IME do celular depois que o servidor VD encerra. **Possui um `CoroutineScope(SupervisorJob() + Dispatchers.IO)` com tempo de vida do processo** — deliberadamente não o escopo do Service, que `onDestroy()` cancela. O contexto `NonCancellable` garante que `cmd display power-reset` não possa ser pulado no meio da restauração. O `AtomicBoolean` `inFlight` condensa requisições de restauração concorrentes em uma. Em camadas: Shizuku (parada graciosa via `VdDeploy.gracefulStopCommand` + espera + fallback `killCommandForce`, depois `cmd display power-reset` + restauração de IME), depois `PowerManager.wakeUp`, depois `FLAG_TURN_SCREEN_ON`, depois um wake lock |
| FileLog | `FileLog.kt` | Log em arquivo em `/sdcard/DiLinkAuto/client.log`, rotação (30 logs arquivados + o arquivo ativo), contorna a filtragem de logcat do HyperOS. Padrão LIGADO para debug/pré-release, DESLIGADO para release; a escolha do usuário persiste via `AppPrefs.LOG_ENABLED`. A chave das configurações lê o estado *efetivo* por meio de `isEnabledIn(prefs)` (issue #72), então um build release mostra DESLIGADO em vez de um LIGADO enganoso. As escritas escoam por uma `AsyncLogQueue` limitada e linhas `CAR_LOG` fornecidas por pares têm o comprimento limitado antes de chegarem a ela |
| MainActivity | `MainActivity.kt` | UI — iniciar/parar, onboarding, configurações, allowlist, instalação na central |

### app-server (Aplicação da central, minSdk 26)

Modelo de conexão paralela: controle por WiFi (9637) + vídeo/entrada/áudio diretos por WiFi (9638/9639/9640), com trilhas de ADB USB e ADB TCP para o deploy do servidor VD.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Coordenador enxuto de Service em primeiro plano (reduzido a 407 linhas). Delega a orquestração de conexão ao `CarSessionCoordinator`, o rastreamento ADB ao `CarAdbTracker` e o parsing da lista de apps ao `CarAppListReceiver` |
| CarSessionCoordinator | `service/CarSessionCoordinator.kt` | Coordenador de ciclo de vida multicanal entre 9637 (controle), 9638 (vídeo), 9639 (entrada) e 9640 (áudio); trata handshake, negociação de recursos (`FEATURE_AUDIO`) e re-handshake de rotação no meio do stream |
| CarAdbTracker | `service/CarAdbTracker.kt` | Gerencia as trilhas duplas de ADB host USB e ADB TCP, os limites de reconexão (`noAdbCount >= 3`) e os gatilhos de deploy do motor |
| CarAppListReceiver | `service/CarAppListReceiver.kt` | Recebe listas de apps do celular, aplicando `AppIconLimits` para rejeitar payloads malformados ou grandes demais |
| AudioPlayer | `audio/AudioPlayer.kt` | Decodificador de áudio AAC-LC com MediaCodec, buffer de jitter de 150ms (60~200ms configurável) e reprodução via AudioTrack; registra periodicamente estatísticas de frames de silêncio (`silenceFrames`/`totalFrames`) |
| VideoDecoder | `decoder/VideoDecoder.kt` | Decodificação de hardware H.264 via `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` (REGULAR_CODECS lista os de hardware primeiro; seleciona `OMX.qcom.video.decoder.avc` na BYD). Usa `DecodeLivenessTracker` e `BackoffPolicy`; `ArrayBlockingQueue` de 4 frames, ressincronização por IDR após flush (pula P-frames até chegar um keyframe), o gate `outputSurfaceValid` evita renderizar em uma surface destruída, `debugFrameStats` com log de tempo de decodificação a cada 30 frames. Thread de feed em `THREAD_PRIORITY_URGENT_DISPLAY`. A tela preta sustentada é julgada pelo `BlackScreenDetector` do `:protocol-core` (janela de 3s, `SystemClock.elapsedRealtime` injetado), compartilhado com o desktop |
| AppIconCache | `AppIconCache.kt` | Cache de ícones do lado da central. `prepareAll` decodifica/redimensiona todos os ícones para o tamanho da grade em paralelo (`Semaphore(4)`), com intermediários reciclados. `getPrepared` é uma consulta O(1) em `ConcurrentHashMap`. `clear()` na desconexão libera ~5-9MB de heap + os PNGs do eMMC |
| CarShell | `ui/screen/CarShell.kt` | Layout de streaming (espelho + barra de navegação) ou tela de lançamento. Aceita o estado `CONNECTING` durante o re-handshake de rotação no meio do stream para que o overlay de espera de vídeo cubra a lacuna do redeploy em vez de piscar o CarLaunchScreen |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | `SurfaceView` (não `TextureView`) — contorna a composição GL por frame do Adreno 505. `surfaceCreated` chama `switchSurface` para reanexar o decodificador sem perder keyframes na navegação HOME<->APP; `surfaceDestroyed` desliga o flag de render sem parar o decodificador |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Tela de lançamento/conexão em tela cheia, branding, IP manual, chave de modo dev, entrada numérica de DPI inicial |
| HomeScreen | `ui/screen/HomeScreen.kt` | Grade de apps (ícones de 64dp, toque longo para Fixar no topo / Desafixar / desinstalar / informações do app). O wrapper `@Immutable AppTileData` exclui o ícone `ByteArray` instável para que a grade pule a recomposição na reatribuição de `appList` |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | Apenas três botões: Ejetar / Home / Voltar. Paisagem: trilho à esquerda. Retrato: barra inferior. Largura calculada para garantir um viewport par para o encoder H.264 |
| NavBarComponents | `ui/nav/NavBarComponents.kt` | `NavActionButton` — ícone de 40dp, tint `onSurfaceVariant` |
| HandshakeFactory | `service/HandshakeFactory.kt` | Monta o `HandshakeRequest` tanto para a conexão inicial quanto para o re-handshake de rotação no meio do stream |
| VdServerDeployer | `service/VdServerDeployer.kt` | Faz o deploy do servidor VD no celular a partir da central via ADB USB ou ADB TCP (`deploy` / `deployDirect`). `shellBackground` mantém o stream ADB aberto para que `app_process` não seja morto pela saída do shell. Dimensões de codificação limitadas a 1920x1080 (limite da VPU do Snapdragon 439). É dono da política de retry e de um `VdDeployExecutor` por transporte; a sequência kill → esperar → force-kill → launch em si é o `vdRunDeploySequence` do `:protocol-core` |
| CarLogWriter | `service/CarLogWriter.kt` | Relay de log central -> celular. Buffer `ConcurrentLinkedQueue` com contador `AtomicInteger` (verificação de limite O(1) — antes era `size()` O(n)), limite de 10k linhas quando a conexão de controle está caída |
| CarTouchSender | `service/CarTouchSender.kt` | Codifica e envia eventos de toque na conexão de entrada (9639). Executor de thread única serializa os envios |
| RemoteAdbController | `adb/RemoteAdbController.kt` | Conexão ADB TCP persistente com o celular via `TcpAdbConnection` |
| CarCrashHandler | `CarCrashHandler.kt` | Handler de exceções não capturadas. Salva um relatório de crash em `filesDir/crash-pending.log`; enviado ao celular via `carLogSend` na próxima conexão bem-sucedida |
| CarTheme | `ui/theme/CarTheme.kt` | Esquema de cores escuro, cores tokenizadas (`onSurfaceVariant` substitui `Color.Gray` / `0xFF888888`), rótulos com mínimo de 14sp |
| MainActivity | `MainActivity.kt` | Fullscreen imersivo, encaminhamento de intent USB, re-handshake de rotação (`onCarViewportChanged`) |

### app-desktop (Receptor Windows, JDK 17)

Um segundo receptor para o mesmo protocolo, escrito em Kotlin/JVM com Compose Desktop 1.6.2 (app-image com `jpackage` via `:app-desktop:createDistributable`, embutindo sua própria JRE enxuta). Ele se conecta diretamente às portas 9637/9638/9639 do celular, decodifica com FFmpeg (JavaCV, Windows x86_64) e pinta em um canvas Swing embutido por meio de `SwingPanel`. Configuração e logs ficam em `%APPDATA%\DiLinkAuto` (substituível com `DILINK_DESKTOP_HOME`). O caminho de deploy via `adb.exe` é condicionado à configuração `dev_mode` e foi **verificado ponta a ponta em um MI 9 real em 2026-10-10** (handshake → deploy → `VD_PORTS_BOUND` → decodificação a 24 fps); com Shizuku o celular faz o deploy do motor sozinho e o desktop apenas consome os streams.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| DesktopMain | `DesktopMain.kt` | Ponto de entrada: configuração/log, modo `--probe` (verificação de link sem decodificação/renderização), modo janela. `PlatformLog.sink` encaminha apenas WARN+ — o log DEBUG por poll do `NioReader` afogaria o log da sessão |
| DesktopApp | `DesktopApp.kt` | Raiz de composição e dona da geração de sessão. Todo trabalho de ciclo de vida é serializado em uma thread; `stop()` é aguardável (o chamador está prestes a chamar `exitProcess`). Os sinais de fim de sessão são protegidos por identidade para que "aplicar e reconectar" não feche a janela; tela preta sustentada dispara no máximo 2 reconexões automáticas por processo, compartilhado com a escalada de estagnação de decodificação. A ordem de `closeSession()` é estrutural: `service.stop()` → `pipeline.stop()` → **`runBlocking { adbDeployer.gracefulStop() }`** → `adbDeployer.close()` → `scope.cancel()` → `keepAwake.disable()` |
| DesktopConnectionService | `DesktopConnectionService.kt` | Máquina de estados de sessão `IDLE → CONNECTING → HANDSHAKING → WAITING_VD → STREAMING` (`DISCONNECTED` na saída); abre controle+dados, faz o handshake, espera por `VD_PORTS_BOUND` (com timeout — o celular nunca relata uma falha de deploy) e então conecta vídeo/entrada diretamente ao servidor VD |
| HandshakeFactory | `HandshakeFactory.kt` | Monta o `HandshakeRequest` a partir da configuração do desktop; é dono do alinhamento par do lado do remetente (`DimAlign.evenMin2`) |
| VideoDecodePipeline | `video/VideoDecodePipeline.kt` | `FFmpegFrameGrabber` em modo Annex B bruto (`maximumSize = 0` desativa a emulação de seek, que trava para sempre em um stream ao vivo), replay de CONFIG + espera por IDR na reconstrução, decodificação de hardware D3D11VA com um watchdog de "iniciou mas não produziu nada" → fallback permanente para software, integrado ao `DecodeLivenessTracker` e ao `BackoffPolicy`, detecção de tela preta sustentada (janela de 10s, mais ampla que a da central) e escalada de estagnação de decodificação |
| FeedGate / VideoStreamPipe | `video/FeedGate.kt`, `video/VideoStreamPipe.kt` | O gate descarta frames até SPS/PPS e depois até o primeiro IDR; o pipe é uma fila limitada que descarta o chunk mais antigo sob backpressure sem expulsar o CONFIG recém-recebido |
| SwingVideoView | `ui/SwingVideoView.kt` | Pintor letterbox + mapeamento de mouse→toque normalizado. Copia cada frame entregue para seu próprio buffer: o JavaCV reutiliza um único `BufferedImage` para todos os frames, então entregar a referência a um repaint assíncrono causaria tearing |
| DesktopWindow | `ui/DesktopWindow.kt` | Shell da janela: trilho de navegação (espelho / apps / display) + visão principal. O `SwingPanel` de vídeo é composto **apenas** na página de espelho — componentes AWT sempre desenham acima do conteúdo Compose |
| AppCatalog / AppIconStore | `apps/*` | Transforma os frames `APP_LIST` / `APP_UNINSTALLED` do celular em um `StateFlow<List<AppEntry>>`, aplicando `AppIconLimits`; a decodificação de PNG acontece na corrotina de despacho de IO, nunca na thread de UI |
| InputSender | `input/InputSender.kt` | Comandos de toque / navegação na conexão de entrada, além da chave manual do painel físico |
| AdbDeployer / AdbDeploy / ProcessAdbRunner | `deploy/*` | Caminho sem Shizuku, condicionado à configuração `dev_mode`: `adb.exe connect` → `vdRunDeploySequence` compartilhado → `exec app_process` em primeiro plano. O `adbPort` e o `vdServerJarPath` fornecidos pelo par do celular passam por allowlist antes de qualquer coisa ser montada. Processos `adb shell` mantidos são a âncora de vivacidade do motor — por isso o teardown faz `gracefulStop()` (o `vdStopEngine` compartilhado, orçamento de 6s, lembra o `lastSerial`) **antes** de `close()`; um shutdown hook da JVM é o ceifador de último recurso |
| DesktopSettings / JsonConfig / DesktopSettingsStore | `config/*` | `config.json` (padrões, caminhos e escritas atômicas tmp+move em `DesktopSettingsStore`) com as mesmas chaves do `SharedPreferences` do Android (`AppPrefs`), mais `startup_hwaccel` / `keep_awake`; JSON escrito à mão é tolerado (incompatibilidade de tipo → padrão, DPI forçado para a faixa do protocolo) |
| KeepAwake | `display/KeepAwake.kt` | `kernel32!SetThreadExecutionState` via JNA em uma thread dedicada; degrada para no-op fora do Windows |
| SessionStatsLogger / FrameDumper | `SessionStatsLogger.kt`, `video/FrameDumper.kt` | Linha de estatísticas da sessão por segundo (fps, reconstruções, decodificação hw/sw) e um hook de depuração que despeja o frame N em um PNG quando `DILINK_DUMP_FRAME` está definido (nenhuma mudança de comportamento caso contrário) |

### vd-server (Processo com privilégios de shell, minSdk 29)

Módulo de biblioteca Android (`com.android.library`), compilado via `bundleLibRuntimeToJarDebug` e depois D8 em um JAR pela task `buildVdServer` em `app-client/build.gradle.kts`. Depende de `:protocol` e `kotlinx-coroutines-core`. Deploy feito pelo celular em `/sdcard/DiLinkAuto/vd-server.jar`. Roda como shell via `app_process`.

| Componente | Arquivo | Propósito |
|-----------|------|---------|
| PipelineServer | `PipelineServer.kt` | Ponto de entrada do processo (`main` interpreta `W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]`) e dono do ciclo de vida. Cria o encoder AVC via `VideoEncoderSelector`. CBR `VideoConfig.DEFAULT_BITRATE` = 4Mbps perfil Main, intervalo de I-frame de 1s. Faz bind de `9638` (vídeo), `9639` (entrada) e `9640` (áudio) em `0.0.0.0` via `PortAcceptor`, aceitando **apenas** o receptor nomeado no slot de argv `CAR_HOST`. Gerencia o ciclo de vida com `ProcessWatchdog` e coordena o `AudioPipeline` quando negociado |
| AudioPipeline | `audio/AudioPipeline.kt` | Pipeline de captura de áudio (`REMOTE_SUBMIX`) e codificação AAC-LC com cadência contínua de silêncio sintético (46.875 fps, 1024 amostras/frame) |
| AudioEncoderSelector | `audio/AudioEncoderSelector.kt` | Seleção do encoder AAC do MediaCodec com ordenação de candidatos |
| AudioSpec / AudioPacing | `audio/AudioSpec.kt`, `audio/AudioPacing.kt` | Constantes de áudio: 48kHz, estéreo de 2 canais, 128kbps, cadência de frame de 21.333ms (46.875 fps) |
| SocketAudioSink | `audio/SocketAudioSink.kt` | Envia o config AAC-LC e os frames do stream para a porta 9640 usando `AsyncSocketSender` |
| PortAcceptor | `PortAcceptor.kt` | Listener não bloqueante multiporta (`9638`, `9639`, `9640`) que impõe a fixação do par `CAR_HOST` |
| AsyncSocketSender | `AsyncSocketSender.kt` | Template unificado de escritor de socket assíncrono não bloqueante |
| ProcessWatchdog | `ProcessWatchdog.kt` | Watchdog desacoplado que monitora a vivacidade do pipeline e as sentinelas de pedido de parada |
| VideoEncoderSelector | `VideoEncoderSelector.kt` | Ordenação de candidatos AVC/H.264 (Codec2 `c2.` antes do legado `OMX.`, não-software primeiro) |
| PersistentShell | `PersistentShell.kt` | O `sh` de longa duração pelo qual todo comando passa, com stdout/stderr drenados. Iniciado antes de o encoder ser configurado, destruído após a restauração do painel/IME — a ordem do teardown depende disso. `exec` é fire-and-forget (retorna assim que o stdin é escrito), então `awaitIdle()` é a única forma de saber que o shell acompanhou: ela escreve `echo <marker>` e espera em `ShellSyncPoint` até o echo voltar pela thread de drenagem — dois FIFOs transformam isso em uma barreira de pipeline completa. `cleanup()` a chama antes de `close()`, senão os últimos comandos de restauração morrem com o SIGTERM |
| CarCommandRouter | `CarCommandRouter.kt` | Tudo que a central pode pedir por `Channel.CONTROL` (launch / home / back / recent / informações do app / desinstalar / energia do painel físico) mais as consultas de display de que o ciclo de vida precisa. Todo acesso a shell é injetado, então ele não possui estado de thread ou socket próprio; toda interpolação recebe quoting de shell |
| LifecycleWriter | `LifecycleWriter.kt` | Escritor único para respostas de ciclo de vida (`MSG_STACK_EMPTY`) construído sobre `AsyncSocketSender` |
| GlPipeline | `GlPipeline.kt` | Loop de render EGL14 + GLES20 na thread do pipeline. Thread única: cadência `parkNanos` -> `updateTexImage` -> quad em tela cheia -> `eglSwapBuffers` -> drenagem do encoder -> escrita TCP. Controle de fluxo natural: uma parada no TCP bloqueia o próximo swap, desacelerando a entrada do encoder. O listener de frame disponível é registrado em `initEglAndSurfaceTexture()` — **antes** da criação da VirtualDisplay — e roda em sua própria HandlerThread `PipeCB`; registrá-lo em `pipelineLoop()` (isto é, só quando a central conecta) perdia todos os frames do intervalo e produzia um stream probabilisticamente todo preto (~50% no MI 9, 2026-10-10). A linha de log periódica do loop carrega `tex=`/`cb=`/`swapFails=` exatamente para esse diagnóstico. Bitrate adaptativo (`AdaptiveBitrate.kt`): piso de 1.5Mbps, janela de recuperação limpa de 2s, passos de 0.5Mbps (reduz quando a escrita passa de 15ms) |
| TouchInjector | `TouchInjector.kt` | Reflection de `InputManager` (`injectInputEvent`), reflection de `MotionEvent.setDisplayId`, estado de multitoque com uma tabela de ponteiros limitada (`PointerCap.kt`: despejo + truncamento + verificação de faixa de id). Recorre a toques de shell `input -d` quando a reflection não está disponível |
| DisplayPowerController | `DisplayPowerController.kt` | Reflection de `DisplayControl.setDisplayPowerMode` (carregada de `services.jar` via `DelegateLastClassLoader`); fallback de shell `cmd display power-reset` (ligar) / `cmd display power-off` (desligar), API 29+. Não existe subcomando `power-on` — `cmd display help` no Android 15 lista apenas `power-off` e `power-reset` ("Turn the DISPLAY_ID power to a state the display supposed to have"); o antigo `power-on` falhava como "Unknown command" e deixava um painel forçado a desligado apagado. Na API 26-28 o fallback é no-op — uma falha de reflection em `DisplayControl` significa que o painel físico não é restaurado (agora logado, antes era silencioso). Persiste e restaura o IME original e a sentinela de timeout de tela (`SessionScreenTimeout.kt`), condicionado por `ImeRestore.shouldRestoreIme` — exclui o IME do linkpc |
| VirtualDisplayCreator | `VirtualDisplayCreator.kt` | VirtualDisplay com shell UID via reflection de `DisplayManagerGlobal` (flag de confiança `0x6c49`, `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`), fallback por `DisplayManager` com `mDisplayIdToMirror` forçado a 0. Aplica o estilo letterbox do Android 12L+ para que apps em retrato renderizem em uma proporção sensata, e verifica novamente se o DTA continua anexado à árvore de displays (`ensureDtaAttached`). `create()` apenas cria o VD — `configureEnvironment()` (que aplica o estilo letterbox + desativa gestos de desligar tela/despertar) precisa ser chamado separadamente DEPOIS que o chamador tiver fotografado as configurações originais via `DisplayPowerController.saveCurrentIme()` |
| VdDisplayHealer | `VdDisplayHealer.kt` | Verificador de diagnóstico e autocura para a fixação do DTA (DisplayTaskAssociation) na árvore da tela virtual |
| ActivityDumpParser | `ActivityDumpParser.kt` | Parser com verificação de limites para as saídas de `dumpsys window` e `dumpsys display`, eliminando vazamento entre displays |
| FakeContext | `FakeContext.kt` | Falsifica `com.android.shell` para acesso ao DisplayManager. Usa `ActivityThread.getSystemContext()` para obter um Context de sistema real |
| PipeLog | `PipeLog.kt` | Log baseado em `println` (sem `android.util.Log` no `app_process`); `ShellExec` executa comandos contra o processo `sh` persistente, e `startOutputDrain(p, onLine)` alimenta cada linha drenada tanto ao `ShellSyncPoint` quanto ao log |

**Arquitetura do PipelineServer.** Uma única thread de pipeline processa cada frame sequencialmente — cadência `parkNanos` -> `updateTexImage()` -> render GL -> `eglSwapBuffers()` -> drenagem do encoder -> escrita TCP. Sem filas entre estágios. O controle de fluxo é natural: se o TCP para, o pipeline bloqueia, atrasando o próximo swap. Usa `System.nanoTime()` + `LockSupport.parkNanos()` para temporização de 24fps sem deriva. A thread do pipeline é um **daemon** e a única thread autorizada a tocar EGL/GL: sinaliza prontidão por meio de um `CountDownLatch` em um `finally` (a thread principal espera nele com timeout de 10s), estaciona em fatias limitadas para que `running=false` possa realmente pará-la, e derruba o GL em seu próprio `finally`. Ao lado dela rodam o daemon TouchReader, o daemon do leitor de Lifecycle, o daemon LifeWriter (prioridade de background), o daemon Watchdog, a HandlerThread `PipeCB` de frame disponível e uma thread `StackCheck` de vida curta por comando de navegação. `cleanup()` apenas *solicita* a saída da thread do pipeline (`running=false` + unpark + join limitado de 2s) — ele nunca destrói o EGL a partir de outra thread.

**Teardown do VD (`cleanup()`).** Idempotente (protegido pelo `AtomicBoolean` `cleanedUp`). A ordem é deliberada:

1. **Estado global primeiro** — `cmd window reset-letterbox-style` + `wm user-rotation -d <id> free`. Estes são de escopo do dispositivo (não por display), então um valor vazado corrompe toda sessão posterior. Fazê-los primeiro significa que mesmo um processo morto no meio do caminho já devolveu o dispositivo ao normal.
2. **Mover o app em primeiro plano** de volta para o display físico (`am display move-stack`).
3. **Restaurar painel + IME** — enquanto `shellInput` ainda é utilizável. `setDisplayPower(true)` + `input keyevent 224` + `restoreIme()`.
4. **Drenar o shell** — `shell.awaitIdle()`, para que as escritas de restauração sejam realmente executadas antes de o shell morrer. `exec` retorna na escrita, não na execução: sem essa barreira, o SIGTERM de `shell.close()` cortava o que ainda estava no pipe (medido: o que se perdia era a restauração de `screen_off_timeout`).
5. **Liberar recursos** — thread LifeWriter interrompida, shell destruído, GL/encoder/VD liberados.

A ordem anterior deixava `virtualDisplay.release()` bem no fim, sem nada depois que pudesse falhar, então uma exceção no meio do caminho ou um SIGKILL do lado do celular deixava o painel desligado e o VD vivo — um display vazado por reconexão.

**Sentinela de pedido de parada (2026-10-10, substitui a parada em dois estágios).** O antigo `VdDeploy.stopCommand` (`pkill -f ...; sleep 1; pkill -9 -f ...`) assumia que o SIGTERM dispara o shutdown hook da JVM — **falso na ART**: o `app_process` trata um SIGTERM puro como terminação imediata, então `cleanup()` (energia do painel / letterbox / IME / restauração de `screen_off_timeout`, liberação do VD) nunca rodava nesse caminho. Medição no MI 9: `kill -TERM` → o processo desaparece em 1 segundo, cleanup executa zero vezes (o `screen_off_timeout` fica para sempre no valor da sentinela). Agora o protocolo de parada é uma **sentinela de arquivo**: `VdDeploy.gracefulStopCommand` escreve `/sdcard/DiLinkAuto/stop-request` e retorna imediatamente; o watchdog do motor a consulta, **consome** (apaga) e define `running=false` — todo ponto de espera (polling de accept, fatias de park, retentativas de bind) então sai em ~100ms, `run()` alcança seu `finally` e `cleanup()` roda até o fim. O chamador espera pelo `vdAwaitExit` compartilhado (orçamento gracioso de 12s — a latência medida de sentinela→saída é de 6.1~6.3s, veja abaixo) e só então recorre ao `killCommandForce`. O motor também apaga uma sentinela obsoleta na inicialização, para que um pedido remanescente nunca mate uma instância nova.

Quatro defeitos encontrados na mesma rodada do MI 9 e corrigidos junto com ela:

- **`pkill -0` não é uma sondagem de vivacidade no toybox.** O `toybox 0.8.11-android` rejeita `-0` (`pkill: bad -L '0'`, rc=1 — sua tabela de opções só aceita `-SIGNAL|-l SIGNAL`), então `probeCommand` / `probeExitCodeCommand` retornavam **GONE incondicionalmente** e `vdAwaitExit` nunca esperava de fato; "esperar o motor antigo sair" era um no-op em produção desde sempre. Ambas as sondagens agora usam `pgrep -f` (mesma fonte e tabela de opções do toybox que o `pkill`, então qualquer dispositivo onde o caminho de force-kill funciona tem um `pgrep` funcional), com o padrão com colchetes `[P]ipelineServer` ainda impedindo que `-f` case com a própria cmdline do shell wrapper.
- **A cauda de `cleanup()` era truncada por `destroy()`.** `ShellExec.execShell` é fire-and-forget (retorna assim que o stdin é escrito); `cleanup()` escrevia seu último comando de restauração e imediatamente chamava `shell.close()` → `input.close()` + `destroy()` (SIGTERM) — o `sh` morria antes de executá-lo. Medição: o log imprime integralmente `settings put system screen_off_timeout 60000` e `Cleanup complete`, mas a leitura de volta no dispositivo continua `2147483647`. Correção: `ShellSyncPoint` — `arm()` gera um marcador único, `PersistentShell.awaitIdle()` escreve `echo <token>` no `sh` de longa duração e bloqueia até a thread de drenagem ver o marcador ecoado de volta; como stdin e stdout são ambos FIFOs, o echo prova que todo comando anterior terminou. `cleanup()` agora chama `shell.awaitIdle()` entre `lifecycle.stop()` e `shell.close()` (timeout de 2s, sem lançar exceção, logado ao expirar).
- **O receptor desktop matava a âncora antes de o motor poder limpar.** No caminho de deploy via ADB, o processo local `adb shell` **é** a âncora de vivacidade do motor (ele segura o stdin/stdout do `app_process`); `AdbDeployer.close()` o destrói, o stream de shell do lado do dispositivo cai e o adbd mata o motor — `cleanup()` nunca rodava. `DesktopApp.closeSession()` agora executa `runBlocking { adbDeployer.gracefulStop { … } }` (ou seja, o `vdStopEngine` compartilhado) **antes** de `close()`, e `STOP_TIMEOUT_MS` cresceu de 5s → 20s para cobrir join de decodificação + espera graciosa + fallback `-9` + round-trips de shell. O `ProbeRunner` faz o mesmo em seu `finally`.
- **Todo orçamento era apertado demais, porque a latência de saída medida é de 6.1–6.3s, não os 2–4s presumidos.** Com o orçamento de teardown em 6s, a espera errava por **130 ms em toda reconexão** — o log da sondagem mostrava `探针 ALIVE @+189ms` e depois `探针 GONE @+6123ms`, e um poller independente no lado do dispositivo (sentinela → pid sumido ≈6.3s) concordava. Então um `-9` era sempre disparado justamente quando `cleanup()` estava terminando, truncando sua cauda. A estimativa antiga cobria apenas os comandos de shell *dentro* de `cleanup()`; ela ignorava o join limitado da thread do pipeline, a liberação de encoder/VD e a espera pelo teardown do `ConnectionService` do lado do celular disputando os mesmos locks de display/rotação. Todos os três orçamentos agora são de 12s (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, `AdbDeployer.TEARDOWN_GRACEFUL_TIMEOUT_MS`, `PhoneDisplayRestorer.ENGINE_EXIT_WAIT_MS`) ≈ 2× o medido. `AdbDeployer.gracefulStop` agora também loga o tempo decorrido e cada transição de sondagem, o que foi o que tornou isso diagnosticável.

**O force kill é `pgrep` + `kill <pid>`, não `pkill -9 -f` (2026-10-10, MI 9).** O truque de colchetes `[P]ipelineServer` é um truque de *regex*: o `pgrep` o respeita (motor presente → rc=0, ausente → rc=1, sem auto-match), mas o `pkill -f` **não** — ele também mata o shell wrapper que está executando o comando, porque o texto do padrão está na cmdline desse próprio shell (`adb shell 'pkill -9 -f "[P]ipelineServer"'` inline retorna 137 antes do próximo `echo`). O motor *é* sinalizado (reproduzido duas vezes), mas o código de saída deixa de ser evidência — inaceitável para um caminho de último recurso. O `VdDeploy.killCommandForce` é, portanto, `for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done 2>/dev/null; exit 0`, o que mantém identificação e morte sob uma única regra de casamento.

## Fluxo de conexão

```
1. Phone and car on the same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB/TCP ADB tracks

   Track A (WiFi control):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + targetFps + dpiOverride + bitrate
   d. Phone responds with device info + vdServerJarPath + connectionMethod + vdDpi
      + the phone-side VD dims (vdWidth/vdHeight) so a deployer without shell
      access to the phone can size the VirtualDisplay correctly
   e. Phone opens lifecycle ServerSocket on 127.0.0.1:19647 (loopback only)
   f. If Shizuku available: phone deploys VD server directly.
      Else the receiver deploys via ADB (car: USB or TCP; desktop: local adb.exe,
      gated on its dev_mode setting).
   g. VD server starts: CLASSPATH=jar app_process / PipelineServer W H DPI PHONE_HOST
      EW EH FPS BITRATE [CAR_HOST [ALLOWED]]   (PHONE_HOST is 127.0.0.1 on every path)
   h. VD server reverse-connects to phone localhost:19647 (NIO non-blocking)
   i. Phone accepts, reads MSG_DISPLAY_READY (displayId + direct-injection flag)
   j. Phone sends VD_PORTS_BOUND to car on the control connection
   k. Car connects video (9638) + input (9639) directly to the VD server in parallel
   l. VD server accepts both -- session fully established

   Track B (USB ADB / TCP ADB):
   a. USB: scan USB devices for ADB interface -> CNXN -> AUTH -> connected
      TCP: dev-mode phone-IP lookup, connect to phone:5555 via TcpAdbConnection
   b. shellBackground(launchCommand) -- keeps ADB stream open so app_process survives
   c. Launch phone app via am start (AppTargets.PHONE_MAIN_ACTIVITY)

3. VD server creates VirtualDisplay (car viewport + auto-calibrated DPI)
4. VD server powers off the phone's physical panel via DisplayControl
5. VD server saves the original IME and sets the VD's IME policy
6. Pipeline thread: EGL/GL init -> SurfaceTexture -> encoder -> bind 9638/9639
7. Car starts VideoDecoder on the SurfaceView surface when MirrorScreen creates it
8. Video streams: VD -> SurfaceTexture -> GL -> encoder -> TCP 9638 -> car VideoDecoder -> SurfaceView
9. Touch: car SurfaceView onTouch -> TouchEvent encode -> TCP 9639 -> VD TouchInjector -> InputManager
```

Estados: `IDLE -> CONNECTING -> CONNECTED -> STREAMING`

Uma rotação do painel da central no meio do stream reutiliza a conexão TCP de controle. `MainActivity.onConfigurationChanged` chama `CarConnectionService.onCarViewportChanged`, que derruba vídeo/entrada + o servidor VD antigo e reenvia um `HandshakeRequest` com as novas dimensões. O celular faz o deploy de um novo servidor VD e reenvia `VD_PORTS_BOUND`. O gate do layout de streaming aceita `state == CONNECTING && appList.isNotEmpty()` para que o overlay de espera de vídeo cubra a lacuna de ~2s do redeploy em vez de piscar o `CarLaunchScreen` (o que destruiria a SurfaceView e perderia o estado do decodificador).

Um stream preto sustentado — nenhum conteúdo visível por `BLACK_SCREEN_SUSTAIN_MS` (3s na central, 10s no desktop) de keyframes minúsculos contínuos — dispara o mesmo caminho de re-handshake via `rehandshakeForBlackScreen()`: o celular derruba e refaz o deploy do servidor VD, que é a recuperação de que um compositor travado precisa.

## Desempenho em central DiLink 4.0 de baixa especificação (Snapdragon 439)

A central de referência é uma central multimídia DiLink 4.0 de baixa especificação: 8x Cortex-A53 (sem big cores), Adreno 505, 4GB de RAM, 1280x800, WiFi apenas 2.4GHz, API 28, decodificação de hardware H.264 limitada a 1080p. O pipeline de streaming foi reajustado para esse hardware. As nove correções:

1. **Dimensões de codificação limitadas a 1920x1080** (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`). Desacopladas das dimensões do VD pelos args `EW EH`; o VD permanece ampliado para preservar a correção de crop de IME (limitado a 2560 px de largura, issue #71), o encoder usa as dimensões reportadas pela central limitadas a 1080p. Sem isso, celulares acima de 1080p disparam decodificação por software no 439 -> fps de um dígito.
2. **Bitrate 8 Mbps -> 4 Mbps CBR** (`PipelineServer.BITRATE`, um alias de `VideoConfig.DEFAULT_BITRATE = 4_000_000`; o valor é passado como argv `BITRATE` e limitado à faixa de deploy). Fallback adaptativo endurecido: piso de 1.5 Mbps, janela de recuperação de 2s, passos de 0.5 Mbps (antes 2 Mbps / 5s / 1 Mbps).
3. **Framerate 30 -> 20 fps** (`VideoConfig.TARGET_FPS = 20`). Orçamento por frame de 33ms -> 50ms, ~33% menos carga de WiFi/GPU/alocação. (Eram 24 fps; reduzido para 20 na issue #71 depois que uma sessão de navegação de 2.5h manteve o celular quente.)
4. **MirrorScreen TextureView -> SurfaceView** (`MirrorScreen.kt`). Contorna a composição GL por frame do Adreno 505 (~2-5ms/frame em 1280x800). O gate `outputSurfaceValid` evita renderizar em uma surface destruída durante o esconder/mostrar da navegação; `switchSurface` reanexa o decodificador sem perder keyframes.
5. **Prioridades de thread.** `THREAD_PRIORITY_URGENT_DISPLAY` no pipeline de codificação, na thread de feed da decodificação e no leitor de drenagem do socket; `THREAD_PRIORITY_BACKGROUND` no LifeWriter. O A53 não tem big cores, então o escalonador precisa favorecer o caminho crítico.
6. **`carLogEnabled` tem como padrão `BuildConfig.DEBUG`** (`CarConnectionService` o reaplica por sessão via `CarLogWriter.setEnabled`); `ConcurrentLinkedQueue.size()` (O(n)) substituído por `AtomicInteger` (`CarLogWriter.bufferCount`).
7. **`AppIconCache.clear()` na desconexão** libera ~5-9MB de heap + os PNGs do eMMC; `prepareAll` paralelizado com `Semaphore(4)`, bitmaps intermediários reciclados.
8. **`Debug.getPss()` removido da inicialização** (chamada binder obsoleta, 100-500ms no A53).
9. **Wrapper `@Immutable AppTileData`** (`HomeScreen.kt`) exclui o ícone `ByteArray` instável das entradas de `AppTile` para que a grade de ícones pule a recomposição na reatribuição de `appList`.

## Compatibilidade da central com API 28

A central multimídia BYD roda Android 9 (API 28). Várias APIs de framework usadas pelo projeto são de API 29+:

- **`MediaCodecInfo.isHardwareAccelerated()` (API 29+)** lançava `NoSuchMethodError` na BYD, matando o processo. Correção: removido o seletor especulativo de decodificador de hardware; `VideoDecoder.start()` chama `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` diretamente. `REGULAR_CODECS` lista os de hardware primeiro e seleciona `OMX.qcom.video.decoder.avc` na BYD.
- **`am display move-stack` (API 29+)** condicionado a `SDK_INT >= 29` em `CarCommandRouter.moveTopApp`. Em níveis mais antigos o app em primeiro plano fica onde está, em vez de uma falha silenciosa de shell se passar por sucesso.
- **`cmd display power-reset` / `power-off` (API 29+)** é o fallback de shell no `DisplayPowerController`. Não existe subcomando `power-on` (`cmd display help` no Android 15: apenas `power-off` e `power-reset`); `power-reset` religa um painel forçado a desligado. Na API 26-28, uma falha de reflection em `DisplayControl` significa que o painel físico não é restaurado — agora logado via `logErr`, antes era silencioso.
- **`minSdk` elevado para 26** em `protocol` e `app-server` (era 24). Rearma o gate de lint NewApi. `app-client` permanece em 29.

## Recursos removidos (em relação ao upstream)

- **Encaminhamento de notificações.** Notificações do celular -> tela da central, o botão de notificações da barra de navegação, a tela de lista de notificações, o `NotificationService` do lado do celular (entrada de manifest, etapa de onboarding, strings) e as mensagens de protocolo `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` foram todos apagados. A barra de navegação da central não tem botão de notificações.
- **Trilho de apps recentes, relógio, informações de rede** na barra de navegação: apagados. A barra de navegação tem quatro botões (home, voltar, app anterior, desconectar; `NavBarLayout.actions()` é dono da ordem — o trilho esquerdo de cima para baixo é home/voltar/recentes/ejetar, a barra inferior em retrato é exatamente o inverso). O quarto não é o antigo trilho de recentes voltando — é a chave única de "trocar para o app anterior" adicionada na issue #152-PA, que apenas envia `GO_RECENT` (0x1F, semântica de toque duplo em recentes, resolvida no celular por `CarCommandRouter.quickSwitch`). Nenhuma lista de tarefas, nenhuma miniatura, nenhum estado por app é cacheado na central.
- **Controles de mídia e widgets de navegação** não estão implementados. (O streaming de áudio está implementado na v1.0.3; `NowPlayingBar` existe na árvore de código, mas só é composto quando `MediaMetadata` está presente, o que o celular nunca envia.)

## Estabilidade da sessão

- **Sessões ativas não são mortas por tentativas de reconexão.** O loop de repetição do gateway WiFi e a conexão manual param após 3 falhas ADB consecutivas (`noAdbCount >= 3`) em vez de repetir indefinidamente.
- **O ADB TCP reconecta quando o IP do celular muda.** O modo dev rastreia `lastAdbHost` e reconecta quando o IP do celular muda.
- **Fallback automático para ADB TCP quando o USB não está disponível.** `VdServerDeployer.deploy` recorre ao ADB TCP usando o IP do celular, se conhecido.
- **A desconexão pelo usuário permanece IDLE**, sem reconexão automática. Persistida em SharedPreferences (`user_disconnected`).
- **Prevenção de vazamento de VD.** `ConnectionService.cleanupGuard` evita `cleanupSession()` duplicado a partir dos pontos de chamada de teardown, e gerações de sessão tornam um teardown obsoleto um no-op (era: 1 desconexão → 9 chamadas de wake). Antes de um motor substituto ser lançado, todo ponto de deploy executa o `vdStopEngine` compartilhado (sentinela de pedido de parada graciosa → duas sondagens `GONE` consecutivas, vivacidade lida com `pgrep -f`, dentro de um orçamento de 12s → fallback `killCommandForce` → esperar de novo) para que duas instâncias vivas nunca disputem a mesma VD/DTA/portas 9638-9639.
- **Autocura de tela preta.** `VideoDecoder.onSustainedBlackScreen` dispara após `BLACK_SCREEN_SUSTAIN_MS` (3s) de keyframes minúsculos contínuos, não em rajadas transitórias de 3 frames durante o aquecimento do app; o desktop usa uma janela mais ampla de 10s via `setSustainWindow`. `CarConnectionService.rehandshakeForBlackScreen()` reconstrói o VD do lado do celular por meio de um re-handshake nas dimensões atuais. O latch `blackScreenRecoveryInFlight` garante no máximo uma reconstrução por sessão, evitando a tempestade de reconexões que originalmente causou o vazamento de VD.

## Decisões de projeto principais

| Decisão | Justificativa |
|----------|-----------|
| **Streaming direto do VD (sem retransmissão pelo celular)** | O servidor VD faz bind de 9638/9639; a central fala com o VD diretamente. Eram 4 operações de socket + 2 trocas de contexto por frame; agora 2 operações de socket + 0. O celular é orquestrador puro. |
| **Canal de ciclo de vida em `localhost:19647`** | Carrega `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` entre o celular e o servidor VD. Separado de vídeo/toque para que um encoder travado não possa bloquear comandos de ciclo de vida. O listener do celular faz bind apenas em loopback e recusa pares que não sejam loopback. |
| **Mensagem de controle `VD_PORTS_BOUND`** | O celular avisa a central quando o servidor VD fez bind de 9638/9639. A central conecta vídeo/entrada somente após esse sinal, evitando corridas de conexão. |
| **Conexões TCP dedicadas (4 sockets)** | Controle (9637), vídeo (9638), entrada (9639) e áudio (9640) em sockets separados. Elimina backpressure entre canais (frames de vídeo grandes não podem travar áudio ou entrada). |
| **Espelhamento de áudio AAC-LC (porta 9640)** | Streaming direto servidor VD -> receptor na porta 9640 com cadência contínua de silêncio sintético a 46.875 fps, evitando underflow do buffer de jitter. O receptor usa buffer de jitter de 150ms e reporta métricas de silêncio. |
| **Pipeline de thread única, sem filas** | `clock -> GL -> encoder -> TCP` em uma thread. Controle de fluxo natural: uma parada no TCP bloqueia o próximo `eglSwapBuffers`, desacelerando o encoder. Usa `System.nanoTime()` + `LockSupport.parkNanos()` para 20fps sem deriva. |
| **O Shizuku faz o deploy do servidor VD quando disponível** | O celular roda `app_process` diretamente via Shizuku; a central só precisa de WiFi. A central espera por `VD_PORTS_BOUND` em vez de fazer o deploy via ADB. |
| **`shellBackground` para o launch do `app_process`** | Mantém o stream ADB aberto para que o shell não saia e mate o processo do servidor VD. |
| **NIO não bloqueante em todo lugar** | Todos os sockets não bloqueantes: conexões WiFi, ciclo de vida em localhost, leituras baseadas em Selector. Nenhum I/O bloqueante no pipeline. |
| **20fps / 4Mbps CBR** | Ajustado para o Snapdragon 439 + Adreno 505 + WiFi 2.4GHz. Orçamento de 50ms por frame; piso de bitrate adaptativo de 1.5Mbps para links congestionados. |
| **SurfaceView (não TextureView) na central** | Contorna a composição GL por frame do Adreno 505. O gate `outputSurfaceValid` + `switchSurface` tratam destruição/recriação da surface durante a navegação sem perder keyframes. |
| **`createDecoderByType` direto** | `isHardwareAccelerated()` é API 29+ e lançava exceção na central BYD com API 28. `REGULAR_CODECS` lista os de hardware primeiro; `OMX.qcom.video.decoder.avc` é selecionado na BYD. |
| **Dimensões de codificação limitadas a 1920x1080** | A VPU do Snapdragon 439 limita a decodificação AVC por hardware a 1080p. Codificar acima disso força decodificação por software nos 8x A53 (fps de um dígito). |
| **Calibração automática de DPI** | `VideoConfig.calculateOptimalDpi` limita o DPI para que apps somente em retrato tenham largura lógica >=360dp em um VD em paisagem. Override opcional do lado da central em [120, 480]. |
| **Escala anti-crop do VD** | A largura do VD é ampliada para corresponder à largura física do celular, limitada a `VdDimensions.MAX_UPSCALED_WIDTH = 2560` (issue #71). ROMs chinesas (Meizu, Xiaomi) fixam a largura do IME no display físico; um VD mais estreito corta o teclado na horizontal. O limite aceita algum crop de IME em celulares acima de 2560px em troca de ~36% menos área de composição por frame. A issue #153 PB adiciona um **nível de área por sessão** (`VdAreaTier`, configuração da central → campo final `HandshakeRequest.vdAreaTier` → `VdDimensions.tierCap`): padrão `max(carWidth*1.6, 1600)` (ainda <= 2560, ~44% menos área em uma central de 1204 de largura), o nível de compatibilidade mantém os 2560 completos. O nível só muda o teto — ele nunca reduz um VD, e trocar de nível só tem efeito no próximo handshake, nunca via redimensionamento na sessão. |
| **Restauração de IME no teardown** | O IME do linkpc do VD não pode sobreviver à sessão. `ImeRestore.shouldRestoreIme` o exclui; o `PhoneDisplayRestorer` do celular e o `DisplayPowerController` do VD restauram o IME original. |
| **O watchdog força `cleanup()`** | Se a thread do pipeline travar em uma chamada nativa do MediaCodec, o `finally` do leitor de ciclo de vida nunca roda. O watchdog consulta `running` + `cleanedUp` e força `cleanup()` + `System.exit(1)` após uma carência de 3s. Ele também **consome a sentinela de pedido de parada** (`/sdcard/DiLinkAuto/stop-request`): ao ver o arquivo, apaga-o, loga, define `running=false` e faz unpark da thread do pipeline — a metade do `gracefulStopCommand` que fica no motor. |
| **Re-handshake de rotação no meio do stream** | A conexão de controle é reutilizada; apenas vídeo/entrada + servidor VD são reciclados. O gate do layout de streaming aceita `CONNECTING && appList.isNotEmpty()` para que a SurfaceView não seja destruída durante a lacuna do redeploy. |
| **A reconexão para após 3 falhas ADB** | `noAdbCount >= 3` interrompe o loop de reconexão em vez de repetir indefinidamente. O usuário é instruído a conectar o celular no USB da central. |
| **Sentinela de pedido de parada (`gracefulStopCommand`)** | Reescrito em 2026-10-10: o `app_process` da ART termina imediatamente ao receber SIGTERM e **não executa o shutdown hook da JVM** (no MI 9, medido: cleanup executa zero vezes), então o antigo "SIGTERM→SIGKILL em duas etapas" quase nunca disparava `cleanup()` — painel/letterbox/IME/screen_off_timeout nunca eram restaurados. Agora o lado externo escreve `/sdcard/DiLinkAuto/stop-request` (retorno instantâneo), o watchdog do motor faz polling → consome (apaga) → `running=false` → caminho normal de saída → **cleanup completo**; só no timeout recorre ao `killCommandForce` como rede de segurança. Na inicialização o motor apaga sentinelas remanescentes para não matar por engano uma instância nova. Terceiros só o acionam por meio de `vdStopEngine` (sem cópias por site); a vivacidade é lida com `pgrep -f` (o toybox rejeita `pkill -0`), e o padrão de colchetes `[P]ipelineServer` é respeitado pelo **`pgrep`** — o `pkill -f` ainda mata o shell wrapper que o executa, e é por isso que o force kill agora é `pgrep` + `kill <pid>` em vez de `pkill -9 -f`. |
| **Idempotência do cleanup (`cleanupGuard`)** | Guarda `AtomicBoolean` em `cleanupSession()`. Eram 7 pontos de chamada sem guarda — 1 desconexão disparava todo o teardown 9 vezes, cada uma competindo com um `pkill` contra a restauração anterior. `resetCleanupGuard()` antes de cada nova sessão. |
| **Fixação de par (slot de argv `CAR_HOST`)** | O motor ainda faz bind em `0.0.0.0` (ele precisa aceitar o receptor), mas o deployer passa o IP do receptor que obteve da conexão de controle ativa e todo outro accept é recusado. `-` mantém o comportamento legado de aceitar qualquer um, apenas para sites que não conseguem determinar seu próprio endereço de saída. Veja a seção de fronteiras de confiança. |
| **Espera de saída do VD antes de relançar** | Um único `vdStopEngine` compartilhado (protocol-core), usado por `vdRunDeploySequence` (todos os quatro pontos de deploy, e então o launch) e pelo teardown do receptor: pedido de parada graciosa → duas sondagens `GONE` consecutivas (`pgrep -f`) dentro de um orçamento de 12s → fallback `killCommandForce` → esperar de novo. Duas instâncias vivas disputam a mesma VirtualDisplay / DTA / portas 9638-9639; a perdedora pula `cleanup()` por completo. Variantes dessa regra por site foram como o vazamento voltou uma vez. |
| **`awaitIdle()` antes de `shell.close()`** | `ShellExec.execShell` retorna assim que o stdin é escrito; `cleanup()` costumava escrever seu último comando de restauração e imediatamente enviar SIGTERM ao `sh`, perdendo-o (medido: `settings put system screen_off_timeout 60000` era engolido). `PersistentShell.awaitIdle()` escreve `echo __DILINK_SYNC__<n>-<nanos>` no mesmo `sh` e bloqueia em `ShellSyncPoint` até a thread de drenagem ver o echo — dois FIFOs transformam isso em uma barreira de pipeline completa. Timeout de 2s, sem lançar exceção. |
| **`saveCurrentIme()` antes de `create()`** | O snapshot precisa rodar antes de `configureEnvironment()` escrever `screen_off_timeout=2147483647`, senão o caminho de restauração vê a sentinela e não faz nada. O código antigo tirava o snapshot depois de `create()` — o timeout real do usuário era perdido para sempre. |
| **Escopo de PhoneDisplayRestorer com tempo de vida do processo** | Possui `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — não o escopo do Service, que `onDestroy()` cancela. `NonCancellable` garante que `cmd display power-reset` não possa ser pulado. `inFlight` condensa requisições concorrentes. |
| **Autocura de tela preta** | `onSustainedBlackScreen` dispara após a janela sustentada (3s na central / 10s no desktop) de keyframes minúsculos, não em rajadas de 3 frames. `rehandshakeForBlackScreen()` reconstrói o VD. O latch `blackScreenRecoveryInFlight` evita tempestades; o desktop ainda limita as reconexões automáticas a 2 por processo. |
| **`stopVdServer()` retorna Boolean** | Os chamadores agora podem verificar se o CMD_STOP teve sucesso ou se caiu no kill por shell. Era void — os chamadores presumiam que a parada graciosa sempre funcionava. |
| **A allowlist filtra o payload de rede** | O `AllowlistScreen` do lado do celular seleciona quais apps do launcher chegam à central. `AppListBuilder.sendAppList` filtra antes de montar o payload de rede; `ACTION_ALLOWLIST_UPDATED` dispara um reenvio ao vivo. Pré-carregada com apps de mapa comuns na primeira execução. |
| **APK da central embutido no APK do celular** | `embedServerApk` empacota `app-server.apk` nos assets do `app-client`; `CarAppInstaller` o envia à central via `dadb`. Habilita o "Instalar na central" a partir do celular. |
| **Task `buildVdServer`** | `bundleLibRuntimeToJarDebug` -> D8 -> `vd-server.dex` -> `vd-server.jar` -> copiado para `app-client/src/main/assets/`. Empacota Kotlin stdlib + coroutines (necessários em tempo de execução no `app_process`). |
| **Heartbeat apenas no controle** | As conexões de vídeo e entrada não têm overhead de heartbeat. O watchdog da conexão de controle detecta pares mortos (timeout de 10s). |
| **O FileLog contorna a filtragem de logcat do HyperOS** | Log em arquivo com rotação em `/sdcard/DiLinkAuto/`. Padrão LIGADO para debug/pré-release, DESLIGADO para release; a escolha do usuário persiste. |
| **Relay de log da central com buffer limitado** | `CarLogWriter` acumula até 10k linhas quando a conexão de controle está caída e esvazia na reconexão. Contador `AtomicInteger` para verificação de limite O(1). |
| **Envio do relatório de crash na próxima conexão** | `CarCrashHandler` escreve em `filesDir/crash-pending.log`; a próxima conexão bem-sucedida o envia ao celular via `carLogSend`. |
| **Callbacks logSink** | `VideoDecoder`, `UsbAdbConnection` e `CarCrashHandler` roteiam logs pelo protocolo até o `FileLog` do celular. |
| **Autenticação ADB pré-hasheada** | `AUTH_SIGNATURE` usa `NONEwithRSA` + prefixo DigestInfo SHA-1 (pré-hasheado). Corresponde ao `RSA_sign(NID_sha1)` do AOSP. O "Sempre permitir" persiste corretamente. |
| **Energia do display via DisplayControl** | `DisplayControl.setDisplayPowerMode` carregado de `services.jar` via `DelegateLastClassLoader`. Recorre a `cmd display power-reset` / `power-off` (API 29+; não existe subcomando `power-on`). O `PhoneDisplayRestorer` do celular faz uma restauração em camadas (Shizuku -> PowerManager.wakeUp -> FLAG_TURN_SCREEN_ON -> wake lock). |
| **Flags de VD confiável** | `0x6c49` (`OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`) impede a migração de activities para fora do VD. |
| **Largura de viewport par** | A largura da barra de navegação é ajustada para garantir dimensões pares compatíveis com H.264. |
| **Deduplicação de launch de app** | `am start` sem `--activity-clear-task`. Apps existentes são retomados em vez de reiniciados. |
| **Costuras de teste que preservam comportamento** | A lógica pura do caminho crítico foi tornada acessível na JVM em vez de reescrita: framing/cripto ADB (`negotiateMaxPayload`, `readMessageFrom`, codificação de chave `java.util.Base64`), intervalos de heartbeat/watchdog do `Connection`, `AssetSource` e `CarInstaller` no celular, `reconnectBackoffMs` / `iconBudgetAccepts` na central. O comportamento em execução não mudou; o objetivo é a rede de regressão. Ver `docs/IMPLEMENTATION_REPORT_TESTING.md`. |
| **Decodificação fail-closed** | Todo decodificador de mensagem valida seus limites e lança `ProtocolDecodeException`; os limites de payload (16MB), os tetos do leitor e o despacho dos listeners tratam um par malformado como uma *conexão* morta, nunca um processo morto. |

## Stack de tecnologia

| Camada | Tecnologia |
|-------|-----------|
| Linguagem | Kotlin 1.9.22 (todos os módulos); Java para `UsbAdbConnection` / `AdbProtocol` / `AdbCrypto` |
| Build | Gradle 8.7, AGP 8.2.2, JDK 17 |
| UI | Jetpack Compose + Material 3 (Compose BOM 2023.10.01) no Android; Compose Desktop 1.6.2 (JetBrains) no Windows |
| Vídeo | MediaCodec H.264 — encoder: servidor VD 4Mbps CBR Main, I-frame 1s; decodificador da central: `createDecoderByType` (hardware primeiro); decodificador do desktop: JavaCV 1.5.10 + FFmpeg 6.1.1 (Windows x86_64), D3D11VA com fallback para software |
| Áudio | AudioRecord (REMOTE_SUBMIX) + MediaCodec AAC-LC (estéreo 48kHz, 128kbps, cadência de silêncio sintético a 46.875 fps) -> TCP 9640 -> AudioTrack (buffer de jitter de 150ms) |
| GPU | EGL14 + GLES20 + SurfaceTexture (pipeline de thread única) |
| Rede | NIO `ServerSocketChannel` / `SocketChannel` / `Selector`, Android NSD (mDNS) |
| ADB USB | Protocolo próprio no módulo `protocol/` (`UsbAdbConnection`, `AdbProtocol`, `AdbCrypto`), `logSink` para diagnóstico |
| ADB TCP | `TcpAdbConnection` (socket único, reutilizado para todos os comandos de shell) no caminho central -> celular; `dadb` 1.2.10 no caminho de instalação celular -> central; o desktop chama `adb.exe` externamente |
| Shell elevado | Shizuku (`dev.rikka.shizuku:api/aidl/provider:13.1.5`) — no celular, opcional |
| Extras do desktop | JNA 5.14 (`SetThreadExecutionState`), app-image com `jpackage` e uma JRE embutida |
| Async | Kotlin Coroutines + Flow (kotlinx-coroutines 1.7.3) |
| Testes | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3, sem Robolectric/Mockito — os módulos Android usam `isReturnDefaultValues = true` mais fakes escritos à mão |
| API mínima | 26 (`protocol`, `app-server`); 29 (`app-client`, `vd-server`) |
| Releases | Ver [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest) |
| Versão do protocolo | `PROTOCOL_VERSION = 1` |

## Referência de portas

| Porta | Direção | Propósito |
|------|-----------|---------|
| 9637 | receptor -> celular | Controle + dados (handshake, lista de apps, heartbeat, `VD_PORTS_BOUND`) |
| 9638 | servidor VD -> receptor | Vídeo H.264 (o servidor VD faz bind em `0.0.0.0`, aceita apenas o `CAR_HOST` fixado) |
| 9639 | receptor -> servidor VD | Entrada de toque (o servidor VD faz bind em `0.0.0.0`, aceita apenas o `CAR_HOST` fixado) |
| 9640 | servidor VD -> receptor | Áudio AAC-LC (o servidor VD faz bind em `0.0.0.0`, aceita apenas o `CAR_HOST` fixado) |
| 19647 | servidor VD -> celular | Canal de ciclo de vida (conexão reversa ao listener `127.0.0.1` do celular) |
| 5555 | central -> celular | ADB TCP (busca do IP do celular no modo dev, autoinstalação) |
