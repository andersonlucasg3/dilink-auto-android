# Especificação do protocolo

## Visão geral

O DiLink-Auto usa **4 conexões TCP dedicadas** entre o celular e a central multimídia (controle/dados, vídeo, entrada, áudio); entre o app do celular e o servidor VD há ainda uma conexão interna de ciclo de vida:

| Conexão | Porta | Direção | Conteúdo |
|------------|------|-----------|---------|
| **Controle + Dados** | 9637 | Bidirectional | Handshake, heartbeat, comandos de app, lista de apps, logs da central, metadados de mídia |
| **Vídeo** | 9638 | VD server -> Car | Apenas CONFIG + FRAME H.264 |
| **Entrada** | 9639 | Car -> VD server | Apenas eventos de toque |
| **Áudio** | 9640 | VD server -> Car | AAC-LC CONFIG + FRAME; bind após negociação bidirecional, transporte independente |
| **Ciclo de vida** | 19647 | VD server -> Phone (localhost) | Sinais de display pronto / pilha vazia / parada |
| **ADB TCP** | 5555 | Car -> Phone (or Phone -> Car for dadb) | ADB persistente de socket único para comandos de shell e instalação do APK da central |

A 9640 **não reutiliza** a 9638 de propósito: o `VideoFrameSender` é o lado de escrita de thread única com fila limitada, e um IDR de 1 MB enfila o frame de áudio de 341 B atrás de si na cabeça da fila. O áudio precisa de uma conexão independente, e uma falha na conexão de áudio não pode derrubar a sessão de vídeo inteira.

O servidor VD faz bind das portas de vídeo e de entrada diretamente em `0.0.0.0:9638` e `0.0.0.0:9639`, então frames de vídeo e eventos de toque fluem direto entre o servidor VD e a central, sem relay do lado do celular. O app do celular não está no caminho de dados de vídeo nem de entrada. Um site de deploy que conhece o próprio endereço de saída pode passar um IP de receptor no slot final `CAR_HOST` do argv (`VdDeployArgs.CAR_HOST_ANY` = `-` mantém o comportamento de aceitar qualquer peer), o que fixa esses dois accepts ao peer que o motor realmente atende.

A conexão de controle (9637) é estabelecida primeiro e carrega o handshake, o heartbeat, os comandos de app e o canal DATA (lista de apps, logs da central, metadados de mídia). Após o handshake o celular faz o deploy do servidor VD, que faz bind em 9638/9639 e se reconecta ao celular em `localhost:19647`. Quando o servidor VD sinaliza display pronto no canal de ciclo de vida, o celular envia `VD_PORTS_BOUND` para a central na conexão de controle; só então a central abre as conexões de vídeo (9638) e de entrada (9639) com o servidor VD. O heartbeat/watchdog roda apenas na conexão de controle; vídeo e entrada não têm custo de heartbeat, mas toda conexão ainda herda o prazo de travamento no meio do frame do leitor (ver [travamentos do NioReader](#formato-do-protocolo)). Qualquer conexão que morre cascateia para o encerramento completo da sessão.

O canal de ciclo de vida em `localhost:19647` está documentado em [client.md](./client.md). Ele usa um conjunto fixo e pequeno de mensagens (`MSG_DISPLAY_READY`, `MSG_STACK_EMPTY`, `CMD_STOP`) e é o único canal que o servidor VD inicia em sentido inverso para o celular.

## Formato do protocolo

```
+-----------------+------------+--------------+-----------------+
| Frame Length     | Channel ID | Message Type | Payload          |
| (4 bytes)        | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian uint32|            |              |                  |
+-----------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Valor = `2 + payload_size` (não inclui o próprio campo de comprimento de 4 bytes). Um comprimento declarado abaixo de `2` é rejeitado (`Frame too small`).
- **Payload máximo**: 16 MB (`FrameCodec.MAX_PAYLOAD_SIZE`). O valor antigo de 128 MB deixava um header de 7 bytes forçar ~256 MB de alocação (o `ByteArray` do payload mais o buffer de crescimento sob demanda do `NioReader`) no celular e no vd-server rodando como shell UID; 16 MB deixam 8x de folga sobre um keyframe 1080p e transformam o mesmo header hostil em um evento de 32 MB que os dois lados sobrevivem.
- **Overhead do header**: 6 bytes por frame (`FrameCodec.HEADER_SIZE`).
- **Codificação do header**: big-endian. `FrameCodec.encodeHeaderInto` é a definição única do layout de 6 bytes, compartilhada por `writeFrame`, `writeFrameToChannel` e pela corrotina de escrita da `Connection`; um buffer de header de 6 bytes em `ThreadLocal` evita alocação por frame.
- **Caminho de escrita**: uma única corrotina de escrita dedicada drena um `Channel<Frame>` limitado de tamanho 64 com `BufferOverflow.SUSPEND`, então um consumidor TCP lento aplica backpressure aos leitores em vez de crescer sem limite. Escritas gather (`channel.write(ByteBuffer[])`) combinam header + payload em uma só syscall. A ordem de enfileiramento é fixada por um `sendDispatcher` de thread única mais um `sendMutex`: sem o lock, `limitedParallelism(1)` serializa o despacho mas não a suspensão, então uma fila cheia reordenava o toque DOWN/MOVE/UP exatamente quando o link estava congestionado.
- **Caminho de leitura**: `FrameCodec.readFrame(NioReader)` (suspend) para chamadores de corrotina (celular, central); `FrameCodec.readFrame(InputStream)` e `FrameCodec.readFrameBlocking(NioReader)` para chamadores bloqueantes (servidor VD). As duas variantes bloqueantes compartilham um único esqueleto `readFrameCore`, e todo caminho valida o tamanho declarado do payload pelo mesmo `validatePayloadSize` antes de alocar qualquer coisa.
- **Teto do buffer de leitura**: `NioReader.MAX_READ_UNIT` = `HEADER_SIZE + MAX_PAYLOAD_SIZE` é um teto rígido para um único crescimento; `maybeShrink()` em cada fronteira de frame devolve um buffer superdimensionado para `DEFAULT_CAPACITY` (128 KB), para que um frame grande não prenda memória durante a sessão.
- **Travamentos do NioReader**: `readStallTimeoutMs` (10 s em toda `Connection`) falha uma leitura que trava *no meio do frame* — bytes parciais já no buffer e depois silêncio (TCP half-open, peer morto). Uma espera ociosa em uma fronteira de frame limpa (link de toque sem entrada do usuário) deliberadamente não arma nada e nunca expira.
- **Timeout de escrita**: `FrameCodec.writeAll` lança exceção se não houver progresso por 10 segundos (buffer de envio cheio / peer não lendo). Era 5 s; aumentado na issue #70 — o prazo só reinicia em progresso real de bytes, então 5 s sem progresso em um buffer de 512 KB significavam que um breve soluço do WiFi matava a sessão. A corrotina de escrita da `Connection` não tem prazo próprio — o watchdog é dono das conexões mortas.
- **Fechamento gracioso**: um EOF limpo do peer (`reader: EOF`) faz flush dos frames já enfileirados por `FLUSH_GRACE_MS` (500 ms) antes de o writer ser cancelado e o socket fechado, então um `DISCONNECT` seguido de FIN ainda entrega as respostas enfileiradas.
- **Prazo de conexão**: `Connection.connect` falha após `CONNECT_TIMEOUT_MS` (10 s) em vez de girar `finishConnect()` para sempre em um SYN engolido (black-holed).
- **Verificação de versão**: `requireSupportedProtocolVersion` rejeita qualquer `protocolVersion` != `PROTOCOL_VERSION` com `ProtocolDecodeException`. O campo costumava ser transportado e nunca validado, o que decodificava os campos finais em offsets errados.
- **Log de frames grandes**: frames acima de 8 MB registram um aviso por stream (um keyframe 1080p tem ~1 MB). O limiar anterior de 100 KB registrava uma vez por GOP nos dois lados.

## Canais

| ID | Nome | Conexão | Direção | Finalidade |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Controle (9637) | Bidirectional | Handshake, heartbeat, comandos de app, sinais de ciclo de vida do VD |
| 1 | VIDEO | Vídeo (9638) | VD server -> Car | Frames de vídeo codificados em H.264 |
| 2 | AUDIO | Áudio (9640) | VD server -> Car | AAC-LC CONFIG + FRAME; habilitado após negociação bidirecional (isAudioNegotiated) |
| 3 | DATA | Controle (9637) | Bidirectional | Lista de apps, dados de info de app, logs da central, metadados de mídia, toggle de log |
| 4 | INPUT | Entrada (9639) | Car -> VD server | Eventos de toque (MOVE em lote para multi-touch) |

O canal DATA faz multiplexação no mesmo socket TCP do CONTROL. Os frames de VIDEO, INPUT e CONTROL são despachados inline na thread do leitor por baixa latência e por ordem de chegada (toque DOWN/MOVE/UP e comandos de navegação são sensíveis a ordem); só DATA é despachado para um escopo de corrotina, porque a decodificação da lista de apps é o único handler pesado que não pode parar o leitor de drenar o TCP. Um listener de frame que lança exceção (um decodificador levantando `ProtocolDecodeException` ou `BufferUnderflowException` em um payload malformado do peer) falha a conexão com um motivo registrado em vez de matar a corrotina do leitor — não existe `CoroutineExceptionHandler` em lugar nenhum deste repositório.

## Canal de controle (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+--------+
| protocolVersion      | int32  |
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| screenWidth          | int32  |  car display width in pixels (after nav bar)
| screenHeight         | int32  |  car display height in pixels
| supportedFeatures    | int32  |  **接收端**想要的能力位掩码；默认仅 FEATURE_VIDEO
| displayMode          | byte   |  0=MIRROR, 1=VIRTUAL (default)
| screenDpi            | int32  |  car display density (e.g. 160)
| appVersionCode       | int32  |  car app version code (informational)
| targetFps            | int32  |  car's requested FPS (e.g. 24)
| appVersionName len   | int16  |  version name string length
| appVersionName       | UTF-8  |  car app version name
| dpiOverride          | int32  |  trailing field, default 0
| bitrate              | int32  |  trailing field, target bitrate in bps, default 0
| vdAreaTier           | int32  |  trailing field, VD 合成面积档位（0=AUTO / 1=COMPAT_2560），default 0
+----------------------+--------+
```

Os três campos finais são anexados depois de `appVersionName` e decodificam como `0` quando ausentes, então peers antigos interoperam: um `dpiOverride` ausente significa autocalibração, um `bitrate` ausente significa o padrão de 4 Mbps, um `vdAreaTier` ausente significa o tier de área padrão ([VdAreaTier.AUTO]).

`vdAreaTier` (issue #153 PB) é um tier **de nível de sessão**: a central envia `0` ou `1` no handshake, e o celular o entrega a `VdDimensions.compute` na criação do VD desta sessão para decidir o limite de ampliação anti-crop (tier padrão `max(carW*1.6, 1600)`, tier de compatibilidade 2560). Ele **não** é uma mensagem de sincronização de tamanho em tempo de execução: a troca de tier depende da próxima conexão / de um novo handshake por rotação, e não há `resize` no meio da sessão. Celulares antigos ignoram os 4 B finais; quando a central antiga não traz o campo, o celular decodifica `0` = tier padrão. Note que o caminho de deploy por ADB da central (`VdServerDeployer`) já cria o VD com o viewport nativo da central e não consome `HandshakeResponse.vdWidth/vdHeight`, portanto o tier é um no-op para ele; os consumidores reais são o caminho Shizuku e o `AdbDeploy` do desktop.

`supportedFeatures` por padrão só liga `FEATURE_VIDEO`. `FEATURE_AUDIO` precisa ser habilitado explicitamente por `HandshakeRequest.builder().supportedFeatures(...)`, e significa "o receptor quer tocar e consegue decodificar áudio", independentemente de o celular conseguir capturar ou não. Antes do S1 o valor padrão era `FEATURE_VIDEO or FEATURE_AUDIO`, o que fazia os dois pontos de construção (`buildHandshakeRequest` da central, `HandshakeFactory.build` do desktop) anunciarem falsamente um canal de áudio que simplesmente não existia (issue #67). A capacidade do próprio celular volta pelo campo final `supportedFeatures` do `HandshakeResponse`; `isAudioNegotiated(request, response)` do protocol-core é o único critério, exigindo `accepted == true` **e** `FEATURE_AUDIO` ligado nos dois lados, e só então o bind da 9640 é permitido. A base para desabilitar o switch de áudio no receptor precisa ser a response do celular, nunca o `SDK_INT` do próprio receptor: o piso de captura do Android 11 (SDK 30) restringe o celular, não a central (`minSdk` do `app-server` é 26).

`dpiOverride` é um campo final. `0` significa autocalibração via `VideoConfig.calculateOptimalDpi` (o limite seguro para apps em retrato). Um valor não zero em `[120, 480]` ignora o limite e é usado literalmente, corrigindo o "UI muito pequena" de apps em paisagem ao custo de espremer apps somente-retrato. A checagem de bytes restantes do decodificador lê `0` quando o campo está ausente, então centrais antigas que não o enviam decodificam como auto. A central normaliza a entrada por `VdDeployArgs.coerceDpiOverride` para que celular e central concordem sobre o que conta como override válido.

`bitrate` é o bitrate de encoder pedido pelo receptor em bps (ex.: 2_000_000, 4_000_000), `0` = `VideoConfig.DEFAULT_BITRATE` (4 Mbps). Ele viaja por `VdDeployArgs.format` até o argv do VD, onde é validado contra a faixa de deploy deliberadamente mais ampla `VideoConfig.INPUT_MIN_BITRATE..INPUT_MAX_BITRATE` (0,5-20 Mbps) e cai para o padrão quando fora da faixa. Os dois emissores (`buildHandshakeRequest` da central, `HandshakeFactory.build` do desktop) montam a mensagem via `HandshakeRequest.builder()`; o builder não alinha dimensões, porque a direção do alinhamento é específica de cada emissor e é load-bearing (o desktop arredonda para baixo até o par, a central alarga a nav bar em um pixel para o viewport cair em par).

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+--------+
| protocolVersion      | int32  |
| accepted             | byte   |  1=accepted, 0=rejected
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| displayWidth         | int32  |  VD width (matches car request)
| displayHeight        | int32  |  VD height (matches car request)
| virtualDisplayId     | int32  |  -1 (set by VD server, not phone)
| adbPort              | int32  |  5555 (phone's ADB TCP port)
| vdServerJarPath len  | int16  |
| vdServerJarPath      | UTF-8  |  path to deployed VD JAR (e.g. /sdcard/DiLinkAuto/vd-server.jar)
| connectionMethod     | byte   |  0=USB_ADB, 1=WIFI_ADB, 2=SHIZUKU
| vdDpi                | int32  |  DPI the VD will use (auto or override)
| vdWidth              | int32  |  trailing field, recommended VD width, 0 = not provided
| vdHeight             | int32  |  trailing field, recommended VD height, 0 = not provided
| supportedFeatures    | int32  |  尾字段，**手机侧**采集能力位掩码，0 = 不支持音频
+----------------------+--------+
```

`vdWidth` / `vdHeight` são campos finais que carregam o tamanho de VirtualDisplay recomendado pelo lado do celular — a saída de `VdDimensions.compute`, que alinha o viewport da central em par e então o escala até a borda longa **física real** do celular. Um IME de ROM chinesa fixa sua largura na largura física em pixels do celular independentemente da densidade do VD, então um VD do tamanho do viewport corta o teclado qualquer que seja o DPI negociado (uma tela de 1280 px com um teclado de 1368 px decepa a borda direita).

`0` significa que um celular antigo não enviou o campo. Sites de deploy sem acesso de shell ao celular — o caminho ADB do desktop — DEVEM usar essas dimensões para o VirtualDisplay quando forem `> 0`; o viewport do próprio desktop é o tamanho do *encoder*, não o tamanho do VD. As métricas do lado do celular precisam vir de métricas físicas reais de display (`WindowManager.maximumWindowMetrics` / `Display.getRealMetrics`), nunca de `resources.displayMetrics`, que reporta valores escalados por compatibilidade no modo screen-compat.

`supportedFeatures` (S1) é a metade **celular / lado de captura** da troca de capacidades de áudio, anexada depois de `vdHeight`, com semântica diferente da do campo de mesmo nome no request: o request diz o que o receptor quer usar, aqui se diz o que o celular pode oferecer. Celulares antigos, sem esses 4 bytes finais, decodificam como `0`, o que é uma direção de fallback segura — `isAudioNegotiated` retorna false e ninguém vai se conectar a uma porta que nunca foi escutada. O celular só liga `FEATURE_AUDIO` aqui quando o próprio SDK e a sonda de captura permitem `REMOTE_SUBMIX`.

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- somente na conexão de controle

Payload vazio. Enviado a cada 3 segundos na conexão de controle. Se nenhum frame for recebido em 10 segundos, a conexão é considerada morta (timeout do watchdog). Conexões de vídeo e de entrada não têm heartbeat. O leitor enfileira `HEARTBEAT_ACK` inline (sem corrotina separada), para que os acks de heartbeat não passem fome por causa de uma fila de escrita ocupada.

Os intervalos são injetáveis: `Connection.start(enableHeartbeat, heartbeatIntervalMs, heartbeatTimeoutMs)` usa por padrão `HEARTBEAT_INTERVAL_MS` (3000) e `HEARTBEAT_TIMEOUT_MS` (10000), então o ajuste é testável sem socket. O watchdog dispara a cada `heartbeatIntervalMs` e derruba a conexão quando `now - lastFrameReceivedAt > heartbeatTimeoutMs`. As duas jobs são canceladas por `disconnect()` e só são iniciadas quando `enableHeartbeat` é true; o prazo separado de travamento no meio do frame do leitor (`readStallTimeoutMs`, também 10 s) cobre as conexões que não têm watchdog nenhum.

### DISCONNECT (0x05) -- Bidirectional

Payload vazio. Encerramento gracioso.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+--------+
| packageName          | UTF-8  |  raw bytes, no length prefix
+----------------------+--------+
```

O celular repassa ao servidor VD, que executa `am start --display <id> -n <component>` (sem `--activity-clear-task` -- apps existentes são retomados).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Payload vazio. O celular repassa ao servidor VD. GO_BACK envia `input -d <id> keyevent 4` e então verifica se a pilha está vazia. GO_HOME é tratado pelo launcher da central (o celular não o repassa ao servidor VD).

### APP_STARTED (0x13) -- Phone -> Car

Mesmo formato do LAUNCH_APP. Definido para confirmação; o handler do lado da central é um no-op.

### APP_STOPPED (0x14) -- Phone -> Car

Definido em `MessageType.kt`. Sem referências de envio/recepção fora da definição da constante; mantido por completude do protocolo.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Payload vazio. Enviado após o GO_BACK quando o servidor VD detecta que não restam tarefas de app na tela virtual (via `dumpsys activity activities`). A central usa isso para passar da visão espelhada para a tela inicial.

### APP_INFO (0x17) -- Car -> Phone

Payload: nome de pacote em UTF-8. A central pede ao celular que abra a tela de informações/configurações do sistema do app para o pacote informado.

### APP_UNINSTALL (0x1B) -- Car -> Phone

Payload: nome de pacote em UTF-8. A central pede ao celular que desinstale o pacote informado. O celular trata o diálogo de desinstalação do sistema e devolve `APP_UNINSTALLED` pelo canal de dados quando terminar.

### GO_RECENT (0x1F) -- Car -> Phone

Payload vazio. Alterna a tela virtual rapidamente para a *outra* tarefa usada mais recentemente (a semântica de "tocar duas vezes em recentes"). Deliberadamente NÃO é `input -d <id> keyevent 187` — o recents do sistema do AOSP fixa o display padrão, então um APP_SWITCH injetado só alterna o recents da tela *física* e nunca o do VD, e não existe interface de shell de recents por display. O vd-server em vez disso analisa `dumpsys activity recents` (pulando a tarefa atual no topo do VD, o app de controle, o launcher e o systemui) e então executa `am start --display <id> --task <taskId> -n <component>`. Totalmente assíncrono — nunca bloqueia o leitor de toque.

### VD_SERVER_READY (0x20)

Definido em `MessageType.kt` como um sinal Car -> Phone, mas sem uso no código atual (nenhuma referência de envio ou recepção). A mensagem `VD_PORTS_BOUND` (0x31) é o sinal ativo de prontidão do VD. Documentado aqui para evitar confusão ao ler a tabela de constantes.

### VD_PORTS_BOUND (0x31) -- Phone -> Car

Payload vazio. Enviado após o servidor VD fazer bind das portas de vídeo (9638) e de entrada (9639) e sinalizar display pronto no canal de ciclo de vida. A central espera essa mensagem antes de chamar `connectVideoAndInput(phoneHost)`, que abre as conexões TCP de vídeo e de entrada diretamente no servidor VD. Esse gate impede a central de se conectar a portas que o servidor VD ainda não fez bind, o que era uma corrida em versões anteriores.

### SET_DISPLAY_POWER (0x32) -- Car/PC -> Phone (canal de controle)

Payload de 1 byte: primeiro byte não zero = tela ligada, `0` ou payload vazio = tela desligada (um payload vazio significa desligada de propósito, para que um peer que esqueça o campo não acenda o painel por acidente). Roteado pelo `handleCarCommand` do vd-server para `DisplayPowerController.powerOffPanel()` (off) ou `restorePanel()` (on) — o mesmo caminho do `GO_HOME`, então o app-client não participa. O vd-server desliga o painel físico do celular por padrão assim que detém vídeo + entrada, e o restaura no disconnect; esta mensagem entrega ao usuário o toggle manual no meio da sessão.

## Canal de vídeo (0x01)

### CONFIG (0x01) -- VD server -> Car

Unidades NAL SPS/PPS H.264 com start codes. Enviado uma vez na inicialização do encoder.

### FRAME (0x02) -- VD server -> Car

Unidades NAL H.264 representando um frame de vídeo.

**Parâmetros de codificação** (definidos pelo servidor VD `PipelineServer`):
- Codec: H.264/AVC
- Perfil: Main
- Resolução: dimensões de codificação escaladas proporcionalmente para caber em 1920x1080 (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`), desacopladas das dimensões do VD pelos args `EW EH`. O VD permanece ampliado para preservar a correção do crop de IME (limitado a 2560 px de largura desde a issue #71); o encoder usa as dimensões reportadas pela central clampadas a 1080p. O escalonamento preserva a proporção (o clamp antigo por eixo transformava um viewport retrato de 1080x2152 em um quadrado 1080x1080). Sem o limite, celulares com saída acima de 1080p disparam decode por software em SoCs de central fracos (ex.: Snapdragon 439) e caem para fps de um dígito.
- Bitrate: 4 Mbps CBR (`VideoConfig.DEFAULT_BITRATE`, `PipelineServer.BITRATE`). Solicitado pelo receptor via `HandshakeRequest.bitrate`; faixa de validação do argv de deploy 0,5-20 Mbps (`INPUT_MIN_BITRATE`..`INPUT_MAX_BITRATE`), faixa da UI de ajustes 1-12 Mbps. Fallback adaptativo: piso de 1,5 Mbps (`VideoConfig.ADAPTIVE_MIN_BITRATE`), queda para 75% do atual em decodificador travado, recuperação com janela de 2 s e passos de 0,5 Mbps.
- Taxa de frames: 20 fps (`VideoConfig.TARGET_FPS = 20`, issue #71). A central solicita isso via `targetFps` no handshake; a UI de ajustes aceita 10-60.
- Intervalo de frame: `1000 / 20` = 50 ms (`VideoConfig.FRAME_INTERVAL_MS`). Usado como espera máxima dos loops do caminho de vídeo (drenagem do encoder, redesenho do SurfaceScaler).
- Intervalo de IDR: 3 segundos (`PipelineServer.I_FRAME_INTERVAL = 3`, issue #70). Era 1 s; o log de navegação de 2,5 h mostrou ~1 IDR/s (`keys=659 / 15731 frames @24fps`), o que queimava banda em keyframes de que a central não precisava.
- SurfaceScaler: redesenho periódico a cada `FRAME_INTERVAL_MS` garante saída do encoder em conteúdo estático.

**Identificação de keyframe**: `H264NalParser.isKeyFrame` (protocol-core) é a definição única compartilhada pelo decodificador da central e pelo pipeline do Windows: ele varre apenas o primeiro ~1 KB de um frame em busca de um start code Annex B de 3 bytes (`00 00 01`) ou de 4 bytes (`00 00 00 01`) e testa `nal_header & 0x1F == 5` — a máscara de tipo, não o byte inteiro, porque um encoder real emite `0x65` (`nal_ref_idc = 3`).

**Detecção de tela preta**: o `BlackScreenDetector` vive no `:protocol-core` e é compartilhado pelo decodificador da central e pelo `VideoDecodePipeline` do Windows — os dois lados enfrentam a mesma falha de encoder (TCP vivo, receptor parado no último frame), então a política tem uma única definição. Um encoder que não produz nada emite I-frames muito pequenos, então uma sequência de keyframes abaixo de `keyframeMaxBytes` (2 KB) significa que o stream está preto. A reação é deliberadamente assimétrica: `alertStreak` (3) keyframes minúsculos apenas ligam um flag de log, e `onSustainedBlackScreen` dispara quando a sequência dura `BLACK_SCREEN_SUSTAIN_MS` (3 s por padrão; os receptores sobrescrevem) — rajadas de aquecimento de app/VD são normais, e escalar nessas rajadas é a tempestade de reconexão que vazava VirtualDisplays. O relógio monotônico é injetado (`clock`), então o protocol-core continua livre de Android, e a escalada fica latchada em uma vez por sessão até um `reset()`.

## Canal de áudio — Audio Channel (0x02)

O protocolo de espelhamento de áudio fechou o ciclo completo na 1.0.3 (Refs #67, #70, #72, #73, #79, #84). Inclui o contrato de payload do S1, o pipeline de captura e codificação com preenchimento de silêncio do S2, o bind de porta e a reprodução no receptor do S3, a negociação bidirecional de capacidades e a persistência do switch do S4, além do teste ponta a ponta do S23 e das estatísticas de frames de silêncio para evitar falso verde. A codificação/decodificação do payload é feita pelo `AudioPayload`, em JVM pura, testável em unidade sem depender do Android.

### Evolução por fases e especificação técnica

- **S1 contrato de payload e formato de frame**: define o formato de codificação/decodificação do `AudioPayload`, porta independente `9640` (`AUDIO_PORT`), com `CONFIG (0x01)` e `FRAME (0x02)`.
- **S2 codificação e preenchimento de silêncio**: o servidor VD captura pela fonte de gravação `REMOTE_SUBMIX` e codifica para AAC-LC (48000 Hz, estéreo de dois canais, 128 kbps) com `AudioPipeline` + `AudioEncoderSelector`. Um AAC-LC de 48 kHz tem 1024 amostras por frame = **21,333 ms / 46,875 fps** (`AudioSpec.kt`, `AudioPacing.kt`). Durante períodos de silêncio na captura, frames de silêncio sintetizados são injetados continuamente no ritmo de 46,875 fps, evitando underflow do buffer de jitter e inchaço da latência de fila no receptor.
- **S3 conexão TCP independente e reprodução**: o servidor VD inicia o `PortAcceptor` fazendo bind da porta 9640 (com trava de IP de peer via `CAR_HOST`) e usa `AsyncSocketSender` para escrita assíncrona. No lado da central, o `CarSessionCoordinator` conecta na 9640 e entrega ao `AudioPlayer` (que mantém internamente um buffer de jitter de 150 ms, configurável entre 60 e 200 ms), e o `AudioTrack` toca livremente.
- **S4 negociação bidirecional de capacidades**: os dois lados do handshake precisam ligar `FEATURE_AUDIO` em `supportedFeatures` (decisão do `isAudioNegotiated`). O cliente oferece um switch de áudio persistido na configuração local; o lado da central desabilita ou habilita o switch de áudio dinamicamente conforme a resposta do celular.
- **S23 cenário ponta a ponta e proteção contra falso verde**: o cenário e2e S23 de espelhamento de áudio foi implementado; o `AudioPlayer` do lado da central instrumenta periodicamente as estatísticas de frames de silêncio no log (`silenceFrames`/`totalFrames`), evitando que um fluxo só de silêncio seja mal interpretado como funcionamento normal — o falso verde (issue #84).

### CONFIG (0x01) -- VD server -> Car

Enviado uma vez antes de qualquer FRAME. Layout big-endian:

```
+----------------------+--------+
| codec                | byte   |  1 = AAC-LC（`AudioPayload.CODEC_AAC_LC`）
| sampleRate           | int32  |  48000
| channelCount         | byte   |  2（立体声）
| bitrate              | int32  |  128000
| csdLen               | byte   |  csd-0 长度（实测 2）
| csd                  | bytes  |  MediaCodec 的 `csd-0`
+----------------------+--------+
```

`csd-0` precisa ser enviado **fora de banda**: unidades de acesso AAC cruas não trazem header ADTS, e sem ele o decodificador não consegue inicializar. Os dois lados rejeitam `csdLen` que não bata com o número de bytes restantes, codec desconhecido, taxa de amostragem / número de canais não positivo, bitrate negativo e payload acima de `FrameCodec.MAX_PAYLOAD_SIZE`.

### FRAME (0x02) -- VD server -> Car

```
+----------------------+--------+
| ptsMs                | uint32 |  毫秒时间戳，无符号；到 2^32 回绕
| aacFrame             | bytes  |  一帧裸 AAC-LC 访问单元（约 341 B）
+----------------------+--------+
```

Um AAC-LC de 48 kHz tem 1024 amostras por frame = **21,333 ms / 46,875 frames por segundo** (não 20 ms / 50 fps). `ptsMs` serve só para log e estatísticas de underflow; esta solução não faz sincronização A/V precisa, e o receptor toca livremente com buffer de jitter (150 ms por padrão, configurável entre 60 e 200 ms). Payload vazio é rejeitado. Em períodos de silêncio na captura o produtor continua reenviando frames de silêncio codificados a 46,875 fps; se o preenchimento seguisse o ritmo de 20 ms, a fila incharia cerca de 300 ms de latência (conclusão do experimento de estabilidade em dispositivo real da issue #13).

## Canal de dados (0x03)

### APP_LIST (0x03) -- Phone -> Car

Lista de apps instalados com ícones. Layout do payload:

```
+----------------------+--------+
| count                | int16  |  number of entries
| N x entry:           |        |
|   packageName len    | int16  |
|   packageName        | UTF-8  |
|   appName len        | int16  |
|   appName            | UTF-8  |
|   category           | byte   |  0=NAVIGATION, 1=MUSIC, 2=COMMUNICATION, 3=OTHER
|   iconSize           | int32  |  PNG bytes length (e.g. 96x96 icon)
|   iconPng            | bytes  |
|   iconHash len       | int16  |
|   iconHash           | UTF-8  |  hash string for cache invalidation
+----------------------+--------+
```

A central usa `iconHash` para pular a recodificação de ícones que já tem em cache. Limpar o cache de hash de ícones no disconnect evita ícones obsoletos depois que a sessão termina.

### APP_UNINSTALLED (0x06) -- Phone -> Car

Payload: nome de pacote em UTF-8. O celular confirma que um app foi desinstalado (em resposta ao `APP_UNINSTALL`). A central remove o app da grade do launcher.

### APP_INFO_DATA (0x07) -- Phone -> Car

Payload: `AppInfoDataMessage`. O celular envia metadados do app para exibição em um diálogo do lado da central quando o usuário seleciona "Informações do app" no menu de contexto.

```
+----------------------+--------+
| packageName len      | int16  |
| packageName          | UTF-8  |
| appName len          | int16  |
| appName              | UTF-8  |
| versionName len      | int16  |
| versionName          | UTF-8  |
| versionCode          | int64  |
| installTime          | int64  |  epoch millis
| targetSdk           | int32  |  targetSdkVersion of the app
+----------------------+--------+
```

### MEDIA_METADATA (0x10) -- Phone -> Car

Informações da faixa. Três strings com prefixo de comprimento (título, artista, álbum) seguidas de `durationMs` (int64). Reservado para integração com widget de mídia; não é populado ativamente.

### MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Estado de 1 byte (0=stopped, 1=playing, 2=paused) seguido de `positionMs` (int64). Reservado.

### MEDIA_ACTION (0x12) -- Car -> Phone

Enum de 1 byte: 0=PLAY, 1=PAUSE, 2=NEXT, 3=PREVIOUS, 4=SEEK. Reservado (ainda não ligado ao MediaSession).

### NAVIGATION_STATE (0x20) -- Phone -> Car

Dados de estado de navegação. Reservado para integração com widget de navegação.

### CAR_LOG (0x30) -- Car -> Phone

Linha de texto UTF-8. A central roteia todos os logs (incluindo VideoDecoder e UsbAdbConnection) por este canal. O celular grava no FileLog (`/sdcard/DiLinkAuto/client.log`) com a tag `CarLog`.

### LOG_TOGGLE (0x31) -- Phone <-> Car

Payload de 1 byte (0 ou 1). Liga/desliga o log do lado da central. Padrões: ON em builds debug/pré-release, OFF em release. A escolha do usuário persiste. Quando desligado, não ocorre nenhuma escrita em disco na central.

### PHONE_STATUS (0x21) -- Phone -> Car

Estado de bateria e carregamento do celular (issue #151). Comprimento fixo de 3 bytes:

```
+----------------------+--------+
| level                | uint8  |  0-100（手机已按 EXTRA_LEVEL/EXTRA_SCALE 换算）
| flags                | uint8  |  bit0 = 充电中；其余位保留，接收端忽略
| temperature          | int8   |  摄氏度；-128 = 手机未上报
+----------------------+--------+
```

- Momento de envio: o celular sobe a informação **depois que o handshake termina e a conexão de controle está disponível**, com throttle do `BatteryReportGate` — a primeira amostra sempre vai, mudança no bit de carregamento ou variação ≥2% vai imediatamente, variação de 1% exige ≥5 s desde o último envio, e com bateria inalterada há um envio de segurança a cada 60 s. Sem conexão/sem handshake não envia; uma falha de envio só gera log e mantém o estado para a próxima tentativa (a lição da issue #41).
- O payload precisa ter exatamente 3 bytes: menos ou mais que 3 bytes lançam `ProtocolDecodeException` (bytes extras em um vetor de estado de tamanho fixo indicam desalinhamento de enquadramento e não podem ser silenciosamente interpretados pelos 3 primeiros bytes). `level > 100` também é erro.
- No lado da central é usado apenas no chip da barra de informações: `86%` / `86%+` (carregando) / `—` (não recebido ou desconectado). O texto é gerado pelo `BatteryFormat` do `protocol-core`, sujeito à largura fixa de 76dp da coluna esquerda em paisagem, sem ícone.

**Compatibilidade retroativa**: o `when` do `CarSessionCoordinator.handleDataFrame` no lado da central não tem ramo else, então versões antigas recebem `0x21` e simplesmente ignoram, sem executá-la por engano como outra mensagem; o celular só envia depois que o handshake termina (`handshakeReceived`), e tipos desconhecidos não afetam os outros frames da sessão antiga.

### Contrato de decodificação (todos os payloads)

Toda leitura de tamanho fixo é precedida de uma checagem de bytes restantes e todo prefixo de comprimento é lido como **não assinado** (um short `0x8004` virava `-32764` e lançava `NegativeArraySizeException`). Violações levantam `ProtocolDecodeException` — nunca um `BufferUnderflowException` não checado — para que o leitor da `Connection` possa capturá-la, registrar o motivo e desconectar, em vez de a exceção escapar para uma corrotina não tratada e matar o processo.

- Campos finais opcionais usam uma leitura tolerante que retorna `""` / `0` em vez de lançar, então um peer que os omite ainda decodifica.
- Um `iconSize` declarado que excede os bytes restantes **falha** a decodificação: descartar o ícone em silêncio fazia o comprimento do próximo `iconHash` consumir bytes do ícone e guardar em cache um hash lixo sob uma chave real.
- Ids desconhecidos de `AppCategory` / `MediaAction` lançam exceção em vez de coagir para `OTHER` / `PLAY` — um erro de digitação de um peer costumava iniciar a reprodução silenciosamente.
- Prefixos UTF-8 de 2 bytes são protegidos no lado do encoder: um payload >= 64 KB costumava estourar negativo e dessincronizar o parser do peer.
- Os payloads de `LAUNCH_APP` / `APP_UNINSTALL` são validados na fronteira do decodificador por `requirePackageName` / `requireComponentName` (`PACKAGE_NAME_REGEX` / `COMPONENT_REGEX` excluem todo metacaractere de shell — `;` `$` backtick quote whitespace `|` `&` `>` `<` `(` `)`), porque o payload é interpolado em linhas de shell `am start` / `pm uninstall` rodando como shell UID. O quoting de shell nos pontos de chamada é defesa em profundidade, não o único gate.
- `AppInfoDataMessage` exige os `8 + 8 + 4` bytes finais antes de lê-los.

## Canal de entrada (0x04)

### TOUCH_DOWN (0x01) -- Car -> VD server

Evento de down de ponteiro único. O payload é um `TouchEvent` (25 bytes):

```
+----------------------+--------+
| action               | byte   |  InputMsg.TOUCH_DOWN (0x01)
| pointerId            | int32  |  multi-touch pointer ID
| x                    | float  |  normalized 0.0-1.0
| y                    | float  |  normalized 0.0-1.0
| pressure             | float  |
| timestamp            | int64  |
+----------------------+--------+
```

### TOUCH_MOVE (0x02) -- Car -> VD server

Evento de move de ponteiro único. Mesmo formato de payload `TouchEvent` do TOUCH_DOWN.

### TOUCH_UP (0x03) -- Car -> VD server

Evento de up de ponteiro único. Mesmo formato de payload `TouchEvent` do TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Car -> VD server

```
+----------------------+--------+
| count                | byte   |  number of pointers
| N x pointer:         |        |
|   pointerId          | int32  |
|   x                  | float  |  normalized 0.0-1.0
|   y                  | float  |  normalized 0.0-1.0
|   pressure           | float  |
|   timestamp          | int64  |
+----------------------+--------+
```

Eventos MOVE em lote -- todos os ponteiros ativos em uma única mensagem. Cada ponteiro tem 24 bytes (sem byte de action por ponteiro). Reduz syscalls em gestos multi-touch.

O byte `count` é o contrato do wire, então o protocolo de frames aceita até 255 ponteiros. O **receptor** é quem limita: `TouchInjector.MAX_POINTERS` é 10, batendo com a faixa de `pointerId` emitida pelo encoder do wire (`0..MAX_POINTERS-1`), e o `PointerCap` descarta as entradas mais baixas em vez de deixar um lote acima do limite travar a injeção pelo resto da sessão. O wire não deve impor o limite — ele é o tamanho da tabela do injetor, não um limite do protocolo — e o `TouchSequenceTest` fixa essa separação, enquanto o `PointerCapTest` fixa que os dois limites andam juntos.

### KEY_EVENT (0x10) -- Car -> VD server

Evento de tecla (ex.: teclas de mídia, teclas de navegação). Reservado para uso futuro.

## Canal de ciclo de vida (localhost:19647)

Interno ao celular: o servidor VD se reconecta ao app do celular em `localhost:19647`. É um protocolo pequeno e separado, não o protocolo de frames acima. Mensagens:

| Valor | Direção | Significado |
|-------|-----------|---------|
| `0x10` (`MSG_DISPLAY_READY`) | VD server -> Phone | O VirtualDisplay está de pé e o servidor VD fez bind de 9638/9639. Payload: `displayId` de 4 bytes, depois 1 byte de flags (bit0 = injeção de toque direta). Dispara o envio de `VD_PORTS_BOUND` do celular para a central. |
| `0x11` (`MSG_STACK_EMPTY`) | VD server -> Phone | Nenhuma activity no VD após o GO_BACK. Sem payload — apenas o byte de comprimento. Dispara o envio de `VD_STACK_EMPTY` do celular para a central. |
| `0xFF` (`CMD_STOP`) | Phone -> VD server | Encerra o processo do servidor VD. |

Esses valores sobrepõem de propósito a numeração do `ControlMsg` (0x10 = `LAUNCH_APP`, 0x11 = `GO_HOME`) e são um **espaço de numeração separado**: o canal de ciclo de vida troca bytes crus, não frames do `FrameCodec`, e uma futura renumeração do `ControlMsg` nunca pode ser autorizada a mover este formato de wire. Os dois lados leem os valores de `VdLifecycle`.

O servidor VD usa `FrameCodec.readFrameBlocking(NioReader)` e `NioReader.readIntOrNullBlocking()` no seu loop de leitura sem corrotinas. Ver [client.md](./client.md) para o lado do orquestrador.

## Referência de ADB

O RemoteAdbController do lado da central usa `TcpAdbConnection` -- um cliente ADB-over-TCP persistente de socket único que mantém um socket aberto e multiplexa comandos de shell por ele (diferente do dadb, que abre uma conexão nova por comando). O CarAppInstaller do lado do celular ainda usa dadb para instalar o APK da central.

Detalhes do protocolo ADB (`AdbProtocol.java`):
- Cabeçalho: 24 bytes, little-endian (command, arg0, arg1, dataLen, checksum, magic).
- Comandos: `A_CNXN` (0x4e584e43), `A_AUTH` (0x48545541), `A_OPEN` (0x4e45504f), `A_OKAY` (0x59414b4f), `A_CLSE` (0x45534c43), `A_WRTE` (0x45545257).
- `A_VERSION = 0x01000000`.
- `MAX_PAYLOAD = 256 KB`. `TcpAdbConnection.negotiateMaxPayload` lê o máximo do peer a partir do banner CNXN (`int` little-endian no offset 4) e usa `min(peerMax, 256KB)`; banner ausente/curto, resultado `0` ou negativo caem para `MAX_PAYLOAD` em vez de travar.
- `AUTH_TOKEN` (1) = o dispositivo envia um token de 20 bytes, `AUTH_SIGNATURE` (2) = o host assina com SHA-1 DigestInfo + NONEwithRSA (pré-hasheado, igual ao `RSA_sign(NID_sha1)` do AOSP), `AUTH_RSAPUBLICKEY` (3) = o host envia a chave pública para aprovação do usuário.
- A sequência de respostas é fixa e compartilhada: o primeiro `AUTH_TOKEN` é respondido com `AUTH_SIGNATURE`, e um segundo (assinatura rejeitada) com `AUTH_RSAPUBLICKEY`. O `AdbCrypto` é dono disso nos dois transportes.
- Chave pública: struct `ANDROID_PUBKEY` (palavras do módulo, `n0inv`, módulo little-endian, `rr`, expoente), codificada em base64 e então `" DiLinkAuto@car\0"` — terminada em NUL conforme a especificação ADB (a forma antiga, sem terminação, do caminho TCP era um bug latente de aceitação de autenticação). `AdbCrypto.encodePublicKey` usa `java.util.Base64` (alfabeto padrão, um `=` de padding), que é byte a byte idêntico à saída `NO_WRAP` do Android e verificável no jar de teste da JVM.
- Fingerprint: SHA-1 sobre a chave pública DER, primeiros 4 bytes em hex — o formato que o próprio `adb` imprime. Os dois transportes usam SHA-1.
- Armazenamento de chaves: o `TcpAdbConnection` grava chaves em formato PEM (`adbkey` / `adbkey.pub`) no seu `keyDir`, para que o dadb leia as mesmas chaves. A central passa um diretório privado do app (`filesDir/adb_keys`) para que a credencial do celular de shell UID não fique exposta a qualquer app com `READ_EXTERNAL_STORAGE`.

**Caminho de leitura endurecido.** Os loops USB e TCP leem por `TcpAdbConnection.readMessageFrom(InputStream)` / `AdbProtocol.parseHeader`, que rejeita um header cujo `magic != command ^ 0xFFFFFFFF` e qualquer `data_len` negativo ou acima de `MAX_PAYLOAD` — um `0x7FFFFFFF` não validado fazia `ByteArray(dataLen)` lançar `OutOfMemoryError` (um `Error`, então o `catch (Exception)` dos chamadores nunca o via). O CRC do payload agora é verificado em toda leitura, não apenas analisado.

**O caminho ADB do desktop é um `adb.exe` externo.** O receptor Windows não implementa o protocolo ADB por conta própria: ele roda `adb connect <phoneHost>:<adbPort>` e `adb -s <serial> shell <command>`, com a linha de lançamento vindo do mesmo `VdDeploy.buildDeployPlan` (com `background = false`, para o stream ADB continuar anexado ao motor). Isso torna dois campos do `HandshakeResponse` entradas controladas pelo peer, então o desktop valida os dois:

- `adbPort` precisa ser exatamente `Ports.ADB_PORT` (o celular sempre o envia, então a whitelist não custa compatibilidade). Qualquer outro valor aborta a sessão em vez de conectar silenciosamente a um host:porta escolhido pelo peer.
- `vdServerJarPath` precisa estar vazio, ser igual a `VdDeploy.JAR_PATH`, ou estar dentro de `VdDeploy.DIR_PATH` (e não conter `..`). O `shellQuote` só impede um caminho de *escapar do comando*; ele não impede um peer de escolher *qual* código roda no dispositivo como shell UID, então a escolha em si está na whitelist.
- `vdWidth` / `vdHeight` são clampados para `[2, 4096]` e arredondados para baixo até o par via `DimAlign.evenMin2` (`DimAlign.even` mapeia negativos para ~2^31 positivo, deixando um peer hostil pedir um VirtualDisplay arbitrariamente grande do lado do dispositivo).

O `adb` ainda é usado pelo app do celular (`CarAppInstaller` via dadb) para instalar o APK da central, e é por isso que `adbkey` / `adbkey.pub` permanecem em formato legível pelo dadb.

### Comandos de shell do servidor VD (`VdDeploy`)

Todos os comandos de ciclo de vida do servidor VD são definidos em `VdDeploy.kt` (módulo protocol) e compartilhados entre celular e central:

| Comando | Descrição |
|---------|-------------|
| `killCommand` | SIGTERM puro: `for p in $(pgrep -f "[P]ipelineServer"); do kill -TERM $p; done 2>/dev/null; exit 0`. **No ART isso encerra o processo imediatamente sem executar o shutdown hook da JVM** (ver `gracefulStopCommand`) — mantido apenas para depuração manual, não é mais usado pela sequência de deploy |
| `killCommandForce` | Kill forçado: **localiza com `pgrep -f "[P]ipelineServer"` e então `kill -9` pelo pid** — `for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done 2>/dev/null; exit 0`. Não é `pkill -9 -f`: no MI 9 (2026-10-10) o `pkill -f` inline também dá SIGKILL no shell wrapper que o está executando (o texto do comando está no próprio cmdline desse shell, e o adb retorna 137), o que torna ilegível a pergunta "o kill acertou alguma coisa" — e esse é o caminho de último recurso. Reutilizar `pgrep -f` também mantém identificação e kill na mesma regra de casamento. SIGKILL é o único sinal de kill com semântica bem definida no ART |
| `gracefulStopCommand(token)` | Pedido de parada graciosa para **uma sessão**: grava `stop-request-<token>` (`stop-request` fixo quando o token está ausente) como `p=<path>; tmp="$p.tmp.$$"; (cat vd-server.pid > "$tmp") \|\| touch "$tmp"; mv -f "$tmp" "$p"; exit 0` — temp no mesmo diretório + `mv`, para que um leitor concorrente nunca veja um arquivo truncado/vazio, e retorna imediatamente (sem esperar: o timeout de shell do transporte mais apertado é 5 s). O watchdog do motor faz polling e **consome** (apaga) o arquivo, e então seta `running=false` para o caminho normal de saída rodar o `cleanup()` completo. Chamadores externos esperam via `vdAwaitExit` e caem para `killCommandForce` |
| `gracefulStopAnyCommand` | Parada de **qualquer motor que esteja rodando** (apenas na limpeza pré-lançamento): lê o token de `/sdcard/DiLinkAuto/vd-server.session` e grava o sentinel daquela sessão, caindo para o `stop-request` fixo quando o arquivo está ausente/inválido. Seguro **apenas** onde nenhum motor novo começou ainda (`vdRunDeploySequence`); ler o dispositivo a partir do teardown de sessão é exatamente a regressão da issue #180 |
| `probeCommand` | Sonda de vivacidade (forma de saída): `if pgrep -f [P]ipelineServer >/dev/null 2>&1; then echo Y; else echo N; fi` — imprime `Y` se vivo, `N` se sumiu. **`pgrep -f`, não `pkill -0`** (2026-10-10): o toybox 0.8.11-android rejeita `pkill -0` com `bad -L '0'` (sempre rc=1 → a sonda sempre reportava GONE, desabilitando silenciosamente a espera de saída). `pgrep` e `pkill` compartilham a tabela de opções do toybox, então qualquer dispositivo onde o caminho de kill funciona tem uma sonda que funciona |
| `probeExitCodeCommand` | Sonda de vivacidade (forma de código de saída): `if pgrep -f [P]ipelineServer >/dev/null 2>&1; then exit 0; else exit 1; fi` — exit 0 = vivo, exit 1 = sumiu. Para chamadores que só veem o status de saída (caminho `shell()` do ADB da central, `AdbDeployer.probe()` do desktop). Também `pgrep`, pelo mesmo motivo acima |

**Parada do motor (2026-10-10, medido em MI 9) — sentinel por sessão desde a issue #180.** SIGTERM **não** executa o shutdown hook da JVM no Android: o `app_process` do ART trata um SIGTERM puro como encerramento imediato e não capturável. Medição no MI 9 `kill -TERM` → processo sumiu em até 1 s, `PipelineServer.cleanup()` executado zero vezes (nenhum passo de recuperação no vd-server.log). O `cleanup()` portanto só roda nos **caminhos normais de saída** do motor (finally do `run()` na thread principal: perda de socket, timeouts de bind, watchdog). O protocolo de parada é um **sentinel de arquivo**, nomeado por sessão (`stop-request-<token>`, token gerado no deploy e repassado no slot 11 do argv), para que o teardown atrasado de uma sessão anterior nunca caia no motor seguinte — um nome de arquivo fixo, e igualmente uma checagem de *conteúdo* de pid, perde essa corrida porque o lado da parada lê o dispositivo no momento da parada, quando o pid/token já pertence ao motor novo. Motores lançados **sem** token (cliente antigo / scripts antigos / e2e) caem para o `stop-request` fixo com pid endereçado por conteúdo e uma regra de mtime contra o início da sessão. Um motor **com token** ainda aceita o `stop-request` fixo, mas só quando o conteúdo dele é igual ao próprio pid: o caminho de deploy ADB da central inicia o motor na central, então o celular nunca fica sabendo do token e seu teardown só pode gravar o nome fixo (o comando copia `vd-server.pid`). Conteúdo vazio/ilegível é ignorado ali — o sentinel vazio atrasado de um cliente antigo carrega um mtime novo, então honrá-lo traria a issue #180 de volta. O watchdog do motor consome o próprio sentinel e sai graciosamente; só se isso estourar o prazo (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, **12s**) o chamador faz force-kill. A limpeza de inicialização apaga sentinels legados obsoletos (e, no modo token, arquivos de sentinel de outras sessões), para que um pedido remanescente nunca mate uma instância recém-criada; um sentinel no caminho da *própria* sessão é mantido (ele só pode vir do cliente dono do token, ou seja, uma parada real durante a janela de inicialização). Motores publicam `vd-server.pid` + `vd-server.session` de forma atômica e só os removem na limpeza quando ainda carregam o próprio valor.

**A latência de saída é ~6,1–6,3 s, não 2–4 s (2026-10-10, duas medições independentes).** A estimativa anterior de "cleanup leva 2–4 s" cobria apenas os comandos de shell *dentro* de `cleanup()`; ela esquecia o join limitado da thread do pipeline, a liberação de encoder/VD e a espera pelo teardown do `ConnectionService` do lado do celular, que pega os mesmos locks de display/rotação. O log de sonda do desktop (`探针 ALIVE @+189ms` → `探针 GONE @+6123ms`) e um poller independente no dispositivo (sentinel → pid sumiu ≈ 6,3 s) concordam. Com o orçamento de 6 s da época a espera errava por **130 ms em toda reconexão**, então um `-9` era sempre emitido exatamente quando o `cleanup()` estava terminando — truncando a cauda dele (a restauração de `screen_off_timeout`). Os dois orçamentos agora são de 12 s (≈2× o valor medido).

**Padrão de processo (`[P]ipelineServer`) — o que ele protege e o que não protege (2026-10-10, MI 9).** Um padrão `PipelineServer` simples é inutilizável: o texto do comando cai no próprio cmdline do shell wrapper que o invoca (`sh -c "... pgrep -f PipelineServer ..."`), então a busca casa com *ela mesma* e todo comando seguinte naquela linha é silenciosamente pulado. A forma com colchetes é uma **regex** que casa com o `com.dilinkauto.vdserver.PipelineServer` do motor mas não com o texto literal `[P]ipelineServer` daquele wrapper. Limites medidos:

- `pgrep -f "[P]ipelineServer"` — o truque **funciona**: motor presente → rc=0, ausente → rc=1, sem auto-casamento. Todas as sondas são construídas sobre isso.
- `pkill -f "[P]ipelineServer"` — **não funciona**: ele também mata o wrapper que o está executando (o adb retorna 137 antes do próximo `echo`). O motor *é* sinalizado (reproduzido duas vezes), mas o código de saída deixa de ser evidência. É por isso que o kill forçado usa `pgrep` + `kill <pid>`: identificação e kill passam a compartilhar uma única regra de casamento.

### argv do servidor VD (`VdDeployArgs`)

`PipelineServer.main()` faz o parse de `W H DPI PHONE_HOST EW EH FPS [BITRATE] [CAR_HOST] [ALLOWED] [SESSION] [DPI_SOURCE]`:

| Slot | Origem / clamp |
|------|-------------------|
| `W H` | Dimensões do VirtualDisplay. No celular: `VdDimensions.resolve` (viewport da central, alinhado em par, anti-crop escalado até a borda longa física do celular). Na central: viewport nativo da central |
| `DPI` | Fonte única (issue #202): `VdDpiResolver` resolve em ordem override do usuário (faixa do protocolo 120..480 **e não acima de `VideoConfig.maxSafeDpi`**) > autocalibração (`calculateOptimalDpi`) > fallback padrão. Fora da faixa/acima do teto não faz clamp de fronteira: cai para o valor automático e registra um log W |
| `PHONE_HOST` | Host ao qual o servidor VD se reconecta em `LIFECYCLE_PORT` |
| `EW EH` | Dimensões do encoder, escaladas proporcionalmente para caber em 1920x1080, depois arredondadas para baixo até o par |
| `FPS` | Taxa de frames alvo; todos os timeouts do pipeline derivam dela |
| `BITRATE` | bps, validado contra `500_000..20_000_000`; fora da faixa cai para 4 Mbps |
| `CAR_HOST` | Final, opcional. IPv4 dotted-quad ou hostname; vazio ou malformado degrada para `-` (= aceita qualquer peer) em vez de falhar o deploy. Fixa os accepts 9638/9639 do motor no receptor que ele atende |
| `ALLOWED` | issue #21: nomes de pacote separados por vírgula (`[A-Za-z0-9._]{1,255}`) que o usuário permite na tela da central; `-` / vazio / tudo inválido = sem filtragem de allowlist (deploys ADB da central/desktop não conseguem ler as prefs do celular). Limita o `RecentsParser.pickQuickSwitchTarget` para que um app privado nunca seja puxado para o VD |
| `SESSION` | issue #180: o token de sessão gerado no deploy (`[A-Za-z0-9_-]{1,64}`, 32 caracteres hex de um UUID), também usado para nomear o sentinel de parada desta sessão. `-` / inválido = motor legado, escutando no `stop-request` fixo com endereçamento por conteúdo de pid + fallback por mtime |
| `DPI_SOURCE` | issue #202: token de origem do DPI (`auto` / `user-240` / `fallback-480-234`, `[A-Za-z0-9._-]{1,48}`); o motor só o imprime na linha `Starting:`; `-` / ausente = deploy antigo não reportou (o log registra `unknown`) |

### Linha de comando de lançamento

`VdDeploy.commandLine(jarPath, logPath, args, background)` produz:

```
CLASSPATH='<jarPath>' <exec|setsid> app_process / com.dilinkauto.vdserver.PipelineServer <args> >>'<logPath>' 2>&1[ &]
```

- `background = false` (caminhos USB/TCP-ADB da central) usa `exec`, então o `app_process` substitui o shell do chamador e permanece anexado ao stream ADB — o `TcpAdbConnection` da central não tem leitor de demux de stream, e um stream que fecha mata o motor que ele acabou de iniciar.
- `background = true` (caminho Shizuku do celular) usa `setsid ... &`, que é o que realmente desanexa o motor do process group do `sh` pai; com um `exec app_process ... &` puro o Shizuku poderia colhê-lo, deixando um `vd-server.log` de 0 byte.
- A saída é anexada (`>>`), nunca truncada: uma tempestade de reconexão roda pkill + restart várias vezes, e truncar deixa o último restart (muitas vezes de vida curta) apagar o diagnóstico da primeira falha.
- **Os dois caminhos usam aspas simples** (`VdDeploy.shellQuote`, com `'` embutido escapado como `'\''`) porque `jarPath` é controlado pelo peer — ele chega em `HandshakeResponse.vdServerJarPath` — e o comando roda como shell UID. `logPath` também recebe aspas, para que ninguém depois o ligue a um parâmetro e reintroduza o buraco. A cauda do argv é só numérica/formato de host, então não precisa de aspas.

## Constantes

```
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637  (phone <-> car, handshake + heartbeat + commands + data)
VIDEO_PORT            = 9638  (VD server binds 0.0.0.0 -> car connects)
INPUT_PORT            = 9639  (VD server binds 0.0.0.0 -> car connects)
AUDIO_PORT            = 9640  (VD server binds 0.0.0.0 -> car connects, AAC-LC CONFIG + FRAME)
LIFECYCLE_PORT        = 19647 (VD server reverse-connects to phone localhost)
ADB_PORT              = 5555  (standard ADB TCP port)
TARGET_FPS            = 20
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS  (~= 50 ms)
DEFAULT_BITRATE       = 4000000  (4 Mbps, CBR start bitrate)
ADAPTIVE_MIN_BITRATE  = 1500000  (runtime adaptive floor)
BITRATE range (UI)    = 1000000 .. 12000000
BITRATE range (argv)  = 500000 .. 20000000  (deliberately wider, for hand-edited configs)
HEARTBEAT_INTERVAL    = 3000 ms  (control connection only, injectable)
HEARTBEAT_TIMEOUT     = 10000 ms (control connection only, injectable)
CONNECT_TIMEOUT_MS    = 10000 ms (TCP connect deadline)
FLUSH_GRACE_MS        = 500 ms  (writer drain on a graceful peer close)
SOCKET_BUF_BYTES      = 524288   (512 KB requested send/receive buffer; explicit SO_SNDBUF = hard cap)
WRITE_QUEUE_CAPACITY  = 64       (frames, BufferOverflow.SUSPEND)
MAX_PAYLOAD_SIZE      = 16,777,216 bytes  (16 MB, FrameCodec)
HEADER_SIZE           = 6 bytes  (4 length + 1 channel + 1 type)
LARGE_FRAME_LOG_THRESHOLD = 8 MB
NIO_READ_UNIT_MAX     = HEADER_SIZE + MAX_PAYLOAD_SIZE  (reader grow ceiling)
NIO_BUFFER_CAPACITY   = 131072   (128 KB default read buffer)
BLACK_SCREEN_SUSTAIN_MS = 3000  (default; receivers override)
KEYFRAME_MAX_BYTES    = 2048     ("tiny" I-frame threshold for black detection)
TINY_KEYFRAME_STREAK  = 3        (alert-only threshold)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
SERVICE_NAME (mDNS)   = "DiLink-Auto"
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

A fonte única das constantes de porta é `Ports` em `:protocol-core`; o `Discovery` mantém aliases obsoletos que as repassam. A descoberta mDNS ignora responders cujo `serviceName` não seja `SERVICE_NAME` e qualquer porta resolvida fora de `1..65535` — um impostor na mesma LAN não tem outro gate.

Constantes de deploy do servidor VD (`VdDeploy`):

```
MAIN_CLASS      = "com.dilinkauto.vdserver.PipelineServer"
PROCESS_PATTERN = "[P]ipelineServer"
DIR_PATH        = "/sdcard/DiLinkAuto"
JAR_PATH        = "/sdcard/DiLinkAuto/vd-server.jar"
LOG_PATH        = "/sdcard/DiLinkAuto/vd-server.log"
MAX_ENCODE_WIDTH  = 1920
MAX_ENCODE_HEIGHT = 1080
DPI_OVERRIDE_MIN  = 120
DPI_OVERRIDE_MAX  = 480
CAR_HOST_ANY      = "-"
```

Constantes de ADB (`AdbProtocol`):

```
HEADER_SIZE   = 24
A_VERSION     = 0x01000000
MAX_PAYLOAD   = 262144  (256 KB)
AUTH_TOKEN    = 1
AUTH_SIGNATURE = 2
AUTH_RSAPUBLICKEY = 3
```

Máscara de bits de capacidade (`HandshakeRequest.supportedFeatures` = o que o receptor quer usar; o campo de mesmo nome no fim de `HandshakeResponse` = o que o celular consegue capturar):

```
FEATURE_VIDEO          = 0x01
FEATURE_AUDIO          = 0x02
FEATURE_MEDIA_CONTROL  = 0x08
FEATURE_NAVIGATION     = 0x10
```

Métodos de conexão (para `connectionMethod` em `HandshakeResponse`):

```
CONNECTION_METHOD_USB_ADB   = 0
CONNECTION_METHOD_WIFI_ADB  = 1
CONNECTION_METHOD_SHIZUKU   = 2
```

## Invariantes travadas por teste

Os contratos entre endpoints acima são fixados por testes unitários, para que uma "arrumação" não mova o wire silenciosamente. O inventário completo está em [IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md); os locks relevantes para o protocolo são:

| Invariante | Onde está travada |
|-----------|--------------------|
| Frame ADB: `magic == command ^ 0xFFFFFFFF`, checksum aditivo, teto `MAX_PAYLOAD` aceito pelo `encode` mas rejeitado pelo `parseHeader`, CRC verificado na leitura | `protocol/.../adb/AdbProtocolTest`, `TcpAdbConnectionTest` |
| Primitivas do `AdbCrypto`: `bigIntToLEPadded`, `SHA1_DIGEST_INFO`, string de chave pública terminada em NUL, fingerprint SHA-1, sequência do `buildAuthReply` | `protocol/.../adb/AdbCryptoTest` |
| Round-trips de handshake mais truncamento de payload legado para cada campo final (`dpiOverride`, `bitrate`, `vdAreaTier`, `vdWidth`/`vdHeight`) | `MessagesTest` |
| Comandos kill/stop/probe do `VdDeploy` byte a byte, padrão `[P]ipelineServer`, `shellQuote`, a linha de comando de lançamento exata, `buildDeployPlan` | `VdDeployConstantsTest`, `VdDeployCommandLineTest` |
| Layout do `VdDeployArgs.format`, clamp que preserva a proporção, limites de bitrate, sanitização de `CAR_HOST`, `coerceDpiOverride` | `VdDeployArgsFormatTest` |
| `H264NalParser.isKeyFrame` via `nal_type & 0x1F` em headers `0x65` de encoder real | `H264NalParserRefIdcTest` |
| Auto-ack de heartbeat da `Connection`, watchdog expulsando um peer silencioso, link ativo não sendo derrubado, intervalos injetados | `ConnectionTest`, `ConnectionHeartbeatTest` |
| Payloads truncados, rejeição de enum, nomes de pacote com metacaractere de shell, validação de versão, guardas de prefixo de 16 bits | `AuditProtocolFixesTest`, `AdversarialDecodingTest` |
| Reação em dois níveis e relógio injetado do `BlackScreenDetector` | `BlackScreenDetectorTest` |
| Padrões de porta / dimensão / bitrate / DPI resolvem para as constantes de `:protocol-core` | testes de config por módulo |

## Ordem de bytes

Todos os inteiros multibyte e floats no protocolo de frames do DiLink-Auto são **big-endian** (inclusive o buffer do `NioReader`). O protocolo ADB (usado na porta 5555) é **little-endian** conforme a especificação ADB — campos de header, a palavra de max-payload do peer no banner CNXN e a struct `ANDROID_PUBKEY` são todos little-endian; os dois são protocolos separados em sockets separados.
