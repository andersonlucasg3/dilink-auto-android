# Especificacao do Protocolo

## Visao Geral

DiLink-Auto usa um protocolo binario customizado sobre **3 conexoes TCP dedicadas** entre celular e carro:

| Conexao | Porta | Direcao | Conteudo |
|------------|------|-----------|---------|
| **Controle** | 9637 | Bidirecional | Handshake, heartbeat, comandos de app, DATA (lista de apps, notificacoes, logs do carro, midia) |
| **Video** | 9638 | Celular → Carro | Apenas H.264 CONFIG + FRAME |
| **Entrada** | 9639 | Carro → Celular | Apenas eventos de toque |

Cada conexao tem seu proprio socket, NioReader e fila de escrita — isolamento total de I/O previne travamentos de video por trafego nao-video (ex.: payloads grandes de lista de apps).

A conexao de controle e estabelecida primeiro (o handshake acontece aqui). Apos o handshake, o carro abre as conexoes de video e entrada em paralelo. O celular aceita todas as tres e as associa como uma sessao. Heartbeat/watchdog executa apenas na conexao de controle; video e entrada nao tem custo de heartbeat. A morte de qualquer conexao causa a derrubada em cascata da sessao completa.

Um protocolo interno separado executa entre o app do celular e o servidor VD em `localhost:19637` (documentado em [client.md](./client.md)). O servidor VD faz conexao reversa para o celular (celular escuta, servidor VD conecta). O servidor VD usa NIO totalmente nao-bloqueante tanto para leituras quanto para escritas no socket localhost.

## Formato de Transmissao

```
+-----------------+------------+--------------+-----------------+
| Tamanho do Quadro | ID do Canal | Tipo de Msg  | Carga Util       |
| (4 bytes)         | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian        |            |              |                  |
+-----------------+------------+--------------+-----------------+
```

- **Tamanho do Quadro**: `uint32` big-endian. Valor = `2 + tamanho_da_carga`.
- **Carga maxima**: 128 MB.
- **Custo do cabecalho**: 6 bytes por quadro.

## Canais

| ID | Nome | Conexao | Direcao | Proposito |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Controle (9637) | Bidirecional | Handshake, heartbeat, comandos de app, sinais do servidor VD |
| 1 | VIDEO | Video (9638) | Celular → Carro | Quadros de video codificados em H.264 (retransmitidos do servidor VD) |
| 2 | AUDIO | (reservado) | Celular → Carro | Reservado (nao implementado) |
| 3 | DATA | Controle (9637) | Bidirecional | Notificacoes, lista de apps, metadados de midia, logs do carro |
| 4 | INPUT | Entrada (9639) | Carro → Celular | Eventos de toque (CMD_INPUT_TOUCH para injecao bruta de MotionEvent) |

## Canal de Controle (0x00)

### HANDSHAKE_REQUEST (0x01) -- Carro -> Celular

```
+----------------------+------+
| protocolVersion       | int32 |
| tamanho deviceName    | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  largura da tela do carro em pixels (apos barra de nav)
| screenHeight          | int32 |  altura da tela do carro em pixels
| supportedFeatures     | int32 |  mascara de bits
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (padrao)
| screenDpi             | int32 |  densidade da tela do carro (ex. 240)
| appVersionCode        | int32 |  codigo de versao do app do carro (legado, para compatibilidade retroativa)
| targetFps             | int32 |  FPS solicitado pelo carro (ex. 60)
| tam appVersionName    | int16 |  tamanho da string versionName
| appVersionName        | UTF-8 |  nome da versao do app do carro (ex. "0.16.0")
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Celular -> Carro

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=aceito, 0=rejeitado
| tamanho deviceName    | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  largura do VD (corresponde a solicitacao do carro)
| displayHeight         | int32 |  altura do VD (corresponde a solicitacao do carro)
| virtualDisplayId      | int32 |  -1 (definido pelo servidor VD, nao pelo celular)
| adbPort               | int32 |  5555 (porta ADB TCP do celular)
| vdServerJarPath       | UTF-8 |  caminho para o JAR VD implantado no celular (ex. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Apenas conexao de controle

Carga vazia. Enviado a cada 3 segundos na conexao de controle. Se nenhum quadro for recebido em 10 segundos, a conexao e considerada morta (timeout do watchdog). As conexoes de video e entrada nao tem heartbeat.

### DISCONNECT (0x05) -- Bidirecional

Carga vazia. Encerramento gracioso.

### APP_STOPPED (0x14) -- Celular -> Carro

Carga vazia. Enviado quando um app no virtual display e interrompido.

### VD_SERVER_READY (0x20) -- Carro -> Celular

Carga vazia. O carro confirma que o processo servidor VD esta em execucao no celular.

### LAUNCH_APP (0x10) -- Carro -> Celular

```
+----------------------+------+
| packageName           | UTF-8 |  bytes brutos, sem prefixo de tamanho
+----------------------+------+
```

O celular encaminha ao servidor VD que executa `am start --display <id> -n <component>` (sem `--activity-clear-task` — apps existentes retomam).

### GO_HOME (0x11) / GO_BACK (0x12) -- Carro -> Celular

Carga vazia. O celular encaminha ao servidor VD. GO_BACK envia `input -d <id> keyevent 4`, depois verifica pilha vazia. GO_HOME e uma operacao nula (o carro gerencia a navegacao do launcher).

### APP_STARTED (0x13) -- Celular -> Carro

Mesmo formato de LAUNCH_APP. Confirma que o app foi iniciado.

### VD_STACK_EMPTY (0x15) -- Celular -> Carro

Carga vazia. Enviado apos GO_BACK quando o servidor VD detecta que nao ha tarefas de app restantes no virtual display (via `dumpsys activity activities`). O carro usa isso para mudar da visualizacao de espelho para a tela home.

### FOCUSED_APP (0x16) -- Celular -> Carro

Carga: UTF-8 nome do pacote. Enviado quando um app ganha foco no virtual display. O carro usa isso para atualizar seu estado de rastreamento de apps.

### APP_INFO (0x17) -- Carro -> Celular

Carga: UTF-8 nome do pacote. O carro solicita que o celular abra a tela de informacoes/configuracoes do sistema para o pacote fornecido.

### APP_SHORTCUTS (0x18) -- Carro -> Celular

Carga: UTF-8 nome do pacote. O carro solicita os atalhos de app Android 7.1+ disponiveis para o pacote fornecido. **Desativado na UI** — a infraestrutura (consulta do servidor VD + fallback APK XML) esta pronta, mas os atalhos estao ocultos pendentes de refinamento (issue #57).

### APP_SHORTCUTS_LIST (0x19) -- Celular -> Carro

Carga: `AppShortcutsListMessage` — nome do pacote + lista de descritores de atalho (id, shortLabel, longLabel). Enviado em resposta a solicitacao APP_SHORTCUTS.

### APP_SHORTCUT_ACTION (0x1A) -- Carro -> Celular

Carga: `AppShortcutActionMessage` — nome do pacote + id do atalho. Inicia o atalho especifico no virtual display.

### APP_UNINSTALL (0x1B) -- Carro -> Celular

Carga: UTF-8 nome do pacote. O carro solicita que o celular desinstale o pacote fornecido. O celular gerencia o dialogo de desinstalacao do sistema e envia de volta `APP_UNINSTALLED` via canal de dados quando concluido.

### UPDATING_CAR (0x30) -- Celular -> Carro

Carga vazia. Enviado antes que o celular comece a auto-atualizar o app do carro. O carro mostra o status "Atualizando app do carro..." e para de reconectar. Apos a atualizacao, o app do carro reinicia renovado.

## Canal de Video (0x01)

### CONFIG (0x01) -- Celular -> Carro

Unidades NAL SPS/PPS H.264 com codigos de inicio. Enviado uma vez no inicio do codificador.

### FRAME (0x02) -- Celular -> Carro

Unidades NAL H.264 representando um quadro de video.

**Parametros de codificacao** (definidos pelo servidor VD, configuraveis via handshake):
- Codec: H.264/AVC
- Perfil: High
- Resolucao: dimensoes do viewport do carro (ex., 1806x990)
- Bitrate: 8 Mbps CBR
- Taxa de quadros: configuravel via `targetFps` no handshake (padrao 30, carro solicita 60)
- Intervalo IDR: 1 segundo
- SurfaceScaler: redesenho periodico a cada `1000/fps` ms garante saida do codificador em conteudo estatico

## Canal de Dados (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Celular -> Carro

Dados de notificacao com id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32). O carro deduplica por ID (atualizacoes substituem existentes). Tocar uma notificacao inicia o app proprietario no VD.

### NOTIFICATION_CLEAR (0x04) — Carro → Celular

Carga: `ClearNotificationMessage` — id da notificacao + nome do pacote. O carro dispensa uma unica notificacao; o celular limpa a notificacao Android correspondente.

### NOTIFICATION_CLEAR_ALL (0x05) — Carro → Celular

Carga vazia. O carro dispensa todas as notificacoes; o celular limpa todas as notificacoes ativas.

### APP_UNINSTALLED (0x06) — Celular → Carro

Carga: UTF-8 nome do pacote. O celular confirma que um app foi desinstalado (em resposta a `APP_UNINSTALL`). O carro remove o app de sua grade do launcher.

### APP_INFO_DATA (0x07) — Celular → Carro

Carga: `AppInfoDataMessage` — nome do pacote, nome da versao, codigo da versao, hora de instalacao, hora de atualizacao, tamanho do app (bytes). O celular envia metadados do app para exibicao em um dialogo do lado do carro quando o usuario seleciona "Info do App" no menu de contexto.

### APP_LIST (0x03) -- Celular -> Carro

Lista de apps instalados com packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (PNG 96x96).

### CAR_LOG (0x30) -- Carro -> Celular

Linha de texto UTF-8. O carro encaminha todos os logs (incluindo VideoDecoder e UsbAdbConnection) por este canal. O celular escreve no FileLog (`/sdcard/DiLinkAuto/client.log`) com a tag `CarLog`.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Celular -> Carro

Informacoes da faixa e estado de reproducao. Ainda nao ativamente populados.

### MEDIA_ACTION (0x12) -- Carro -> Celular

Controle de midia (play/pause/next/previous). Ainda nao conectado ao MediaSession.

### NAVIGATION_STATE (0x20) -- Celular -> Carro

Dados de estado de navegacao. Reservado para integracao de widget de navegacao.

## Canal de Entrada (0x04)

### TOUCH_DOWN (0x01) -- Carro -> Celular

Evento de toque para baixo com um unico ponteiro. A carga e um TouchEvent (25 bytes):

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  ID do ponteiro multi-toque
| x                     | float |  normalizado 0.0-1.0
| y                     | float |  normalizado 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Carro -> Celular

Evento de movimento com um unico ponteiro. Mesmo formato de carga TouchEvent que TOUCH_DOWN.

### TOUCH_UP (0x03) -- Carro -> Celular

Evento de toque para cima com um unico ponteiro. Mesmo formato de carga TouchEvent que TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Carro -> Celular

```
+----------------------+------+
| count                 | byte  |  numero de ponteiros
| N × ponteiro:         |       |
|   pointerId           | int32 |
|   x                   | float |  normalizado 0.0-1.0
|   y                   | float |  normalizado 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Eventos MOVE em lote — todos os ponteiros ativos em uma mensagem. Reduz chamadas de sistema para gestos multi-toque.

### KEY_EVENT (0x10) -- Carro -> Celular

Evento de tecla (ex., teclas de midia, teclas de navegacao). Reservado para uso futuro.

## Constantes

```
APP_VERSION_COMPARISON = versionName via semver (com fallback para versionCode em carros antigos)
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637 (celular <-> carro, handshake + heartbeat + comandos + dados)
VIDEO_PORT            = 9638 (celular -> carro, apenas quadros H.264)
INPUT_PORT            = 9639 (carro -> celular, apenas eventos de toque)
VD_SERVER_PORT        = 19637 (celular <-> servidor VD, apenas localhost, NIO nao-bloqueante)
TARGET_FPS            = 60 (configuravel via handshake, padrao 30)
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS (16ms a 60fps, espera maxima para loops do caminho de video)
HEARTBEAT_INTERVAL    = 3000 ms (apenas conexao de controle)
HEARTBEAT_TIMEOUT     = 10000 ms (apenas conexao de controle)
MAX_PAYLOAD_SIZE      = 134.217.728 bytes (128 MB)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

## Ordem dos Bytes

Todos os inteiros e floats multi-byte estao em **big-endian**.
