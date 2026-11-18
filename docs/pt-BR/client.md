# App cliente do celular (app-client)

## Visão geral

O app cliente do celular é um **orquestrador puro**. Ele não retransmite vídeo, áudio nem toque. O servidor VD faz bind de `9638` (vídeo), `9639` (entrada) e `9640` (áudio, quando negociado) diretamente em `0.0.0.0`, e a central se conecta a essas portas sem o celular no meio — antes eram 4 operações de socket + 2 trocas de contexto de processo por frame, agora são 2 operações de socket + 0 trocas de contexto. O celular cuida apenas do ciclo de vida e do plano de controle: handshake, negociação de capacidade de áudio (`FEATURE_AUDIO`), deploy do servidor VD, instalação do app da central, filtragem pela allowlist de apps e roteamento do log da central.

Fluxo:

1. Registra um serviço mDNS (`_dilinkauto._tcp`) e escuta TCP vindo da central na porta `9637` (controle + dados, NIO ServerSocketChannel em `0.0.0.0`).
2. Aceita a conexão de controle, lê `HANDSHAKE_REQUEST` (viewport, `screenDpi`, `appVersionCode`, `appVersionName`, `targetFps`, `dpiOverride`, `supportedFeatures`).
3. Abre um ServerSocket de ciclo de vida em `127.0.0.1:19647` **antes** de enviar `HANDSHAKE_RESPONSE`, para que o socket já esteja pronto quando o servidor VD se conectar de volta.
4. Envia `HANDSHAKE_RESPONSE` com `deviceName`, `vdServerJarPath`, `connectionMethod` (`CONNECTION_METHOD_SHIZUKU` quando o Shizuku está autorizado, senão `CONNECTION_METHOD_USB_ADB`), o `vdDpi` negociado e os `supportedFeatures` do celular (incluindo `FEATURE_AUDIO` quando habilitado e disponível).
5. Se o Shizuku estiver disponível, faz o deploy do `vd-server.jar` diretamente no celular via `ShizukuVdDeployer` (`CLASSPATH=... app_process / com.dilinkauto.vdserver.PipelineServer ...`). Caso contrário, a central faz o deploy do servidor VD via ADB USB ou TCP.
6. O servidor VD se conecta de volta em `localhost:19647`. O celular lê `MSG_DISPLAY_READY` (traz `displayId` + flag de injeção direta) e então envia `VD_PORTS_BOUND` para a central na conexão de controle — agora a central conecta vídeo (`9638`), entrada (`9639`) e áudio (`9640`, se negociado) diretamente ao servidor VD.
7. Transmite `MSG_STACK_EMPTY` (contrapressão da central) do servidor VD para a central pela conexão de controle.
8. Roteia frames de dados `CAR_LOG` vindos da central para o `FileLog` (tag `CarLog`).
9. Envia a lista de apps visível para a central, filtrada pela allowlist do usuário, com PNGs de ícone suprimidos por hash.

**Sem captura de tela. Sem MediaProjection. Sem encaminhamento de notificações.** Todo o vídeo vem do processo do servidor VD rodando como shell UID. O `NotificationService` e a etapa de "Acesso a notificações" do onboarding foram removidos neste fork.

## Componentes

### ClientApp

Subclasse de `Application`. Cria o canal de notificação (`dilinkauto_service`), inicializa o `ShizukuManager` em `onCreate`. Abriga o helper `loadIconPng` do lado do celular, que redimensiona um ícone de launcher para um tamanho dado e o codifica em PNG para o payload de rede. Os PNGs codificados são memoizados em um pequeno `LruCache` (orçamento de 8MB, chave `package|size|changeHash`, auditoria A-M12), de modo que reenviar um ícone inalterado é uma consulta ao mapa em vez de um novo raster + compressão PNG — o `AppIconCache` da própria central é o que persiste os ícones entre sessões.

### MainActivity

Ponto de entrada. Inicia o `ConnectionService` automaticamente quando o app é aberto (por exemplo, via `am start` do ADB USB da central) se o onboarding estiver concluído, o serviço estiver `IDLE` **e** o usuário não o tiver parado explicitamente. Faz o roteamento entre quatro telas (um único estado selado `Screen`, auditoria R3-SRP-09, substituindo o trio de booleanos anterior de onboarding/settings/allowlist):

- **OnboardingScreen** (primeira execução): assistente de 6 etapas — Boas-vindas, Acesso a todos os arquivos, Otimização de bateria, Serviço de acessibilidade, Configuração da central, Concluído. Cada etapa de permissão explica o que deixa de funcionar sem ela e avança automaticamente em `ON_RESUME` assim que a permissão é concedida. A etapa de configuração da central mostra o `installStatusFlow` ao vivo e um botão de instalação. Qualquer etapa pode ser pulada. Não existe etapa de Acesso a notificações.
- **MainScreen** (execuções seguintes): card de status, botão iniciar/parar, card Instalar na central, botão Compartilhar logs, card de aviso para dispositivos Galaxy da Samsung que precisam do contorno de Shizuku / depuração sem fio.
- **SettingsScreen**: status das permissões, alternador de log de debug, card sobre.
- **AllowlistScreen**: seletor no celular de quais apps do launcher chegam à central (ver abaixo).

`shareLogs()` chama `FileLog.zipLogs()` e compartilha o zip via `FileProvider`. O roteamento de tela de nível superior, o estado da etapa e o estado de polling de permissões ficam em holders separados (`OnboardingState`, auditoria R3-SRP-05), e não nos composables.

**`userStopped` (auditoria A-L21)** é persistido no próprio arquivo de prefs da Activity, e não mantido em memória, de propósito: o caminho que ele protege é o `PhoneDisplayRestorer` abrindo a `MainActivity` com `FLAG_TURN_SCREEN_ON` após um teardown — uma instância de Activity *nova*, então uma flag em memória já voltaria ao seu valor padrão na leitura. É ele que impede que uma recriação disparada pela restauração ressuscite um serviço que o usuário acabou de parar. A ação de parar da notificação vai direto ao `ConnectionService`, que não consegue escrever uma flag pertencente à Activity.

### ConnectionService

Serviço em primeiro plano (`foregroundServiceType="connectedDevice"`) que orquestra a sessão celular-central. Iniciado automaticamente pela `MainActivity`; executa o loop de escuta em `Dispatchers.Main` com um `PARTIAL_WAKE_LOCK` (liberação automática em 4h).

- **Conexão de controle (porta `9637`)**: accept TCP NIO em `0.0.0.0`. Trata `HANDSHAKE_REQUEST`, heartbeat e o canal DATA (`APP_LIST`, `APP_UNINSTALLED`, `APP_INFO_DATA`, `CAR_LOG`, `LOG_TOGGLE`). `LAUNCH_APP`, `GO_BACK`, `GO_HOME`, `APP_UNINSTALL`, `APP_INFO` agora viajam diretamente central → servidor VD na porta `9639` e não são mais tratados aqui.
- **Canal de ciclo de vida (porta `19647`)**: delegado ao `VirtualDisplayClient` (ver abaixo). O ServerSocket é aberto **antes** de enviar `HANDSHAKE_RESPONSE`, para que a conexão reversa do servidor VD nunca caia em um socket fechado — uma regressão que antes causava falhas de inicialização do VD.
- **`deployAssets()`**: extrai `vd-server.jar` para `/sdcard/DiLinkAuto/` e `app-server.apk` para `filesDir` através do `AssetDeployer` (ver abaixo). Com verificação de CRC; pula a escrita quando o asset já corresponde ao arquivo em disco. Roda uma única vez a partir de `onCreate` e define `assetsReady`, que `ensureAssetsReady()` consulta (até 5s) antes de uma instalação na central, para que o caminho de instalação nunca corra com a extração. Ambos usam as constantes de caminho absoluto do `VdDeploy` — juntar `getExternalStorageDirectory()` com um filho iniciado por barra costumava produzir um diretório sombra `/storage/emulated/0/sdcard/DiLinkAuto`.
- **`handleHandshake()`**: rejeita um `protocolVersion` não suportado já na fronteira, calcula as dimensões do VD via `VdDimensions`, derruba qualquer VD anterior (caso de rotação) sob `vdLock`, abre o ServerSocket de ciclo de vida (ou o reutiliza em um re-handshake no meio do stream), reverifica o jar em disco via `ensureVdServerJarCurrent()`, avalia a capacidade e a preferência de áudio (`AppPrefs.AUDIO_ENABLED`) para preencher `FEATURE_AUDIO` em `supportedFeatures`, e então envia `HANDSHAKE_RESPONSE`, faz o deploy do servidor VD via `ShizukuVdDeployer` e espera por `MSG_DISPLAY_READY`. Ao receber `MSG_DISPLAY_READY`, envia `VD_PORTS_BOUND`, muda o estado para `STREAMING` e chama `AppListBuilder.sendAppList`. Roda em `handshakeJob` em `Dispatchers.IO` (auditoria A-02) — ele faz I/O de disco, um bind de socket e enumeração de pacotes, tudo o que antes travava todos os outros frames na corrotina leitora, e uma exceção lançada costumava matar o leitor sem desconectar. Todo o read-modify-write de `vdClient` mais a criação do cliente de ciclo de vida são serializados por `vdLock` (A-03): dois handshakes sobrepostos (rotação + reconexão) podiam ambos passar pela checagem de null e ambos fazer bind em 19647, vazando um ServerSocket. A decisão sobre o Shizuku é capturada **uma única vez** em `useShizuku` e propagada tanto para o `connectionMethod` da resposta quanto para o caminho de deploy (A-L18) — ler `checkPermission()` para a resposta e `isAvailable` depois para o deploy permitia que uma morte do binder entre as duas dissesse "SHIZUKU" enquanto nada era implantado. A resposta também carrega `vdWidth`/`vdHeight` escalados, para que um deployer sem Shizuku (o caminho ADB do desktop) ainda receba a correção anti-corte do IME. Uma falha no lançamento do VD derruba a sessão em vez de registrar sucesso (A-M7, ver abaixo).
- **Re-handshake no meio do stream**: quando a central gira, a conexão TCP de controle é reutilizada. `handleHandshake` derruba o VD antigo (`stopVdServer` + `disconnect`) e faz o deploy de um novo na nova orientação, deixando o ServerSocket de ciclo de vida aberto durante a troca.
- **Espera pela saída do VD antes de relançar**: o celular não roda mais seu próprio loop de espera. Ele monta um `VdDeploy.DeployPlan`, fornece um `VdDeployExecutor` (`shellSync` → `ShizukuManager.execAndWait`, `launch` → `execBackground`, `probe` → `ShizukuManager.probeVdServer`) e chama o `vdRunDeploySequence` compartilhado em **protocol-core**, para que o celular, os caminhos ADB USB/TCP da central e o desktop não se desviem da regra de convergência. A sequência é: parada graciosa (`VdDeploy.gracefulStopCommand` escreve o arquivo sentinela de parada) → `vdAwaitExit` (orçamento gracioso de 12s — latência sentinela→saída medida no MI 9 é 6.1~6.3s, polling de 150ms, duas confirmações consecutivas de `GONE`, `UNKNOWN` aceito imediatamente para que um transporte morto nunca trave o deploy) → em caso de timeout, `VdDeploy.killCommandForce` (-9) e nova espera → lançamento. `forcedKill` é registrado no log quando o caminho gracioso expirou. Por que não SIGTERM: o `app_process` do ART termina imediatamente ao receber SIGTERM e não roda shutdown hooks (medido no MI 9); o arquivo sentinela é o único meio de fazer o motor seguir o caminho normal de saída e executar `cleanup()` por completo. Dois motores ativos disputam o mesmo VirtualDisplay / DTA / portas 9638-9639; o perdedor pula `cleanup()` inteiramente.
- **Status de deploy honesto (auditoria A-M7)**: `VdDeployExecutor.launch` retorna o `Boolean` que `ShizukuManager.execBackground` produz. Quando é `false`, a sequência reporta `launched = false`, o serviço registra a falha, chama `cleanupSession()` e retorna — ele não imprime mais "VD server started" para um motor que nunca foi lançado. Antes disso, `execBackground` retornava `Unit` e o operador só ficava sabendo da falha pelo timeout de accept de 60s.
- **Guarda de idempotência do cleanup**: duas guardas compostas. **Gerações de sessão (auditoria A-01)**: cada accept gera `sessionId = sessionSeq.incrementAndGet()`, o publica como `activeSessionId` e chama `resetCleanupGuard(sessionId)`; o `finally` do loop de escuta então chama `cleanupSession(sessionId)` com o id que capturou, e um teardown cujo id não está mais ativo registra `cleanupSession: stale session #n (active #m) — skipping` e retorna. Antes, o `finally` tardio de um loop cancelado consumia a guarda pertencente à geração **seguinte**, de modo que o novo ServerSocket de ciclo de vida nunca era fechado e o cliente VD obsoleto continuava transmitindo — o modo de falha do VD vazado. O `AtomicBoolean` `cleanupGuard` então limita a execução a um teardown por geração; `cleanupSession()` podia ser chamado de 7 lugares (dois callbacks de rede, o `finally` do loop de escuta, o caminho de falha do handshake, `stopEverything` e `onDestroy`) sem guarda alguma — observou-se 1 desconexão → 9 linhas "Force-waking physical display". `resetCleanupGuard()` é chamado quando uma nova sessão é estabelecida (accept / re-handshake no meio do stream) e em `stopEverything()`, que sempre precisa derrubar a sessão mesmo que um callback de rede já o tenha feito.
- **`teardownScope` (auditoria A-04)**: a metade bloqueante de `cleanupSession()` roda em `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — um escopo com a vida do processo — nunca em `serviceScope` (que `onDestroy()` cancela antes que a restauração possa rodar) e nunca no chamador da Main (`stopVdServer()` faz join em um worker de escrita por até 1s: um travamento com cara de ANR por chamada). Ordem preservada: `CMD_STOP` gracioso → fechamento do canal → desconexão do controle → restauração de painel/IME.
- **Smart network callback**: `NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)` — reage apenas a mudanças de WiFi e ignora oscilações de dados móveis. `onLost` tem debounce de 3s (reinícios de hotspot 4G podem se recuperar imediatamente); `onAvailable` reinicia o loop de escuta apenas quando o estado é `WAITING`. Desconexão proativa em `CONNECTED`/`STREAMING` para que o timeout de heartbeat não precise expirar.
- **`ACTION_ALLOWLIST_UPDATED`**: reenvia a lista de apps para que a grade da central se atualize ao vivo enquanto houver sessão ativa.
- **`ACTION_INSTALL_CAR`**: caminho de instalação manual. `installCarApp` valida um literal IPv4 explícito (rejeitando um malformado), descarta um duplo-toque ou uma ação reentregue por meio de um `AtomicBoolean` `installInFlight` (auditoria A-M9 — dois fluxos concorrentes costumavam enviar o mesmo APK para o mesmo caminho remoto), aguarda `ensureAssetsReady()` e então entrega todo o fluxo ao `CarInstallCoordinator` (ver abaixo). O serviço continua dono do `installStatusFlow`; o coordinator reporta por meio de um callback.
- **Receiver de pacote removido**: em `ACTION_PACKAGE_REMOVED` (com `EXTRA_REPLACING` filtrado), envia `APP_UNINSTALLED` e reenvia a lista completa de apps.
- **`setLogEnabled(context, enabled)`**: ponto de entrada no companion chamado pela `SettingsScreen`. Persiste em `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`, define `FileLog.enabled` e propaga para a central pela conexão de controle ativa via `DataMsg.LOG_TOGGLE` (payload de 1 byte). Desde a issue #72 o mesmo frame é **também reenviado após cada resposta de handshake** (`syncLogToggle`) — a central redefine `CarLogWriter` para `BuildConfig.DEBUG` a cada `startConnection`, então sem isso uma reconexão descartava silenciosamente o log do lado da central pelo resto da sessão.
- **`cleanupSession()`**: cancela `handshakeJob`, tira um snapshot e limpa `vdClient` sob `vdLock`, limpa o binding de VD do `InputInjectionService`, desconecta a conexão de controle, reinicia os hashes de ícone, retorna o estado para `WAITING` e entrega o IME em cache ao `PhoneDisplayRestorer`. O snapshot/limpeza de estado é síncrono para que um handshake concorrente veja o cliente antigo como ausente; tudo que pode bloquear roda em `teardownScope`.
- **Limite de `CAR_LOG` (auditoria A-05)**: o despacho do DATA é assíncrono e uma única linha de log controlada pelo par podia ter dezenas de MB, entrando na fila ilimitada do `FileLog` como uma String. As linhas agora são limitadas a 8KB — truncadas, não descartadas, para que o resto do log continue utilizável — e a fila do `FileLog` é limitada a 2048 entradas.
- **Registro de mDNS** roda em um `launch` de background com um `withTimeoutOrNull` de 5s, para que o `NsdManager` não trave o loop de escuta quando não há rede.
- **`FileLog.rotate()`** em `onCreate` — arquiva o log da sessão anterior.

Fluxo de estados: `IDLE → WAITING → CONNECTED → STREAMING`, exposto via `serviceState: StateFlow<State>`.

### Colaboradores extraídos (refatoração SRP/DRY, Refs #87, #92)

Para preservar a responsabilidade única e a manutenibilidade, o `ConnectionService` delega a lógica específica de domínio a colaboradores extraídos:
- **`ShizukuVdDeployer`**: gerencia a execução via Shizuku do `vdRunDeploySequence`, incluindo parada graciosa, sondagem de vivacidade e lançamento de `app_process` em background.
- **`ShizukuNav`**: coordena ações de navegação do sistema (voltar, home, recentes) pelo shell do Shizuku quando disponível.
- **`ClientNetworkMonitor`**: observa as transições de conectividade de rede, desacoplando a lógica de listener de estado do Wi-Fi do ciclo de vida central da sessão. `onAvailable` não é tratado como "o link está utilizável": uma reassociação de Wi-Fi pode reportar a nova rede vários segundos antes de o tráfego voltar a fluir, então o `ConnectionService` só reconstrói quando [`NetworkRebuildPolicy`] vê o canal de controle silencioso por ≥6 s (dois heartbeats de 3 s perdidos) — o caso de socket morto que, de outra forma, esperaria pelo watchdog de keep-alive de 10 s (issue #200). **Essa política não dispara no incidente de campo da issue #200**: `onAvailable` chegou com o canal de controle silencioso por apenas 1.96 s (o valor de 5983 ms é o silêncio de *vídeo*, medido após a recuperação), e `msSinceLastFrameReceived()` retorna -1 até que um frame seja realmente lido. O benefício de campo da #200 vem do pré-armamento de `LINK GAP` do lado da central, não deste caminho de reconstrução — não leia um build verde aqui como "o congelamento de 6 s está corrigido".
- **`CarAppInstallHost`**: hospeda o fluxo de instalação do APK do app da central, gerenciando estados assíncronos de instalação sem inchar o serviço de conexão.
- **`AllowlistRepository`**: encapsula a persistência e a recuperação das aplicações de launcher permitidas.
- **`InputInjectionService` (@Deprecated)**: antes mantinha os bindings de tela virtual para injeção de gestos por acessibilidade. Com a injeção direta de toque na porta 9639 tratada pelo `vd-server`, seu caminho de invocação foi completamente cortado.

### VirtualDisplayClient

Somente ciclo de vida. Aceita a conexão reversa do servidor VD em `localhost:19647` (o servidor VD se conecta **ao** celular, nunca o contrário). Não recebe parâmetros `videoConnection`/`controlConnection` — vídeo e toque não são mais retransmitidos pelo celular.

- `startListening(port = SERVER_PORT)`: bind síncrono de `ServerSocketChannel` em `127.0.0.1:19647`. Chamado **antes** de `HANDSHAKE_RESPONSE` para que o socket esteja aberto quando o servidor VD se conectar de volta. Ligado a **loopback, não a `0.0.0.0`** (auditoria A-M10): o canal é exclusivo de localhost por design, e a primeira mensagem lida após o accept carrega o `displayId` no qual o toque é injetado — um par da LAN vencendo a corrida de accept no listener wildcard poderia ter redirecionado isso.
- `acceptConnection(port, timeoutMs = 60000)`: loop de accept não bloqueante que **recusa pares fora do loopback** e continua esperando o motor real (A-M10). O primeiro byte deve ser `MSG_DISPLAY_READY` (carrega `displayId: Int` + a flag `directInjection: Byte`). Em caso de sucesso, define `isConnected`, dispara `onDisplayReady` (que o `ConnectionService` usa para enviar `VD_PORTS_BOUND`) e inicia o relay de comandos. O ServerSocket é fechado em um `finally`, de modo que um accept que falha não deixa nada ligado para o próximo handshake herdar.
- **Relay de comandos** (`Dispatchers.IO`): lê `MSG_STACK_EMPTY` e o encaminha pelo callback `onStackEmpty` → o `ConnectionService` envia `ControlMsg.VD_STACK_EMPTY` para a central.
- `stopVdServer()`: escreve `CMD_STOP` (`0xFF`) no servidor VD para desligamento gracioso. **Retorna `Boolean`** — `true` quando o byte foi entregue ao socket, `false` quando o canal de ciclo de vida já se foi. Um retorno `false` é esperado sempre que o canal já está fechado — o `readLifecycleCommands()` do motor trata o EOF/IOException resultante exatamente como `CMD_STOP` (define `running=false` → `finally cleanup()`), então o teardown ainda acontece; o chamador só não pode assumir que foi gracioso. A escrita em si acontece em um worker daemon de vida curta com join via `STOP_WRITE_JOIN_MS` (1s): o Android lança `NetworkOnMainThreadException` em uma escrita de socket a partir da Main, e `cleanupSession()` roda lá, então o `CMD_STOP` gracioso nunca havia sido realmente enviado em um dispositivo real — o `NetworkOnMainThreadException` no log era isso. A escrita é mantida *síncrona para o chamador* porque `disconnect()` (que libera o `ServerSocketChannel` de 19647 para o re-bind do re-handshake) precisa rodar logo depois dela; despachar ambos para um escopo assíncrono introduziria uma corrida de ocupação de porta.
- `disconnect()`: fecha o reader, o server socket e o canal; redefine `displayId = -1`.
- Na desconexão, o cleanup do próprio servidor VD só roda se ele recebeu `CMD_STOP`. Se o canal de ciclo de vida quebrar primeiro, o `PhoneDisplayRestorer` é a rede de segurança (ver abaixo).

### AllowlistScreen

Seletor no celular de quais apps do launcher a central tem permissão para mostrar. A seleção fica em `SharedPreferences` (`dilinkauto_allowlist` / `allowed_packages`), lida por `AppListBuilder.sendAppList` para filtrar o payload de rede antes que ele chegue à central. Alternar uma linha grava as prefs, marca `allowlist_configured = true` e dispara `ConnectionService.ACTION_ALLOWLIST_UPDATED`, para que o serviço em execução reenvie a lista imediatamente — a grade da central se atualiza ao vivo sem reconexão. Usa `startService` simples, e não `startForegroundService` (auditoria A-L21: o serviço já está em primeiro plano, e o upgrade rearmaria o contrato de 5s do `startForeground` para uma ação que não publica notificação), e é totalmente ignorado quando o serviço está `IDLE` — iniciá-lo só para atualizar uma lista ressuscitaria um serviço parado em estado não-foreground. UI: caixa de busca, Selecionar tudo / Desmarcar tudo, Switch por linha e um contador. Pré-povoado com apps de mapa comuns na primeira execução (ver `AllowlistSeeder`).

### AppListBuilder

Monta e envia a lista de apps visível para a central em nome do `ConnectionService`.

- É dono apenas do filtro de allowlist e do envio; a montagem da lista fica em `AppInfoProvider` (consulta os apps de launcher habilitados, deriva o hash de mudança de cada pacote) e a supressão de ícones em `IconHashGate` (auditoria R3-SRP-18).
- Filtra os apps de launcher pela allowlist (`ALLOWLIST_PACKAGES_KEY`) antes de irem para a rede, reduzindo o payload de rede e o trabalho de decodificação de ícones da central. Uma seleção **null** (nunca configurada) deixa tudo passar.
- `filterByAllowlist` primeiro chama `AllowlistSeeder.seedIfNeeded` quando `ALLOWLIST_CONFIGURED_KEY` é false, para que um arquivo de prefs vazio e obsoleto seja semeado exatamente uma vez por instalação.
- `allowedPackages()` expõe o mesmo conjunto efetivo ao caminho de deploy: o motor (`app_process` com `shell UID`) não consegue ler as prefs deste app, então `ConnectionService.startVdServerViaShizuku` o passa adiante pelo slot de argv `ALLOWED` e `ensureContent` só restaura apps a partir dele (issue #21). Uma seleção **null** (nunca configurada) não passa nada adiante e o motor mantém seu comportamento sem filtro.
- Os dados de ícone são enviados uma vez por pacote por sessão — `IconHashGate.lastSent` (um `ConcurrentHashMap`, com chave em `lastUpdateTime`; tornado concorrente na auditoria A-M11 porque `reset()` roda na Main enquanto `iconFor()` roda na thread de IO que monta a lista) suprime ícones inalterados. `resetIconHashes()` força um reenvio completo na desconexão ou na reinstalação do app da central.
- A categorização não é feita aqui — ver `AppCategorizer` abaixo.

### AppCategorizer

`object` que classifica um pacote instalado em `AppCategory.NAVIGATION / MUSIC / COMMUNICATION / OTHER` para a grade da central. Extraído do `AppListBuilder` (auditoria S10) para que as regras sejam testáveis em unidade sem um `Context` ou `PackageManager`. As regras são baseadas em palavras-chave do nome do pacote e **a ordem importa**: um pacote que casa com uma palavra-chave de navegação nunca chega aos ramos de música ou comunicação.

**Limitação conhecida:** o conjunto de palavras-chave é estreito, então apps comuns caem em `OTHER` — `com.google.android.youtube` não casa com nada. Isso é uma lacuna de UX, não um crash, e está fixado por `AppCategorizerTest`. A correção é um classificador que considere o label ou a categoria.

### AllowlistSeeder

Semeadura inicial da allowlist de apps da central (extraída de `AppListBuilder`, auditoria SRP-7). `seedIfNeeded(pm, prefs)` faz a interseção de `DEFAULT_MAP_PACKAGES` com os apps de launcher realmente instalados (`LauncherApps.queryInstalledPackageNames`) e persiste a interseção, marcando a allowlist como configurada. Padrões que não estão instalados são no-op, então um usuário que não tem nenhum deles recebe uma allowlist vazia **mas configurada**, em vez de uma não configurada que se re-semeia a cada execução. Roda uma vez por instalação — depois disso o usuário cura a lista, então re-semear sobrescreveria suas escolhas.

### CarAppInstaller

Instala o `app-server.apk` embutido na central via `dadb` (ADB-over-WiFi, porta `5555`). É dono de: geração do par de chaves ADB e `Dadb.create()` com timeout rígido de 15 segundos em `Future.get` (o I/O de socket bloqueante não pode ser interrompido pelo cancelamento de corrotina) — ambos delegados ao `AdbKeyUtil`, cujo `ensureAdbKeyPair` se auto-regenera quando o par de chaves está pela metade (auditoria A-L24) — leitura de versão com `dumpsys package`, `pm install -r` e `am start --activity-clear-task -n com.dilinkauto.server/.MainActivity` (`AppTargets.CAR_MAIN_ACTIVITY`). As strings de status voltam ao chamador por um callback `onStatus`, para que `installStatusFlow` permaneça no `ConnectionService`. Ele não conduz mais o fluxo de instalação — isso é papel do `CarInstallCoordinator` (abaixo). `CarAppInstaller` é a **implementação de produção de `CarInstaller<Dadb>`**.

`parseInstalledVersion(output)` é uma função pura de companion que retorna `"0"` — o sentinela de não instalado — quando o `dumpsys` não tem linha `versionName=`, para que o parse seja testável em unidade sem uma sessão `Dadb`. Ver a nota de problema conhecido sobre esse sentinela em `Versioning`.

### CarInstallCoordinator

Conduz uma instalação do app da central: localiza a central, conecta via ADB, pula se já estiver atualizada e, caso contrário, faz push e instala. Extraído de `ConnectionService.installCarApp` (auditoria SRP-2) — aquele método tinha ~70 linhas de sequenciamento que não pertenciam a nenhuma preocupação única de conexão: ele não abre nem derruba a conexão de streaming, apenas roda ao lado dela e reporta progresso.

O status é reportado por um callback, e não por um `StateFlow`, para que esta classe não tenha opinião sobre como a UI o observa — o serviço continua dono do `installStatusFlow`. Sequência:

1. Sai com o status "car APK not found" se `apkFile` não existir.
2. Emite "searching" (ou "connecting to `<ip>`" quando um IP explícito foi informado).
3. `resolveCarIp(explicitIp)` — injetado, por padrão faz uma checagem `CarIpLocator.probePortSync` no IP explícito seguida de `CarIpLocator.findCarAdb`.
4. `installer.connect(carIp)`; `null` significa que a central está esperando o prompt de autorização do ADB, então o status é **mantido** na tela (`keepStatus`) em vez de ser limpo após os 5s habituais.
5. `readInstalledVersion` → `compareVersions(my, installed) <= 0` → "already up-to-date" e para (a sessão ainda é fechada em um `finally`).
6. `pushAndInstall` → em caso de `"Success"`, roda `onReinstalled` (o serviço limpa seus hashes de ícone) e reporta a versão instalada; caso contrário, reporta a saída de falha aparada.
7. Uma exceção lançada é reportada e a sessão fechada; a limpeza automática de 5s roda a menos que `keepStatus` esteja ativo.

Todas as strings de status resolvem por `context.getString`, de forma null-safe: no setup de teste JVM puro, `getString` retorna null, então o fallback mantém a máquina de estados testável em unidade enquanto a produção sempre retorna texto real.

### CarInstaller (ponto de substituição para teste)

`internal interface CarInstaller<S>` — o ponto de substituição para teste da instalação ADB por trás do `CarInstallCoordinator`. Genérica sobre o tipo de "sessão" porque o coordinator só passa o handle por `connect → readVersion → pushAndInstall → close` e nunca chama seus métodos por conta própria. É isso que permite acionar toda a máquina de estados de instalação em um teste JVM com uma sessão fake e sem um `dadb.Dadb` real. A implementação de produção é `CarAppInstaller` com `S = Dadb`.

### AssetDeployer

Desempacota um asset embarcado para o disco, pulando a escrita quando a cópia em disco já corresponde. Extraído do `ConnectionService` (auditoria DRY-4), onde `extractAsset` e `ensureVdServerJarCurrent` implementavam cada um, de forma independente, a mesma sequência ler-asset → CRC32 → comparar → escrever, de modo que uma correção em um pulava silenciosamente o outro.

- `extract(assetName, target)` → `Result.Current(crc)` quando o CRC em disco já corresponde (sem escrita, sem necessidade de Shizuku), `Result.Written(crc, bytes)` após uma escrita nova, ou `Result.Failed(reason)` com prefixo `read:` / `write:`. `mkdirs()` precede a checagem de existência porque `/sdcard/DiLinkAuto` não existe em uma instalação nova; um alvo ilegível cai no fluxo normal e é sobrescrito em vez de falhar.
- `ensureCurrent(assetName, target)` → o CRC do jar agora em vigor: verificado como atual, recém-escrito, ou a última cópia **utilizável** em disco quando a atualização falhou (essa cópia ainda roda), ou `-1` quando não há arquivo utilizável e o chamador precisa decidir se aborta. Auditoria A-L15: os dois ramos de falha costumavam retornar `-1` incondicionalmente, o que tornava inalcançável o contrato de "valor utilizável" e transformava o ramo de fallback de todo chamador em código morto.
- As escritas usam arquivo temporário + rename, para que um crash no meio da escrita não deixe um jar truncado que passa em uma checagem de existência, com fallback de escrita direta quando o rename falha (alguns volumes com FUSE). O `finally` apaga o irmão `.tmp` incondicionalmente (auditoria A-L16): a escrita de fallback também pode lançar, e sem essa exclusão cada atualização falha deixava mais um `<target>.tmp` perdido no armazenamento compartilhado.
- Chamado de dois lugares: `deployAssets()` em `onCreate` (ambos os assets) e `ensureVdServerJarCurrent()` em cada handshake, porque o jar implantado sobrevive ao processo enquanto `deployAssets` roda uma única vez — sem a reverificação, o `app_process` pode carregar silenciosamente um motor obsoleto que ainda roda e ainda gera log, apenas executando código antigo.

### AssetSource (ponto de substituição para teste)

`internal interface AssetSource { fun read(assetName: String): ByteArray }` — o ponto de substituição para teste da fonte de bytes do `AssetDeployer`. Ele existe porque a implementação de produção envolve o `AssetManager` do Android, que é `final` e portanto não pode ser subclassado como dublê de teste. `AssetManagerAssetSource(assets)` é a forma de produção; os testes injetam um fake para que `extract` / `ensureCurrent` — o gate de atualidade de CRC do `vd-server.jar` em disco — se tornem testáveis em unidade em todos os ramos.

### CarIpLocator

Localiza o serviço ADB-over-WiFi da central (porta `5555`) para o fluxo "Instalar na central". Estratégias em ordem de latência: o IP remoto da conexão de controle ativa, enumeração de sub-rede, tabela ARP (`/proc/net/arp`), cache de vizinhos do `ip neigh` (sempre limpo em `finally`), varredura paralela da sub-rede `/24` (32 sondagens concorrentes, timeout de 150ms por sondagem) e o gateway do WiFi. `object` puro — sem dependência de `Service` — então é testável em unidade isoladamente. `probePortSync` é a variante síncrona de 500ms usada pelo caminho de instalação manual.

### VdDimensions

Matemática pura de viewport para a tela virtual do lado do celular criada em resposta a um `HandshakeRequest`. Fica em **protocol-core** (movida do app-client em 2026-10-09) para que as regras possam ser lidas e testadas como código JVM puro — o chamador passa o tamanho real em pixels físicos do celular, sem tipos do Android envolvidos.

- Largura/altura do VD partem do viewport da central, alinhadas a números pares (H.264 precisa de dimensões pares).
- **Escala anti-corte**: se o viewport da central for mais estreito que a largura física do celular (resolvida por orientação: a borda longa de um celular em retrato atua como largura para uma central em paisagem), o VD é escalado para igualar. Muitas ROMs chinesas (Meizu, Xiaomi) fixam a largura do IME na largura física da tela, e a largura do IME não segue a densidade do VD; um VD mais estreito corta o teclado horizontalmente independentemente do DPI negociado. A escala preserva a proporção da central enquanto satisfaz a exigência de largura do SO.
- **Somente métricas físicas reais**: o tamanho do celular precisa vir de `WindowManager.maximumWindowMetrics` (API 30+) / `Display.getRealMetrics` (API 29) — `resources.displayMetrics` reporta valores escalados por compatibilidade no modo screen-compat (Meizu 20 Inf: reporta 2992 para uma tela fisicamente de 3192px), o que deixava o IME 200px (~6.7%) mais largo que o VD em qualquer ajuste de DPI.
- **DPI**: um `dpiOverride` vindo da central (coagido para `[120, 480]` via `VdDeployArgs.coerceDpiOverride`) contorna o limite seguro para apps em retrato; caso contrário, `VideoConfig.calculateOptimalDpi` calibra automaticamente para uma largura lógica de ~380dp em paisagem.
- Retorna `(vdWidth, vdHeight, dpi)` para os argumentos de deploy do VD e a criação do `VirtualDisplay`.

### InstallStatus

Enum que classifica a string de status livre produzida pelo caminho de instalação. Centraliza o parse de `status.contains(...)` para que `OnboardingScreen`, `CarInstallCard` (`MainScreen`) e o checklist de etapas de instalação (`CarSetupInstallSection`) concordem sobre o que está em andamento, é terminal ou precisa de autenticação. Estados: `IDLE, SEARCHING, CONNECTING, CHECKING, PUSHING, INSTALLING, LAUNCHING, AUTH_NEEDED, DONE, ERROR`. Expõe `stageKeywords` (Searching / Connecting / Checking / Push / Install / Launching, cada um com um id de string-resource para que a UI o resolva com `stringResource`) para as UIs de progresso de etapa, além de `parse(status)`, `stageIndex(status)`, `isInProgress` e `isTerminal`.

**Limitação conhecida (C-6):** `parse` classifica casando **substrings em inglês** de uma string que `context.getString` produz. Em qualquer locale `values-*` que não seja inglês, todo status cai em `IDLE`, então o usuário não vê progresso nem erro algum, e as strings que o próprio celular emite em inglês — `"not reachable"`, `"Invalid IP: …"`, `"Install already in progress"` — classificam como `IDLE` em vez de `ERROR`. A correção é um enum de status estruturado (o estado `InstallStatus` mais um id de erro) propagado do `CarInstallCoordinator` até a UI, em vez de fazer parse de texto localizado. `InstallStatusTest` fixa o comportamento atual para que a lacuna fique visível em vez de silenciosa.

### PhoneDisplayRestorer

Restaura a tela física e o IME do celular após o teardown do servidor VD. O servidor VD desliga o painel físico diretamente via `DisplayControl.setDisplayPowerMode(0)` — um desligamento mais profundo do que o `PowerManager` consegue recuperar. Seu cleanup só roda se ele recebeu `CMD_STOP`; quando o canal de ciclo de vida quebra primeiro (ou o processo trava em um futex nativo), o `PhoneDisplayRestorer` é a rede de segurança. Quatro camadas, tentadas em ordem, cada uma independente:

1. **Shizuku**: `VdDeploy.gracefulStopCommand` (escreve o arquivo sentinela de parada) → polling aguardando o motor sair (12s; medido no MI 9, sentinela→desaparecimento do processo em 6.1~6.3s) → só em caso de timeout, `killCommandForce` (-9, localização via pgrep + kill por pid) → `cmd display power-reset 0` → restauração do IME via `ImeRestore.imeRestoreCommandLine`. O sentinela faz o motor seguir o **caminho normal de saída** e executar `cleanup()` por completo (libera o VD, restaura IME/letterbox/configurações de tela, religa o painel) — **não dá para usar SIGTERM**: o app_process do ART termina assim que o recebe e não roda shutdown hooks (medido no MI 9 em 2026-10-10). (`power-reset`, não `power-on`: este último subcomando não existe — `cmd display help` no Android 15 lista apenas `power-off` e `power-reset`.)
2. `PowerManager.wakeUp()` via reflection (despertar em nível de sistema).
3. Abre a `MainActivity` com `FLAG_TURN_SCREEN_ON` (o WindowManager dispara a ativação da tela).
4. Wake lock `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE`.

**Tem seu próprio `CoroutineScope`** — deliberadamente NÃO o escopo do Service:

- **Problema**: a versão anterior rodava no `serviceScope` do Service, que `ConnectionService.onDestroy()` cancela. Quando o usuário toca em "parar" (ou o sistema recupera o Service), a corrotina de restauração era cancelada *entre* o `pkill` e o `cmd display power-reset`, deixando o painel físico desligado sem nada para religá-lo. Observado nos logs: 3× "Force-waking physical display" seguidas de 3× "Shizuku display restore failed: Job was cancelled".
- **Correção**: `restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — um escopo com a vida do processo que nenhum ciclo de vida de Service consegue cancelar. `withContext(NonCancellable)` envolve a restauração, para que, uma vez que começamos a matar o motor, o power-reset também precise rodar. O `AtomicBoolean` `inFlight` colapsa requisições de restauração concorrentes em uma.

### AppVersion

Lê o label de versão de um app instalado como string, com fallback para `versionCode` em pares pré-semver. `label(context, preferCode, packageName)` retorna `versionName ?: versionCode.toString()`; `nameOrEmpty` é a variante de string vazia usada pelo card Sobre. Extraído de trechos duplicados no `ConnectionService` (checagem de versão da instalação na central) e no card sobre.

### Versioning

Parse e comparação de versões para a checagem de versão da instalação na central (pula a reinstalação quando a central já tem a versão embutida). Funções puras, para que o caminho de instalação na central e os testes possam comparar versões sem carregar nenhuma máquina de estados. Dois esquemas são suportados:

**Semântico (atual):** `0.19.0` (release), `0.19.0-dev` (dev, num 0), `0.19.1-dev-02` (dev, num 2). Release > dev da mesma base; builds dev são ordenados por `devNum`. Componentes não numéricos são coagidos para 0 por robustez contra entrada de rede malformada, e um componente ausente conta como 0. Qualquer coisa que não seja a forma reconhecida `-dev[-n]` (por exemplo, `-SNAPSHOT`) é interpretada como release com a string inteira como base.

**Data-hora (compatibilidade legada):** `20261117-2315` — `YYYYMMDD-HHMM`, casado por `^(\d{8})-(\d{4})$` e comparado como os dois segmentos numéricos `[YYYYMMDD, HHMM]`. Esse esquema veio do fork (issue #224) e é mantido apenas para que o celular possa comparar com builds antigos do fork que ainda estão instalados em uma central. Os releases do próprio repositório voltaram a ser semânticos, então qualquer versão de data tem precedência sobre qualquer versão semântica; um sufixo `-dev` também funciona em uma base de data. Motivo para mantê-lo: o parser semântico coage `20261117-2315` a um único segmento não numérico → 0, então **quaisquer duas versões de data comparavam iguais** e o `CarInstallCoordinator` pulava silenciosamente toda instalação do app da central enquanto reportava "already up to date".

**Risco conhecido (C-5):** `CarAppInstaller.readInstalledVersion` retorna `"0"` como sentinela de não instalado, e `compareVersions` coage componentes não numéricos para 0 — então um par cujo `versionName` real é `0`, `0.0` ou `0.0.0` compara **igual** ao sentinela e a instalação é pulada como "already up-to-date" na que deveria ter sido a primeira instalação. `-SNAPSHOT` também compara igual ao seu release correspondente. `VersioningTest` caracteriza os dois casos; a correção é um sentinela distinto de não instalado (ou um sinal separado de "not installed" vindo do `dumpsys`), em vez de uma versão que colide com uma real.

### ShizukuManager

Gerencia o ciclo de vida do Shizuku e fornece execução de comandos em nível de shell via `IShizukuService.newProcess` (UID 2000). `init(context)` registra os listeners de binder-received / binder-dead / permission-result. `checkPermission()` faz cache de `isAvailable`.

- `execAndWait(command)` roda `sh -c` com prazo de 30s (drena stdout + stderr concorrentemente para que uma escrita em stderr maior que 64KB não trave o pipe em deadlock) e retorna um `ExecResult(stdout, stderr)` — ou `null` quando o Shizuku está indisponível, o binder está morto ou a chamada falha. Os dois streams são mantidos **separados** (auditoria A-M6): a string única mesclada anterior fazia a sondagem do servidor VD decidir ALIVE/GONE a partir de texto que poderia igualmente ter vindo do stderr, então uma sondagem que apenas falhava ruidosamente era lida como "alive". A drenagem do stderr é sincronizada por um sentinela em uma fila limitada, para que o chamador nunca observe um buffer meio preenchido, e os FDs só são fechados depois disso.
- `execBackground(command)` é fire-and-forget (usado para `app_process`, para que o servidor sobreviva ao stream do shell) e **retorna `Boolean`** — `false` quando o Shizuku está indisponível, o binder está morto ou `newProcess` lançou exceção. Os chamadores devem tratar `false` como "o motor nunca foi lançado" (auditoria A-M7, ver *Status de deploy honesto* acima). O comando é passado a `sh -c` literalmente; o chamador decide o backgrounding (`setsid … &`), e remover um `&` final e adicionar outro costumava produzir `… & &`, um erro de sintaxe do shell.
- `probeVdServer()` roda o `VdDeploy.probeCommand` compartilhado (checagem de existência de `[P]ipelineServer` baseada em `pgrep -f` — sem precisar de `ps`/`pidof`; `pkill -0` é inutilizável, ver `VdDeploy`) e retorna `VdProbeResult.ALIVE / GONE / UNKNOWN`, decidindo **somente pelo stdout**. Este é o equivalente Shizuku da sondagem por código de saída da central/desktop; ele deliberadamente **não** implementa mais seu próprio loop de espera — as regras de convergência (duas GONE consecutivas) ficam no `vdAwaitExit` do protocol-core. `UNKNOWN` quando o Shizuku está indisponível ou a sondagem falha, o que a sequência de deploy trata como "aceitar a saída", para que um transporte morto nunca trave o deploy.

`execAndWait` é usado para `shellSync` e `execBackground` + `probeVdServer` para `launch`/`probe` pelo `VdDeployExecutor` em `ConnectionService.startVdServerViaShizuku`. O antigo helper de polling `waitForVdServerExit(timeoutMs)` e o helper de streaming de shell `copyToFile` não existem mais.

### FileLog

Logger em arquivo que contorna a filtragem do logcat do Android (o HyperOS filtra `Log.i/d` de apps que não são do sistema).

- Escreve em `/sdcard/DiLinkAuto/client.log`. Uma única thread escritora drena a `AsyncLogQueue` compartilhada (`LOG_QUEUE_CAPACITY = 2048`, auditoria A-05); o `SimpleDateFormat` só é tocado pela thread escritora.
- `loadEnabled(prefs)`: padrão LIGADO para debug/pré-release (`BuildConfig.DEBUG`), DESLIGADO para release. Uma vez que o usuário alterne explicitamente, essa escolha persiste via `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`. A regra vive em um único lugar — `isEnabledIn(prefs)` (sobre a função pura `resolveLogEnabled`) — e a `SettingsScreen` a lê para o valor inicial do switch, então a UI não pode mais mostrar LIGADO enquanto o logger está DESLIGADO (issue #72).
- `rotate()`: arquiva o log atual como `client-YYYYMMDD-HHmmss.log`, começa um novo e poda para 30 arquivados + 1 atual.
- `zipLogs()`: produz `dilinkauto-logs.zip` a partir de todos os arquivos `.log` em `/sdcard/DiLinkAuto/` e ainda inclui `/data/local/tmp/vd-server.log` quando ele existe — o log do próprio servidor VD faz parte de todo envio de Compartilhar logs.
- Também chama `android.util.Log.*` para a saída padrão do logcat.

O alternador fica em **Configurações → Debug**. Alterná-lo chama `ConnectionService.setLogEnabled`, que propaga a escolha para a central pela conexão de controle ativa via `DataMsg.LOG_TOGGLE` (payload de 1 byte), para que o `carLogEnabled` da central siga o ajuste do celular.

### PermissionChecker

Checagens de permissão em runtime para as telas de onboarding e configurações da UI do celular. Extraído de `OnboardingScreen` e `SettingsScreen`, que tinham cada checagem escrita inline três vezes (leitura inicial, rechecagem periódica e o switch `pollPermission` do onboarding). `hasAllFilesAccess()`, `hasBatteryExemption(context, pkg)`, `hasAccessibility(context, pkg)` — todos receptores simples de `Context`, para que possam ser chamados de qualquer `@Composable` ou escopo de corrotina.

## Testes

o app-client é coberto por testes JVM simples de JUnit4 + `kotlinx-coroutines-test` (`app-client/src/test/java/com/dilinkauto/client/service/`), executados com `./gradlew :app-client:testDebugUnitTest`. Sem Robolectric/Mockito/MockK — as dependências Android ou são injetadas através de um ponto de substituição para teste, ou tornadas inofensivas por `testOptions { unitTests.isReturnDefaultValues = true }` mais fakes escritos à mão (`ContextWrapper(null)`, lambdas pré-gravadas, um `AssetSource` / `CarInstaller` fake).

A suíte de primeira rodada (`VersioningTest`, `AppCategorizerTest`, `InstallStatusTest`) tem **38 métodos `@Test`** (19 + 8 + 11), conforme registrado em [../IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md); o detalhamento por arquivo abaixo é a contagem literal de métodos.

Depois, pontos de substituição para teste que preservam comportamento adicionaram mais cobertura em cima disso:

| Arquivo de teste | Cobertura |
|---|---|
| `VersioningTest` (19) | `parseVersion` / `compareVersions` — ordenação release vs dev, numeração dev, componentes malformados e não numéricos, axiomas de ordenação e os riscos do sentinela zero / `-SNAPSHOT` |
| `AppCategorizerTest` (8) | ordenação de categorias e as regras de palavras-chave, incluindo apps que caem em `OTHER` |
| `InstallStatusTest` (11) | classificação de `InstallStatus.parse` / `stageIndex`, `isInProgress` / `isTerminal` e o risco do texto localizado |
| `CarInstallCoordinatorTest` (8) | toda a máquina de estados de instalação através do ponto de substituição para teste `CarInstaller` — pular quando atualizado, push quando mais antigo, manter a mensagem de autenticação necessária, sessão sempre fechada, caminho de exceção |
| `CarAppInstallerParseVersionTest` (6) | `parseInstalledVersion` contra formatos reais de `dumpsys package` e o sentinela `"0"` |
| `AssetDeployerExtractTest` (12) | `extract` / `ensureCurrent` em todos os ramos via um `AssetSource` fake — escrita, salto por CRC, reescrita, mkdirs, falha de leitura/escrita, fallback A-L15 e o irmão `.tmp` nunca sobrevivendo a nenhum desfecho |
| `AssetCrcPolicyTest` (8) | política de atualidade de CRC, casos-limite de `usableExistingCrc` e a escrita com arquivo temporário + rename + fallback |
| `IconHashGateTest` (5) | `iconFor` / `reset` concorrentes sob uma tempestade de 8 threads (auditoria A-M11) |
| `AdbKeyUtilSelfHealTest` (5) | regeneração de par de chaves pela metade (auditoria A-L24) |
| `CarIpLocatorTest` (7) | estratégias de descoberta do ADB da central |
| `ConnectionServiceInitGuardTest` (4) | invariantes da guarda de inicialização |
| `AdversarialM2Test` (2) | características de entrada hostil |

Ver [../IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md) para o relatório do projeto completo (todos os seis módulos, as invariantes travadas entre módulos e os achados de auditoria que foram deliberadamente mantidos no lugar como testes de caracterização). Os achados do app-client registrados ali que este documento referencia por id: **C-5** (sentinela zero do Versioning), **C-6** (classificação por substring em inglês do InstallStatus) e a lacuna do `AppCategorizer`.

## Permissões necessárias

| Permissão | Finalidade |
|-----------|---------|
| `MANAGE_EXTERNAL_STORAGE` | Acesso a todos os arquivos para o deploy do `vd-server.jar` no sdcard em `/sdcard/DiLinkAuto/` |
| Serviço de acessibilidade (`InputInjectionService`) | Mantém o binding da tela virtual (`displayId` + dimensões do VD) para a conexão com a central. A injeção de toque em si acontece no processo do servidor VD via reflection no `InputManager`; o caminho do AccessibilityService não é usado para injeção. Sem monitoramento de eventos. |
| API do Shizuku (`moe.shizuku.manager.permission.API_V23`) | Acesso elevado de shell para deploy do servidor VD sem ADB (`app_process` como shell UID) |
| `QUERY_ALL_PACKAGES` | Grade de apps do launcher (descontinuada pela política do Play; sem problema para centrais multimídia instaladas por sideload) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Tipo foreground do `ConnectionService` |
| `WAKE_LOCK` | `PARTIAL_WAKE_LOCK` de 4h para que o streaming sobreviva com a tela desligada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Isenção de bateria para que o streaming continue ativo com a tela desligada |
| `POST_NOTIFICATIONS` | Notificação do serviço em primeiro plano (Android 13+) |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Sockets TCP, callback de rede inteligente, mDNS |

O `NotificationService` e a permissão `BIND_NOTIFICATION_LISTENER_SERVICE` correspondente foram **removidos** do manifest e do onboarding neste fork.

## Dependências

- Jetpack Compose + Material 3 (Compose BOM 2023.10.01)
- `material-icons-extended` (ainda presente em `build.gradle.kts`; a auditoria de performance recomendou removê-la, mas a dependência não foi removida)
- `kotlinx-coroutines`
- `dev.mobile:dadb:1.2.10` (ADB por WiFi para instalação do app da central)
- `dev.rikka.shizuku:api / aidl / provider:13.1.5`
- Módulo de protocolo (`:protocol`) — compartilhado com o app da central e o servidor VD (`:protocol` também puxa o `:protocol-core`, JVM puro)
- `app-server.apk` e `vd-server.jar` embutidos em `app-client/src/main/assets/` pelas tarefas `embedServerApk` e `buildVdServer` (apenas o APK do celular é instalado manualmente; o APK da central e o JAR do VD são enviados/embutidos automaticamente). `buildVdServer` faz dex de `:vd-server` + `:protocol` + `:protocol-core` + a stdlib do Kotlin e coroutines em um único JAR para `app_process`; `embedServerApk` se recusa a entregar um APK obsoleto quando o módulo do servidor não foi buildado.
- Teste: `junit:junit:4.13.2`, `kotlinx-coroutines-test:1.7.3` e `testOptions { unitTests.isReturnDefaultValues = true }`

Build: Kotlin 1.9.22, Android Gradle Plugin 8.2.2, `compileSdk = 34`, `minSdk = 29` (app-client), JDK 17, versão atual `0.19.0` (versionCode `60`). Builds de release exigem as variáveis de ambiente `RELEASE_KEYSTORE_PASSWORD` e `RELEASE_KEY_PASSWORD` — sem elas, um build de release falha na fase de configuração em vez de produzir silenciosamente um APK não assinado. Ver [./setup.md](./setup.md) para comandos de build, [../release.md](../release.md) para regras de assinatura e release, e [./architecture.md](./architecture.md) para o layout entre módulos.
