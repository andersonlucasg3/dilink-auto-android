# App da central (app-server)

## Visão geral

O app da central roda no sistema de infotainment da BYD DiLink. Ele usa um **modelo de conexões paralelas** com WiFi (4 conexões dedicadas: controle/dados, vídeo, toque, áudio) e trilhas USB rodando simultaneamente:

1. Trilha A (WiFi): IP do gateway + descoberta mDNS, conexão de controle (9637), handshake com o celular (incluindo a negociação de `FEATURE_AUDIO`)
2. Após o handshake: conexões de vídeo (9638) + entrada (9639) + áudio (9640, se negociado) abertas em paralelo
3. Trilha B (USB): varredura de dispositivos, conexão ADB USB (com diagnóstico via logSink), lançamento do app do celular
4. `checkAndAdvance()` avalia o estado quando qualquer pré-requisito muda
5. Recebe vídeo H.264 e renderiza no display da central via SurfaceView
6. Recebe áudio AAC-LC e reproduz via `AudioPlayer` (jitter buffer de 150ms) e `AudioTrack`
7. Captura eventos de toque e envia ao celular pela conexão de entrada para a injeção de entrada no VD

Estados: IDLE → CONNECTING → CONNECTED → STREAMING

O APK da central é embutido no APK do celular. O celular instala o app da central via dadb sob demanda (etapa "Car setup" do onboarding ou botão "Install on Car" da tela principal).

### UI de dois modos

O app da central separa o fluxo de conexão da experiência de streaming em dois modos distintos, acompanhando a abordagem do app do celular:

- **Modo de lançamento** (`CarLaunchScreen`): tela cheia, focado na conexão. Mostra a identidade visual, instruções passo a passo de conexão, o status da conexão, a entrada manual de IP e os campos de DPI / bitrate / FPS iniciais. Sem barra de navegação — a tela inteira é dedicada a estabelecer a conexão. Exibido quando o app da central inicia e permanece até os ícones de apps serem recebidos do celular pela conexão de controle.

- **Modo streaming** (`CarShell` com `PersistentNavBar`): o layout com barra de navegação à esquerda (Eject / Home / Back) e área de conteúdo (grade de apps, visão do espelhamento). Exibido quando `appList` não está vazio e o estado é CONNECTED ou STREAMING. Durante um re-handshake de rotação no meio do streaming, `onCarViewportChanged` coloca o estado em CONNECTING e derruba o VD, mas o gate do layout de streaming também aceita `state == CONNECTING && appList.isNotEmpty()` para que o layout permaneça no lugar e um overlay de "aguardando vídeo" cubra a janela de ~2s de redeploy em vez de piscar de volta para `CarLaunchScreen` (o que destruiria a surface do SurfaceView e bloquearia a reinicialização do decodificador).

O gatilho da transição: quando o celular envia `APP_LIST` pela conexão de controle e o estado da conexão chega a CONNECTED/STREAMING, a UI passa do modo de lançamento para o modo streaming.

## Componentes

### CarConnectionService

Serviço em primeiro plano que gerencia o ciclo de vida da conexão com uma máquina de estados de pré-requisitos paralelos e 4 conexões dedicadas. Enxugado substancialmente para 407 linhas por meio de desacoplamento arquitetural (Refs #87, #92), delegando coordenação de estado, rastreamento de ADB, parsing da lista de apps e decodificação de áudio a colaboradores dedicados.

**Arquitetura de 4 conexões:**
- `controlConnection`: handshake, negociação de recursos, heartbeat, comandos de app, canal DATA (lista de apps, metadados de mídia, logs da central)
- `videoConnection`: apenas frames de vídeo H.264 (celular → central, porta 9638)
- `inputConnection`: apenas eventos de toque (central → celular, porta 9639)
- `audioConnection`: apenas frames de áudio AAC-LC (celular → central, porta 9640, quando `isAudioNegotiated`)
- Heartbeat/watchdog apenas na conexão de controle; vídeo, entrada e áudio não têm custo de heartbeat
- A queda de qualquer conexão crítica cascateia → teardown completo da sessão; falha de áudio degrada graciosamente sem derrubar o vídeo

**Arquitetura de trilhas paralelas e colaboradores (Refs #87, #92):**
- Delegado a `CarSessionCoordinator`, `CarAdbTracker`, `CarAppListReceiver` e `AudioPlayer`.
- O backoff de reconexão utiliza `BackoffPolicy` de `:protocol-core` em vez de cálculos ad-hoc.
- Saúde do decodificador monitorada via `DecodeLivenessTracker`.
- `connectionScope`: Job pai de todas as corrotinas de descoberta, cancelado no disconnect
- Trilha A e trilha B rodam simultaneamente
- `checkAndAdvance()` avalia o estado geral quando qualquer pré-requisito muda
- Desconexão do usuário: permanece IDLE, sem reconexão automática (persistido em SharedPreferences)
- O loop de reconexão para após 3 falhas consecutivas de ADB (`noAdbCount >= 3`); o ADB TCP reconecta automaticamente na mudança de IP do celular e cai automaticamente para ADB TCP quando o USB não está disponível
- `handleDisconnect()` roda no máximo uma vez por geração de sessão (`teardownGuard`): um teardown intencional limpa os listeners de desconexão, e qualquer reentrância é recusada, então um teardown não consegue mais se desdobrar em 2–4 corrotinas de reconexão concorrentes que sobrescrevem o `connectionScope`/`connectJob` uma da outra (auditoria S-05)

**Trilha A — WiFi:**
- Descoberta: IP do gateway (hotspot/LAN, novas tentativas a cada 3s) → mDNS → IP manual
- Conexão de controle: connect NIO não bloqueante via SocketChannel ao TCP:9637 do celular
- Handshake (montado via `HandshakeFactory.buildHandshakeRequest`): envia dimensões do viewport + DPI + appVersionCode + appVersionName + targetFps + `dpiOverride` + `bitrate` → recebe informações do celular + vdServerJarPath + connectionMethod + DPI do VD ajustado (`handshakeVdDpi`)
- Na resposta do handshake: aguarda `VD_PORTS_BOUND` e então abre as conexões de vídeo (9638) + entrada (9639) em paralelo, definindo `wifiReady = true` depois que as 3 estão estabelecidas
- Vídeo: recebe frames H.264 pela conexão de vídeo, despacha para `VideoDecoder`. CONFIG (SPS/PPS) e o IDR mais recente ficam em cache dentro do `VideoDecoder` para que o codec possa ser (re)iniciado a qualquer momento; o decodificador NÃO é iniciado em um `SurfaceTexture(0)` fora da tela — frames decodificados sem consumidor desperdiçariam banda de decodificação por hardware e uma alocação temporária de gralloc. O decodificador inicia quando a surface real do `SurfaceView` do `MirrorScreen` fica disponível.
- Toque: delegado ao `CarTouchSender` (executor de thread única) via `sendTouchEvent()` / `sendTouchBatch()`
- Heartbeat: intervalo de 3s, timeout do watchdog de 10s (apenas na conexão de controle)
- Backoff de reconexão: `CarConnectionService.reconnectBackoffMs(failures)` (função pura no companion) — 500ms na primeira falha, dobrando a cada falha, com teto de 8000ms e o shift limitado. A fórmula antes ficava inline no ponto de chamada e nunca era testada (6d25390 extraiu tanto isso quanto o orçamento de ícones para que os testes exercitem as constantes reais).

**Trilha B — USB:**
- Registra `BroadcastReceiver` para `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` (além da action do prompt de permissão). Um dispositivo que é conectado depois da varredura única re-arma a trilha USB (auditoria S-M7).
- `MainActivity` encaminha os intents de USB ao serviço (`onUsbDeviceFromActivity`), guardando o dispositivo em cache quando o serviço ainda não está vinculado e entregando exatamente uma vez no bind.
- Varre dispositivos USB em busca da interface ADB
- Conexão ADB USB via `UsbAdbConnection` (no módulo protocol/), com `logSink` roteando todos os logs de autenticação ADB para o `CarLogWriter`
- Uma desconexão USB no meio da sessão encerra a sessão ativa pelo mesmo caminho de teardown usado por uma desconexão real (auditoria S-M7): a sessão fica meio morta — a UI ainda diz STREAMING, mas todo re-handshake de rotação seguinte falharia — então ela volta para IDLE e reconecta de forma limpa.
- Inicia o app do celular: `am start -n com.dilinkauto.client/.MainActivity`
- Modo dev: o caminho de ADB TCP usa `RemoteAdbController` em vez de `UsbAdbConnection`, reconectando automaticamente na mudança de IP do celular

**Fluxos de estado:**
- `_state`, `_phoneName`, `_appList`, `_mediaMetadata`, `_playbackState`: estado principal exposto à UI
- `_videoReady`: true quando chega o primeiro frame de vídeo que não é de configuração
- `_statusMessage`: status legível para exibição na UI
- `_vdStackEmpty` (SharedFlow): emitido quando o celular informa que o VD não tem activities (dispara a navegação para a home)

**Deploy do servidor VD:**
- Delegado a `VdServerDeployer.deploy()` (ou `deployDirect()` para o caminho TCP-ADB de modo dev). O deployer é dono do contador de tentativas e da política de fallback para ADB TCP (tenta duas vezes em "no ADB" transitório e então cai para ADB TCP usando o IP do host do celular, se conhecido), monta o plano com `VdDeploy.buildDeployPlan` e executa a sequência kill → espera pela saída → launch por meio de `vdRunDeploySequence` — a orquestração no protocol-core que o caminho Shizuku do celular e o caminho adb.exe do desktop também chamam, para que a regra de "esperar por uma saída real" não volte a divergir entre os pontos de deploy.
- O launch roda com `background = false` (`shellBackground` no transporte TCP mantém o stream ADB aberto). Um launch em background com `&` encerra o shell imediatamente, o que fecha o stream `TcpAdbConnection` do lado da central e mata o motor que ele acabou de iniciar.
- Args: `W H DPI PORT EW EH FPS` — `targetFps` de `VideoConfig.TARGET_FPS` (20), `bitrate` de `carPrefs.startupBitrate` (`VideoConfig.DEFAULT_BITRATE`, 4 Mbps)

**Re-handshake de rotação:**
- `onCarViewportChanged(widthPx, heightPx, dpi)` é chamado por `MainActivity.onConfigurationChanged` quando o painel da central gira. Se as dimensões do viewport mudaram e o estado é STREAMING ou CONNECTED, o serviço cancela os loops de descoberta e chama `rehandshakeOnExistingControl`, que derruba vídeo/entrada (limpando seus listeners de desconexão para que `handleDisconnect` não seja invocado no teardown intencional), limpa `wifiReady`/`vdServerStarted`/`handshakeDone`, coloca o estado em CONNECTING e reenvia `HANDSHAKE_REQUEST` na conexão de controle existente com as novas dimensões + o `startupDpi` atual — deixando o decodificador rodando (veja a seção Re-handshake de tela preta para saber por que pará-lo deixaria a tela preta). O celular derruba o servidor VD antigo, faz o deploy de um novo nas novas dimensões, re-vincula 9638/9639 e envia `VD_PORTS_BOUND` de novo — mesmo fluxo da conexão inicial, só que sem reestabelecer a conexão TCP de controle.
- As dimensões de viewport informadas pelo peer na resposta do handshake são limitadas por `sanitizePeerDimension` (2..4096, caindo para o viewport da própria central) antes de chegarem ao `MediaFormat` ou ao guard de rotação (auditoria S-M4).

**Re-handshake de tela preta:**
- `videoDecoder.onSustainedBlackScreen` é ligado em `onCreate` para chamar `rehandshakeForBlackScreen()`, que reconstrói o VD do lado do celular nas dimensões *atuais* (sem mudança de viewport — o motor está travado, não o display). O gate é uma sessão ativa (`STREAMING`/`CONNECTED` + `handshakeDone`) mais `!reHandshakeInFlight`, `!blackScreenRecoveryInFlight` e um orçamento de reconstruções por sessão de `MAX_BLACK_SCREEN_REBUILDS` (2, zerado por `startConnection()`). O gate antigo também exigia `vdServerStarted`, que o modo Shizuku nunca define e que a própria recuperação limpa — então o caminho principal não tinha autocorreção nenhuma, e um contador por sessão é o que de fato limita a tempestade que originalmente vazava VDs (auditoria S-M1).
- `rehandshakeOnExistingControl(ctrl, newVpW, newVpH, dpi)` — compartilhado pelos caminhos de rotação e de tela preta. Ele derruba vídeo/entrada, re-arma o detector de tela preta, limpa `wifiReady`/`vdServerStarted`/`handshakeDone`, coloca o estado em CONNECTING e envia `HANDSHAKE_REQUEST` na conexão de controle existente. Ele deliberadamente **não** para o decodificador (auditoria S-03): o `CarShell` mantém o layout de streaming montado enquanto o estado é CONNECTING, o SurfaceView nunca é destruído e `surfaceCreated` nunca dispara de novo — então parar o codec deixava a tela preta pelo resto da sessão, exatamente o sintoma que essa recuperação existe para evitar. Os loops de descoberta são cancelados durante a janela e `reHandshakeInFlight` impede que um callback que chegue nesse intervalo feche justamente a conexão de controle que carrega o re-handshake (auditoria S-04).

**Roteamento de log da central:**
- O `CarLogWriter` roteia todos os logs do lado da central pelo canal DATA (`DataMsg.CAR_LOG`) até o celular. Os chamadores fazem um `send` não bloqueante (logcat + fila) que entrega a entrada a uma `AsyncLogQueue` limitada (1024); uma corrotina dedicada em `Dispatchers.IO` a drena, formata cada linha (`LogLine.bracketed`) e a envia, então nenhum chamador bloqueia na rede. Quando a conexão de controle está caída, as linhas são bufferizadas em uma `ConcurrentLinkedQueue` limitada (teto de 10k, contador `AtomicInteger` para checagem de teto O(1)) e liberadas antes da próxima linha ao vivo. `beginSession()` descarta o buffer da sessão anterior a cada `startConnection`, e `shutdown()` fecha a fila e descarta o buffer para que um writer morto não os prenda pelo resto do processo (auditoria S-L4). `videoDecoder.logSink`, `CarCrashHandler.logSink` e `adb.setLogSink()` passam todos por `carLogSend`, então todo caminho de log chega ao celular. Todos os logs ficam visíveis no `/sdcard/DiLinkAuto/client.log` do celular. O log inicia como `BuildConfig.DEBUG` (desligado em release); o celular o alterna via `LOG_TOGGLE`.

### CarSessionCoordinator

Gerencia as conexões de rede multicanal (`service/CarSessionCoordinator.kt`), separando a orquestração de conexão do ciclo de vida do Service do Android:
- Gerencia quatro canais TCP dedicados: controle (9637), vídeo (9638), entrada (9639) e áudio (9640).
- Executa o handshake inicial com `HandshakeFactory`, avaliando `FEATURE_AUDIO` via `isAudioNegotiated`.
- Orquestra o re-handshake de rotação no meio do streaming e o re-handshake de tela preta na conexão de controle existente.
- Despacha o vídeo recebido para `VideoDecoder`, o áudio para `AudioPlayer` e os eventos de app para `CarAppListReceiver`.

### CarAdbTracker

Gerencia as trilhas de conexão ADB USB e ADB TCP (`service/CarAdbTracker.kt`):
- Trata os intents de broadcast de USB, enumeração de dispositivos, autenticação e lançamento de app em background.
- Mantém a lógica de fallback para ADB TCP e monitora falhas consecutivas de ADB (`noAdbCount >= 3`).
- Rastreia as transições de estado do ADB independentemente do estado de streaming por Wi-Fi.

### CarAppListReceiver

Processa as listas de apps e os metadados do launcher (`service/CarAppListReceiver.kt`):
- Decodifica as listas de apps recebidas e valida os tamanhos dos ícones contra `AppIconLimits` (`MAX_ICON_DIM`, `MAX_ICON_BYTES`) para evitar esgotamento de memória em centrais multimídia com pouca RAM.
- Gerencia o preenchimento do cache de ícones e atualiza o estado reativo da lista de apps.

### AudioPlayer

Gerencia a decodificação e a reprodução do stream de áudio AAC-LC na central (`audio/AudioPlayer.kt`):
- **Codec**: configura o `MediaCodec` para decodificação de áudio AAC-LC a partir de `AudioPayload.CONFIG` (`csd-0`).
- **Jitter buffer**: mantém um jitter buffer de 150ms (configurável na faixa 60~200ms) para suavizar o jitter da transmissão Wi-Fi sem atraso excessivo de áudio.
- **Reprodução**: envia o áudio PCM decodificado para um `AudioTrack` configurado para reprodução de mídia de baixa latência.
- **Métricas de silêncio**: registra periodicamente estatísticas de frames de silêncio (`silenceFrames`/`totalFrames`), fechando o contrato de teste da S23 contra aprovações silenciosas falso-verdes (issue #84).

### HandshakeFactory

Monta o `HandshakeRequest` da central para o celular (`service/HandshakeFactory.kt`). Preenche `deviceName` (`DiLink-${Build.MODEL}`), dimensões do viewport, DPI, `appVersionCode`/`appVersionName`, `targetFps`, `dpiOverride` e `bitrate`. O mesmo builder é usado nos dois pontos de chamada — conexão WiFi inicial e re-handshake de rotação no meio do streaming — então apenas as dimensões do viewport e o DPI diferem entre os dois; os chamadores calculam esses valores a partir do estado atual do display. Extraído de `CarConnectionService` para remover a duplicação entre `connectToPhone` e `onCarViewportChanged`.

### VdServerDeployer

É dono do deploy do vd-server (`PipelineServer`) no celular a partir da central, pelo transporte ADB que estiver disponível no momento (USB ou TCP), além da política de retry/fallback (`service/VdServerDeployer.kt`). Dois pontos de entrada:

- `deploy()` — executa a checagem de disponibilidade, o retry e o fallback para ADB TCP. Chamado pela máquina de estados quando as duas trilhas estão prontas ou depois que uma conexão ADB USB é concluída após o handshake.
- `deployDirect(controller)` — para o caminho TCP-ADB de modo dev, que já tem um `RemoteAdbController` novo em mãos e ignora a sonda de disponibilidade.

Lê as dimensões do viewport + DPI do host (que é dono do display) via `CarViewport.size`, e reporta o status pelo message sink do host. O DPI vem de `handshakeVdDpi` (se o celular o ajustou) ou de `VideoConfig.calculateOptimalDpi` caso contrário. Usa `VdDeploy.buildDeployPlan` para o comando de launch; o lado de stop é de responsabilidade de `VdDeploy.gracefulStopCommand` dentro da sequência compartilhada (o stop não faz parte do plano). Extraído de `CarConnectionService` — que desde então passou por uma refatoração abrangente até 407 linhas (Refs #87, #92) com a extração de `CarSessionCoordinator`, `CarAdbTracker` e `CarAppListReceiver`.

**Parada do motor antigo**: o stop é um **protocolo de sentinela cooperativo**, não um sinal. Medido no MI 9 (2026-10-10): na ART, um `SIGTERM` puro encerra o `app_process` imediatamente e **nunca executa o shutdown hook da JVM**, então `PipelineServer.cleanup()` é pulado — o stop em dois estágios `SIGTERM → SIGKILL` antigo era silenciosamente equivalente a um force kill. Em vez disso, `vdRunDeploySequence` primeiro escreve `/sdcard/DiLinkAuto/stop-request` (`VdDeploy.gracefulStopCommand`, precisa retornar instantaneamente — o transporte desktop tem deadline de 5s). O watchdog do motor faz polling desse arquivo, o consome e vira `running=false` + libera todos os pontos de espera, de modo que `run()` desenrola pelo seu caminho de saída natural e o `cleanup()` completo roda. `vdRunDeploySequence` então espera pela saída: faz polling do celular via ADB com `VdDeploy.probeExitCodeCommand` (exit 0 = vivo, exit 1 = sumiu) por até 12s (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, polling a cada 150ms; a latência medida de sentinela→saída no MI 9 é **6.1~6.3s**, então os orçamentos anteriores de 6s/10s eram ambos apertados demais) e exige **duas sondagens GONE consecutivas** antes de aceitar a saída — um falso transitório numa falha de transporte não pode liberar um launch que corre com um motor ainda vivo. Uma sondagem que não pôde rodar de jeito nenhum (`VdProbeResult.UNKNOWN`, transporte morto) é aceita imediatamente para que o deploy nunca trave. No timeout gracioso, um force kill (`VdDeploy.killCommandForce`) é emitido e a espera é repetida uma vez com o orçamento curto de 3s (`EXIT_WAIT_TIMEOUT_MS`). Dois motores vivos disputam o mesmo VirtualDisplay / DTA / binds 9638-9639 e o perdedor nunca roda `cleanup()` — um VD vazado por reconexão, que é o que deixa a tela da central preta depois de alguns ciclos. O motor além disso apaga uma sentinela obsoleta na inicialização, para que uma requisição que nunca foi consumida não possa matar a instância substituta. A orquestração em si mora no protocol-core (`VdDeploySequence.kt`) porque os pontos da central, do Shizuku do celular e do desktop já tinham divergido em três regras diferentes de "esperar pela saída".

### CarLogWriter

Retransmissor de log central → celular (`service/CarLogWriter.kt`). Os chamadores fazem um `send` não bloqueante (logcat + offer em uma `AsyncLogQueue` limitada, 1024) e uma corrotina dedicada em `Dispatchers.IO` a drena, formata cada linha como um frame `DataMsg.CAR_LOG` (`LogLine.bracketed`) na conexão de controle. Quando a conexão de controle está caída, as linhas são bufferizadas em uma `ConcurrentLinkedQueue` limitada (teto de 10k, contador `AtomicInteger`) e liberadas antes da próxima linha ao vivo, para que o contexto de diagnóstico em torno de desconexões/crashes seja preservado. `beginSession()` descarta o buffer da sessão anterior a cada `startConnection`, e `shutdown()` fecha a fila e descarta o buffer para que as entradas do writer não fiquem presas pelo resto do processo (auditoria S-L4). `carLogEnabled` inicia como `BuildConfig.DEBUG`; o celular alterna via `LOG_TOGGLE`. Extraído de `CarConnectionService` para isolar a maquinaria de log da máquina de estados da conexão.

### CarTouchSender

Codifica e envia eventos de toque da central para o servidor VD do celular na conexão de entrada (porta 9639, `service/CarTouchSender.kt`). Um executor dedicado de thread única mantém a codificação de toque fora da thread de UI e serializa os envios para que a ordem dos frames do canal de entrada permaneça estável. Contadores de drop/envio limitam o spam de log por meio de um predicado compartilhado: os 5 primeiros envios e os 3 primeiros drops, depois a cada 100º evento. `resetCounters()` é chamado na desconexão para que o log da próxima sessão comece limpo. Extraído de `CarConnectionService`.

### VideoDecoder

Decodificador H.264 usando `MediaCodec` com saída em Surface (renderização direta na GPU). Integra `DecodeLivenessTracker` para rastreamento unificado de vitalidade de decodificação e de baseline, e usa `BackoffPolicy` para backoff exponencial durante resets do decodificador.

- Criação do codec: apenas `MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)`. `REGULAR_CODECS` lista os decodificadores de hardware primeiro, então isso resolve para `OMX.qcom.video.decoder.avc` na BYD sem perguntar. O seletor especulativo `findHardwareAvcDecoder` + `createByCodecName` foi removido porque chamava `MediaCodecInfo.isHardwareAccelerated()`, um método de API 29+ que lançava `NoSuchMethodError` na central multimídia BYD com API 28 e matava o processo.
- `fps` é limitado uma vez no `start()` pela função pura `coerceFps(fps)` para `VideoConfig.MIN_FPS..MAX_FPS` (10..60). O valor vem de uma preferência persistida e é usado duas vezes como divisor (`1000L / fps`) e como `KEY_OPERATING_RATE`, então um `0` legado ou editado à mão levantava `ArithmeticException` dentro da thread de feed e matava a decodificação sem recuperação (auditoria S-L2).
- A largura/altura fornecida pelo peer chega ao `MediaFormat.createVideoFormat` e ao guard de rotação. Valores zero/negativos/absurdos derrubam o codec ou suprimem a recuperação de rotação para sempre, então o serviço os limita por `sanitizePeerDimension` (2..4096, caindo para o viewport da própria central) antes de chegarem aqui (auditoria S-M4).
- O ciclo de vida do codec (create / configure / start / stop) roda no `decoderExecutor` único do serviço, nunca na thread principal que entrega `surfaceCreated`/`surfaceDestroyed`; a execução serial também impede que um stop enfileirado se intercale com um start (auditoria S-M3).
- `start()` / `stop()` são seções críticas inteiras sob `lifecycleLock`, e a thread de feed é dona da liberação da instância de codec que ela capturou — liberá-la em `stop()` enquanto a thread está parada num `dequeueOutputBuffer` nativo é o padrão de SIGSEGV documentado (auditoria S-07/S-08).
- Fila de frames: `VideoDecoder.FRAME_QUEUE_CAPACITY` = 8 frames — dimensionada para `FeedBurst.MAX_FRAMES_PER_ROUND`, com drop-oldest no overflow (issue #163: a fila antiga de 4 slots derrubava um frame a cada burst)
- `onFrameReceived()`: enfileira frames mesmo antes de `start()` ser chamado; CONFIG e keyframes são cacheados separadamente
- `start()`: alimenta primeiro o CONFIG cacheado, depois o keyframe IDR cacheado (para que o codec tenha um frame de referência imediatamente) e então drena a fila
- Drop-oldest com fila cheia: prefere descartar P-frames, despeja P-frames enfileirados em favor de keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE = fps` para atraso mínimo de decodificação
- CONFIG (SPS/PPS) cacheado e reproduzido no restart do decodificador; um CONFIG novo também descarta o keyframe cacheado obsoleto
- Ressincronização de IDR após flush: após 10+ drops consecutivos de `dequeueInputBuffer`, o codec recebe flush, o CONFIG é realimentado e `seekingKeyFrame` é ativado; o loop de feed drena P-frames até o próximo IDR chegar antes de retomar a alimentação normal
- Propriedade `isRunning` para coordenação de início antecipado
- `switchSurface(newSurface)` usa `MediaCodec.setOutputSurface()` para reanexar uma nova surface sem stop/start (elimina a tempestade de perda de keyframe que acontece com stop()+start())
- `invalidateSurface()` desliga o flag de render do `releaseOutputBuffer` para que os frames não sejam empurrados para uma surface destruída durante o esconder/mostrar da navegação
- O gate `outputSurfaceValid` impede a renderização em uma surface destruída ao longo do esconder/mostrar da navegação
- O callback `logSink` roteia todos os logs do decodificador para o celular via `carLogSend`
- Graduação de stall (issue #200): `StallEscalationPolicy` observa o relógio do loop de feed, não o contador de render, então um link silencioso fica visível **enquanto** acontece. Em 1.5 s ele registra `LINK GAP … ctrlSilent=…`, chama `noteGap()` (pré-armando o resync; a requisição de IDR continua passando pelo `SyncRequestGate`) e em 3 s registra `STALLED …`. `ctrlSilent` é `Connection.msSinceLastFrameReceived()` no canal de controle, o que separa um link morto (os frames de controle também param) de um stall do encoder (os frames de controle continuam fluindo). A linha `STALL` existente mantém seu papel de resumo pós-recuperação. Enquanto o `ResyncPolicy` aguarda um IDR, os P-frames que chegam são descartados na fronteira de recebimento em vez de encher a fila de 8 slots — esse flush de backlog é o que produziu 88 drops e uma segunda quebra da cadeia de referência em campo (issue #200). Tanto `LINK GAP` quanto a linha `STALLED` escalada passam por `VideoDecoder.diagSink` → `CarLogWriter.sendUnconditional()`, então sobrevivem a um build release em que `CarLogWriter.setEnabled(BuildConfig.DEBUG)` é false; faça grep nelas ao reverificar a #200 em uma central real.
- Flag `debugFrameStats` (alternada por `LOG_TOGGLE`): log a cada 30 frames inclui tempo de decodificação acumulado e profundidade da fila
- A thread de feed roda em `Process.THREAD_PRIORITY_URGENT_DISPLAY` (o 8x A53 não tem núcleos big; avise o scheduler de que isso é crítico em latência)

**Autocorreção de tela preta:**

O julgamento não mora mais neste arquivo: o `BlackScreenDetector` do `protocol-core` é dono dele (e1674f8), então o decodificador da central e o receptor Windows aplicam uma única definição de "stream preto" e a máquina de estados é testável em unidade sem `MediaCodec`. A central injeta `SystemClock.elapsedRealtime()` como seu relógio; o lado desktop usa o divisor padrão de `nanoTime`.

- `onSustainedBlackScreen: (() -> Unit)?` — callback disparado quando o stream está persistentemente preto por `BLACK_SCREEN_SUSTAIN_MS` (3000ms, definido em `BlackScreenDetector`). Alguns I-frames minúsculos logo após o início são normais (o VD pode compor nada por um instante antes do primeiro frame do app chegar), e re-handshakear nisso causaria loops de reconexão — exatamente o que vazava VDs. Exigir uma janela sustentada significa que só escalamos em um stream genuinamente travado.
- `tinyKeyframeStreak` / `alerted` — estado de alerta dentro do detector: após 3 keyframes minúsculos consecutivos (<2048 bytes), `isAlerted()` vira e a central registra "Suspected BLACK SCREEN". O alerta é só de log; ele nunca dispara uma recuperação.
- `blackSinceMs` / `recoveryFired` — a janela de sustentação do detector, rastreada como um início anulável em vez de uma sentinela `0L`. O `0L` tanto engolia um período preto que por acaso começasse no relógio 0 quanto, num relógio relativo ao processo, fazia o frame minúsculo seguinte ser lido como "já faz 3s" — um bug real na versão inline (o desktop registrava "1 tiny keyframe, 10000ms" e queimava os dois orçamentos de autocorreção em um stream saudável). `onSustainedBlackScreen` dispara uma vez por janela armada; um I-frame de tamanho normal limpa a sequência, anula o início e re-arma a escalada.
- `resetBlackScreenState()` — re-arma o detector em cada `start()` e `stop()`, e explicitamente antes de um re-handshake, que não para mais o decodificador.

### AppIconCache

Cache de ícones do lado da central que decodifica e redimensiona os ícones uma vez e depois os serve instantaneamente durante a rolagem.

- `putSource(packageName, pngBytes)`: armazena o PNG de origem em alta resolução (192x192) recebido do celular pela lista de apps. Persiste em disco como `{packageName}_src.png`.
- `prepareAll(apps, sizePx)`: decodifica e redimensiona todos os ícones em paralelo em threads de background, limitado por `Semaphore(4)` (8 núcleos A53; limita o paralelismo para conter a rotatividade do heap nativo). Incrementa `preparedVersion` para disparar a recomposição da UI. Intermediários reciclados.
- `getPrepared(packageName)`: consulta síncrona O(1) em `ConcurrentHashMap` — zero corrotinas, zero I/O, zero decodificação durante a rolagem. Retorna um `ImageBitmap` pronto para renderizar.
- `get(packageName, sizePx)`: caminho completo de decode+resize (usado pela NavBar para poucos ícones). Retorna `Bitmap` no tamanho em pixels solicitado. Não polui o cache `prepared` do tamanho da grade.
- `evict(packageName)`: remove os dados em cache de apps desinstalados.
- `clear()`: limpa todos os caches de ícones em memória e em disco. Chamado na desconexão — o celular reenvia os ícones no próximo `APP_LIST`, então retê-los entre sessões só desperdiça ~5-9MB de heap em um aparelho de 4GB e faz o cache no eMMC crescer sem limite.
- O caminho de APP_LIST em `CarConnectionService` limita o que sequer chega a este cache: um teto de bytes por ícone (`MAX_ICON_PNG_BYTES`, 1MB) mais um teto de dimensão via `inJustDecodeBounds` (`MAX_ICON_DIMENSION`, 512px), para que uma bomba de descompressão nunca chegue ao `BitmapFactory`, e um orçamento agregado `MAX_APP_LIST_ICON_BYTES` (64MB, verificado pela função pura `iconBudgetAccepts`) para que um único APP_LIST não prenda centenas de MB de PNGs de origem em uma central com 4GB (auditoria S-M5).

### ServerApp

Classe Application. Cria o canal de notificação `dilinkauto_car_service` com `IMPORTANCE_LOW`. Inicializa o `AppIconCache` em `filesDir/icons`. Instala o `CarCrashHandler` e registra as informações do dispositivo na criação.

### CarCrashHandler

Handler de exceções não capturadas do app da central (`CarCrashHandler.kt`). Em um crash: salva um relatório de crash com stack trace e informações do dispositivo em `filesDir/crash-pending.log`. O relatório é enviado ao celular na próxima conexão bem-sucedida via `carLogSend` (`CarConnectionService.onCreate` chama `consumePendingCrash()` e ecoa o relatório de volta pelo canal de log entre os marcadores `─── PREVIOUS CRASH REPORT ───`). `consumePendingCrash()` retorna e arquiva o relatório pendente como `crash-sent-<ts>.log` para que ele não seja reenviado a cada conexão; o arquivo é podado para os 5 arquivos mais recentes, senão um app em loop de crash enche o armazenamento da central (auditoria S-L5). O flush TCP é limitado a `LOG_FLUSH_WAIT_MS` (300ms) em vez de um `Thread.sleep(1500)` fixo dentro de `uncaughtException` — essa espera roda na thread que está prestes a morrer, então uma espera longa atrasa o diálogo de crash que o usuário vê e o início do próximo processo.

A formatação do relatório e das informações do dispositivo mora em `CarCrashReport` (auditoria R3-SRP-19), separada do handler, que mantém apenas a entrega: `CarCrashReport.build(thread, throwable)` emite um cabeçalho `=== DiLink Auto Car Crash Report <yyyy-MM-dd HH:mm:ss.SSS> ===`, `thread=`, o stack trace e uma seção `── Process State ──` com `pid=`/`uid=`; `CarCrashReport.deviceInfo(context)` estende o `DeviceInfo.buildDeviceInfoBlock` compartilhado com uma seção Memory e uma linha `memThreshold`. O celular consome esse texto linha por linha, então os rótulos das seções são um contrato — `CarCrashReportTest` (7 casos) trava o cabeçalho, o formato de timestamp US, o nome da thread, o marcador de stack-frame, a cadeia de causas, a seção `Suppressed:` e o determinismo do corpo.

### RemoteAdbController

Cliente ADB direto usando uma conexão ADB TCP persistente (`adb/RemoteAdbController.kt`). Usa `TcpAdbConnection`, que mantém um único socket aberto para todos os comandos. Fornece `shell`, `shellBackground` (mantém o stream ADB aberto — evita que o `app_process` seja morto pela saída de um shell não interativo), `shellNoWait` e `disconnect`. Usado pelo caminho TCP-ADB de modo dev e por `VdServerDeployer.deployDirect`.

### CarShell

Shell de nível superior da central: layout de streaming (espelhamento + barra de navegação) ou tela de lançamento (`ui/screen/CarShell.kt`). Extraído de `MainActivity` para que a Activity mantenha apenas suas responsabilidades de ciclo de vida (bind do serviço, encaminhamento de intent USB, modo imersivo, re-handshake de rotação) e este arquivo seja dono da árvore Compose e do roteamento de estado de tela.

- `showStreamingMode = appList.isNotEmpty() && (isConnected || state == CONNECTING)` — o ramo CONNECTING mantém o layout de streaming visível durante um redeploy de rotação no meio do streaming, para que o overlay de espera de vídeo cubra a janela em vez de piscar o `CarLaunchScreen`.
- `CarContentArea`: área de conteúdo compartilhada pelos layouts paisagem (Row) e retrato (Column) — renderiza `MirrorContent`, o overlay de "aguardando vídeo" quando o stream não está pronto, e `HomeContent` quando a tela atual é HOME. Os dois ramos de layout diferem apenas no posicionamento da barra de navegação.
- Diálogo de informações do app (`ui/screen/AppInfoDialog.kt`) exibido quando `appInfoData` não é nulo; layout separado deste arquivo (auditoria R3-SRP-19).
- `vdStackEmpty` coletado → muda para a tela HOME.

### MainActivity

Activity launcher do lado da central (`MainActivity.kt`). É dona apenas de responsabilidades de nível de processo:

- Bind / unbind do serviço e encaminhamento do intent de conexão USB (`ACTION_USB_DEVICE_ATTACHED`) para `CarConnectionService`.
- Modo imersivo (tela cheia sticky via `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` ao ganhar foco).
- Re-handshake de rotação: `onConfigurationChanged` empurra as novas dimensões do viewport para `CarConnectionService.onCarViewportChanged` para que o celular recrie o VD na nova orientação sem derrubar o serviço vinculado. O `configChanges` no manifest (`orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden`) permite que a Activity receba `onConfigurationChanged` em vez de ser recriada.
- Sem lock de `screenOrientation` — a tela da central pode ser um painel rotacionável.

A árvore Compose (streaming vs tela de lançamento, barra de navegação, diálogo de informações do app) mora no `CarShell`.

### CarLaunchScreen

Composable de tela cheia focado na conexão, exibido antes de a conexão com o celular ser estabelecida — sem barra de navegação, sem grade de apps (`ui/screen/CarLaunchScreen.kt`).

- Identidade visual do DiLink Auto (ícone, título, tagline)
- `ConnectionStatusCard` com ponto indicador colorido (verde=streaming, laranja=conectado/conectando, cinza=idle) e texto de status ao vivo. Contém:
  - Alternador de modo dev (ADB TCP em vez de USB)
  - Três configurações iniciais, cada uma com uma linha de chips predefinidos mais um campo manual (`ui/screen/CarLaunchSettings.kt`, compartilhado com as telas de ajustes do celular por meio de `SettingsFormat`): `startup_dpi` (0 = Auto → `VideoConfig.calculateOptimalDpi`; override em [120, 480] coagido por `VdDeployArgs.coerceDpiOverride` via `SettingsFormat.coerceDpi`), bitrate (predefinições 2 / 2.5 / 3 / 4 / 6 Mbps, `SettingsFormat.coerceBitrate`) e FPS (20 / 24 / 30 / 60, `SettingsFormat.coerceFps`, que cai dentro de `VideoConfig.MIN_FPS..MAX_FPS`).
  - DPI e bitrate são lidos no momento da construção do handshake, então uma mudança só vale na próxima conexão ou no re-handshake de rotação no meio do streaming. O FPS passa por `CarConnectionService.setStartupFps`, que também o aplica imediatamente ao `targetFps` ao vivo.
  - O campo manual é dono do seu texto bruto e só confirma em **Done** ou **perda de foco** (auditoria UX-02). Ele antes ecoava o valor coagido a cada tecla, então digitar "160" colapsava para 120 no primeiro dígito e armazenava silenciosamente o DPI errado; a semente é o número armazenado (`SettingsFormat.manualSeed`), nunca dígitos extraídos de um rótulo de display que pode mostrar "2.5M", e a entrada é filtrada para 3 dígitos ASCII. O chip selecionado combina seu container azul-claro com `onPrimary` (preto, 10.48:1) em vez de branco (2.00:1) (auditoria UX-03), o hint perde o alpha 0.7 (UX-05), e a linha de chips é um `FlowRow` para que uma tradução mais longa quebre linha em vez de transbordar o card.
- Instruções de "Como conectar": 4 passos numerados
- Entrada manual de IP (`ManualConnectBox`)
- Substituído pelo layout de modo streaming quando `appList` fica não vazio e o estado chega a CONNECTED/STREAMING

### ConnectionStatusScreen

Painel de status da conexão exibido dentro do `HomeContent` quando o link ainda não está em streaming (`ui/screen/ConnectionStatusScreen.kt`). Distinto do `CarLaunchScreen` (a tela cheia pré-streaming) — este composable compartilha o `ManualConnectBox` com ela. Persiste o último IP usado e pré-preenche com o gateway WiFi quando não existe IP salvo.

A consulta ao gateway em si é `adb/WifiGatewayProbe.kt` — um único lugar que resolve `WIFI_SERVICE` e sobrevive a um dispositivo em que ela retorna null (WiFi desligado, permissão não concedida, framework stubbado em teste), retornando null ou um fallback. Ela antes era duplicada literalmente no fallback de IP do celular do serviço e na caixa de conexão manual (DRY-7); é `internal` porque é código de framework Android, não parte da superfície do protocolo. A persistência da conexão manual mora em `data/ManualConnectState`.

### NowPlayingBar

Barra inferior que mostra a faixa de mídia atual e os controles de transporte (`ui/screen/NowPlayingBar.kt`). Só é composta dentro do `HomeContent` quando `MediaMetadata` está presente. Alvos de toque grandes (48dp para pular, 56dp para play/pause) para uso na central.

### PersistentNavBar / PersistentBottomNavBar

Barras de navegação em paisagem (rail esquerdo, `PersistentNavBar`) e em retrato (barra inferior, `PersistentBottomNavBar`) — **exibidas somente no modo streaming**. Ambas renderizam o mesmo conjunto enxuto de ações: **Eject (desconectar) / Home / Back** — apenas três botões. O botão de notificações, o rail de apps recentes, o relógio e as informações de rede foram removidos. O conjunto de ações (`NavActionButtons`) e a geometria (`rememberNavBarSize`) são definidos uma vez cada e compartilhados pelos dois layouts, então não podem divergir conforme o painel gira; a barra inferior renderiza os três em ordem inversa (Eject / Home / Back) de modo que girar o dispositivo mantenha cada botão do mesmo lado físico. Barra de 76dp (`CarViewport.NAV_BAR_TARGET_DP = 76f`), ícones de 40dp com largura mínima de toque de 48dp e o rótulo exposto apenas como `contentDescription` — nenhum composable de texto. A largura é `CarViewport.navBarWidthPx`, que arredonda o offset de 76dp **para cima** para que o viewport restante (`screenWidthPx - navBarPx`) fique par para o encoder H.264.

### HomeScreen / HomeContent / AppGrid / AppTile

Os composables ativos que antes ficavam em `LauncherScreen.kt` agora moram em `HomeScreen.kt`.

- `HomeContent`: área de conteúdo principal quando o modo streaming está ativo. Mostra `AppGrid` em streaming com apps, um estado vazio explicativo em streaming com allowlist vazia, ou `ConnectionStatus` caso contrário. Renderiza `NowPlayingBar` quando há metadados de mídia.
- `AppGrid`: ícones de app de 64dp em colunas fixas de grade calculadas dinamicamente (3-12: `max(3, maxWidth / 100.dp)`), com `key` e `contentType` estáveis para que os tiles mantenham sua identidade entre atualizações da lista. Campo de busca com `imePadding()`. Ordenação alfabética (chave de ordenação em minúsculas calculada uma vez por app), apps fixados primeiro. `windowSoftInputMode="adjustResize"` no manifest.
- Desinstalar pelo menu do tile agora passa primeiro por um `AlertDialog` de confirmação — a requisição só sai para o celular depois de aceita (auditoria UX-01). Uma busca sem resultados mostra "no apps match" mais uma ação de limpar busca em vez de uma grade em branco que parece falha de carregamento (UX-09), e uma legenda informa que o gerenciamento dos tiles fica atrás de um toque longo, algo sobre o qual uma tela de toque de central não dá nenhuma pista (UX-08).
- `AppTileData` (`@Immutable`): wrapper estável para as entradas do `AppTile`. `AppInfo` carrega um `ByteArray` instável (`iconPng`), o que tornava o `AppTile` não pulável (non-skippable) e forçava recomposição da grade inteira em qualquer reatribuição de `appList`. Este wrapper exclui o byte array — o `AppTile` lê o ícone preparado do cache pelo packageName, então a identidade do wrapper é a única entrada que importa para a recomposição.
- `AppTile`: um toque longo abre um menu de contexto com **Pin to Top / Unpin**, **Uninstall** e **App Info**. O estado de pin persiste por `data/PinnedAppsRepository` (`dilinkauto_pinned`); a grade mantém apenas uma cópia local para conduzir a recomposição, o repositório é dono da persistência. A desinstalação envia `APP_UNINSTALL` pelo canal de controle; o celular processa o diálogo do sistema e envia `APP_UNINSTALLED` para atualizar a lista de apps da central. `AppInfoDataMessage` exibe as informações do app em um diálogo do lado da central (`ui/screen/AppInfoDialog.kt`).

### MirrorScreen

Conteúdo do espelhamento — `SurfaceView` para vídeo + encaminhamento de toque (`ui/screen/MirrorScreen.kt`). SurfaceView (não TextureView) para que a saída do decodificador passe por um overlay de hardware em vez de um passe extra de composição GL por frame. No Adreno 505 o custo de composição do TextureView (~2-5ms/frame em 1280x800) é uma fatia significativa do orçamento de 50ms a 20fps; o SurfaceView o contorna por completo.

Tradeoff: o SurfaceView destrói sua surface quando a view fica INVISIBLE, enquanto o TextureView a mantinha viva. A navegação HOME<->APP alterna `visible`, então `surfaceCreated`/`surfaceDestroyed` disparam a cada troca. O decodificador NÃO é parado em `surfaceDestroyed` — ele continua rodando, e `VideoDecoder.invalidateSurface()` desliga o flag de render para que os frames não sejam entregues a uma surface destruída. `surfaceCreated` chama `switchSurface` (`setOutputSurface`) para reanexar, restaurando a renderização com zero perda de keyframe. `handleDisconnect`/`shutdown` são donos do stop final do decodificador.

Os dois corpos de `SurfaceHolder.Callback` são deliberadamente finos: eles encaminham para `CarConnectionService.onMirrorSurfaceCreated/Destroyed`, que repassa o trabalho a um executor de thread única do lado do serviço para que o create/configure do `MediaCodec` nunca rode na thread principal que os entrega (auditoria S-M3).

A barra de navegação persistente é irmã (não sobreposta) deste composable nos layouts paisagem e retrato (`CarShell`), então a ordem z padrão do SurfaceView — surface abaixo da hierarquia de janelas — renderiza a barra de navegação corretamente acima do vídeo.

Encaminhamento de toque: `ACTION_DOWN`/`ACTION_POINTER_DOWN`/`ACTION_UP`/`ACTION_POINTER_UP` enviam `TouchEvent`s de um único ponteiro; `ACTION_MOVE` agrupa todos os ponteiros ativos em um único `TouchMoveBatch` (reduz syscalls no multi-toque); `ACTION_CANCEL` libera todos os ponteiros para evitar dedos fantasmas. As coordenadas são normalizadas para `[0,1]` dividindo pelas dimensões da view.

### NavBarComponents

Composables individuais dos widgets da barra de navegação (`ui/nav/NavBarComponents.kt`). `NavActionButton` (ícone de 40dp, sem texto de rótulo — o rótulo é exposto apenas como `contentDescription`) é o único widget restante: ele carrega um mínimo de 48dp em `widthIn` para que o alvo de toque não seja uma fatia de 40x60dp em um controle que é tocado às cegas enquanto se dirige (auditoria UX-11). `ClockDisplay`, `NetworkInfo`, `RecentAppIcon` e `NotificationsButton` foram removidos junto com os recursos que eles sustentavam.

### CarTheme

Esquema de cores escuro do Material3 (`CarDark`) com cores de tile de app por categoria: Navegação (verde), Música (rosa), Comunicação (azul), Outros (cinza). As cores vêm dos tokens `UiPalette` do protocol-core (`onSurfaceVariant = 0xFFAAAAAA` substitui os cinzas literais espalhados; o vermelho de erro consolidado em um único `0xFFEF5350`), e os tokens semânticos de estado (`SuccessColor` / `WarningColor` / `InfoColor` / `PinnedColor` / `NavBarBackgroundColor`) substituem os hexadecimais crus que as telas codificavam à mão em 20+ lugares. `CarTypography` (auditoria UX-07) é uma escala definida à mão — 12 / 14 / 16 / 18 / 20 / 22 / 24 / 32sp, nada abaixo de 12sp, todo `fontSize` solto nas telas da central migrou para ela — e agora define os slots com que as telas contavam implicitamente (`titleSmall`, `bodySmall`, `headlineSmall`, `labelMedium`), que antes caíam nos padrões do Material.

### Informações do display da central

Testado na BYD DiLink 4.0 (Qin PLUS DM-i 2023 Champion 55KM Leading trim — baixa especificação):
- SoC: Snapdragon 439 (8x Cortex-A53, Adreno 505), 4GB RAM, 16GB eMMC
- Tela: 1280x800 @ 240dpi, WiFi somente 2.4GHz, Android 9 / API 28
- Decodificação H.264 por hardware limitada a 1080p

## Testes unitários

`app-server/src/test/` — JUnit 4 sem Robolectric nem framework de mocking: o módulo define `unitTests.isReturnDefaultValues = true` e escreve seus fakes à mão (`ContextWrapper(null)`, relógios injetados). 37 casos em 9 arquivos; `:app-server:testDebugUnitTest` roda verde offline.

- `service/CarConnectionServicePolicyTest` (3) — `reconnectBackoffMs` dobra 500 → 1000 → … → 8000 e segura o teto em uma varredura com shift limitado, além da aritmética de fronteira de `iconBudgetAccepts` contra a constante real de 64MB `MAX_APP_LIST_ICON_BYTES` (6d25390: as duas fórmulas eram inline e não testadas, e os testes antes rederivavam a aritmética em vez de chamá-la).
- `service/CarConnectionValidationTest` (8) — limitação das dimensões de viewport do peer (incluindo limites de `Int`), aritmética do orçamento de ícones e o orçamento de reconstruções de tela preta por sessão.
- `service/CarViewportTest` (6) — matemática de viewport alinhada a par e o offset da barra de navegação que mantém o viewport do encoder par.
- `decoder/VideoDecoderFpsTest` (5) — `coerceFps` puxa valores 0 / negativos / absurdos para `VideoConfig.MIN_FPS..MAX_FPS` e o divisor permanece seguro.
- `CarCrashReportTest` (7) — o contrato de formato do relatório de crash: cabeçalho, formato de timestamp US, nome da thread, marcador de stack-frame, `── Process State ──`, cadeia de causas, seção `Suppressed:`, determinismo do corpo. O próprio CarCrashHandler deliberadamente não tem teste unitário (seu caminho `uncaughtException` mata a JVM).
- `CarCrashHandlerArchiveTest` (5) — poda do arquivo e consumo exatamente uma vez.
- `adb/WifiGatewayProbeTest` (1) — a consulta ao gateway cai para `""` / um valor fornecido quando `WIFI_SERVICE` não pode ser resolvida.
- `UsbPendingIntentTest` (1) / `MainActivityAdversarialTest` (1) — os flags do `PendingIntent` de permissão e o ciclo de vida do cache de intent USB (entrega exatamente uma vez no bind, sem reentrega em uma segunda conexão, filtragem de action).

## Desempenho em aparelho de baixa especificação do DiLink 4.0

Nove correções miram a central multimídia Snapdragon 439 / Adreno 505 / 4GB RAM:

1. Dimensões de encode limitadas a 1920x1080 (desacopladas das dimensões do VD via args `EW EH`; o VD permanece ampliado para preservar a correção do crop do IME, o encode usa as dimensões reportadas pela central limitadas a 1080p). Sem isso, celulares acima de 1080p disparam decode por software no 439 → fps de um dígito.
2. Bitrate 8 Mbps → 4 Mbps; fallback adaptativo apertado (piso 1.5 Mbps, janela de recuperação de 2s, passos de 0.5 Mbps — era 2 Mbps / 5s / 1 Mbps).
3. Framerate 30 → 20 fps (`VideoConfig.TARGET_FPS = 20`). Orçamento por frame 33ms → 50ms, -33% de WiFi/GPU/alocação. (Era 24 fps antes da issue #71.)
4. MirrorScreen TextureView → SurfaceView (contorna a composição GL por frame do Adreno 505; o gate `outputSurfaceValid` impede renderizar em uma surface destruída ao longo do esconder/mostrar da navegação).
5. Prioridades de thread: `THREAD_PRIORITY_URGENT_DISPLAY` nas threads de encode/decode/drain de socket; `THREAD_PRIORITY_BACKGROUND` no LifeWriter. O A53 não tem núcleos big.
6. `carLogEnabled` inicia como `BuildConfig.DEBUG` (era true); `ConcurrentLinkedQueue.size()` (O(n)) → `AtomicInteger`.
7. `AppIconCache.clear()` na desconexão (libera ~5-9MB de heap + PNGs no eMMC); `prepareAll` paralelizado com `Semaphore(4)`, intermediários reciclados.
8. `Debug.getPss()` removido da inicialização (chamada de binder obsoleta, 100-500ms no A53).
9. Wrapper `@Immutable AppTileData` para que a grade de ícones pule a recomposição na reatribuição de `appList`.

## Compatibilidade com a central no API 28

- `MediaCodecInfo.isHardwareAccelerated()` (API 29+) lançava `NoSuchMethodError` na central multimídia BYD com API 28, matando o processo. Correção: removido o seletor especulativo de decodificador de hardware; `VideoDecoder.start()` chama `MediaCodec.createDecoderByType` diretamente.
- `minSdk` do app-server 24 → 26; `minSdk` do protocol 24 → 26 (re-arma o gate de lint NewApi).
- `am display move-stack` (API 29+) condicionado a `SDK_INT >= 29` com log em níveis anteriores (antes mascarava silenciosamente a falha de shell como sucesso).
- O fallback de shell `cmd display power-reset` / `power-off` é API 29+ (não existe subcomando `power-on`); na API 26-28 uma falha de reflexão do DisplayControl significa que o painel físico não é restaurado — agora registrado em log, antes era silencioso.

## Correção da tela preta na rotação

`onCarViewportChanged` coloca o estado em CONNECTING e derruba o VD para redeploy, mas o gate do layout de streaming usava `isConnected` (false durante os ~2s de redeploy) → o `CarShell` virava para `CarLaunchScreen` → SurfaceView removida → a nova surface não conseguia reiniciar o decodificador. O gate agora também aceita `state == CONNECTING && appList.isNotEmpty()`. Veja `CarShell.showStreamingMode`.

O mesmo fato de montagem do layout foi a raiz da segunda metade desse bug (35d7821, S-03): como o layout agora permanece montado, `surfaceCreated` nunca dispara de novo durante um re-handshake, então qualquer coisa que pare o decodificador no caminho de recuperação deixa a tela preta pelo resto da sessão. `rehandshakeOnExistingControl` portanto derruba vídeo/entrada e re-arma o detector de tela preta, mas deixa o codec rodando, e o `CarShell` cobre a janela com o overlay de "aguardando vídeo".

## Estrutura dos módulos

```
app-server/
├── service/        CarConnectionService, HandshakeFactory, VdServerDeployer, CarLogWriter, CarTouchSender, CarPrefs, CarViewport
├── decoder/        VideoDecoder
├── adb/            RemoteAdbController, WifiGatewayProbe
├── data/           ManualConnectState, PinnedAppsRepository
├── ui/screen/      CarShell, CarLaunchScreen, CarLaunchSettings, HomeScreen, MirrorScreen, ConnectionStatusScreen, NowPlayingBar, AppInfoDialog
├── ui/nav/         PersistentNavBar, NavBarComponents
├── ui/theme/       CarTheme
├── AppIconCache.kt
├── ServerApp.kt
├── CarCrashHandler.kt
├── CarCrashReport.kt
└── MainActivity.kt
```

minSdk 26, compileSdk 34, Kotlin 1.9.22, Android Gradle 8.2.2, JDK 17.

## Dependências

- Jetpack Compose (BOM 2023.10.01) + Material 3 + material-icons-extended
- Módulo protocol (compartilhado com o app do celular): uma camada fina do lado Android (`UsbAdbConnection`, `AdbProtocol`, `TcpAdbConnection`, `DeviceInfo`, `Discovery`) que depende de `:protocol-core` via `api`, então a superfície pura do protocolo fica visível por meio dele — `VideoConfig`, `VdDeploy`, `VdDeployArgs`, `VdDeploySequence`, `AppPrefs`, `WifiGatewayIp`, `DimAlign`, `UiPalette`
- De protocol-core (compartilhado com o receptor desktop e testado lá sem Android): `BlackScreenDetector` (política de tela preta sustentada), `H264NalParser` (detecção de IDR), `SettingsFormat` (coerção de ajustes + semente de entrada manual), `AsyncLogQueue` (fila de log limitada), `LogLine`
- `TcpAdbConnection` (em protocol/) para o fallback de ADB TCP

Veja [architecture.md](./architecture.md) para o diagrama entre módulos, [protocol.md](./protocol.md) para o wire format e a atribuição de portas, e [client.md](./client.md) para o orquestrador do lado do celular. Baixe em [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).
