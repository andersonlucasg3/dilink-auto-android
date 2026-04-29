# App Servidor do Carro (app-server)

## Visao Geral

O app servidor do carro e executado no sistema de infotainment BYD DiLink. Ele usa um **modelo de conexao paralela** com trilhas WiFi (3 conexoes dedicadas) e USB executando simultaneamente:

1. Trilha A (WiFi): gateway IP + descoberta mDNS, conexao de controle (9637), handshake com o celular
2. Apos o handshake: conexoes de video (9638) + entrada (9639) abertas em paralelo
3. Trilha B (USB): varre dispositivos, USB ADB connect (com diagnosticos logSink), inicia app do celular
4. `checkAndAdvance()` avalia o estado quando qualquer pre-requisito muda
5. Recebe video H.264 e renderiza na tela do carro (inicio antecipado do decoder em superficie offscreen)
6. Captura eventos de toque e os envia ao celular via conexao de entrada para injecao no VD

Estados: IDLE → CONNECTING → CONNECTED → STREAMING

O APK do carro e incorporado no APK do celular. O celular auto-atualiza o app do carro via dadb quando uma discrepancia de versao e detectada durante o handshake. O carro recebe a mensagem `UPDATING_CAR` e mostra o status em vez de reconectar as cegas.

### UI de Dois Modos

O app do carro separa o fluxo de conexao da experiencia de streaming em dois modos distintos, correspondendo a abordagem do app do celular:

- **Modo launch** (`CarLaunchScreen`): Tela cheia, foco em conexao. Mostra branding, instrucoes passo a passo de conexao, status da conexao e entrada manual de IP. Sem barra de navegacao — a tela inteira e dedicada a estabelecer a conexao. Exibido quando o app do carro inicia e permanece ate que os icones de apps sejam recebidos do celular via conexao de controle.

- **Modo streaming** (`CarShell` com `PersistentNavBar`): O layout familiar com barra de navegacao esquerda (notificacoes, home, voltar, apps recentes) e area de conteudo (grade de apps, visualizacao de espelho, notificacoes). Exibido quando `appList` nao esta vazia e o estado e CONNECTED ou STREAMING.

O gatilho de transicao: quando o celular envia `APP_LIST` via conexao de controle e o estado da conexao atinge CONNECTED/STREAMING, a UI muda do modo launch para o modo streaming.

## Componentes

### CarConnectionService

Servico em primeiro plano gerenciando o ciclo de vida completo da conexao com uma maquina de estados de pre-requisitos paralela e 3 conexoes dedicadas.

**Arquitetura de 3 Conexoes:**
- `controlConnection`: handshake, heartbeat, comandos de app, canal DATA (lista de apps, notificacoes, logs do carro)
- `videoConnection`: apenas quadros de video H.264 (celular → carro)
- `inputConnection`: apenas eventos de toque (carro → celular)
- Heartbeat/watchdog apenas na conexao de controle; video e entrada nao tem custo de heartbeat
- A morte de qualquer conexao causa a derrubada em cascata da sessao completa

**Arquitetura de Trilhas Paralelas:**
- `connectionScope`: Job pai para todas as corrotinas de descoberta, cancelado na desconexao
- Trilha A e Trilha B executam simultaneamente
- `checkAndAdvance()` avalia o estado geral quando qualquer pre-requisito muda
- Desconexao do usuario: permanece IDLE, sem auto-reconexao (persistido em SharedPreferences)

**Trilha A — WiFi:**
- Descoberta: gateway IP (hotspot/LAN, tenta novamente a cada 3s) → mDNS → IP manual
- Conexao de controle: SocketChannel NIO nao-bloqueante conectando ao celular TCP:9637
- Handshake: envia dimensoes do viewport + DPI + appVersionCode + targetFps (60) → recebe informacoes do celular + vdServerJarPath
- Na resposta do handshake: abre conexoes de video (9638) + entrada (9639) em paralelo, define `wifiReady = true` apos todas as 3 estabelecidas
- Video: recebe quadros H.264 via conexao de video, despacha para VideoDecoder
- Toque: executor dedicado de thread unica, `sendTouchEvent()` / `sendTouchBatch()` via conexao de entrada
- Heartbeat: intervalo de 3s, timeout de watchdog de 10s (apenas conexao de controle)
- Backoff: atraso exponencial em falhas de reconexao

**Trilha B — USB:**
- Registra `BroadcastReceiver` para `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED`
- `MainActivity` encaminha intents USB para o servico
- Varre dispositivos USB por interface ADB
- USB ADB connect via UsbAdbConnection (no modulo protocol/), com `logSink` encaminhando todos os logs de autenticacao ADB para o FileLog do celular
- Inicia o app do celular: `am start -n com.dilinkauto.client/.MainActivity`

**Fluxo de Atualizacao:**
- Se o celular envia `UPDATING_CAR`, o carro define `updatingFromPhone = true`, mostra o status "Atualizando app do carro..."
- Pula tentativas de conexao de video/entrada e loop de reconexao durante a atualizacao
- Apos `pm install -r`, o app do carro reinicia renovado

**State Flows:**
- `_state`, `_phoneName`, `_appList`, `_notifications`, `_mediaMetadata`, `_playbackState`: estado principal exposto a UI
- `_videoReady`: true quando o primeiro quadro de video nao-config chega
- `_statusMessage`: status legivel para exibicao na UI
- `_vdStackEmpty` (SharedFlow): emitido quando o celular reporta que o VD nao tem atividades (aciona navegacao para home)

**Lancamento do Servidor VD:**
- `deployVdServer()`: `shellNoWait` com CLASSPATH do vdServerJarPath do handshake
- Args: `W H DPI PORT EW EH FPS` — FPS passado do targetFps do handshake

**Inicio Antecipado do Decoder:**
- No primeiro quadro de video CONFIG, inicia VideoDecoder em SurfaceTexture offscreen (antes do MirrorScreen)
- `onSurfaceTextureAvailable` do MirrorScreen para e reinicia o decoder com a superficie TextureView real
- Previne perda de keyframes durante o atraso de composicao da UI

**Roteamento de Log do Carro:**
- `carLogSend()` encaminha todos os logs do lado do carro pelo canal DATA `CAR_LOG` para o celular
- `videoDecoder.logSink` e `adb.setLogSink()` conectam os logs do VideoDecoder e UsbAdbConnection pelo mesmo caminho
- Todos os logs visiveis em `/sdcard/DiLinkAuto/client.log` do celular

### VideoDecoder

Decodificador H.264 usando MediaCodec com saida Surface (renderizacao direta via GPU).

- Fila de quadros: 15 quadros — absorve corrida de inicializacao e jitter de rede
- `onFrameReceived()`: enfileira quadros mesmo antes de `start()` ser chamado
- `start()`: alimenta CONFIG em cache primeiro, depois drena a fila
- Descarta o mais antigo na fila cheia: prefere descartar P-frames, remove P-frames enfileirados para keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0` para atraso minimo de decodificacao
- CONFIG (SPS/PPS) em cache e reproduzido no reinicio do decoder
- Propriedade `isRunning` para coordenacao de inicio antecipado
- Callback `logSink` encaminha todos os logs do decoder para o celular via carLogSend
- Modo catchup: quatro zonas graduadas de aceleracao baseadas na profundidade da fila — normal (0-6 quadros), suave 1,5x (7-12 quadros, pula 1 de 3 nao-keyframes), medio 2x (13-20 quadros, pula 1 de 2), agressivo 3x (21+ quadros, pula 2 de 3). Keyframes nunca sao pulados.
- Libera codec e realimenta CONFIG apos 10+ quedas consecutivas de dequeueInputBuffer

### ServerApp

Classe Application. Cria canal de notificacao `dilinkauto_car_service` com `IMPORTANCE_LOW`.

### RemoteAdbController

Cliente ADB direto usando a biblioteca dadb. Fornece toque, deslize, voltar, home e lancamento de apps via comandos shell no virtual display. Usado como caminho de entrada alternativo.

### CarLaunchScreen

Composable em tela cheia focado em conexao, exibido antes que a conexao com o celular seja estabelecida — sem barra de nav, sem grade de apps.

- Branding DiLink Auto (icone, titulo, slogan)
- Cartao de status da conexao com indicador colorido (verde=streaming, laranja=conectado/conectando, cinza=idle) e texto de status ao vivo
- Instrucoes "Como conectar": 4 passos numerados (ativar hotspot, conectar USB, abrir app do celular, aguardar auto-conexao)
- Entrada manual de IP para conexao direta
- Substituido pelo layout de modo streaming quando `appList` se torna nao vazia e o estado atinge CONNECTED/STREAMING

### PersistentNavBar

Barra de navegacao esquerda de 76dp — **exibida apenas no modo streaming** — com:
- Exibicao de relogio (HH:mm, atualiza a cada 1s)
- Botao ejetar (desconecta e persiste a preferencia do usuario)
- Indicador de status da rede
- Botao de notificacoes com contagem de badges nao lidas
- Botao Home
- Botao Voltar
- Icones de apps recentes (max 5, podados quando apps se tornam indisponiveis)
- Icones 40dp, texto 12-14sp

Largura calculada para garantir viewport par para o codificador H.264.

### NotificationScreen

- Lista de notificacoes ordenada por timestamp (mais recentes primeiro)
- Dedup por ID: atualizacoes substituem existentes (gerencia notificacoes de progresso)
- Barras de progresso: determinada (preenchida) e indeterminada (giratoria)
- Toque-para-iniciar: tocar uma notificacao inicia o app proprietario no VD e muda para visualizacao de espelho

### Grade de Apps (HomeContent)

Exibida como area de conteudo principal quando o modo streaming esta ativo e a tela atual e HOME:
- Campo de busca com `imePadding()` — teclado nao empurra a atividade, apenas a barra de busca se move
- `windowSoftInputMode="adjustNothing"` no manifest
- Icones de apps 64dp em celulas de grade adaptativa de 160dp
- Texto do nome do app: bodyLarge
- Ordenacao alfabetica
- Entrada manual de IP
- Status da conexao

### LauncherScreen (Legado)

Layout de launcher integrado completo com `CarStatusBar`, `SideNavBar` (80dp) e `AppGrid`. Nao usado no roteamento atual do `CarShell` — a UI ativa usa composables inline `PersistentNavBar` + `HomeContent`/`MirrorContent`/`NotificationContent`.

### RecentAppsState

Rastreia apps lancados recentemente (max 5), persistidos em SharedPreferences. `pruneUnavailable()` remove apps que nao estao mais presentes quando a lista de apps e atualizada.

### NavBarComponents

Composables de widgets individuais da barra de nav: `ClockDisplay` (atualiza a cada 1s), `NetworkInfo` (estado conectado/desconectado), `RecentAppIcon` (40dp, com destaque de estado ativo), `NavActionButton` (icones 40dp, rotulos 12sp).

### CarTheme

Esquema de cores escuras Material3 (`CarDark`) com cores de bloco de app especificas por categoria: Navegacao (verde), Musica (rosa), Comunicacao (azul), Outros (cinza).

### Informacoes da Tela do Carro

Testado no BYD DiLink 3.0:
- Tela: 1920x990 @ 240dpi
- Viewport: ~1806x990 (apos barra de nav de 76dp)
- VD: ~3282x1800 @ 480dpi (reduzido por GPU para ~1806x990 para codificacao)

## Dependencias

- Jetpack Compose + Material 3
- Modulo Protocol (compartilhado com o app do celular, inclui UsbAdbConnection + AdbProtocol + VideoConfig)
- dadb 1.2.10 (fallback TCP ADB)
