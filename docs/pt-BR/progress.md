# Rastreador de progresso

Estado atual: veja [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).

## Repositório canônico (2026-11)

- **Merge do fork de volta**: os 252 commits do fork `ID-VerNe/dilink-auto-android` foram incorporados ao repositório original como um único merge (`develop` e depois `main`), preservando o histórico. O delta trouxe `:protocol-core`, `:app-desktop`, a arquitetura de VD direto, áudio em 9640, o laço de reconexão/limpeza e mais de 1.200 casos de teste.
- **Versionamento de volta ao semver**: o esquema de data (`YYYYMMDD-HHMM`) do fork foi descartado; `app.versionName`/`app.versionCode` voltam a ser semver + contador monotônico, e as pipelines de release/pre-release voltam a casar `vX.Y.Z` e `vX.Y.Z-dev-N`. Primeiro release com a cadeia oficial: `v0.19.0`.
- **Documentação**: README, índice e guias de processo em português; os documentos técnicos de aprofundamento seguem em inglês (base do upstream).

## Marcos

Os marcos abaixo são o histórico do repositório upstream, preservado como contexto histórico. Eles descrevem o que entrou em `andersonlucasg3/dilink-auto-android` até `v0.18.0-dev` (2026-05-09). Tudo depois desse ponto é trabalho próprio deste fork — veja [Mudanças do fork (post v0.18.0-dev)](#mudanças-do-fork-post-v0180-dev) abaixo.

### v0.18.0-dev (2026-05-09)

- **PipelineServer — streaming de VD em thread única**: Substituiu o VirtualDisplayServer (1287 linhas, 9 threads, 2 filas) pelo PipelineServer (~620 linhas, 3 threads, 0 filas). Thread de pipeline única: frame clock → render GL → drenagem do encoder → escrita TCP. Elimina todas as filas entre threads e a sincronização `park()`/`unpark()`.
- **Frame clock sem drift**: Usa `System.nanoTime()` + `LockSupport.parkNanos()` para timings com precisão de microssegundos. Nada de `Object.wait()` — sempre agenda a partir da linha do tempo ideal, com um único reset de catch-up quando atrasado.
- **Bitrate adaptativo**: Começa em 5 Mbps CBR, mede o tempo de escrita TCP por frame. Reduz 25% em congestionamento (escrita >15ms), sobe 1 Mbps após 5s limpos. Faixa de 2-8 Mbps. Elimina a fila de escrita e as quedas de frame por backpressure.
- **Resolução de VD nativa da central**: VD criado nas dimensões do viewport da central (ex.: 1806×990) em vez do DPI do celular (3282×1800). Elimina o downscale de GPU — SurfaceScaler removido. Superfície da VD → SurfaceTexture → passthrough GL → encoder. 70% menos pixels processados.
- **Encoder: Main Profile, I-frame 1s, latência 0, sem B-frames**: Baseline → perfil Main por ~20% de compressão melhor. `KEY_OPERATING_RATE` e `KEY_MAX_B_FRAMES=0` para latência previsível.
- **Decoder: fila de 2 frames, prioridade de keyframe**: `ArrayBlockingQueue` mínima de 2 frames. Keyframes sempre aceitos (descartam P-frames). Sem lógica de catchup — os frames chegam na hora ou são descartados. `drainOutput()` antes de `feedBuffer()` para liberar primeiro os buffers do decoder.
- **Correções de estabilidade de conexão**: `connectToPhone` não mata a sessão ativa quando `handshakeDone`. As tentativas de gateway e os laços de mDNS param imediatamente no envio do handshake. O ADB via TCP reconecta quando o IP do celular muda. Auto-fallback para ADB via TCP quando o USB está indisponível. O laço de reconexão para após 3 falhas consecutivas sem ADB.
- **Modo Shizuku**: O celular faz o deploy do VD server via Shizuku quando disponível. A central espera por `VD_PORTS_BOUND` em vez de fazer o deploy via ADB.
- **Diagnóstico de crash**: `CarCrashHandler` salva a stack do crash + informações do dispositivo em arquivo, envia ao celular na próxima inicialização pelo canal de log TCP.
- **Log de informações do dispositivo**: Celular e central registram memória, CPU, tela e versão do Android no início da sessão.
- **Chave de logs**: Ajustes → Debug → chave de logs de diagnóstico. Quando desligada, zero escritas em disco. Propagada para a central pela mensagem de dados `LOG_TOGGLE`.
- **Canal de ciclo de vida**: O celular faz bind em `0.0.0.0:19647`, a central passa o IP do celular (não `127.0.0.1`) para acesso com UID de shell em ROMs restritivas.
- **Correção da comparação de versão**: Quando a central não envia `appVersionName`, compara `versionCode` dos dois lados em vez de misturar semver com inteiro.
- **Log do VD server em Compartilhar logs**: `zipLogs()` agora inclui `/sdcard/DiLinkAuto/vd-server.log` quando disponível.
- **TcpAdbConnection — ADB persistente de conexão única**: Substituiu a biblioteca Dadb (que abria uma nova conexão TCP por comando, causando ECONNREFUSED no Xiaomi/HyperOS) por um `TcpAdbConnection` próprio no módulo de protocolo. Mantém um único socket TCP para todos os comandos shell. Trata CNXN/AUTH/SIGNATURE/RSAPUBLICKEY com o formato ANDROID_PUBKEY correto e armazenamento de chave em PEM. Compartilhado entre os apps da central e do celular via módulo de protocolo.
- **Correção do contexto de textura EGL/GL**: Corrigido crash do PipelineServer em que a textura GL era criada em um contexto EGL temporário (depois destruído) e a thread do pipeline vinculava a textura 0 em vez do ID real da textura. Toda a inicialização EGL/GL foi movida para a thread do pipeline com sincronização adequada por CountDownLatch.
- **Correção do deploy do VD server**: O `app_process` era morto quando o processo do shell ADB terminava (shells não interativos matam jobs em background). Corrigido mantendo o stream do shell ADB aberto via método `shellBackground`, permitindo que o processo do VD server sobreviva.
- **Correção da restauração da tela do celular**: Reordenada a limpeza do PipelineServer para restaurar o display físico ANTES de destruir o processo shell persistente (era chamado `input keyevent 224` depois de o shell já ter sido fechado).
- **Padrões da chave de logs**: Logs de diagnóstico ficam ON por padrão em builds debug/pre-release (`BuildConfig.DEBUG`) e OFF em builds release. Uma vez que o usuário define a chave explicitamente, a escolha persiste independentemente do tipo de build.

### v0.17.0 (2026-05-02)

- **Reinício do decoder eliminado**: `MediaCodec.setOutputSurface()` substitui `stop()`+`start()` quando a superfície da TextureView muda. O MirrorContent permanece vivo na árvore Compose com alternância de `View.INVISIBLE` — zero perda de frame durante a navegação HOME↔APP↔NOTIFICAÇÕES. Eliminados ~3s de artefatos por queda de keyframe a cada transição de tela.
- **Codificação fixa em 30fps**: Reduzido de 60fps para taxa fixa de 30fps. O celular não superaquece mais durante o streaming. Carga de CPU/GPU caiu pela metade. Tráfego WiFi reduzido em ~40%.
- **Framerate adaptativo removido**: O modo estático inicial de 15fps causava taxa de frames quase nula durante a troca de apps. Revertido para 30fps fixos — transições determinísticas e suaves entre apps.
- **Documentação**: Todas as mudanças da v0.17.0 documentadas com traduções completas em 8 idiomas (en, pt-BR, ru, be, fr, kk, uk, uz). Card de suporte flutuante adicionado ao GitHub Pages. Seletor de idioma/locale para as datas de release.

### v0.17.0-dev-02 (2026-05-01)

- **Correção do superaquecimento do celular**: Eliminados padrões de CPU spin no pipeline de streaming que causavam superaquecimento durante o streaming. Esperas ocupadas com `delay(1)` substituídas por mecanismos bloqueantes/baseados em selector adequados.
- **AppIconCache movido para a central**: O cache de ícones do lado da central persiste os PNGs de origem (192x192) em disco. `prepareAll()` decodifica+redimensiona todos os ícones em thread de background antes de a grade aparecer; `getPrepared()` é uma consulta O(1) em ConcurrentHashMap com zero I/O durante a rolagem. Eliminadas a decodificação por tile e o crash na rolagem rápida.
- **AppTile simplificado**: Removidos o collect de StateFlow por tile, o DropdownMenu lazy e os efeitos de ripple no clique. Tiles leves com clickable em vez de combinedClickable para o toque principal.
- **Dedup da grade de apps**: Corrigido crash do LazyGrid deduplicando itens por packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Dispensar notificação por item e Limpar tudo**: A tela de notificações da central agora tem botões de dispensar por item com animações de slide-out e um botão "Limpar tudo" no cabeçalho. Novas mensagens de protocolo: `NOTIFICATION_CLEAR` (0x04) e `NOTIFICATION_CLEAR_ALL` (0x05) no canal de dados. Ícones por item a partir do payload `iconPng` do celular.
- **Ações de contexto de app**: Toque longo em tiles de app (launcher) e em apps recentes da barra de navegação mostra um menu dropdown com Desinstalar e Informações do app. Propagação da desinstalação via `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). Informações do app exibem um diálogo do lado da central com metadados `APP_INFO_DATA` (0x07) do celular. As ações do menu de contexto passam pelo VD server para acesso em nível de shell.
- **Correção do botão Voltar**: GO_BACK agora fecha as activities uma a uma antes de voltar ao menu inicial, usando rastreamento adequado da pilha via `dumpsys activity activities` no VD server.
- **DPI do Samsung DeX / Modo desktop** (revertido): A implementação inicial usando detecção por `UiModeManager.currentModeType` e 213dpi foi revertida na dev-02. Substituída pela abordagem de remoção da flag no nível da VD.

### v0.16.0 (2026-04-29)

- **Shizuku**: O app agora aparece na lista de apps autorizados do Shizuku (adicionado ShizukuProvider ContentProvider). O card em Ajustes abre o app Shizuku diretamente para gerenciamento de permissões.
- **Correção do exec no Shizuku**: Corrigido EBADF de ParcelFileDescriptors em transações binder fazendo dup dos FDs antes da leitura. `pm install` via Shizuku para auto-atualização silenciosa.
- **Modo Shizuku na central**: A conexão da central não fica mais travada em "Aguardando WiFi" quando o Shizuku está ativo — o laço de retry do IP do gateway não quebra mais após a primeira tentativa.
- **Verificação de versão passou a usar versionName**: As atualizações do app da central agora comparam strings de versionName (ciente de semver) em vez de inteiros de versionCode, permitindo atualizações de pré-release na central.
- **Endurecimento de segurança**: Removidas as permissões não utilizadas `RECORD_AUDIO` e `SYSTEM_ALERT_WINDOW`. O serviço de acessibilidade não escuta mais eventos `typeAllMask` (usa apenas `dispatchGesture`).
- **Desempenho da grade de apps**: Corrigido crash durante rolagem rápida no display da central. `GridCells.Adaptive` → `GridCells.Fixed` com colunas calculadas. Decodificação lazy de bitmap por tile com `inSampleSize=2` + `RGB_565`.
- **Estabilidade de rede**: O `NetworkCallback` do lado do celular agora é filtrado apenas para `TRANSPORT_WIFI`, ignorando oscilações de dados móveis 3G/4G.

### v0.15.0 (2026-04-28)

- **Auto-início do serviço no celular**: `ConnectionService` inicia automaticamente quando o app do celular é aberto (por exemplo, via ADB USB da central), removendo a necessidade de pressionar Iniciar manualmente.
- **A central não limpa mais a tarefa do celular**: Removido `--activity-clear-task` do lançamento do app do celular via ADB USB feito pela central.
- **Botão Compartilhar logs**: O botão "Compartilhar logs" na tela principal compacta todos os arquivos `*.log` de `/sdcard/DiLinkAuto/` e compartilha via share sheet do Android. `FileLog.zipLogs()` cria um `dilinkauto-logs.zip`.
- **Configuração do encoder**: Ajustado para 8 Mbps CBR Main profile para maior compatibilidade entre dispositivos. Backpressure adicionado (descarta não-keyframes quando a fila de escrita passa de 6 frames).
- **Catchup do VideoDecoder**: Quatro zonas graduadas de aceleração (normal, suave 1.5x, média 2x, agressiva 3x) para recuperação mais suave da latência.
- **Tradução para francês**: Adicionado francês (fr) aos 7 idiomas existentes (agora 8 no total).
- **Verificação de atualização ao abrir o app**: A verificação de auto-atualização roda imediatamente quando o app abre, com notificação de atualização e botão de verificar novamente.
- **Seletor de canal de distribuição**: Card em Ajustes para escolher entre releases estáveis e pré-releases dev para a auto-atualização.
- **Redesign do CarLaunchScreen**: Layout em duas colunas otimizado para displays largos de central.
- **Refatoração da UI do app do celular**: Tela principal reorganizada, bugs do fluxo de instalação corrigidos.
- **Melhorias no onboarding**: Pré-requisitos de configuração da central, UI de progresso de instalação aprimorada, card "Como conectar" melhorado.
- **Separação dos dois modos da UI da central**: Tela de lançamento (tela cheia, focada em conexão) e modo de streaming (barra de navegação + conteúdo). Transição suave quando a lista de apps chega.
- **Correções de artefatos de vídeo**: Drops inteligentes do decoder + catchup graduado + backpressure do encoder eliminam artefatos visuais.
- **Correções de entrada por toque**: Mapeamento de coordenadas correto no DPI fixo de 480dpi do VD server, envio incremental de toque em MOVE, correções no gesto de tap e no IP manual.
- **Restauração de tela e estabilidade de rede**: Restauração do display após desconexão USB, melhorias no callback de rede.
- **Internacionalização**: Todas as novas strings de UI traduzidas para 8 idiomas (en, pt-BR, ru, be, fr, kk, uk, uz).
- **Automação de CI/CD**: 6 workflows dedicados — validação (`build.yml`, `build-develop.yml`), pré-release em tags `-dev` (`build-pre-release.yml`), release em tags `vX.Y.Z` (`build-release.yml`), back-sync main→develop (`sync-main-to-develop.yml`) e agente autônomo de issues (`issue-agent.yml`).

### v0.14.0

- **Fonte de versão compartilhada**: Version code/name agora em gradle.properties — uma única edição para os dois apps.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: A lista de apps com 136+ ícones PNG ultrapassava 2MB, causando ProtocolException e quedas de conexão.
- **Correção da restauração do display**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` com `ACQUIRE_CAUSES_WAKEUP` restaura o display após desconexão USB, mesmo quando o VD server morre sem fazer limpeza.
- **Compatibilidade com POCO F5**: `FLAG_KEEP_SCREEN_ON` impede o bloqueio de tela durante o streaming. Entrada por toque confirmada funcionando no POCO F5 com Xiaomi 17 Pro Max.
- **Log de toque do lado da central**: Eventos de toque do MirrorScreen e sucesso do sendTouchEvent registrados para depuração.
- **Crédito do desenvolvedor em Sobre**: "Desenvolvido com ❤" com link do GitHub em todos os 7 idiomas.

### v0.13.1 — Primeiro release (2026-04-26)

- **Fluxo de onboarding**: Configuração guiada de permissões no primeiro lançamento (Todos os arquivos, Bateria, Acessibilidade, Notificações). Detecção automática das concessões, fallback de polling para configurações em estilo de diálogo.
- **Auto-atualização (UpdateManager)**: Consulta a API de Releases do GitHub, baixa o APK com progresso, instala pelo instalador de pacotes do sistema. Cooldown de 6 horas.
- **Reorganização da tela principal**: Tela principal focada no uso diário (guia de conexão, status, iniciar/parar, atualizações). Tela de ajustes com permissões, instalação na central, sobre e links de doação.
- **Instalação USB + WiFi na central**: Scanner de sub-rede em paralelo sonda todos os 254 IPs em busca do ADB da central. Combinado com descoberta por ARP/vizinhos/gateway. USB host tentado, mas a USB-A da central é somente host.
- **VD server agora é um módulo Gradle Kotlin**: Depende de :protocol e kotlinx-coroutines. Compartilha NioReader, FrameCodec.writeAll.
- **Desempenho**: Eliminada a alocação intermediária de ByteArray por frame no encoder. Capacidade inicial do NioReader em 256KB. isKeyFrame cacheado em FrameData.
- **Encoder**: CBR 8 Mbps, perfil Main, FPS configurável (padrão 30, a central solicita 60), PRIORITY 0 (tempo real). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms para conteúdo estático.
- **Doações**: Badges do GitHub Sponsors e Pix (Brasil) no README e nos ajustes do app.
- **Ícone vetorial adaptativo**: Silhueta de carro com sinais wireless, aplicado aos apps do celular e da central.
- **Internacionalização**: Recursos de string em inglês, português (pt-BR), russo (ru), bielorrusso (be), francês (fr), cazaque (kk), ucraniano (uk) e usbeque (uz).
- **Assinatura de release**: Keystore fixo com senha forte. O CI compila APKs de release assinados via GitHub Secrets.

### v0.13.0 — Correção da autenticação ADB via USB (2026-04-25)

Causa raiz encontrada e corrigida: `Signature.getInstance("SHA1withRSA")` aplica hash duplo no AUTH_TOKEN do ADB. O token de 20 bytes do ADB é um valor já com hash — o `RSA_sign(NID_sha1)` do AOSP o trata como já hasheado. Agora usa `NONEwithRSA` com prefixo ASN.1 de DigestInfo SHA-1 concatenado manualmente (assinatura pré-hasheada). "Sempre permitir" agora persiste corretamente — AUTH_SIGNATURE aceito na reconexão sem diálogo.

### v0.13.0 — Energia do display + Codificação de teclas (2026-04-25)

- **Energia do display via SurfaceControl (Android 14+)**: Carrega `DisplayControl` de `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader()` + biblioteca nativa `android_servers`. Faz fallback para `cmd display power-off/on` se a reflexão falhar.
- **Restauração de tela na desconexão**: O `VirtualDisplayClient.disconnect()` do celular executa `cmd display power-on 0` + `KEYCODE_WAKEUP` como rede de segurança quando o processo do VD server é morto antes da limpeza.
- **Reescrita da codificação de chave ADB**: `encodePublicKey()` reescrita seguindo exatamente a referência do AOSP — constantes fixas, `bigIntToLEPadded()` explícito, log do cabeçalho da struct.
- **Catchup do decoder**: Quando a fila ultrapassa `100ms * TARGET_FPS / 1000` frames (6 a 60fps), pula um a cada dois não-keyframes. A imagem continua se movendo a 2x de velocidade, recuperando-se gradualmente sem saltos.
- **Buffer de log da central**: 200 → 10.000 mensagens. Logs de autenticação do ADB USB agora sobrevivem até a conexão de controle drená-los.

### v0.12.5 — Estabilidade de conexão (2026-04-24)

- **Callback de rede inteligente**: `onLost` agora verifica se a rede perdida é a que carrega a conexão. Ignora quedas não relacionadas (ciclagem de dados móveis). Antes, qualquer perda de rede matava a sessão de streaming.
- **Diagnóstico de autenticação ADB USB**: Todo o fluxo de autenticação registrado via carLogSend → FileLog do celular. Revelou que AUTH_SIGNATURE é rejeitado todas as vezes (o adbd do celular não reconhece a chave armazenada). Em investigação.
- **Log de prévia da chave AUTH_RSAPUBLICKEY**: Registra os primeiros/últimos bytes da chave pública enviada ao celular para comparação com o formato ADB padrão.

### v0.12.0–v0.12.4 — Correções de bugs e polimento (2026-04-24)

- **Entrada por toque corrigida**: `handleInputFrame` despachado em `Dispatchers.IO` (era Main, causava `NetworkOnMainThreadException` na escrita no socket localhost)
- **Leitor de comandos NIO do VD server**: Corrigido laço infinito — o `break` dentro do switch só saía do switch, não do laço de parse. Agora usa `break parseLoop;` (break rotulado).
- **Dedup no lançamento de app**: Removido `--activity-clear-task` do `am start`. Apps existentes retomam em vez de reiniciar.
- **Bitrate**: Definido em 8 Mbps CBR (ajustado de 12 Mbps em releases posteriores por compatibilidade com dispositivos).
- **FPS configurável**: Campo `targetFps` adicionado ao HandshakeRequest. A central solicita 60fps. O VD server aceita FPS como argumento de linha de comando e o usa em `KEY_FRAME_RATE` e `FRAME_INTERVAL_MS` do encoder.
- **Barra de navegação**: 72dp → 76dp, ícones 32dp → 40dp, altura da linha 52dp → 60dp, texto 12sp → 14sp.
- **Ícones de app do launcher**: 40dp → 64dp, células da grade 140dp → 160dp, texto bodyMedium → bodyLarge.
- **Teclado da barra de busca**: `windowSoftInputMode="adjustNothing"` + `imePadding()` no TextField. O teclado não empurra a activity, só a barra de busca se move.
- **Notificações**: Dedup por ID (atualizações de progresso substituem a existente), suporte a barra de progresso (determinada + indeterminada), toque para abrir o app dono na VD + troca para a visão de espelhamento.
- **Apps recentes**: `pruneUnavailable()` remove apps que não estão mais no celular quando a lista de apps é atualizada.
- **Armazenamento da chave ADB USB**: Ordem de prioridade: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. A migração procura em todos os locais.
- **Fluxo de atualização**: O celular envia a mensagem `UPDATING_CAR`. A central mostra o status "Atualizando app da central..." e não reconecta.
- **Correção do crash no fluxo de atualização**: A central pula a conexão de vídeo/entrada quando a flag `updatingFromPhone` está definida.
- **logSink em VideoDecoder/UsbAdbConnection**: Logs do lado da central roteados via protocolo para o FileLog do celular.

### v0.11.0–v0.11.3 — Pipeline não bloqueante + Correção do encoder (2026-04-24)

- **VideoConfig**: Constantes compartilhadas `TARGET_FPS` e `FRAME_INTERVAL_MS`. Todas as esperas/pollings do caminho de vídeo limitadas ao intervalo de frame.
- **Redesenho periódico do SurfaceScaler**: Sempre chama `glDrawArrays + eglSwapBuffers` a cada intervalo de frame, mesmo quando não há frame novo da VD. Só chama `updateTexImage` quando há frame novo disponível. Alimenta o encoder em conteúdo estático.
- **NIO no VD server**: Substituídos `DataOutputStream/DataInputStream` bloqueantes por fila de escrita NIO (`ConcurrentLinkedQueue<ByteBuffer>`) + leitor de comandos baseado em Selector. Nenhum I/O bloqueante em qualquer lugar do pipeline.
- **Poll do encoder**: Timeout de `dequeueOutputBuffer` reduzido de 100ms para `FRAME_INTERVAL_MS` (16ms a 60fps).
- **Poll da fila do VideoDecoder**: 100ms → `FRAME_INTERVAL_MS`.
- **Timeout de select do NioReader**: 100ms → `FRAME_INTERVAL_MS` (configurável por parâmetro do construtor).
- **Park do writer de conexão**: 50ms → `FRAME_INTERVAL_MS`.
- **Laço de accept do VirtualDisplayClient**: 100ms → `FRAME_INTERVAL_MS`.
- **Início antecipado do VideoDecoder**: Inicia em uma SurfaceTexture offscreen quando o primeiro frame CONFIG chega (antes do MirrorScreen). O MirrorScreen reinicia o decoder com a superfície real da TextureView.
- **Fila do VideoDecoder**: 3 → 30 frames. Frames enfileirados mesmo antes de `start()` ser chamado.
- **FileLog**: Logger em arquivo (`/sdcard/DiLinkAuto/client.log`) contorna a filtragem de logcat do HyperOS. Rotação: arquiva como `client-YYYYMMDD-HHmmss.log`, mantém no máximo 10.

### v0.10.0 — Arquitetura de 3 conexões (2026-04-24)

Dividida a única conexão TCP multiplexada em 3 conexões dedicadas para eliminar a interferência entre canais que causava travamentos de vídeo:
- **Conexão de controle** (porta 9637): handshake, heartbeat, comandos de app, canal DATA
- **Conexão de vídeo** (porta 9638): apenas CONFIG + FRAME H.264 (celular → central)
- **Conexão de entrada** (porta 9639): apenas eventos de toque (central → celular)

Cada conexão tem sua própria instância de `Connection` com SocketChannel, NioReader e fila de escrita independentes. Heartbeat/watchdog apenas no controle.

### v0.9.2 — Build de diagnóstico (2026-04-23)

Logging abrangente para investigar o travamento de frames de vídeo após ~420 frames:
- **Laço de relay de vídeo**: registra antes/depois de readByte, tamanho do payload, msgTypes desconhecidos
- **NioReader**: registra quando channel.read() retorna 0 (a cada 100ª ocorrência, com o estado do buffer)
- **Writer da conexão**: registra a cada 60 frames de vídeo (contagem, tamanho, profundidade da fila, stalls), registra stalls de escrita
- **Correção do stall do writer**: `Thread.yield()` → `delay(1)` em writeBuffersToChannel — devolve a thread de IO ao pool de corrotinas em vez de fazer espera ocupada (achado da investigação: Thread.yield deixava a corrotina de relay de vídeo sem CPU)
- **Listeners de frame**: handlers de frames não-vídeo despachados de forma assíncrona (`scope.launch`) para que processamento pesado (decodificação da lista de apps) não bloqueie o leitor de drenar o TCP

### v0.9.0-v0.9.1 — Investigação de stall de escrita (2026-04-23)

Investigando a causa raiz do travamento de vídeo. Adicionados log de tamanho de buffer TCP e diagnóstico de stall de escrita.
- Confirmado que o buffer de envio TCP congela em 108.916 bytes restantes durante o envio da lista de apps
- Confirmado que os frames de vídeo em si não têm nenhum stall de escrita (queue=0 durante o vídeo)
- Confirmado que a chave ADB USB é estável (LOADED fp=c4e88a05) — a autenticação repetida é comportamento do HyperOS

### v0.8.4-v0.8.8 — Correções de bugs + Roteamento de logs (2026-04-23)

- **Roteamento de log da central**: Todas as chamadas `Log.*` do lado da central passam por `carLogSend()`, que envia via canal DATA `CAR_LOG` para o celular. O celular registra com a tag `CarLog` no logcat. Buffer de até 200 mensagens antes de a conexão ser estabelecida.
- **Lançamento do VD server revertido** para `shellNoWait` + `exec app_process` (abordagem da v0.6.2). O desacoplamento com `setsid`/`nohup` quebrou a conectividade com localhost. O VD server morre na desconexão USB mas se recupera ao replugar.
- **ServerSocket do VD server**: `startListening()` abre de forma síncrona, `waitForVDServer()` pula se já estiver esperando
- **Persistência da chave ADB USB**: `getExternalFilesDir` com verificação de gravabilidade + migração de `getFilesDir` + log de fingerprint
- **ClosedSelectorException**: verificações de `selector.isOpen` no writer e no NioReader
- **Correção de recursão infinita**: a substituição em massa de Log→carLogSend acabou atingindo o próprio carLogSend

### v0.8.3 — Polimento final + Correção da espera da VD + Diagnóstico da chave USB (2026-04-23)

Marco final + correções de bugs:
- **Guarda da espera da VD**: `waitForVDServer` pula se já estiver esperando — impede fechar/reabrir o ServerSocket quando a central envia múltiplos handshakes durante a reconexão (causa raiz de o VD server não conseguir conectar, confirmada via logcat do celular mostrando 4x `startListening` em 45s)
- **Diagnóstico da chave USB**: A central grava informações da chave (`LOADED`/`GENERATED` + fingerprint + caminho) em `/data/local/tmp/car-adb-key.log` no celular após conectar o ADB USB. Permite diagnosticar se a autenticação se repete porque a chave muda ou porque o celular não persiste o "Sempre permitir".
- **M3**: Multi-toque em lote — novo tipo de mensagem `TOUCH_MOVE_BATCH` (0x04) carrega todos os ponteiros em um frame. Eventos MOVE agrupados no lado da central, desagrupados no lado do celular. Reduz syscalls de N*60/s para 60/s em gestos de N dedos.
- **M10**: Estado de ejeção persistido em SharedPreferences — sobrevive a kill/restart do app da central. Limpo ao replugar o USB ou em ACTION_START.
- **L4**: NioReader usa ByteBuffer de heap em vez de direto — limpeza determinística pelo GC.

### v0.8.2 — Polimento + ServerSocket da VD + Persistência da chave USB (2026-04-23)

6 correções de polimento + 2 correções críticas:
- **Hotfix**: VirtualDisplayClient dividido em `startListening()` (bind síncrono) + `acceptConnection()` (espera assíncrona). O ServerSocket abre ANTES da resposta do handshake — corrige o VD server não conseguindo conectar em localhost:19637.
- **Persistência da chave USB**: O armazenamento da chave usa `getExternalFilesDir` com verificação de gravabilidade + migração de `getFilesDir`. Log de fingerprint a cada conexão para diagnosticar se a chave muda entre conexões.
- **M12**: `checkStackEmpty` usa `grep -E` mais simples em vez de parsing frágil de seção com `sed`
- **L2**: Buffers do socket localhost do VD server definidos em 256KB
- **L3**: O decoder usa `System.nanoTime()/1000` para timestamps (era incremento fixo de 33ms)
- **L5**: `UsbAdbConnection.readFile()` usa laço de leitura + try-with-resources
- **L6**: HandlerThread do SurfaceScaler encerrado adequadamente no stop
- **L7**: Ícones de app aumentados de 48x48 para 96x96px

### v0.8.1 — Toque + Desempenho do decoder + Hotfixes (2026-04-23)

4 otimizações de desempenho + 2 correções de crash:
- **M2**: `checkStackEmpty()` roda em thread de background — o leitor de comandos não fica mais bloqueado por 300ms+ após o Voltar
- **M4**: Pools pré-alocados de `PointerProperties[10]` + `PointerCoords[10]` no VD server — elimina a pressão de GC por toque
- **M5**: Fila de frames do decoder reduzida de 6 para 3 (limite de latência de 200ms → 100ms)
- **M6**: `cmd display power-off 0` roda em thread fire-and-forget — removeu o jitter do caminho de injeção de toque
- **Correção de crash**: `ClosedSelectorException` no writer de Connection + NioReader — adicionadas verificações de `selector.isOpen` e bloco catch. Corrida: `disconnect()` fecha selectors enquanto as corrotinas de leitura/escrita ainda executam.
- **Correção de bug**: reset de `usbConnecting` em `startConnection()` agora também protegido por `usbAdb == null` (segundo local da corrida de autenticação USB, o primeiro foi corrigido na v0.7.3)

### v0.8.0 — Desempenho do pipeline de I/O (2026-04-23)

3 otimizações de desempenho de I/O + hotfix:
- **H2**: Escritas gathering — `channel.write(ByteBuffer[])` junta cabeçalho+payload em uma única syscall/segmento TCP
- **H3**: O relay de vídeo aloca `ByteArray(size)` do tamanho exato diretamente — remove o `relayBuf` intermediário + `copyOf`
- **M1**: O VD server envolve o DataOutputStream em `BufferedOutputStream(65536)` — junta escritas pequenas no localhost
- **Hotfix**: `waitForVDServer()` chamado ANTES de enviar a resposta do handshake — garante que o ServerSocket em :19637 esteja aberto antes de a central fazer o deploy do VD server (regressão da v0.7.4: o sequenciamento colocava a espera da VD depois da resposta, fazendo o VD server falhar ao conectar)

### v0.7.4 — Fila de escrita + Sequenciamento de fluxo (2026-04-23)

Mudança na arquitetura de escrita + melhorias de fluxo:
- **Fila de escrita**: Substituído `synchronized(outputLock)` por `ConcurrentLinkedQueue` lock-free + corrotina de escrita dedicada. O writer usa `delay(1)` quando o buffer de envio TCP está cheio (devolve a thread de IO ao pool). Fim do bloqueio de outras corrotinas durante as escritas.
- **H10**: Handshake → auto-update → deploy da VD sequenciados. A auto-atualização pausa a inicialização, desconecta e espera a reconexão da central.
- **H11**: Mensagens de status progressivas na central: "Preparando..." → "Iniciando..." → "Aguardando stream de vídeo..."
- **H12**: A central mostra "Verifique o diálogo de autorização no celular" durante a conexão ADB USB
- **Correção de bug**: O VD server abre a activity home na VD após a criação — o encoder tem conteúdo imediatamente
- **Correção de bug**: `usbConnecting` só é resetado quando `usbAdb == null` — evita diálogos duplicados de autenticação USB-ADB

### v0.7.3 — Resiliência de rede + Correção de congelamento no HyperOS (2026-04-23)

Resiliência de rede + correção crítica do HyperOS descoberta por evidência de logcat:
- **Isenção de bateria**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — impede que o "greeze" do HyperOS congele o app cliente com a tela desligada. Causa raiz do bug "frames apenas durante o toque": o VD server (processo shell) produzia 960+ frames, mas o NioReader do app cliente era congelado pelo gerenciamento de energia do SO. Prompt exibido no primeiro lançamento.
- **C4/M11**: A trilha WiFi da central agora repete o IP do gateway a cada 3s (era uma única tentativa). Adicionado `ConnectivityManager.NetworkCallback` na central que re-dispara a trilha WiFi quando o WiFi fica disponível. Trata o hotspot habilitado após o plugue USB.
- **H9**: O celular desconecta proativamente na perda de rede em estado CONNECTED/STREAMING (antes: esperava 10s pelo timeout do heartbeat). `onLost` → `cleanupSession()` → o laço de escuta reinicia. `onAvailable` só reseta quando em WAITING (sem perturbar conexões ativas).
- **Correção de bug**: O VD server abre a activity home na VD após a criação (`am start --display <id> HOME`) — garante que o encoder tenha conteúdo imediatamente.
- **Correção de bug**: `usbConnecting` só é resetado quando `usbAdb == null` — evita diálogos duplicados de autenticação USB-ADB.

### v0.7.2 — Estabilidade do lado da central + Correção do Selector (2026-04-23)

5 correções de estabilidade + correção crítica do NioReader:
- **H1**: Polling com `delay(1)` substituído por **Selector** no NioReader — `selector.select(100)` acorda instantaneamente via epoll quando dados chegam. Corrige o vídeo não transmitindo (os frames só fluíam durante o toque na central Android 10 porque `delay(1)` levava 10-16ms). Adicionados `wakeup()`/`close()` para desligamento limpo a partir do disconnect.
- **H7**: `@Volatile` em `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — evita estado CONNECTING travado
- **H8**: `VideoDecoder.stop()` faz join da thread de feed (timeout de 2s) antes de `codec.stop()` — evita crash nativo
- **M7**: Removida a `cleanup()` dupla no VD server — evita IllegalStateException em liberação dupla
- **M8**: `cleanupSession()` reseta `_serviceState` para WAITING — evita UI obsoleta durante o atraso de reconexão
- **M9**: `onCreate()` limpa `activeConnection` estático e `_serviceState` — evita estado obsoleto no restart do serviço

### v0.7.1 — Correções críticas de bugs (2026-04-23)

6 correções críticas/altas da revisão abrangente:
- **C1**: Timeout de escrita de 5s em `writeAll()` — evita congelamento do sistema com o buffer de envio cheio
- **C3**: Flag de tentativa de auto-atualização — quebra o laço infinito de atualização/restart
- **C5**: Liberação automática do WakeLock em 4h — evita consumo de bateria em saída anormal
- **C6**: `@Volatile` no channel/reader do VirtualDisplayClient + escritas protegidas por timeout
- **H5**: try/catch em `Connection.connect()` — fecha o SocketChannel no cancelamento
- **H6**: Listener de desconexão envolto em try/catch — evita propagação de exceção

### v0.7.0 — NIO completo + Correção do serviço (2026-04-23)

Todas as operações de socket convertidas para NIO não bloqueante. O registro mDNS não bloqueia mais o laço de escuta. Version code lido em runtime via PackageManager.

**Mudanças:**
- **NioReader**: Novo leitor bufferizado não bloqueante para SocketChannel (polling com delay(1), cooperativo com corrotinas)
- **Connection.kt**: SocketChannel permanece não bloqueante o tempo todo — fim do configureBlocking(true) após connect/accept
- **FrameCodec.kt**: Adicionados os métodos NIO `readFrame(NioReader)` e `writeFrameToChannel(SocketChannel, Frame)`
- **VirtualDisplayClient.kt**: Leituras NIO (NioReader) + escritas ByteBuffer, o channel permanece não bloqueante
- **VirtualDisplayServer.java**: SocketChannel NIO para connect (finishConnect não bloqueante com retry)
- **ConnectionService.kt**: probePort() convertido para NIO; registro mDNS lançado em background com timeout de 5s (corrige o serviço não iniciar sem WiFi)
- **Version code**: Removida a constante `APP_VERSION_CODE` — os dois apps leem versionCode em runtime via `PackageManager.getPackageInfo()`

### v0.6.2 — Modelo de conexão paralela + Auto-atualização (2026-04-23)

Reescrita arquitetural importante: trilhas paralelas WiFi + USB, sockets NIO não bloqueantes, auto-atualização conduzida pelo celular, multi-toque via IInputManager.

**Funcionando:**
- **Máquina de estados de conexão paralela**: Descoberta WiFi + ADB USB rodam simultaneamente, a VD é implantada quando ambos estão prontos
- **Auto-atualização**: O celular detecta app da central desatualizado no handshake, envia a atualização via WiFi ADB (dadb)
- **O celular faz o deploy do JAR da VD**: Extraído para `/sdcard/DiLinkAuto/vd-server.jar` no lançamento (CRC32 verificado)
- **APK da central embutido no celular**: O sistema de build compila o APK da central nos assets do celular
- **Sockets NIO não bloqueantes**: Todo accept/connect usa `ServerSocketChannel`/`SocketChannel` — cancelamento instantâneo, sem EADDRINUSE
- **Entrada multi-toque**: Injeção direta de MotionEvent via `ServiceManager → IInputManager` (suporta tap, swipe, pinch)
- **Gerenciamento de energia da tela**: `cmd display power-off 0` durante o streaming, despertar por proximidade/elevação desabilitado, novo power-off limitado após injeção de toque
- **Recuperação da máquina de estados**: `connectionScope` cancela todas as corrotinas na desconexão, reconexão com backoff exponencial
- **Desconexão pelo usuário**: O botão Ejetar interrompe a reconexão (permanece IDLE)
- **Busca de apps**: Campo de busca na parte inferior da grade de apps, apps ordenados alfabeticamente
- **Painel de notificações**: Ícone de sino na barra de navegação com badge de contagem
- **Barra de navegação de 72dp**: Ícones (32dp) e texto (12sp) maiores para displays de central
- **Verificação de versão no handshake**: Campo `appVersionCode` em HandshakeRequest, `vdServerJarPath` em HandshakeResponse
- **Tratamento de mudança de rede**: O celular reseta o laço de escuta em mudanças de interface de rede (alternância de hotspot)
- **Codificação H.264**: 8 Mbps CBR, perfil High, modo de baixa latência
- **Timeout de handshake**: Timeout de 10s com cancelamento adequado (sem timeouts obsoletos)

**Mudanças de arquitetura em relação à v0.5.0:**
- Removido o polling de SSID de hotspot/auto-conexão WiFi da central (simplificado)
- UsbAdbConnection + AdbProtocol movidos para o módulo de protocolo (compartilhados pelos dois apps)
- O VD server conecta AO celular (conexão reversa) em vez de o celular conectar ao VD server
- O VD server encerra na desconexão do celular (one-shot, a central refaz o deploy se necessário)
- O celular extrai o JAR da VD para armazenamento compartilhado, a central lê o caminho do handshake

### v0.5.0 — ADB USB + Configuração automatizada (2026-04-22)

Mudança arquitetural importante: a **central** faz o deploy do VD server no celular via ADB USB. Depuração sem fio eliminada.

### v0.4.0 — VirtualDisplay escalado por GPU (2026-04-22)

Apps renderizam no DPI nativo do celular (480dpi), a GPU faz downscale para o viewport da central. Pipeline EGL/GLES do SurfaceScaler.

### v0.3.0 — Barra de navegação persistente (2026-04-21)

UI da central com barra de navegação esquerda sempre visível, TextureView, ícones reais de app.

### v0.2.0–v0.2.3 — Fundação da tela virtual (2026-04-21)

Criação da VD, self-ADB, servidor resiliente, suporte a múltiplos apps.

### v0.1.0–v0.1.1 — Implementação inicial (2026-04-21)

Projeto criado. Espelhamento de tela em emuladores.

---

## Mudanças do fork (post v0.18.0-dev)

O repositório upstream ficou parado após `v0.18.0-dev` (2026-05-09). Este fork (`ID-VerNe/dilink-auto-android`) então entregou um conjunto direcionado de mudanças voltado a celulares com ROM chinesa (Xiaomi HyperOS, Meizu etc.) pareados com centrais multimídia BYD DiLink. A central de referência é um BYD Qin PLUS DM-i 2023 Champion 55KM Leading — DiLink 4.0 de baixa especificação (Snapdragon 439, 8x Cortex-A53, Adreno 505, 4GB RAM, 16GB eMMC, 1280x800, WiFi apenas 2.4GHz, Android 9 / API 28, decodificação H.264 por hardware limitada a 1080p). Todo o trabalho está em `main` (modelo git-flow develop abandonado). Em 2026-11-17, os 252 commits deste fork foram mesclados de volta ao repositório original, que voltou ao fluxo de dois branches `develop`/`main`. As seções abaixo estão em ordem do mais recente para o mais antigo.

### v1.0.3 (2026-11-13)

- **Loop fechado completo de espelhamento de áudio (issues #67, #70, #72, #73, #79, #84)**:
  - **Porta 9640 e framing (S1)**: `AUDIO_PORT = 9640` em `Ports.kt`. Conexão TCP dedicada e independente do vídeo para evitar que IDRs de vídeo de 1MB causem head-of-line blocking nos frames de áudio de 341B. Framing binário via `AudioPayload` com `CONFIG` fora de banda (`csd-0`, AAC-LC 48kHz estéreo, 128kbps) e `FRAME` (timestamp pts + unidades de acesso AAC-LC cruas).
  - **Captura, codificação e pacing (S2)**: `AudioRecord` com `REMOTE_SUBMIX` captura o áudio do sistema renderizado para a VirtualDisplay. `AudioPipeline` + `AudioEncoderSelector` codificam em AAC-LC. Pacing constante de 46.875 fps (1024 amostras/frame a 48kHz = 21.333ms por frame) com injeção de frames de silêncio sintéticos durante pausas de áudio (`AudioSpec.kt`, `AudioPacing.kt`), evitando underruns do jitter buffer e acúmulo de atraso.
  - **Bind de porta e reprodução no receptor (S3)**: `PortAcceptor` no `vd-server` faz bind da porta 9640 (aceitando apenas o `CAR_HOST` fixado). `CarSessionCoordinator` conecta na 9640 e entrega o stream ao `AudioPlayer`. Jitter buffer interno de 150ms (configurável 60~200ms) com reprodução free-running do `AudioTrack`.
  - **Negociação bidirecional de recursos (S4)**: Negociação de `FEATURE_AUDIO` dos dois lados via `isAudioNegotiated(request, response)`. O cliente oferece um toggle de áudio persistido; a UI da central habilita ou esmaece dinamicamente o switch de áudio conforme a capacidade do celular.
  - **Cenário E2E e métricas de silêncio (S23)**: O cenário de teste end-to-end de espelhamento de áudio S23 entrou em `e2e-scenarios.md`. O log periódico no `AudioPlayer` acompanha as proporções de frames de silêncio (`silenceFrames`/`totalFrames`), fechando o contrato contra passes silenciosos falsos-verdes (issue #84).
- **Duas rodadas de desacoplamento arquitetural SRP/DRY (Refs #87, #92)**:
  - `:protocol-core` e `:protocol`: Extraídos `NioWriter` (escritas em SocketChannel com spin-blocking e timeout), `BackoffPolicy` (backoff exponencial parametrizado com jitter), `DecodeLivenessTracker` (acompanhamento unificado de vitalidade de decodificação e gestão de baseline), `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator` (estrito/frouxo) e `AppIconLimits` (dimensão máxima de ícone 512, bytes máximos 1MB). Limpeza dos métodos mortos de sync/push em `UsbAdbConnection` e escrita UTF-8 unificada em `Messages.kt`.
  - `:vd-server`: Extraídos `AsyncSocketSender` (escritor de socket não bloqueante unificado), `AudioEncoderSelector`, `ProcessWatchdog` (laço de watchdog separado do observador de stop-sentinel), `VideoEncoderSelector`, `PortAcceptor` (listener multi-porta com imposição de `CAR_HOST`), `ActivityDumpParser` (parsing de dumpsys window/display com checagem de limites) e `VdDisplayHealer`. `LifecycleWriter` migrado para `AsyncSocketSender`.
  - `:app-client`: `ConnectionService` desacoplado, extraindo `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository` e `SessionState`. `InputInjectionService` marcado formalmente como `@Deprecated` e seu caminho de chamada cortado.
  - `:app-server`: `CarConnectionService` substancialmente enxugado até 407 linhas, extraindo `CarSessionCoordinator` (gerencia o ciclo de vida dos 4 canais 9637/9638/9639/9640 e o re-handshake), `CarAdbTracker` (trilhas duais ADB USB/TCP) e `CarAppListReceiver` (barreira de segurança `AppIconLimits`).
  - `:app-desktop`: Alinhado com `AppIconLimits`, `DecodeLivenessTracker` e `BackoffPolicy`.
- **Correções de estabilidade e diagnóstico**:
  - **Throttling de pedidos de IDR (#75)**: Limiares de throttling de pedido de IDR unificados entre o servidor da central e o VD server, eliminando a zona morta de 500-999ms durante a recuperação de keyframe.
  - **Parsing da seção de display do dumpsys (#77)**: Adicionados limites superiores e verificação de cabeçalho de tarefa no parsing do dumpsys de display, eliminando duplicação de tarefas e vazamento de activities entre displays.

### Cobertura de testes de todos os módulos — 18 arquivos, 139 casos (commit 8157c86, 2026-10-10)

O repositório não tinha suíte de testes unitários significativa (`:protocol` não tinha sequer um source set de teste unitário). Uma passada preencheu a lacuna nos seis módulos Gradle, e a passada de emendas de testabilidade abaixo adicionou o resto. Os detalhes completos — matriz de cobertura, as invariantes entre módulos agora fixadas e os riscos deixados para decisão de produto — estão em [IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md).

| Módulo | Novos arquivos de teste | Objetos de produção fixados | Casos |
|---|---|---|---|
| `:protocol` | `AdbProtocolTest`, `AdbCryptoTest` | cabeçalho de frame do ADB / magic / checksum / round-trip / constantes; `bigIntToLEPadded`, `SHA1_DIGEST_INFO`, `fingerprint`, `signAuthToken`, `buildAuthReply` | 25 |
| `:protocol-core` | `VideoConfigTest`, `VdDeployArgsFormatTest`, `ImeRestoreGuardTest`, `H264NalParserRefIdcTest`, `AsyncLogQueueTest`, `VdDeployConstantsTest`, `WifiGatewayIpTest` | todos os ramos de `calculateOptimalDpi`; layout / scaling / bordas de bitrate de `VdDeployArgs.format`; `shouldRestoreIme`; recuperação de `nal_ref_idc` do H264; overflow / FIFO / suspend / close do `AsyncLogQueue`; comandos kill / stop / probe travados literalmente; ordem de bytes do IP do gateway | 34 |
| `:vd-server` | `AdaptiveBitrateEdgeTest`, `DisplayPowerControllerRestoreTest`, `TouchInjectorFallbackTest`, `CarCommandRouterDispatchTest` | bordas de limiar do bitrate adaptativo / truncamento / latch de sincronização; o fallback sentinela S-M10 em `restoreIme`; scaling da normalização de toque + fallback de shell; roteamento de `am` / `pm` / `input` com shell-quote + `SET_DISPLAY_POWER` | 28 |
| `:app-client` | `VersioningTest`, `AppCategorizerTest`, `InstallStatusTest` | parse / comparação de versão (bordas de sentinela + SNAPSHOT); palavras-chave ordenadas de categoria de app; classificação da string de status de instalação + índice de estágio (risco de localização incluído) | 38 |
| `:app-server` | `WifiGatewayProbeTest`, `CarCrashReportTest` | fallback de busca de gateway; contrato de formato do relatório de crash (cabeçalho / timestamp / thread / stack / Process State / Caused by / Suppressed) | 8 |
| `:app-desktop` | `HandshakeFactoryTest`, `DesktopConfigTest` | mapeamento de campos do handshake + `evenAlign` (piso 2, fallback negativo); os padrões de configuração devem vir das constantes do protocolo; `copy` só renomeia | 6 |
| **Total** | **18 arquivos** | | **139** |

- **A stack de testes é a convenção existente do projeto**: JUnit4 + kotlinx-coroutines-test. Sem Robolectric / Mockito / MockK. Os módulos Android se apoiam em `isReturnDefaultValues = true` mais fakes escritos à mão (`ContextWrapper(null)`, um `OutputStream` que captura, lambdas pré-montadas). `:protocol` ganhou `testOptions { unitTests.isReturnDefaultValues = true }` + `junit:junit:4.13.2` — antes não tinha nenhum source set de teste.
- **Verificação offline, todos os seis módulos**: `:protocol:test`, `:protocol-core:test`, `:vd-server:test`, `:app-client:testDebugUnitTest`, `:app-server:testDebugUnitTest`, `:app-desktop:test` — todos `BUILD SUCCESSFUL`.
- **A continuação das emendas adicionou mais 44 métodos de teste** em 7 arquivos (`TcpAdbConnectionTest`, `ConnectionHeartbeatTest`, `CarConnectionServicePolicyTest`, `AssetDeployerExtractTest`, `CarInstallCoordinatorTest`, `CarAppInstallerParseVersionTest`, `FeedGateVideoStreamPipeIntegrationTest`, mais adições em `AdbCryptoTest`), para **183 testes novos no total** (próxima seção). As contagens são anotações `@Test` literais.
- `:protocol-core` também ganha `ScreenMode` + `CaptureModePolicy` (a escada de auto-fallback VD → screencast) com testes, para o futuro receptor dual-mode — veja [FEATURE_DUAL_MODE_RECEIVER.md](../FEATURE_DUAL_MODE_RECEIVER.md).
- Quatro defeitos que a auditoria revelou estão **fixados por testes de caracterização em vez de corrigidos** — mudá-los é decisão de produto, veja [Problemas conhecidos](#problemas-conhecidos).

### Refatorações de testabilidade — emendas que preservam comportamento (2026-10-10)

Cada item extrai uma emenda sem alterar o comportamento em produção e depois fixa lógica que nunca tinha sido coberta. Os padrões ainda apontam para os valores reais anteriores, então os chamadores existentes não são afetados.

- **`1b6092c` — emendas de ADB + uma invariante de pipe entre módulos.** `AdbCrypto.encodePublicKey` passou de `android.util.Base64` para `java.util.Base64` (bytes idênticos, incluindo o único `=` final; a struct ANDROID_PUBKEY de 524 bytes agora é verificável na JVM). `TcpAdbConnection` extrai os métodos do companion `negotiateMaxPayload(cnxn)` / `readMessageFrom(InputStream)`, então a negociação de conexão e o tratamento de CRC / magic inválido são testáveis sem socket. Também `FeedGate` ↔ `VideoStreamPipe`: a política de descarte do mais antigo em um pipe cheio pode remover silenciosamente o CONFIG que o `FeedGate` acabou de entregar, e reconstruir o pipe precisa reexecutar o `initialConfig`.
- **`fa00527` — heartbeat / watchdog injetável.** `Connection.start()` recebe `heartbeatIntervalMs` / `heartbeatTimeoutMs` (padrão 3s / 10s), propagados para os laços de heartbeat e watchdog — o contrato de vivacidade (um HEARTBEAT emitido é auto-ACKed, o watchdog derruba um par que fica silencioso, um par vivo nunca é derrubado) agora é coberto em milissegundos.
- **`bbd6fe0` — emenda `AssetSource`.** `AssetDeployer` depende de um `AssetSource { fun read(name): ByteArray }` (um `AssetManager` é `final` e não pode ser estendido); a produção o envolve via `AssetManagerAssetSource(assets)`. Cobre todos os ramos de extract / `ensureCurrent`, o fallback A-L15 para o CRC do jar em disco utilizável (não `-1`), e o `.tmp` nunca sobrevivendo a nenhum desfecho.
- **`5d576e8` — emenda `CarInstaller`.** Nova interface `CarInstaller<S>` (connect / readInstalledVersion / pushAndInstall / close), genérica sobre o handle de sessão; `CarAppInstaller` implementa `CarInstaller<Dadb>` e é dono de `close(dadb)`. `CarInstallCoordinator<S>` depende da interface com `resolveCarIp` / `myVersionName` injetáveis, além de um resolvedor `statusString()` null-safe (o `getString` de produção é non-null, então o comportamento é idêntico — apenas o null do jar mockável é guardado). Toda a máquina de estados de instalação agora é testável por meio de um instalador fake.
- **`6d25390` — backoff de reconexão e orçamento de ícones como funções puras.** `CarConnectionService` extrai do companion `reconnectBackoffMs(failures)` (500ms → dobrando → teto de 8s com clamp de shift) e `iconBudgetAccepts(total, next)` contra o `MAX_APP_LIST_ICON_BYTES` real de 64MB; ambos os pontos de chamada delegam.

### Endurecimento de segurança — auditoria de todo o projeto (2026-10-09)

A camada de protocolo compartilhada e os três endpoints tratavam todo par de socket como confiável, e vários decoders podiam matar o processo com um frame de 6 bytes. As correções de endurecimento de segurança entraram como um único lote:

- **`a7d2b72` — o protocolo de rede contra pares hostis** (`P-*`, `S-02`). Todo `ByteBuffer.getX()` de tamanho fixo em `HandshakeRequest` / `HandshakeResponse` / `AppListMessage` / `MediaMetadata` / `AppInfoDataMessage` agora é protegido por `require()`, e o leitor de `Connection` captura `Throwable` (registra o motivo, desconecta) em vez de morrer — não há `CoroutineExceptionHandler` em lugar nenhum deste repositório, e o handler padrão do `app-server` termina em `killProcess`. `MAX_PAYLOAD_SIZE` 128MB → 16MB, com teto rígido no `NioReader` e `maybeShrink` de volta nas fronteiras de frame. O `parseHeader` do ADB rejeita `data_len` negativo / grande demais, e os laços de leitura USB e TCP finalmente verificam `data_crc`. Stalls do `NioReader` no meio de um frame falham após 10s (uma espera ociosa numa fronteira de frame deliberadamente *não* expira). `LaunchAppMessage` / `AppUninstalledMessage` validam o formato do nome de pacote na fronteira do decoder (`requirePackageName` / `requireComponentName` exportados para os pontos de chamada do vd-server). Também: ícones truncados agora falham na decodificação em vez de cachear um `iconHash` lixo sob uma chave real, ids desconhecidos de `AppCategory` / `MediaAction` lançam exceção em vez de coagir para um padrão vivo, `requireSupportedProtocolVersion()` finalmente é imposto, `sendMutex` fixa a ordem de enfileiramento sob backpressure (`limitedParallelism(1)` serializa o despacho, não a suspensão), prazo de conexão de 10s, flush gracioso de 500ms em EOF antes de o writer ser cancelado, e ids de IME com shell-quote + verificação de formato.
- **`8601fb1` — autenticação de canal e vazamentos de processo no vd-server** (`S-01`, `S-06..S-10`). Um novo argv `CAR_HOST` final (sanitizado em `VdDeployArgs`) é fixado por `acceptCarChannel`, que recusa qualquer outro par — 9638 / 9639 estavam bound em `0.0.0.0` sem nenhuma autenticação, rodando como UID de shell. `bindAndAccept` fecha ambos os sockets de escuta em um único `finally`, a thread do pipeline estaciona em fatias limitadas de `parkNanos` rechecando `running`, é daemon, e recebe sinal em todos os caminhos de falha (um park ilimitado sem ninguém para despertá-lo travava o processo para sempre). `cleanup()` libera o encoder e *depois* o `encoderSurface` (a superfície de entrada do MediaCodec nunca era liberada). O novo `PointerCap` limita ponteiros ativos (o mais antigo é removido, nunca o do frame atual), trunca `pts`, valida o intervalo de `pointerId` antes da indexação no pool, e substitui o `catch(_:Exception){}` silencioso que deixava um par enviando 11 DOWNs travar o toque pelo resto da sessão. fps é coagido via `VideoConfig.MIN/MAX`, as escritas do `PipeLog` são sincronizadas no stream, e o `SESSION_TIMEOUT_SENTINEL` compartilhado mapeia para 60000 em vez de ser escrito de volta.
- **`1f6836a` — entrada de deploy e vazamentos de adb no receptor Windows** (`D-*`). O receptor transformava campos não autenticados da resposta do handshake em um comando de deploy do adb, então um MITM podia escolher qual código rodava no celular via `CLASSPATH`: `AdbDeploy.checkAdbPort` aceita apenas `Ports.ADB_PORT`, `sanitizeJarPath` coloca na allowlist o local do vd-server.jar (deve ser igual a `VdDeploy.JAR_PATH` ou começar com `/sdcard/DiLinkAuto/`, `..` rejeitado), e ambas as barreiras rejeitam falhando a sessão com um motivo legível. `clampVdDimension` (`DimAlign.evenMin2` + `coerceIn(2, 4096)`) é a implementação compartilhada única — `DimAlign.even` sozinho mapeia negativos para ~2^31. `adbDeployer.close()` roda no caminho de fim de sessão sob a mesma guarda de geração, então um DISCONNECT / EOF do celular colhe o `adb.exe` retido (a âncora de vivacidade do motor do lado do dispositivo). As mutações de configuração passam por um único executor de ciclo de vida (restart e setKeepAwake competiam numa var simples, perdendo atualizações de DPI), as escritas de `config.json` são atômicas (`tmp` + `Files.move`), `deploy()` é uma função suspend verdadeira serializada por um `deployMutex`, um timeout de join da thread de decodificação é contado / registrado / escalado, rebuilds consecutivos do decoder fazem backoff 500ms → 8s atrás de um latch de escalada one-shot, ícones PNG têm o cabeçalho verificado antes da decodificação, processos com timeout são colhidos, e `adb.exe` precisa resolver para um caminho absoluto.
- **`5eae3e7` — linha de comando de deploy com shell-quote.** A linha de comando gerada no lado do dispositivo coloca entre aspas o caminho do jar e o caminho do log (um `vdServerJarPath` fornecido pelo par poderia, de outra forma, carregar metacaracteres de shell); aspas simples embutidas são escapadas para que um caminho nunca possa escapar.
- **`bc2634c` — gerações de sessão, ciclo de vida loopback, status de deploy honesto** (`A-*`, `S-01`). `cleanupSession` tem escopo de geração: cada accept gera um id de sessão e uma teardown só roda quando seu id ainda está ativo, então o `finally` tardio de um laço cancelado não pode mais consumir a guarda pertencente à próxima sessão — essa corrida deixava o `ServerSocket` 19647 novo aberto e o cliente VD antigo em streaming, ou seja, o modo de falha de VD vazada. O listener de ciclo de vida faz bind em `127.0.0.1` e recusa pares não-loopback, então a injeção de displayId não é mais alcançável pela LAN; o deploy via Shizuku passa `CAR_HOST` para o motor fixar seus accepts 9638 / 9639. A parte bloqueante do teardown (join do CMD_STOP, disconnect, restauração de painel / IME) vai para um `teardownScope` com tempo de vida do processo — antes ela bloqueava a thread principal até 1s por chamada, e o `serviceScope` era cancelado pelo `onDestroy` antes de a restauração poder rodar. `handleHandshake` roda no job de handshake em vez de travar todos os outros frames na corrotina do leitor. As linhas de `CAR_LOG` são limitadas a 8KB (truncadas, não descartadas) e a fila do `FileLog` é limitada em 2048 — um par podia empurrar um frame de 16MB em uma fila ilimitada e causar OOM no celular. `installCarApp` é single-flight e valida um IPv4 explícito, `CarIpLocator` é serializado atrás de um `Mutex` sobre um snapshot de sub-rede publicado, a decisão do Shizuku é capturada uma vez, e `usesCleartextTraffic="false"` é adicionado como defesa em profundidade.
- **`35d7821` — correção da auto-recuperação de tela preta** (`S-03..S-M11`). O caminho de recuperação chamava `videoDecoder.stop()` enquanto o `CarShell` mantém o layout de streaming montado em CONNECTING, então nada reiniciava o codec e uma recuperação disparada deixava a tela preta pelo resto da sessão; agora ele reseta o estado do detector de tela preta e deixa o codec ainda em execução consumir o IDR novo. O laço de retry de WiFi e o fluxo de descoberta mDNS são lançados em `trackScope()` (um filho de `connectionScope`) para que `connectionScope.cancel()` realmente os pare, com uma guarda `reHandshakeInFlight` impedindo que um callback que cai dentro da janela de ~1s de re-handshake feche justamente a conexão de controle que o carrega. `handleDisconnect` é protegido por um `teardownGuard` (antes ele reentrava em si mesmo formando 2-4 corrotinas de reconexão concorrentes). A barreira de auto-recuperação não exige mais `vdServerStarted` — nunca definido no modo Shizuku, então o caminho principal não tinha recuperação nenhuma — e usa em vez disso um orçamento de rebuild por sessão de 2. `discoveryJob` é cancelado antes de cada relançamento (~450 listeners NsdManager vazados por hora em um link instável). create / configure / start do MediaCodec saem da thread principal para um `decoderExecutor`. Dimensões de viewport fornecidas pelo par são limitadas a 2..4096 antes do `MediaFormat` (valores não positivos derrubavam o decoder; valores absurdos suprimiam o re-handshake de rotação para sempre). Os ícones de `APP_LIST` recebem um limite de bytes por ícone e um orçamento agregado — um único `APP_LIST` antes era capaz de causar OOM em uma central de 4GB.

### Receptor Windows — módulo `:app-desktop` (commit 22d3b7a, 2026-10-08)

Um receptor desktop que faz o papel da central sobre o mesmo protocolo de rede, para que um celular possa ser espelhado para qualquer um dos dois endpoints. O novo `:protocol-core` (Kotlin/JVM puro) contém as primitivas de protocolo independentes de plataforma, com `PlatformLog` e `ThreadPriority` como hooks substituíveis instalados pelos três pontos de entrada de processo; `:protocol` depende dele via `api`. O receptor em si é uma aplicação Compose Desktop: conexão de controle (9637) → handshake → `VD_PORTS_BOUND` → vídeo direto (9638) e entrada (9639) para o VD server, `FFmpegFrameGrabber` do JavaCV decodificando em um `Canvas` Swing reutilizado dentro de um `SwingPanel`, eventos de mouse mapeados pelo retângulo de conteúdo letterbox em `TouchEvent`s normalizados, uma grade de apps sobre `AppListMessage`, `config.json` em `%APPDATA%\DiLinkAuto\`, e um exe `jpackage --type app-image` com um JRE embutido. As sessões carregam uma geração, então uma mudança de DPI / decodificação por hardware troca a sessão inteira e reconstrói o painel de vídeo com `key(generation)`. O painel físico do celular pode ser alternado no meio da sessão pela nova mensagem de controle `SET_DISPLAY_POWER` (0x32) na porta de entrada — o app-client não muda. A decodificação por hardware D3D11VA cai para software por um watchdog, e a auto-recuperação de tela preta sustentada é herdada do `BlackScreenDetector` compartilhado.

- **Verificado no dispositivo (caminho Shizuku)**: handshake pela 9637, sequenciamento de `VD_PORTS_BOUND`, 24-25fps ao longo de 400+ frames contínuos, um espelhamento em tempo real (o hash do frame despejado acompanha o que quer que seja aberto no celular), e teardown correto (9638 / 9639 fechados, 9637 de volta à escuta, nenhum `app_process` sobrando). Dois bugs reais apareceram e foram corrigidos aqui: a lista de entrada do d8 da Fase 0 nunca ganhou o `protocol-core.jar`, então o `app_process` morria com `NoClassDefFoundError` e um `vd-server.log` completamente vazio, com o desktop travado em `WAITING_VD`; e a causa raiz do frame preto na tela de bloqueio acabou sendo o `FLAG_ALWAYS_UNLOCKED` que nunca era realmente definido (o código testava `0x40`, que é `SUPPORTS_TOUCH`) — as flags agora são `0x6c49 → 0x7c49`.
- **Auditado e corrigido**: os 15 achados `WIN-*` da auditoria do desktop foram todos fechados em `aaf5cd5` / `489944f` / `1f6836a`. O mais grave foi o WIN-02 — o processo local `adb shell` é a âncora de vivacidade do motor do lado do dispositivo, então fechar o deployer logo após `deploy()` matava o motor antes de ele dar bind em 9638 / 9639.
- **Ainda em aberto**: o caminho de deploy via `adb.exe` (usado quando o celular não tem Shizuku) **nunca foi verificado em um dispositivo real** — é o todo em `.plan/windows-client-plan.md`. Os extras restantes da Fase 5 (multi-monitor / tela cheia, decodificação por hardware e seu fallback, keep-awake, o switch manual da tela do celular) também ainda precisam de uma passada no dispositivo; o switch da tela do celular em particular precisa de uma reinstalação para que o APK carregue o `protocol-core` do 5e / 5f.

### v0.18.0-dev-13 — Correção do vazamento de VD, idempotência da limpeza, auto-recuperação de tela preta

Um VirtualDisplay vazado por ciclo de reconexão deixava a tela da central preta após alguns ciclos. Causa raiz: `pkill -9` pulava o shutdown hook da JVM, então `PipelineServer.cleanup()` nunca rodava — vazando a VD, mantendo o painel físico desligado e perdendo a configuração de `screen_off_timeout` (o snapshot capturava seu próprio sentinela `2147483647`). Corrigido em 12 arquivos:

1. **Parada de VD em dois estágios** (`VdDeploy.stopCommand`). SIGTERM → espera 1s → SIGKILL, como uma única linha de shell. Um cancelamento de corrotina não pode cair entre os dois sinais. *(Substituído em 2026-10-10: a ART nunca roda o shutdown hook em SIGTERM, então isso era equivalente a um kill forçado — substituído pelo protocolo de sentinela de pedido de parada, veja a linha do Rastreador de correções abaixo.)*
2. **Padrão de processo com colchetes** (`VdDeploy.PROCESS_PATTERN`). `[P]ipelineServer` impede que `pkill -f` case com a própria cmdline do shell wrapper. *(Fronteira medida em 2026-10-10: o truque dos colchetes é honrado pelo `pgrep -f`; o `pkill -f` ainda matava o shell wrapper invocador quando executado inline — os kills agora localizam com `pgrep` e sinalizam por PID, veja o Rastreador de correções.)*
3. **Sondas de vivacidade** (`VdDeploy.probeCommand` / `probeExitCodeCommand`). Verificação de existência com `pkill -0` — nenhum sinal entregue. *(Substituído em 2026-10-10: o toybox rejeita `pkill -0` com `bad -L '0'`, então a sonda sempre reportava GONE — ambas as sondas agora usam `pgrep -f`.)*
4. **Espera de saída da VD** (`ShizukuManager.waitForVdServerExit()` / `VdServerDeployer.waitForVdServerExit()`). Faz polling até o motor antigo sair antes de lançar o substituto. Dois motores vivos disputam o mesmo VirtualDisplay / DTA / binds 9638-9639 e o perdedor pula a `cleanup()` inteira.
5. **Idempotência da limpeza** (`ConnectionService.cleanupGuard`). Guarda `AtomicBoolean` impede que 7 pontos de chamada de `cleanupSession()` disparem repetidamente (observado: 1 desconexão → 9 linhas "Force-waking physical display"). `resetCleanupGuard()` antes de cada nova sessão.
6. **Escopo de PhoneDisplayRestorer com tempo de vida do processo**. Possui um `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — deliberadamente não o escopo do Service, que o `onDestroy()` cancela. O contexto `NonCancellable` garante que `cmd display power-reset` não possa ser pulado. O `AtomicBoolean` `inFlight` junta pedidos de restauração concorrentes em um.
7. **`stopVdServer()` retorna Boolean**. Era void — os chamadores não tinham como saber se o CMD_STOP teve sucesso ou caiu no kill por shell.
8. **Reordenação de `PipelineServer.cleanup()`**. Reset do estado global de janela/rotação PRIMEIRO (de todo o dispositivo, não por display), depois mover o app em primeiro plano, depois restaurar painel + IME, depois liberar threads/GL/encoder/VD. A ordem anterior tinha `virtualDisplay.release()` por último sem nada depois dela.
9. **`saveCurrentIme()` antes de `VirtualDisplayCreator.create()`**. O snapshot costumava rodar depois de `create()` já ter escrito `screen_off_timeout=2147483647`, então o caminho de restauração tratava o sentinela como "já é o sentinela, nada a fazer" e o timeout real do usuário era perdido para sempre.
10. **Divisão do `VirtualDisplayCreator`**. `create()` não chama mais `configureDisplayEnvironment()`. O novo `configureEnvironment()` precisa ser chamado após o snapshot.
11. **`DisplayPowerController.restoreSetting()`**. Valida antes de escrever: não em branco, não `null`/`undefined`, numérico > 0. Um snapshot falho gera um marcador; escrevê-lo de volta pioraria as coisas.
12. **`VideoDecoder.onSustainedBlackScreen`**. Dispara apenas após `BLACK_SCREEN_SUSTAIN_MS` (5000ms) de keyframes minúsculos contínuos. Uma rajada de 3 frames durante o aquecimento de app/VD é normal e não deve disparar um re-handshake — é justamente a tempestade de reconexões que vazava VDs em primeiro lugar. `resetBlackScreenState()` se rearma por sessão.
13. **`CarConnectionService.rehandshakeForBlackScreen()`**. Reconstrói a VD do lado do celular quando o stream está persistentemente preto. O latch `blackScreenRecoveryInFlight` garante no máximo um rebuild por sessão.
14. **`rehandshakeOnExistingControl()` extraído**. Compartilhado pelos caminhos de rotação e tela preta — derruba vídeo/entrada na conexão de controle existente sem restabelecer TCP.
15. **`scripts/verify-blackscreen-fix.sh`**. Verificação automatizada dos logs: checagem de vazamento de VD (contagem de starts vs contagem de cleanups), idempotência da limpeza (contagem de desconexões vs contagem de wakes), auto-recuperação de tela preta (detecção sustentada vs gatilho de rebuild), auditoria do caminho de parada.
16. **Automação de regressão de limpeza em reconexão/fechamento** (`scripts/e2e-reconnect-cleanup.sh` + `scripts/verify-reconnect-cleanup.sh`, 2026-10-10). O laço manual de verificação no dispositivo para as correções do caminho de parada agora é um comando: barreira de build (afirma 0 falhas de teste) → checagem de símbolos dex no `assets/vd-server.jar` do APK (`ShellSyncPoint` / `__DILINK_SYNC__` / `awaitIdle` / `pgrep -f` / `stop-request`) → instalação + relançamento → sessão de desktop isolada com `DILINK_DESKTOP_HOME` → 「应用并重连」 e fechamento de janela conduzidos pela UI → asserções ao vivo no dispositivo (sentinela de timeout na sessão, restauração para o baseline após a limpeza, motor ausente) → asserções offline de log (transições da sonda ALIVE→GONE, 「引擎已优雅退出」 sem 「强制 kill」, tempo decorrido < orçamento de 12s, eco de drenagem `__DILINK_SYNC__`, `Cleanup complete`, segundo handshake). `debug-logs/uitest/win_auto.py` ganhou um subcomando `close` (WM_CLOSE) como fallback de clique com ponteiro. A primeira execução real endureceu os dois lados do acoplamento de UI: (a) **todos os laços de polling agora usam tempo decorrido real** (prazo em `SECONDS`) em vez de `iteration-count × sleep` — esta máquina faz fork/exec em ~1s por chamada (varredura em tempo real do AV), o que transformou um orçamento nominal de 150s em 626s; (b) `win_auto.py focus` agora usa `AttachThreadInput` (a correção real para o travamento de primeiro plano do Windows, com `attached=True ok=True` verificado) porque o antigo ALT-tap + `SetForegroundWindow` era recusado e os cliques injetados estavam sendo engolidos; o E2E também ativa a janela clicando na barra de título e verifica que cada clique chegou (novo 「优雅停止设备侧引擎」 em `desktop.log`) com uma nova tentativa automática. **Para que as etapas de UI funcionem, o desktop deve ficar intocado enquanto o script roda.** Execução final após o endurecimento: **32 pass / 0 fail em 2m48s** (saídas graciosas em 6462/6499ms < orçamento de 12s; artefatos em `debug-logs/e2e/20261010-210104/`). Esta execução também serve como evidência de fechamento em dispositivo real para o desktop WIN-01/02/04 — `win_auto.py` / `poll_engine.py` foram forçados no repositório para que um clone novo possa rodar o E2E.

### Arquitetura — streaming de VD direto (sem relay do celular)

O VD Server faz bind de `9638` (vídeo) e `9639` (entrada) diretamente em `0.0.0.0`; a central fala com a VD sem o app do celular como intermediário. Antes eram 4 operações de socket + 2 trocas de contexto de processo por frame; agora são 2 operações de socket + 0 trocas de contexto. O celular é um orquestrador puro: handshake, ciclo de vida da VD (mensagem de controle `VD_PORTS_BOUND`), roteamento de log da central. `VirtualDisplayClient` simplificado para apenas ciclo de vida. O canal de ciclo de vida está em `localhost:19647` (a documentação upstream dizia `19637` — estava errada; `Discovery.LIFECYCLE_PORT = 19647`). Aproximadamente -356 linhas em 7 arquivos.

### Desempenho na DiLink 4.0 de baixa especificação (Snapdragon 439, nove correções)

1. **Dimensões de encode limitadas a 1920x1080** (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`). Desacopladas das dimensões da VD pelos argumentos `EW EH`; a VD permanece escalada para preservar a correção do crop de IME, o encoder usa as dimensões reportadas pela central limitadas a 1080p. Sem isso, celulares acima de 1080p disparam decodificação por software no 439 → fps de um dígito.
2. **Bitrate 8 Mbps → 4 Mbps CBR** (`PipelineServer.BITRATE = 4_000_000`). Fallback adaptativo mais apertado: piso de 1.5 Mbps, janela de recuperação de 2s, passos de 0.5 Mbps (era 2 Mbps / 5s / 1 Mbps).
3. **Framerate 30 → 24 fps** (`VideoConfig.TARGET_FPS = 24`). Orçamento por frame 33ms → 42ms, carga de WiFi/GPU/alocação ~20% menor.
4. **MirrorScreen TextureView → SurfaceView** (`MirrorScreen.kt`). Contorna a composição GL por frame do Adreno 505 (~2-5ms/frame em 1280x800). A guarda `outputSurfaceValid` evita renderizar para uma superfície destruída ao longo de esconder/mostrar da navegação; `switchSurface` reanexa o decoder com zero perda de keyframe.
5. **Prioridades de thread.** `THREAD_PRIORITY_URGENT_DISPLAY` no pipeline de encode (`PipelineServer.kt`), na thread de feed de decodificação (`VideoDecoder.kt`) e no leitor de drenagem do socket (`Connection.kt`); `THREAD_PRIORITY_BACKGROUND` no LifeWriter. O A53 não tem big cores.
6. **`carLogEnabled` padrão em `BuildConfig.DEBUG`** (`CarConnectionService.onCreate` → `logWriter.setEnabled(BuildConfig.DEBUG)`); `ConcurrentLinkedQueue.size()` (O(n)) substituído por `AtomicInteger` (`CarLogWriter.bufferCount`).
7. **`AppIconCache.clear()` na desconexão** libera ~5-9MB de heap + PNGs no eMMC; `prepareAll` paralelizado com `Semaphore(4)`, bitmaps intermediários reciclados.
8. **`Debug.getPss()` removido da inicialização** (chamada binder obsoleta, 100-500ms no A53).
9. **Wrapper `@Immutable AppTileData`** (`HomeScreen.kt`) exclui o `ByteArray` de ícone instável das entradas do `AppTile` para que a grade de ícones pule a recomposição na reatribuição de `appList`.

### Compatibilidade com a central em API 28

- **`MediaCodecInfo.isHardwareAccelerated()` (API 29+)** lançava `NoSuchMethodError` na central BYD API-28, matando o processo. Correção: removido o seletor especulativo de decodificador por hardware; `VideoDecoder.start()` chama `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` diretamente. `REGULAR_CODECS` lista hardware primeiro e seleciona `OMX.qcom.video.decoder.avc` na BYD.
- **`am display move-stack` (API 29+)** protegido por `SDK_INT >= 29` em `PipelineServer.moveTopApp`. Em níveis mais antigos o app em primeiro plano fica onde está em vez de uma falha silenciosa de shell se passando por sucesso.
- **`cmd display power-reset` / `power-off` (API 29+)** é o fallback de shell no `DisplayPowerController` (não existe subcomando `power-on`). Em API 26-28 uma falha de reflexão do `DisplayControl` significa que o painel físico não é restaurado — agora registrado via `logErr`, antes era silencioso.
- **`minSdk` elevado para 26** em `protocol` e `app-server` (era 24). Rearma a barreira de lint NewApi. `app-client` e `vd-server` continuam em 29.

### UI / interação

- **Barra de navegação redesenhada com três botões (Ejetar / Home / Voltar).** Botão de notificações, trilho de apps recentes, relógio e informações de rede todos removidos. O código morto que os sustentava foi excluído em quatro módulos: `NotificationScreen` e `RecentAppsState` do lado da central excluídos; `NotificationService` do lado do celular (entrada no manifest, etapa de onboarding, strings) excluído; tipos de mensagem `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` removidos do protocolo, de `ConnectionService`, de `VirtualDisplayClient` e de `PipelineServer`.
- **Fixação de apps.** Toque longo em um tile de app → Fixar no topo / Desafixar (SharedPreferences `dilinkauto_pinned` / `pinned_apps`). Apps fixados vão para o topo da grade.
- **Allowlist de apps.** A nova `AllowlistScreen` (no celular) permite ao usuário escolher quais apps do launcher chegam à central; os não selecionados são filtrados em `AppListBuilder.sendAppList` antes de o payload de rede ser montado. Pré-carregada com apps de mapa comuns (Baidu, AMap, Google Maps, Waze, Sogou, Tencent, Mapabc) na primeira execução. `ACTION_ALLOWLIST_UPDATED` dispara um reenvio ao vivo para que a grade da central atualize sem reconectar.
- **Escalonamento de apps em retrato.** DPI dinâmico via `VideoConfig.calculateOptimalDpi` — limita o DPI para que apps somente retrato (Amap, WeChat) tenham >=360dp de largura lógica em uma VD paisagem. IME restaurado automaticamente na desconexão da sessão (antes deixava o celular com o IME do linkpc). Índice de injeção de toque multi-ponteiro corrigido.
- **Substituição de DPI na inicialização do lado da central.** Campo `HandshakeRequest.dpiOverride` (0 = automático, [120, 480] = valor do usuário); SharedPreferences `startup_dpi` do lado da central; ligado tanto na conexão WiFi inicial quanto no re-handshake de rotação em meio ao stream via `HandshakeFactory.buildHandshakeRequest`. Entrada numérica no `ConnectionStatusCard` do `CarLaunchScreen`.
- **Cores tokenizadas** (`Color.Gray` / `0xFF888888` / `0xFFCCCCCC` / `0xFF757575` / `0xFFBBBBBB` → `onSurfaceVariant`; vermelhos de erro duplicados mesclados `0xFFFF5252` + `0xFFEF5350` → `error`). Rótulos com piso de 14sp (`labelSmall` = 14sp). "WiFi Direct Mode" → "WiFi ADB Mode" em `strings.xml` e em todas as variantes de locale. Texto do fluxo de desenvolvedor `adb tcpip 5555` na tela da central removido (risco de distração) e substituído por um apontamento para o app do celular.
- **Correção de tela preta na rotação.** `onCarViewportChanged` define o estado como CONNECTING e derruba a VD para redeploy, mas a barreira do layout de streaming usava `isConnected` (falso durante os ~2s de redeploy) → `CarShell` virava `CarLaunchScreen` → SurfaceView removida → a nova superfície não conseguia reiniciar o decoder. A barreira agora também aceita `state == CONNECTING && appList.isNotEmpty()` para que o overlay de espera de vídeo cubra a lacuna de redeploy.

### Estabilidade de sessão

- **Sessões ativas não são mortas por tentativas de reconexão.** `connectToPhone` verifica `handshakeDone && controlConnection?.isConnected == true` e pula se já há sessão viva. As tentativas de gateway WiFi e os laços de mDNS param no envio do handshake. O laço de reconexão para após 3 falhas consecutivas de ADB (`noAdbCount >= 3`).
- **O ADB via TCP reconecta quando o IP do celular muda.** O modo dev rastreia `lastAdbHost` e reconecta quando o IP do celular muda.
- **Auto-fallback para ADB via TCP quando o USB não está disponível.** `VdServerDeployer.deploy` cai para ADB via TCP usando o IP de host do celular, se conhecido.
- **Limite de reconexão 3.** `noAdbCount >= 3` interrompe o laço de reconexão; o usuário é instruído a plugar o celular na USB da central.

### Estabilização do PipelineServer (commit efced8b)

- **Correção do contexto de textura EGL/GL.** A textura GL era criada em um contexto EGL temporário (depois destruído) e a thread do pipeline vinculava a textura 0 (tela preta). Toda a inicialização EGL/GL foi movida para a thread do pipeline com sincronização por `CountDownLatch`.
- **ADB de conexão única.** `RemoteAdbController` simplificado para usar `TcpAdbConnection` (socket único, reutilizado para todos os comandos shell); dependência do Dadb removida do caminho central → celular (o Dadb ainda é usado pelo `CarAppInstaller` do celular → central).
- **Correção do deploy da VD.** O `app_process` era morto quando o processo do shell ADB terminava (shells não interativos matam jobs em background). Corrigido via `shellBackground` — mantém o stream do ADB aberto para que o processo do VD server sobreviva.
- **Ordem de restauração da tela do celular.** Reordenada a limpeza do `PipelineServer` para restaurar o display físico ANTES de destruir o processo shell persistente (era chamado `input keyevent 224` depois de o shell já ter sido fechado).
- **Chave de logs.** Ajustes → Debug → chave de logs de diagnóstico. Desligada = zero escritas em disco. Propagada para a central pela mensagem de dados `LOG_TOGGLE`, e reenviada após cada handshake para que uma reconexão não resete mais o lado da central para OFF (issue #72). A chave mostra o estado efetivo (builds release começam corretamente em OFF). Padrões: ON para debug/pre-release (`BuildConfig.DEBUG`), OFF para release; a escolha do usuário persiste via `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`.
- **Porta 19637 → 19647.** A porta do canal de ciclo de vida estava errada na documentação upstream. `Discovery.LIFECYCLE_PORT = 19647`.

### Refatoração do streaming direto de VD (commit 1c28d71 / fd89f0b)

O VD Server faz bind de `9638` (vídeo) e `9639` (entrada) diretamente; a central fala com a VD sem o app do celular como intermediário. O celular é um orquestrador puro: handshake, ciclo de vida da VD (`VD_PORTS_BOUND`), roteamento de log da central. `VirtualDisplayClient` simplificado para apenas ciclo de vida. A mensagem de controle `VD_PORTS_BOUND` avisa a central quando o VD server deu bind em 9638/9639, para que a central conecte vídeo/entrada diretamente sem uma corrida de conexão.

### PipelineServer — streaming em thread única (commit 1c28d71)

Thread de pipeline única: frame clock → render GL → drenagem do encoder → escrita TCP. 0 filas entre estágios. Controle de fluxo natural — um stall do TCP bloqueia o próximo `eglSwapBuffers`, desacelerando o encoder. Usa `System.nanoTime()` + `LockSupport.parkNanos()` para timing de 24fps sem drift. Bitrate adaptativo 2-8 Mbps (depois limitado a 4 Mbps para o Snapdragon 439 — veja desempenho na DiLink 4.0 acima). Total: 3 threads (Pipeline em `THREAD_PRIORITY_URGENT_DISPLAY`, TouchReader, Lifecycle/LifeWriter em `THREAD_PRIORITY_BACKGROUND`) contra 9 na versão anterior.

### Resolução de VD nativa da central, encoder, decoder

- **Resolução de VD nativa da central.** VD criado nas dimensões do viewport da central (ex.: 1280x800) em vez do DPI do celular. Elimina o downscale de GPU — SurfaceScaler removido. Superfície da VD → SurfaceTexture → passthrough GL → encoder.
- **Encoder: Main Profile, I-frame 1s, latência 0, sem B-frames.** `KEY_OPERATING_RATE` e `KEY_MAX_B_FRAMES=0` para latência previsível. CBR 4 Mbps.
- **Decoder: fila de 4 frames, prioridade de keyframe.** `ArrayBlockingQueue(4)`. Keyframes sempre aceitos (descartam P-frames). Sem lógica de catchup — os frames chegam na hora ou são descartados. `drainOutput()` antes de `feedBuffer()` para liberar primeiro os buffers do decoder. A ressincronização por IDR pós-flush pula P-frames até chegar um keyframe.

### Auto-atualização do app da central

O celular compara `appVersionName` (semver, via `Versioning.compareVersions`) com a versão reportada pela central; se a central não envia `appVersionName` (pares anteriores à 0.17.0), cai para `versionCode` dos dois lados. Em caso de divergência, envia `UPDATING_CAR` e empurra o `app-server.apk` embutido via `CarAppInstaller` (dadb por WiFi, timeout de conexão de 15s). A central mostra o status "Atualizando app da central..." e não reconecta.

### Log do VD server em Compartilhar logs

`FileLog.zipLogs()` inclui `/data/local/tmp/vd-server.log` (escrito pelo `PipeLog`) quando disponível, junto aos arquivos `client.log` rotacionados.

### TcpAdbConnection — ADB persistente de conexão única

`TcpAdbConnection` (módulo de protocolo) mantém um único socket TCP para todos os comandos shell. Trata CNXN/AUTH/SIGNATURE/RSAPUBLICKEY com o formato ANDROID_PUBKEY correto e armazenamento de chave em PEM. Substitui a biblioteca Dadb no caminho central → celular (o Dadb abria uma nova conexão TCP por comando, causando `ECONNREFUSED` no Xiaomi/HyperOS). Compartilhado entre os apps da central e do celular via módulo de protocolo.

### i18n

Chinês simplificado (`values-zh-rCN` / `values-zh`) adicionado aos dois apps `app-client` e `app-server`. Mais os 8 idiomas do upstream (en, pt-BR, ru, be, fr, kk, uk, uz).

### Correção da entrada de DPI e corrida do re-handshake (issue #1, commit 8e78671)

- **A faixa de coerção de DPI do lado da central agora corresponde ao [120, 480] do lado do celular** com 0 = Automático. Antes a central usava `coerceIn(0, 480)` enquanto o celular usava `coerceIn(120, 480)`, então valores de 1 a 119 eram silenciosamente elevados para 120 pelo celular enquanto o campo de entrada continuava mostrando o valor digitado.
- **Campo de entrada de DPI ligado diretamente a `startupDpi`** (fonte única da verdade) em vez de um estado `dpiText` separado com chave em `startupDpi`. O antigo `remember(startupDpi)` não recompunha quando o valor coagido era igual ao estado atual, então o campo se desalinhava do valor persistido.
- **Filtro de dígitos ASCII.** `Char.isDigit()` (aceita Nd Unicode: arábico-índico, devanagari) substituído por `it in '0'..'9'`. Dígitos não-ASCII passavam pelo filtro antigo mas eram rejeitados por `toIntOrNull()`, causando fallback silencioso para Automático enquanto o campo mostrava o caractere não-ASCII.
- **`onCarViewportChanged` cancela `connectionScope` e `connectJob` antes do teardown do re-handshake**, espelhando o padrão de `startConnection`. Antes, o laço de retry do gateway WiFi enxergava `state=CONNECTING` + `handshakeDone=false` + `wifiReady=false` (definidos pelo teardown) e chamava `connectToPhone`, cuja guarda `handshakeDone` agora era falsa, desconectando a conexão de controle viva no meio do re-handshake.

### Limpeza do hash de ícones na desconexão e teardown de conexão síncrono (commit 1c144ea)

- **`AppListBuilder.resetIconHashes()`** (limpa `lastSentIconHash`) chamado de `ConnectionService.cleanupSession` para que os ícones sejam reenviados à central na reconexão — corrige o fallback de ícone padrão após uma reconexão.
- **O teardown da conexão e os resets de estado executam de forma síncrona** em `CarConnectionService` antes de a corrotina de IO ser lançada, evitando condições de corrida durante a rotação em meio ao stream.
- `AppIconCache.clear()` na desconexão libera ~5-9MB de heap + PNGs no eMMC.

### Saúde do código — passada DRY/SRP (commits 628d80e + 56b18ae)

- **Constantes compartilhadas entre módulos.** `AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp` (portas 5555/9637/9638/9639/19647, chaves de prefs, componentes de `am start`, construtores de comando `pkill`/`app_process`, formatador de IP do gateway, faixa de DPI 120..480) agora vivem no módulo de protocolo.
- **Colaboradores extraídos.** `app-client`: `ApkInstaller`, `AppListBuilder`, `AppVersion`, `InstallStatus`, `PhoneDisplayRestorer`, `VdDimensions`, `Versioning`. `app-server`: `CarLogWriter`, `CarTouchSender`, `HandshakeFactory`, `VdServerDeployer`, `CarShell`, `ConnectionStatusScreen`, `NowPlayingBar`. `vd-server`: `GlPipeline`, `TouchInjector`, `DisplayPowerController`, `PipeLog`, `VirtualDisplayCreator`.
- **Consolidação DRY (commit 56b18ae).** `AdbCrypto` unifica a assinatura de auth Tcp/Usb, a codificação de pubkey e o fingerprint (eram ~150 linhas duplicadas com uma divergência silenciosa de SHA-1 vs SHA-256 e uma divergência de sufixo `\0` vs ` ` — o sufixo do TcpAdb foi corrigido para `" DiLinkAuto@car\0"` conforme a especificação do ADB). `VdDeploy.DeployPlan` monta o comando de lançamento do vd-server uma vez. `DeviceInfo.buildDeviceInfoBlock` compartilhado por `ConnectionService.logDeviceInfo` e `CarCrashHandler.buildDeviceInfo`. `ImeRestore` compartilhado entre `ConnectionService`, `PhoneDisplayRestorer`, `DisplayPowerController`. `AdbKeyUtil` compartilhado entre `CarAppInstaller` e `ApkInstaller`. `NetUtil.localIpv4Addresses` compartilhado entre `NetworkInfo` e `CarIpLocator`. `AppCategorizer` extrai as regras pacote→`AppCategory` do `AppListBuilder`. `H264NalParser` extrai a varredura de IDR (NAL tipo 5) do `VideoDecoder`. `CarIpLocator` mescla três sondagens de porta quase idênticas em um `probePortBlocking`. `FrameCodec` extrai `encodeHeaderInto` + `validatePayloadSize`. `NioReader` extrai `growIfNeeded`. `GlPipeline` nomeia `WRITE_TIMEOUT_NS` / `WRITE_BACKOFF_NS`. `CarTouchSender` extrai `checkConn(label)`. `PersistentNavBar` extrai `rememberNavBarSize` + `NavActionButtons`. `Connection` / `PipelineServer` nomeiam `SOCKET_BUF_BYTES` (era 262144 inline).
- **`CarConnectionService` 1237 → 1137 linhas** (commit 628d80e); depois para 1133 após a passada DRY (commit 56b18ae). Deploy/retry/fallback TCP da VD movidos para `VdServerDeployer`.

---

## Rastreador de correções

Revisão abrangente realizada em 2026-04-23 cobrindo desempenho, estabilidade e continuidade de fluxo.

### Fase 1 — Crítico (corrigir antes do próximo release)

| ID | Categoria | Constatação | Status |
|----|----------|---------|--------|
| C1 | Estabilidade/Desempenho | `writeAll()` gira indefinidamente com o buffer de envio cheio — sem timeout, segura o `outputLock`, bloqueia todos os remetentes. Risco de congelamento do sistema | **v0.7.1** |
| C2 | Fluxo | O VD server morre na desconexão USB (`shellNoWait` amarra o processo ao stream ADB). Reconexão completa de 5-15s | REVERTIDO — `setsid`/`nohup` quebrou o localhost. Usando `shellNoWait`+`exec` (abordagem da v0.6.2). Reconecta ao replugar. |
| C3 | Fluxo | A auto-atualização não tem quebra de laço — se `pm install` falhar silenciosamente, ciclo infinito de restart | **v0.7.1** |
| C4 | Fluxo | A trilha WiFi da central roda uma vez e desiste — hotspot habilitado após o plugue USB → travado para sempre | **v0.7.3** |
| C5 | Estabilidade | WakeLock adquirido sem timeout — consumo de bateria se o serviço for morto sem `onDestroy()` | **v0.7.1** |
| C6 | Estabilidade | Escrita não bloqueante com spin em `VirtualDisplayClient.touch()` + campo `channel` não volátil — corrida de dados | **v0.7.1** |

### Fase 2 — Alto (latência e estabilidade)

| ID | Categoria | Constatação | Status |
|----|----------|---------|--------|
| H1 | Desempenho | Polling com `delay(1)` no NIO adiciona piso de 1-4ms de latência por leitura + 1000 despertares/s em ociosidade. Usar Selector ou `runInterruptible` | **v0.7.2** |
| H2 | Desempenho | Duas syscalls por escrita de frame (cabeçalho de 6 bytes + payload). Usar `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Desempenho | `ByteArray.copyOf()` por frame no relay de vídeo (~30 alocações/s de 10-100KB). Passar offset+length | **v0.8.0** |
| H4 | Desempenho | `synchronized(outputLock)` serializa vídeo+toque+heartbeat. Escrita de keyframe bloqueia o toque por ~200ms | **v0.7.4** |
| H5 | Estabilidade | `Connection.connect()` vaza SocketChannel no cancelamento — sem try/finally | **v0.7.1** |
| H6 | Estabilidade | `disconnectListener` invocado de forma síncrona no CAS — possível deadlock | **v0.7.1** |
| H7 | Estabilidade | Flags de estado da central (`wifiReady`, `usbReady`) não voláteis — pode ficar preso em CONNECTING | **v0.7.2** |
| H8 | Estabilidade | `VideoDecoder.stop()` não faz join da thread de feed antes de `codec.stop()` — risco de crash nativo | **v0.7.2** |
| H9 | Fluxo | O callback de rede do celular ignora CONNECTED/STREAMING — alternar o hotspot causa 10s de frame congelado | **v0.7.3** |
| H10 | Fluxo | Handshake + auto-atualização + deploy da VD todos competem — o deployAssets pode não ter terminado, operações ADB concorrentes | **v0.7.4** |
| H11 | Fluxo | Nenhum feedback ao usuário durante os 5-12s de inicialização do VD server — a central mostra um spinner estático | **v0.7.4** |
| H12 | Fluxo | Diálogo de autenticação USB ADB na primeira vez no celular sem orientação na tela da central — timeout de 30s | **v0.7.4** |

### Fase 3 — Médio (problemas perceptíveis)

| ID | Categoria | Constatação | Status |
|----|----------|---------|--------|
| M1 | Desempenho | O VD server faz flush após cada frame no localhost — syscall desnecessária | **v0.8.0** |
| M2 | Desempenho | `checkStackEmpty()` bloqueia o leitor de comandos por 500ms — apagão de toque após o Voltar | **v0.8.1** |
| M3 | Desempenho | Multi-toque envia N frames separados por MOVE — deveria agrupar todos os ponteiros | **v0.8.3** |
| M4 | Desempenho | PointerProperties/Coords de MotionEvent alocados por injeção — colocar em pool | **v0.8.1** |
| M5 | Desempenho | Fila de frames do decoder com 6 de profundidade (200ms) — reduzir para 2-3 para menor latência | **v0.8.1** |
| M6 | Desempenho | ~~`execFast("cmd display power-off 0")` na thread de toque — mover para timer~~ — `execFast` removido (Fase B6), a energia do display agora é gerenciada via `execShell` em uma thread dedicada | **v0.8.1** |
| M7 | Estabilidade | `cleanup()` dupla no VD server — finally de handleClient + run ambos chamam | **v0.7.2** |
| M8 | Estabilidade | `cleanupSession()` não reseta `_serviceState` — UI obsoleta durante o atraso | **v0.7.2** |
| M9 | Estabilidade | MutableStateFlow estático no companion sobrevive a restarts do serviço — activeConnection obsoleto | **v0.7.2** |
| M10 | Fluxo | Desconexão do usuário (ejetar) não persiste — a central reconecta após kill do processo | **v0.8.3** |
| M11 | Fluxo | mDNS da central + sonda de IP do gateway em tentativa única — precisa de retry periódico | **v0.7.3** |
| M12 | Fluxo | Parsing de `dumpsys activity` em checkStackEmpty frágil entre versões do Android | **v0.8.2** |

### Fase 4 — Baixo (polimento)

| ID | Categoria | Constatação | Status |
|----|----------|---------|--------|
| L1 | Desempenho | `TouchEvent.encode()` aloca ByteArray de 25 bytes por evento — não dá para usar pool com fila de escrita assíncrona | WONTFIX |
| L2 | Desempenho | Socket localhost do VD server sem configuração de tamanho de buffer de envio/recepção | **v0.8.2** |
| L3 | Desempenho | O decoder de vídeo usa timestamp fixo de 33.333us — deveria usar relógio de parede | **v0.8.2** |
| L4 | Estabilidade | ByteBuffer direto do NioReader não é liberado de forma determinística | **v0.8.3** |
| L5 | Estabilidade | `UsbAdbConnection.readFile()` não garante leitura completa | **v0.8.2** |
| L6 | Estabilidade | HandlerThread do SurfaceScaler nunca é encerrado | **v0.8.2** |
| L7 | Fluxo | Ícones de app 48x48px — borrados em displays de central, precisam de 96-128px | **v0.8.2** |

### Fase 5 — Endurecimento de segurança (auditoria de todo o projeto, 2026-10-09)

A auditoria de segurança de todo o projeto em 2026-10-09 revisou o código-base tratando todo par de socket como hostil. Seus ids `P-` / `S-` / `A-` / `D-` são um **esquema de numeração separado** do `C1..L7` de 2026-04-23 acima. Todos os achados listados aqui estão fechados.

| ID | Categoria | Constatação | Status |
|----|----------|---------|--------|
| P-01 | Segurança/Estabilidade | Os decoders usavam `getX()` de tamanho fixo cru em payloads truncados; um listener de frame que lançava escapava da corrotina do leitor e matava o processo (não há `CoroutineExceptionHandler` no repositório, e o handler padrão do `app-server` termina em `killProcess`) | **a7d2b72** |
| P-02 | Segurança/DoS | `MAX_PAYLOAD_SIZE` 128MB — um cabeçalho de 7 bytes forçava ~256MB de alocação no celular e no vd-server com UID de shell; o `NioReader` só crescia | **a7d2b72** — teto de 16MB, limite rígido, `maybeShrink` nas fronteiras de frame |
| P-03 | Segurança | O `parseHeader` do ADB aceitava `data_len` negativo / grande demais (um `OutOfMemoryError` é um `Error`, então escapava do catch), e `data_crc` era parseado mas nunca verificado | **a7d2b72** |
| P-04 | Estabilidade | O `NioReader` travava para sempre no meio de um frame com um par half-open | **a7d2b72** — prazo de 10s apenas nas fronteiras de frame; um link de toque ocioso deliberadamente não expira |
| S-01 | Segurança | O vd-server fazia bind de 9638 / 9639 em `0.0.0.0` sem nenhuma autenticação, e o listener de ciclo de vida do celular era legível pelo mundo (injeção de displayId alcançável pela LAN) | **8601fb1 + bc2634c + a7d2b72** — fixação de argv `CAR_HOST` sanitizado, bind em loopback, pares não-loopback recusados |
| S-02 | Segurança | Nomes de pacote fornecidos pelo par eram interpolados em linhas de shell `am start` / `pm uninstall` rodando como UID de shell | **a7d2b72 + 8601fb1** — validação de formato no decoder mais `shellQuote` em cada ponto de chamada. O try/catch do `dispatch` ainda falta, veja [Problemas conhecidos](#problemas-conhecidos) |
| S-03 | Fluxo | A auto-recuperação de tela preta chamava `videoDecoder.stop()`, então o codec nunca reiniciava e uma recuperação disparada deixava a tela preta pelo resto da sessão | **35d7821** |
| S-04 | Estabilidade | `connectionScope.cancel()` não parava os laços de retry de WiFi / mDNS, e um callback que caía dentro da janela de ~1s de re-handshake fechava justamente a conexão de controle que o carregava | **35d7821** — laços em `trackScope()` + guarda `reHandshakeInFlight` |
| S-05 | Estabilidade | `handleDisconnect` reentrava em si mesmo formando 2-4 corrotinas de reconexão concorrentes | **35d7821** — `teardownGuard` + listeners limpos no caminho de teardown intencional |
| S-06 | Estabilidade | Uma falha em `bindAndAccept` estacionava a thread do pipeline sem ninguém para despertá-la — o processo travava para sempre | **8601fb1** — sockets fechados em um único `finally`, `parkNanos` limitado rechecando `running`, thread daemon, `signalPipelineStop` em todo caminho de falha |
| S-09 | Estabilidade | O TouchInjector aceitava ponteiros ilimitados, indexava o pool com um `pointerId` não validado e engolia a falha em silêncio — um par enviando 11 DOWNs travava o toque pelo resto da sessão | **8601fb1** — novo `PointerCap` (remove o mais antigo, trunca `pts`, valida intervalo, `logErr` em vez de silêncio) |
| A-01 | Estabilidade | `cleanupGuard` competia entre gerações de sessão — o teardown tardio de um laço cancelado consumia a guarda da sessão seguinte, vazando o socket 19647 e a VD | **bc2634c** — gerações de sessão (`AtomicInteger` + checagem de id) |
| A-04 | Desempenho/Estabilidade | `cleanupSession` bloqueava a thread principal até 1s por chamada e rodava a restauração em um escopo que o `onDestroy()` cancela | **bc2634c** — `teardownScope` com tempo de vida do processo |
| A-05 | Segurança/DoS | Fila de log ilimitada — um par podia empurrar um frame de 16MB em `CAR_LOG` e causar OOM no celular | **bc2634c** — truncamento de linha em 8KB + fila limitada (2048) |
| D-01 | Segurança | O receptor Windows transformava campos não autenticados da resposta do handshake em um comando de deploy do adb — um MITM podia escolher qual código rodava no celular via `CLASSPATH` | **1f6836a** — `checkAdbPort` (somente 5555) + allowlist `sanitizeJarPath` + `clampVdDimension`; a rejeição falha a sessão com um motivo legível |
| D-03 | Estabilidade | Uma desconexão normal deixava o `adb.exe` retido — a âncora de vivacidade do motor do dispositivo — rodando para sempre | **1f6836a** — `adbDeployer.close()` no caminho de fim de sessão sob a guarda de geração |
| — | Segurança | A linha de comando de deploy gerada no lado do dispositivo interpolava um `vdServerJarPath` fornecido pelo par sem aspas | **5eae3e7** |

---

## Problemas conhecidos

| Problema | Impacto | Status |
|-------|--------|--------|
| Diálogo de autenticação ADB USB ao replugar | O celular perguntava "Permitir depuração USB?" todas as vezes | **CORRIGIDO v0.13.1** — era hash duplo do AUTH_TOKEN com SHA1withRSA. Agora usa NONEwithRSA + DigestInfo SHA-1 pré-hasheado. "Sempre permitir" persiste. |
| Vazamento de VD na reconexão | Cada reconexão vazava um VirtualDisplay; o painel permanecia desligado | **CORRIGIDO v0.18.0-dev-13**, **recorrigido em 2026-10-10** — os mecanismos da dev-13 se apoiavam em duas premissas falsas em dispositivos reais: (1) o `app_process` da ART **não** roda o shutdown hook da JVM em SIGTERM, então a "parada em dois estágios" era um kill forçado puro e `cleanup()` nunca rodava; a parada agora é um **arquivo sentinela de pedido de parada** que o watchdog do motor consome (caminho de saída normal → limpeza completa), com `killCommandForce` apenas como fallback de timeout de 10s. (2) o toybox rejeita `pkill -0`, então ambas as sondas de vivacidade reportavam GONE em silêncio e a "espera de saída da VD" nunca esperava; as sondas agora usam `pgrep -f`. Verificado end-to-end no MI 9 (2026-10-10) |
| Limpeza não idempotente | 1 desconexão disparava 9 chamadas de restauração duplicadas | **CORRIGIDO v0.18.0-dev-13** — `cleanupGuard` `AtomicBoolean` |
| Timeout de tela perdido no teardown | `screen_off_timeout=2147483647` persistia após a sessão | **CORRIGIDO v0.18.0-dev-13** — `saveCurrentIme()` agora roda antes de `configureEnvironment()`; `restoreSetting()` valida os valores |
| Tela preta após reconexão | A central travada em tela preta, sem recuperação | **CORRIGIDO v0.18.0-dev-13** — o callback `onSustainedBlackScreen` dispara o rebuild da VD após 3s (`BlackScreenDetector.BLACK_SCREEN_SUSTAIN_MS`, protocol-core; era 5s no app-server antes da extração e1674f8). A auto-recuperação em si então teve um defeito: **CORRIGIDO `35d7821`** — o caminho de recuperação chamava `videoDecoder.stop()` enquanto o `CarShell` mantém o layout de streaming montado, então nada reiniciava o codec e uma recuperação disparada deixava a tela preta pelo resto da sessão. Agora ele reseta o estado do detector e deixa o codec vivo consumir o IDR novo. |
| Apps em retrato com letterbox em VD paisagem | Tela inicial do Petal Maps estreita | Mitigado — `VideoConfig.calculateOptimalDpi` limita o DPI para que apps em retrato tenham >=360dp; o usuário pode sobrescrever via `startup_dpi`. |
| Hotspot precisa ser habilitado manualmente | O usuário habilita antes de plugar | Limitação do Android 16 |
| Streaming de áudio | **CORRIGIDO / IMPLEMENTADO na v1.0.3** | Loop completo operacional na porta 9640 (AAC-LC 48kHz estéreo, pacing de silêncio sintético a 46.875 fps, jitter buffer de 150ms, negociação bidirecional de capacidade, auditoria da métrica de silêncio). |
| Controles de mídia / widgets de navegação | Não implementado | `NowPlayingBar` existe na árvore de código mas só é composto quando `MediaMetadata` está presente, o que o celular nunca envia. |
| Sentinela de versão zero em `Versioning` (auditoria de testes C-5) | Sem nada instalado, `readInstalledVersion` retorna `"0"`, que compara igual a `versionName` `"0"` / `"0.0"` / `"0.0.0"` — então o app da central é julgado "já atualizado" e a **primeira instalação é pulada**. `-SNAPSHOT` também compara igual ao seu release | **ABERTO** — decisão de produto, fixada por um teste de risco em `VersioningTest` (commit 8157c86). Correção: um estado explícito de "não instalado" que nunca possa comparar igual, em vez de um sentinela `"0"` |
| `InstallStatus` classifica por substring em inglês (auditoria de testes C-6) | As strings de status vêm de `context.getString`, então todo locale `values-*` não-inglês cai em `IDLE` — sem progresso e sem UI de erro. `"not reachable"` / `"Invalid IP"` também não são classificados como ERROR | **ABERTO** — decisão de produto, fixada por um teste de risco em `InstallStatusTest` (8157c86). Correção: classificar por um enum de estado estruturado em vez de pela texto localizado |
| `CarCommandRouter.dispatch` com decodificação desprotegida (residual S-02 da auditoria de testes) | `LAUNCH_APP` / `APP_UNINSTALL` chamam seus decoders fora de qualquer try/catch (só `APP_INFO` tem um), então um nome de pacote rejeitado escapa para a thread de leitura de frames do vd-server em vez de ser registrado e descartado | **ABERTO** — a verificação de formato do nome de pacote em si entrou (`a7d2b72`, `8601fb1`); só falta a guarda no ponto de chamada. Correção: envolver as duas chamadas `decode` como `APP_INFO` faz. Fixado por `CarCommandRouterDispatchTest` |
| Apps comuns do `AppCategorizer` caem em OTHER | ex.: `com.google.android.youtube` não casa com nenhuma palavra-chave, então a grade da central não consegue agrupá-lo | **ABERTO** — lacuna de UX, não um crash. Fixado por `AppCategorizerTest` (8157c86) |
| Frame ocasionalmente corrompido | Um frame corrompido por uma corrida de restart do decoder; o próximo keyframe restaura a imagem (~1s) | **CONHECIDO** — aceito; o caminho da central mantém o codec vivo deliberadamente entre restarts (reanexação por `switchSurface`, `35d7821`), trocando isso pelos bugs de tela preta que uma nova correção reintroduziria |
| Caminho de deploy ADB do `app-desktop` não verificado | Quando o celular não tem Shizuku, o receptor Windows faz o deploy do VD server via `adb.exe`; esse caminho nunca foi exercitado em um dispositivo real, e a auditoria errou seus defeitos por causa disso | **ABERTO** — todo em `.plan/windows-client-plan.md`. O resto da Fase 5 (multi-monitor / tela cheia, decodificação por hardware e seu fallback, keep-awake, o switch manual da tela do celular) também aguarda uma passada no dispositivo |

---

## Arquitetura (atual)

```
Phone (Chinese ROM — Xiaomi HyperOS / Meizu etc., Android 14+)
├── DiLink Auto Client App (app-client, minSdk 29)
│   ├── ConnectionService — pure orchestrator (FEATURE_AUDIO negotiation, Shizuku deploy delegation)
│   │   ├── Control (9637): accept → handshake → VD_PORTS_BOUND → car logs
│   │   ├── Collaborators: ShizukuVdDeployer, ShizukuNav, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository
│   │   ├── Car auto-update: UPDATING_CAR → CarAppInstaller (dadb) push+install
│   │   ├── App allowlist filter (AppListBuilder.sendAppList)
│   │   ├── Smart network callback (ClientNetworkMonitor, TRANSPORT_WIFI only)
│   │   └── FileLog: /sdcard/DiLinkAuto/client.log (rotation, 10 max)
│   ├── VirtualDisplayClient — lifecycle-only
│   │   ├── startListening() — synchronous ServerSocket on 127.0.0.1:19647 (loopback; non-loopback peers refused)
│   │   ├── acceptConnection() — NIO non-blocking accept
│   │   ├── Reads MSG_DISPLAY_READY (displayId + direct-injection flag)
│   │   ├── Sends VD_PORTS_BOUND to car on display ready
│   │   └── Sends CMD_STOP on teardown
│   ├── PhoneDisplayRestorer — Shizuku → wakeUp → FLAG_TURN_SCREEN_ON → wake lock
│   └── AllowlistScreen / ApkInstaller / AppVersion / Versioning / UpdateManager
│
├── VD Server (app_process, shell UID 2000, vd-server.jar)
│   ├── PipelineServer — process entry point + lifecycle owner
│   │   ├── Binds 9638 (video), 9639 (input), and 9640 (audio) on 0.0.0.0 via PortAcceptor
│   │   ├── Accepts car's video, input, and audio connections directly (CAR_HOST pinned)
│   │   ├── Reverse-connects lifecycle to phone localhost:19647
│   │   ├── VideoEncoderSelector: candidate-ordered pick (Codec2 before legacy OMX), CBR 4Mbps Main, I-frame 1s
│   │   ├── AudioPipeline: REMOTE_SUBMIX capture, AudioEncoderSelector AAC-LC, 46.875 fps synthetic silence pacing
│   │   ├── ProcessWatchdog: forces cleanup() if pipeline hangs in native MediaCodec, watches stop-sentinel
│   │   └── Cleanup: am display move-stack → restore panel → restore IME → kill shell
│   ├── GlPipeline — EGL14 + GLES20 on the pipeline thread
│   │   ├── parkNanos pace → updateTexImage → fullscreen quad → eglSwapBuffers
│   │   ├── Encoder drain → TCP write (no queues)
│   │   └── Adaptive bitrate: floor 1.5Mbps, 2s recovery, 0.5Mbps steps
│   ├── TouchInjector — InputManager reflection, multi-touch, TOUCH_MOVE_BATCH
│   ├── DisplayPowerController — DisplayControl reflection; cmd display power-reset/off fallback (API 29+)
│   ├── VirtualDisplayCreator — trust flag 0x6c49, 12L letterbox style
│   └── PipeLog — vd-server.log
│
Car (BYD DiLink 4.0, Snapdragon 439, Android 9 / API 28, 1280x800)
├── DiLink Auto Server App (app-server, minSdk 26)
│   ├── CarConnectionService — parallel WiFi + USB/TCP ADB tracks (slimmed to 407 lines)
│   │   ├── CarSessionCoordinator: manages 9637 (control), 9638 (video), 9639 (input), 9640 (audio)
│   │   ├── CarAdbTracker: USB host ADB and TCP ADB dual tracks, reconnect bounds (noAdbCount >= 3)
│   │   ├── CarAppListReceiver: app list parsing with AppIconLimits enforcement
│   │   ├── AudioPlayer: MediaCodec AAC-LC decode, 150ms jitter buffer, AudioTrack, silence frame metrics
│   │   ├── VdServerDeployer: shellBackground launchCommand
│   │   ├── HandshakeFactory.buildHandshakeRequest (initial + rotation re-handshake)
│   │   └── startup_dpi SharedPreferences (0 = auto, [120, 480] = override)
│   ├── VideoDecoder — createDecoderByType, 4-frame queue, keyframe priority, DecodeLivenessTracker
│   │   ├── outputSurfaceValid gate (SurfaceView destroy/re-create across navigation)
│   │   ├── switchSurface re-attaches decoder with zero keyframe loss
│   │   ├── post-flush IDR resync (skips P-frames until keyframe)
│   │   ├── Feed thread at THREAD_PRIORITY_URGENT_DISPLAY
│   │   └── debugFrameStats (decode time, queue depth) gated on LOG_TOGGLE
│   ├── AppIconCache — prepareAll (Semaphore(4)), getPrepared (O(1)), clear() on disconnect
│   ├── CarShell — streaming layout accepts CONNECTING && appList.isNotEmpty()
│   ├── MirrorScreen — SurfaceView (not TextureView)
│   ├── CarLaunchScreen — startup_dpi numeric input, dev-mode toggle, manual IP
│   ├── HomeScreen — @Immutable AppTileData, Pin-to-Top, long-press context menu
│   ├── PersistentNavBar — three buttons only: Eject / Home / Back
│   ├── CarLogWriter — AtomicInteger bufferCount, 10k cap, BuildConfig.DEBUG default
│   ├── CarTouchSender — TOUCH_MOVE_BATCH on input connection
│   ├── RemoteAdbController — TcpAdbConnection (single socket)
│   ├── CarCrashHandler — crash-pending.log → carLogSend on next connect
│   └── CarTheme — tokenized colors, labels floored to 14sp
│
Windows (x64, no JDK required — jpackage app-image bundles a JRE)
└── DiLink Auto Desktop Receiver (app-desktop, Kotlin/JVM + Compose Desktop)
    ├── DesktopConnectionService — same state machine as the car
    │   ├── controlConnection (9637) → HandshakeFactory (viewport + evenAlign + dpiOverride)
    │   ├── videoConnection (9638) / inputConnection (9639) direct to the VD server
    │   └── SET_DISPLAY_POWER (0x32) over the input port's Channel.CONTROL
    ├── VideoDecodePipeline — JavaCV FFmpegFrameGrabber over a custom InputStream,
    │   d3d11va hardware decode with a software-fallback watchdog + rebuild backoff
    ├── FeedGate / H264NalParser — CONFIG before IDR, skip P-frames after a rebuild
    ├── SwingVideoView / Letterbox — double-buffered letterbox paint + mouse → TouchEvent
    ├── AppIconStore / AppCatalog / AppGridView — APP_LIST icons + launch
    ├── DesktopApp — session generations (DPI / hardware-decode change replaces the session)
    ├── AdbDeployer / ProcessAdbRunner — adb.exe deploy when the phone has no Shizuku
    └── DesktopPaths / JsonConfig / DesktopLog — %APPDATA%\DiLinkAuto\, jpackage exe
```

## Fluxo de conexão

```
1. Phone and car on the same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB/TCP ADB tracks

   Track A (WiFi control):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + appVersionName
                 + targetFps + dpiOverride + supportedFeatures (FEATURE_AUDIO)
   d. Phone responds with device info + vdServerJarPath + vdDpi + supportedFeatures
   e. Phone checks version — if mismatch, sends UPDATING_CAR, auto-updates
      car APK via dadb, disconnects to wait for car restart
   f. Phone opens lifecycle ServerSocket on 127.0.0.1:19647 (loopback only)
   g. If Shizuku available: phone deploys VD server directly.
      Else car deploys via ADB (USB or TCP).
   h. VD server starts: CLASSPATH=jar app_process / PipelineServer
                          W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]
   i. VD server reverse-connects to phone localhost:19647 (NIO)
   j. Phone reads MSG_DISPLAY_READY (displayId + direct-injection flag)
   k. Phone sends VD_PORTS_BOUND to car on the control connection
   l. Car connects video (9638), input (9639), and audio (9640, when negotiated) directly to VD server
   m. VD server accepts connections — session fully established

   Track B (USB ADB / TCP ADB):
   a. USB: scan USB devices for ADB interface → CNXN → AUTH → connected
      TCP: dev-mode phone-IP lookup, connect to phone:5555 via TcpAdbConnection
   b. shellBackground(launchCommand) — keeps ADB stream open so app_process survives
   c. Launch phone app via am start (AppTargets.PHONE_MAIN_ACTIVITY)

3. VD server creates VirtualDisplay (car viewport + auto-calibrated DPI)
4. VD server powers off the phone's physical panel via DisplayControl
5. VD server saves the original IME and sets the VD's IME policy
6. Pipeline thread: EGL/GL init → SurfaceTexture → encoder → bind 9638/9639/9640
7. Car starts VideoDecoder on the SurfaceView surface when MirrorScreen creates it
8. Video: VD → SurfaceTexture → GL → encoder → TCP 9638 → car VideoDecoder → SurfaceView
9. Audio: VD → AudioRecord (REMOTE_SUBMIX) → AAC-LC encoder (synthetic silence pacing) → TCP 9640 → car AudioPlayer (150ms buffer) → AudioTrack
10. Touch: car SurfaceView onTouch → TouchEvent encode → TCP 9639 → VD TouchInjector
          → InputManager.injectInputEvent
11. Car logs: carLogSend() + logSink callbacks → DATA CAR_LOG → phone FileLog
```

Estados: `IDLE → CONNECTING → CONNECTED → STREAMING`

Uma rotação do painel da central em meio ao stream reutiliza a conexão TCP de controle. `MainActivity.onConfigurationChanged` chama `CarConnectionService.onCarViewportChanged`, que cancela `connectionScope` + `connectJob`, derruba vídeo/entrada + o VD server antigo e reenvia um `HandshakeRequest` nas novas dimensões. O celular faz o deploy de um VD server novo e reenvia `VD_PORTS_BOUND`. A barreira do layout de streaming aceita `state == CONNECTING && appList.isNotEmpty()` para que o overlay de espera de vídeo cubra a lacuna de ~2s do redeploy em vez de piscar o `CarLaunchScreen` (o que destruiria a SurfaceView e perderia o estado do decoder).
