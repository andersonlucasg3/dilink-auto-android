# Arquitetura

## Visao Geral

DiLink-Auto e um projeto Gradle com quatro modulos:

```
DiLink-Auto/
├── protocol/        Biblioteca Android -- compartilhada entre os dois apps (modulo Gradle)
├── app-client/      Aplicativo Android -- executado no celular (modulo Gradle)
├── app-server/      Aplicativo Android -- executado no carro (modulo Gradle)
├── vd-server/       Biblioteca Android -- servidor VirtualDisplay (modulo Gradle)
```

## Arquitetura do Virtual Display

O **celular** implanta e inicia o servidor VD localmente. O servidor VD se conecta de volta ao app do celular (conexao reversa em localhost:19637, totalmente NIO nao-bloqueante). Os apps renderizam em um VirtualDisplay no DPI nativo do celular (480dpi), com reducao de resolucao via GPU para a resolucao do viewport do carro para codificacao H.264. A tela fisica do celular e completamente independente.

```
+- Celular -----------------------------------------------------------+
|                                                                     |
|  +---------------------+                                            |
|  | Tela Fisica          |  Independente -- usuario pode usar        |
|  | (mostra o app        |  o celular normalmente                    |
|  |  DiLink Auto ou      |                                           |
|  |  qualquer coisa)     |                                           |
|  +---------------------+                                            |
|                                                                     |
|  +-----------------------+    +----------------------------------+  |
|  | ConnectionService      |    | Servidor VD (shell UID 2000)     |  |
|  | 3 servidores NIO TCP   |<---+| Implantado pelo app do CELULAR  |  |
|  |  9637: controle        |19637| Conecta-se de volta ao celular  |  |
|  |  9638: video           | NIO |  via NIO nao-bloqueante em      |  |
|  |  9639: entrada         |     |  localhost                      |  |
|  | Implante do JAR VD     |     |                                  |  |
|  | Auto-atualiz. do carro |     | VirtualDisplay (DPI do celular)  |  |
|  | FileLog                |     | GPU SurfaceScaler (reducao)      |  |
|  | Retransmite video      |     |   redesenho periodico em idle   |  |
|  |  para o carro          |     | Codificador H.264 (viewport)     |  |
|  | Encaminha toque ao VD  |     | Iniciador de apps (am start)     |  |
|  +----------+-------------+     | Injetor de entrada (IInputManager)|  |
|             |                   | Fila de escrita NIO + leitura    |  |
|             |                   |  via Selector                    |  |
|             |                   +----------------------------------+  |
+-------------+--------------------------------------------------------+
              | WiFi TCP (3 conexoes)
              | 9637: controle + dados
              | 9638: video H.264
              | 9639: entrada de toque
              v
+- Carro (BYD DiLink 3.0) ---------------------------------------------+
|                                                                      |
|  CarConnectionService                                                |
|  Modelo de conexao paralela: trilhas WiFi + USB executam simultan.   |
|                                                                      |
|  Trilha A (WiFi):                                                    |
|  +-- Gateway IP + descoberta mDNS                                   |
|  +-- Conexao de controle (9637) → handshake (viewport+DPI+FPS)      |
|  +-- Video (9638) + Entrada (9639) conectam apos o handshake         |
|  +-- Recebe video H.264 → VideoDecoder → TextureView                 |
|  +-- Envia eventos de toque via conexao de entrada                   |
|                                                                      |
|  Trilha B (USB):                                                     |
|  +-- Varre dispositivos USB → USB ADB connect (logSink para diag.)  |
|  +-- Inicia app do celular (am start)                               |
|                                                                      |
|  Celular auto-atualiza app do carro via dadb ao detectar            |
|  versao diferente                                                    |
|  Carro recebe mensagem UPDATING_CAR → mostra status, para reconexao  |
|                                                                      |
|  UI: CarLaunchScreen → (icones de apps chegam) → CarShell + NavBar  |
|  Dois modos: launch (foco em conexao, sem nav) e streaming          |
|  Barra de nav (76dp): Notificacoes (badge+progresso), Home, Voltar  |
|  Icones 40dp, texto 14sp                                             |
+----------------------------------------------------------------------+
```

## Por que USB ADB a partir do Carro?

`app_process` deve executar como shell UID (2000) para criar VirtualDisplays que possam hospedar apps de terceiros. O carro se conecta ao `adbd` do celular via modo USB host usando uma implementacao customizada do protocolo ADB (`UsbAdbConnection` no modulo protocol/).

**Abordagem atual:** O carro atua como host ADB USB. O celular precisa apenas da **Depuracao USB** ativada (Opcao de Desenvolvedor padrao). Sem Depuracao Sem Fio, sem codigos de pareamento, sem dependencia de WiFi para configuracao.

## Responsabilidades dos Modulos

### protocol (Biblioteca Android)

Compartilhada entre ambos os apps. Contem UsbAdbConnection, AdbProtocol, VideoConfig e NioReader. Zero dependencias alem de corrotinas Kotlin.

| Componente | Arquivo | Proposito |
|-----------|---------|-----------|
| Codec de quadros | `FrameCodec.kt` | Codificacao/decodificacao binaria de quadros, buffer de cabecalho reutilizavel, writeAll NIO |
| Canais | `Channel.kt` | IDs de canal (control, video, audio, data, input) |
| Tipos de mensagem | `MessageType.kt` | Constantes de byte incluindo `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Mensagens | `Messages.kt` | Data classes serializaveis (handshake inclui `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Conexao | `Connection.kt` | Conexao TCP com heartbeat/watchdog opcional, fila de escrita lock-free, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — constantes de temporizacao compartilhadas |
| NioReader | `NioReader.kt` | Leitor nao-bloqueante baseado em Selector, timeout de select configuravel |
| Discovery | `Discovery.kt` | Registro/descoberta de servico mDNS, constantes de porta (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | Protocolo ADB sobre USB (CNXN, AUTH, OPEN, WRTE), callback logSink |
| AdbProtocol | `adb/AdbProtocol.java` | Constantes de mensagem ADB e serializacao |

### app-client (Aplicativo do Celular)

Gerencia a implantacao do servidor VD, auto-atualizacao do carro, retransmissao de 3 conexoes e FileLog.

| Componente | Arquivo | Proposito |
|-----------|---------|-----------|
| ConnectionService | `service/ConnectionService.kt` | Aceita 3 portas (9637/9638/9639), implante do JAR VD, auto-atualizacao do carro, callback de rede inteligente |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | Aceita NIO em localhost:19637, retransmissao de video (videoConnection), encaminhamento de toque, pilha vazia (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Captura e encaminha notificacoes do celular com progresso |
| InputInjectionService | `service/InputInjectionService.kt` | Fallback de injecao de toque (tela fisica) |
| FileLog | `FileLog.kt` | Log baseado em arquivo em `/sdcard/DiLinkAuto/client.log`, rotacao, ignora filtragem do logcat |
| MainActivity | `MainActivity.kt` | UI — iniciar/parar, status de permissoes, botao Instalar no Carro |

### app-server (Aplicativo do Carro)

Modelo de conexao paralela com trilhas WiFi (3 conexoes) e USB.

| Componente | Arquivo | Proposito |
|-----------|---------|-----------|
| CarConnectionService | `service/CarConnectionService.kt` | Maquina de estados paralela, 3 conexoes WiFi + trilha USB, tratamento de UPDATING_CAR |
| VideoDecoder | `decoder/VideoDecoder.kt` | Decodificacao H.264, fila de 30 quadros, inicio antecipado em superficie offscreen, callback logSink |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Tela de lancamento/conexao em tela cheia (sem nav), branding, instrucoes, IP manual |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + encaminhamento de toque, reinicio do decoder na superficie disponivel |
| HomeContent | `ui/screen/HomeScreen.kt` | Grade de apps (icones 64dp, celulas 160dp) ou status de conexao, exibido no modo streaming |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | Tela integrada legada com SideNavBar, CarStatusBar, AppGrid |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Lista de notificacoes com barras de progresso, toque-para-iniciar |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | Barra de nav 76dp (icones 40dp, texto 14sp), apps recentes (poda), apenas modo streaming |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Rastreia apps recentes, poda indisponiveis |
| MainActivity | `MainActivity.kt` | Tela cheia imersiva, encaminhamento de intents USB, roteamento de tela em dois modos (launch vs streaming) |

### vd-server (Processo com Privilegio de Shell)

Modulo de biblioteca Android (`com.android.library`), compilado via `bundleLibRuntimeToJarDebug` e depois D8 em um JAR pela task `buildVdServer` em `app-client/build.gradle.kts`. Depende de `:protocol` e `kotlinx-coroutines-core`. Implantado pelo celular em `/sdcard/DiLinkAuto/`.

| Componente | Arquivo | Proposito |
|-----------|---------|-----------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | Cria VD, fila de escrita NIO + leitor Selector, codificador H.264, FPS configuravel, contrapressao |
| FakeContext | `FakeContext.kt` | Falsifica `com.android.shell` para acesso ao DisplayManager |
| SurfaceScaler | `SurfaceScaler.kt` | Pipeline de reducao GPU via EGL/GLES, pula trabalho GL em idle (depende de repeat-previous-frame-after do codificador) |

## Fluxo de Conexao

```
1. Celular e carro na mesma rede (ou celular conectado ao USB do carro)
2. App do carro inicia, dispara trilhas paralelas WiFi + USB

   Trilha A (WiFi):
   a. Descoberta de gateway IP + consulta mDNS
   b. Conexao NIO na porta de controle do celular (9637)
   c. Handshake: carro envia viewport + DPI + appVersionCode + targetFps
   d. Celular responde com informacoes do dispositivo + vdServerJarPath
   e. Celular verifica appVersionCode -- se diferente, envia UPDATING_CAR, auto-atualiza via dadb
   f. Carro conecta video (9638) + entrada (9639) em paralelo apos handshake
   g. Celular aceita ambos, sessao totalmente estabelecida

   Trilha B (USB):
   a. Varre dispositivos USB por interface ADB
   b. USB ADB connect (CNXN -> AUTH -> conectado), logSink encaminha para carLogSend
   c. Inicia app do celular via am start

3. Celular implanta vd-server.jar em /sdcard/DiLinkAuto/
4. Carro inicia: CLASSPATH=jar app_process / VirtualDisplayServer W H DPI PORT EW EH FPS
5. Servidor VD cria VirtualDisplay (DPI do celular) + GPU SurfaceScaler (redesenho periodico)
6. Servidor VD faz conexao reversa PARA o celular em localhost:19637 (NIO nao-bloqueante)
7. Celular aceita conexao do servidor VD via NIO ServerSocketChannel
8. Carro inicia VideoDecoder em superficie offscreen imediatamente no primeiro quadro CONFIG
9. MirrorScreen exibe, reinicia decoder com superficie TextureView real
10. Fluxo de video: VD -> SurfaceScaler -> codificador -> fila de escrita NIO -> localhost -> NioReader do celular -> fila de escrita videoConnection -> WiFi TCP -> NioReader do carro -> VideoDecoder -> TextureView
```

Estados: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Principais Decisoes de Design

| Decisao | Justificativa |
|----------|-----------|
| **3 conexoes TCP dedicadas** | Controle/video/entrada em sockets separados. Elimina contrapressao entre canais (lista de apps nao pode travar o video). |
| **Trilhas paralelas WiFi + USB** | Ambas executam simultaneamente; checkAndAdvance() avalia o estado quando qualquer pre-requisito muda. |
| **Celular implanta o JAR VD** | Nao precisa de push pelo lado do carro. Celular extrai vd-server.jar para o sdcard via deployAssets(). |
| **Conexao VD reversa** | Servidor VD conecta AO celular (nao o celular ao VD), simplificando o tratamento de firewall/NAT. |
| **NIO nao-bloqueante em toda parte** | Todos os sockets sao nao-bloqueantes: conexoes WiFi, servidor VD em localhost, leituras baseadas em Selector. Sem I/O bloqueante no pipeline. |
| **FPS configuravel** | Carro envia `targetFps` no handshake, servidor VD o utiliza. Todos os timeouts do pipeline derivam de `FRAME_INTERVAL_MS = 1000/fps`. |
| **Encoder repeat-previous-frame-after** | SurfaceScaler pula trabalho GL em quadros ociosos. Codificador configurado para repetir o ultimo quadro por ate 500ms em conteudo estatico, prevenindo inanicao sem custo de GPU. |
| **Inicio antecipado do decoder** | Decoder inicia em SurfaceTexture offscreen quando o primeiro CONFIG chega, antes que o TextureView do MirrorScreen seja criado. Previne perda de keyframes durante a composicao da UI. |
| **Callback de rede inteligente** | `onLost` ignora quedas de rede nao relacionadas (dados moveis). So derruba a sessao se a rede da conexao ativa for perdida. |
| **Auto-atualizacao do carro com mensagens** | Celular envia UPDATING_CAR antes de instalar. Carro mostra status, nao reconecta as cegas. |
| **APK do carro incorporado no APK do celular** | Sistema de build empacota app-server.apk dentro do app-client, permitindo o recurso Instalar no Carro. |
| **GPU SurfaceScaler** | VD renderiza a 480dpi do celular (sem escalonamento de compatibilidade). GPU reduz para o viewport do carro. |
| **FakeContext** | ActivityThread + getSystemContext() para Context de sistema real. Ignora NPE do UserManager via reflexao mDisplayIdToMirror. |
| **Flags de VD confiavel** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` previnem migracao de atividades. |
| **Largura par do viewport** | Largura da barra de nav ajustada para garantir dimensoes pares compativeis com H.264. |
| **Heartbeat apenas no controle** | As conexoes de video e entrada nao tem custo de heartbeat. Watchdog da conexao de controle detecta peers inativos. |
| **FileLog** | Ignora a filtragem do logcat do HyperOS. Log baseado em arquivo com rotacao em `/sdcard/DiLinkAuto/`. |
| **Callbacks logSink** | VideoDecoder e UsbAdbConnection encaminham logs pelo protocolo para o FileLog do celular. |
| **Autenticacao ADB pre-hash** | AUTH_SIGNATURE usa `NONEwithRSA` + prefixo SHA-1 DigestInfo (pre-hash). Corresponde a `RSA_sign(NID_sha1)` do AOSP. "Sempre permitir" persiste corretamente. |
| **Desligamento de tela via SurfaceControl** | `DisplayControl` carregado de `services.jar` via `ClassLoaderFactory` (Android 14+). Fallback para `cmd display power-off/on`. Celular restaura tela ao desconectar VD. |
| **Decoder catchup** | Quatro zonas graduadas de aceleracao: normal (0-6 quadros), suave 1,5x (7-12), medio 2x (13-20), agressivo 3x (21+). Keyframes nunca sao pulados. |
| **Dedup de lancamento de app** | `am start` sem `--activity-clear-task`. Apps existentes retomam em vez de reiniciar. |
| **Contrapressao do servidor VD** | Descarta nao-keyframes no codificador quando a fila de escrita excede 6 quadros. Previne crescimento ilimitado de memoria. |
| **Desconexao do usuario** | Permanece IDLE, sem auto-reconexao. Persistido em SharedPreferences. |

## Stack Tecnologico

| Camada | Tecnologia |
|-------|-----------|
| Linguagem | Kotlin 1.9.22 (todos os modulos) |
| Build | Gradle 8.7, AGP 8.2.2 |
| UI | Jetpack Compose + Material 3 |
| Video | MediaCodec H.264 (codificador: servidor VD 8Mbps CBR Main, decodificador: carro) |
| GPU | EGL14 + GLES20 + SurfaceTexture (SurfaceScaler com redesenho periodico) |
| Rede | NIO ServerSocketChannel / SocketChannel / Selector, Android NSD (mDNS) |
| USB ADB | Protocolo customizado no modulo protocol/ (compartilhado), logSink para diagnosticos |
| WiFi ADB | dadb 1.2.10 (auto-atualizacao do carro) |
| Assincrono | Kotlin Coroutines + Flow |
| API Minima | 29 (Android 10) |
| Versao do App | versionName comparado via semver (compartilhado em gradle.properties); versionCode ainda enviado para compatibilidade retroativa |
| Versao do Protocolo | PROTOCOL_VERSION = 1 |
