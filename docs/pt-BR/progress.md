# Rastreador de Progresso

Versao atual: **v0.17.0-dev-02** (pre-release)
Ultima atualizacao: 2026-05-01

## Marcos

### v0.17.0-dev-02 (2026-05-01)

- **Correcao de superaquecimento do telefone**: Eliminados padroes de espera ocupada da CPU no pipeline de streaming que causavam superaquecimento do telefone. Substituidos busy-waits `delay(1)` por mecanismos adequados baseados em blocking/selector.
- **AppIconCache movido para o carro**: Cache de icones do lado do carro persiste PNGs fonte (192x192) no disco. `prepareAll()` decodifica+redimensiona todos os icones em thread de fundo antes da grade aparecer; `getPrepared()` e busca O(1) ConcurrentHashMap sem I/O durante a rolagem. Eliminada a decodificacao por tile e crash na rolagem rapida.
- **AppTile simplificado**: Removido StateFlow collect por tile, DropdownMenu lazy e efeitos click ripple. Tiles leves com clickable em vez de combinedClickable para o toque principal.
- **Deduplicacao da grade de apps**: Corrigido crash do LazyGrid ao deduplicar itens por packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Dispensa individual de notificacoes e Limpar Tudo**: A tela de notificacoes do carro agora tem botoes de dispensar por item com animacoes slide-out e um botao "Limpar Tudo" no cabecalho. Novas mensagens de protocolo: `NOTIFICATION_CLEAR` (0x04) e `NOTIFICATION_CLEAR_ALL` (0x05) no canal de dados. Icones por item do payload `iconPng` do celular.
- **Acoes de contexto de apps**: Toque longo nos tiles de apps (launcher) e apps recentes da barra de nav mostra menu suspenso com Desinstalar e Info do App. Propagacao de desinstalacao via `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). Info do App exibe dialogo do lado do carro com metadados `APP_INFO_DATA` (0x07) do celular. Acoes do menu de contexto passam pelo servidor VD para acesso em nivel shell.
- **Infraestrutura de atalhos de apps** (desativada na UI): Mensagens de protocolo `APP_SHORTCUTS` (0x18) / `APP_SHORTCUTS_LIST` (0x19) / `APP_SHORTCUT_ACTION` (0x1A) com consulta ao servidor VD + fallback APK XML. Desativada enquanto a resolucao de rotulos e refinada (issue #57).
- **Correcao do botao voltar**: GO_BACK agora fecha atividades uma por uma antes de retornar ao menu inicial, usando rastreamento de pilha adequado e mensagens `FOCUSED_APP` (0x16).
- **DPI Samsung DeX / Modo Desktop** (revertido): Implementacao inicial usando deteccao `UiModeManager.currentModeType` e 213dpi foi revertida no dev-02. Substituida por abordagem de remocao de flag no nivel VD.

### v0.16.0 (2026-04-29)

- **Shizuku**: App agora aparece na lista de apps autorizados do Shizuku (adicionado ShizukuProvider ContentProvider). Cartao de configuracoes abre o app Shizuku diretamente para gerenciamento de permissao.
- **Correcao Shizuku exec**: Corrigido EBADF de ParcelFileDescriptors em transacoes binder ao duplicar FDs antes da leitura. `pm install` via Shizuku para auto-atualizacao silenciosa.
- **Modo Shizuku no carro**: Conexao do carro nao fica mais presa em "Aguardando WiFi" quando Shizuku esta ativo — loop de tentativa de gateway IP nao para mais apos a primeira tentativa.
- **Verificacao de versao alterada para versionName**: Atualizacoes do app do carro agora comparam strings versionName (compativel com semver) em vez de inteiros versionCode, permitindo atualizacoes pre-release do carro.
- **Reforco de seguranca**: Removidas permissoes nao utilizadas `RECORD_AUDIO` e `SYSTEM_ALERT_WINDOW`. Servico de acessibilidade nao escuta mais eventos `typeAllMask` (usa apenas `dispatchGesture`).
- **Performance da grade de apps**: Corrigida falha durante rolagem rapida no display do carro. `GridCells.Adaptive` → `GridCells.Fixed` com colunas calculadas. Decodificacao lazy de bitmap por tile com `inSampleSize=2` + `RGB_565`.
- **Estabilidade de rede**: `NetworkCallback` do lado do telefone agora filtrado para `TRANSPORT_WIFI` apenas, ignorando flutuacoes de dados moveis 3G/4G.

### v0.15.0 (2026-04-28)

- **Inicio automatico do servico do celular**: `ConnectionService` inicia automaticamente quando o app do celular e aberto (ex. via USB ADB do carro), removendo a necessidade de pressionar Iniciar manualmente. ✅ Concluido
- **Carro nao limpa mais a tarefa do celular**: Removido `--activity-clear-task` do lancamento do celular via USB ADB do carro. Se o app do celular ja estiver aberto, o carro avanca sem interrompe-lo. ✅ Concluido
- **Botao Compartilhar Logs**: Botao "Compartilhar Logs" na tela principal compacta todos os arquivos `*.log` de `/sdcard/DiLinkAuto/` e compartilha via planilha de compartilhamento do Android. `FileLog.zipLogs()` cria um `dilinkauto-logs.zip`. ✅ Concluido
- **Configuracao do codificador**: Ajustado para 8Mbps CBR perfil Main para compatibilidade mais ampla com dispositivos. Adicionada contrapressao (descarta nao-keyframes quando a fila de escrita excede 6 quadros). ✅ Concluido
- **VideoDecoder catchup**: Quatro zonas graduadas de aceleracao (normal, suave 1,5x, medio 2x, agressivo 3x) para recuperacao mais suave de latencia. ✅ Concluido
- **Traducao para frances**: Adicionado frances (fr) aos 7 idiomas existentes (agora 8 no total). ✅ Concluido
- **Verificacao de atualizacao ao abrir o app**: A verificacao de auto-atualizacao executa imediatamente quando o app abre, com notificacao de atualizacao e botao de reverificacao. ✅ Concluido
- **Seletor de canal de distribuicao**: Cartao de configuracoes para escolher entre releases estaveis e pre-releases de desenvolvimento para auto-atualizacao. ✅ Concluido
- **Redesign da CarLaunchScreen**: Layout de duas colunas otimizado para telas largas de carros. ✅ Concluido
- **Refatoracao da UI do app do celular**: Tela principal reorganizada, corrigidos bugs no fluxo de instalacao. ✅ Concluido
- **Melhorias na integracao**: Pre-requisitos de configuracao do carro, UI de progresso de instalacao aprimorada, cartao Como Conectar melhorado. ✅ Concluido
- **Separacao de dois modos da UI do carro**: Tela de lancamento (tela cheia, foco em conexao) e modo streaming (barra de nav + conteudo). Transicao suave quando a lista de apps chega. ✅ Concluido
- **Correcoes de artefatos de video**: Descarte inteligente no decoder + catchup graduado + contrapressao no codificador eliminam artefatos visuais. ✅ Concluido
- **Correcoes de entrada de toque**: Mapeamento correto de coordenadas com DPI fixo de 480dpi do servidor VD, despacho incremental de toque no MOVE, correcoes de gesto de toque e IP manual. ✅ Concluido
- **Restauracao de tela e estabilidade de rede**: Restauracao de tela apos desconexao USB, melhorias no callback de rede. ✅ Concluido
- **Internacionalizacao**: Todas as novas strings de UI traduzidas para 8 idiomas (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Concluido
- **Automacao CI/CD**: 6 workflows dedicados — validacao (`build.yml`, `build-develop.yml`), pre-release em tags `-dev` (`build-pre-release.yml`), release em tags `vX.Y.Z` (`build-release.yml`), sincronizacao reversa main→develop (`sync-main-to-develop.yml`) e issue-agent autonomo (`issue-agent.yml`). ✅ Concluido

### v0.14.0

- **Fonte de versao compartilhada**: Version code/name agora em gradle.properties — unica edicao para ambos os apps.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: Lista de apps com mais de 136 icones PNG excedia 2MB causando ProtocolException e quedas de conexao.
- **Correcao de restauracao de tela**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` com `ACQUIRE_CAUSES_WAKEUP` restaura a tela apos desconexao USB, mesmo quando o servidor VD morre sem limpeza.
- **Compatibilidade com POCO F5**: `FLAG_KEEP_SCREEN_ON` previne bloqueio de tela durante streaming. Entrada de toque confirmada funcionando no POCO F5 com Xiaomi 17 Pro Max.
- **Log de toque no lado do carro**: Eventos de toque do MirrorScreen e sucesso de sendTouchEvent registrados para depuracao.
- **Credito do desenvolvedor no Sobre**: "Desenvolvido com ❤" com link do GitHub em todos os 7 idiomas.

### v0.13.1 — Primeiro Lancamento (2026-04-26)

- **Fluxo de integracao**: Configuracao guiada de permissoes no primeiro lancamento (Todos os Arquivos, Bateria, Acessibilidade, Notificacoes). Detecta concessoes automaticamente, fallback de polling para configuracoes estilo dialogo.
- **Auto-atualizacao (UpdateManager)**: Verifica a API GitHub Releases, baixa APK com progresso, instala via instalador de pacotes do sistema. Intervalo de 6 horas.
- **Reorganizacao da visualizacao principal**: Tela principal focada em uso diario (guia de conexao, status, iniciar/parar, atualizacoes). Tela de configuracoes com permissoes, instalacao no carro, sobre e links de doacao.
- **Instalacao no carro via USB + WiFi**: Scanner de sub-rede paralelo sonda todos os 254 IPs pelo ADB do carro. Combinado com descoberta ARP/neighbor/gateway. USB host tentado, mas USB-A do carro e apenas host.
- **Servidor VD agora e modulo Kotlin Gradle**: Depende de :protocol e kotlinx-coroutines. Compartilha NioReader, FrameCodec.writeAll.
- **Performance**: Eliminada alocacao intermediaria de ByteArray por quadro no codificador. Capacidade inicial do NioReader 256KB. isKeyFrame em cache no FrameData.
- **Codificador**: CBR 8Mbps, perfil Main, FPS configuravel (padrao 30, carro solicita 60), PRIORITY 0 (tempo real). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms para conteudo estatico.
- **Doacoes**: Badges do GitHub Sponsors e Pix (Brasil) no README e configuracoes do app.
- **Icone vetorial adaptativo**: Silhueta de carro com sinais wireless, aplicado a ambos os apps do celular e carro.
- **Internacionalizacao**: Recursos de string em Ingles, Portugues (pt-BR), Russo (ru), Bielorrusso (be), Frances (fr), Cazaque (kk), Ucraniano (uk) e Uzbeque (uz).
- **Assinatura de release**: Keystore fixa com senha forte. Builds CI geram APKs assinados de release via GitHub Secrets.

### v0.13.0 — Correcao de Autenticacao USB ADB (2026-04-25)

Causa raiz encontrada e corrigida: `Signature.getInstance("SHA1withRSA")` fazia hash duplo do AUTH_TOKEN do ADB. O token de 20 bytes do ADB e um valor pre-hash — `RSA_sign(NID_sha1)` do AOSP o trata como ja hasheado. Agora usa `NONEwithRSA` com prefixo ASN.1 SHA-1 DigestInfo manualmente prefixado (assinatura pre-hash). "Sempre permitir" agora persiste corretamente — AUTH_SIGNATURE aceito na reconexao sem dialogo.

### v0.13.0 — Desligamento de Tela + Codificacao de Chave (2026-04-25)

- **Desligamento de tela via SurfaceControl (Android 14+)**: Carrega `DisplayControl` de `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader()` + biblioteca nativa `android_servers`. Fallback para `cmd display power-off/on` se a reflexao falhar.
- **Restauracao de tela ao desconectar**: `VirtualDisplayClient.disconnect()` do celular executa `cmd display power-on 0` + `KEYCODE_WAKEUP` como rede de seguranca quando o processo servidor VD e finalizado antes da limpeza.
- **Reescrita de codificacao de chave ADB**: Reescreveu `encodePublicKey()` correspondendo exatamente a referencia AOSP — constantes corrigidas, `bigIntToLEPadded()` explicito, log de cabecalho de struct.
- **Decoder catchup**: Quando a fila excede `100ms * TARGET_FPS / 1000` quadros (6 a 60fps), pula cada segundo nao-keyframe. A imagem ainda se move a 2x de velocidade, gradualmente alcancando sem saltos.
- **Buffer de log do carro**: 200 → 10.000 mensagens. Logs de autenticacao USB ADB agora sobrevivem ate que a conexao de controle os libere.

### v0.12.5 — Estabilidade de Conexao (2026-04-24)

- **Callback de rede inteligente**: `onLost` agora verifica se a rede perdida e a que transporta a conexao. Ignora quedas nao relacionadas (ciclagem de dados moveis). Anteriormente, qualquer perda de rede matava a sessao de streaming.
- **Diagnosticos de autenticacao USB ADB**: Log completo do fluxo de autenticacao encaminhado via carLogSend → FileLog do celular. Revelou que AUTH_SIGNATURE e rejeitado todas as vezes (adbd do celular nao reconhece a chave armazenada). Sob investigacao.
- **Log de preview da chave AUTH_RSAPUBLICKEY**: Registra primeiros/ultimos bytes da chave publica enviada ao celular para comparacao com o formato ADB padrao.

### v0.12.0–v0.12.4 — Correcoes de Bugs e Refinamento (2026-04-24)

- **Entrada de toque corrigida**: `handleInputFrame` despachado em `Dispatchers.IO` (estava em Main, causava `NetworkOnMainThreadException` na escrita do socket localhost)
- **Leitor de comandos NIO do servidor VD**: Corrigido loop infinito — `break` dentro do switch apenas saia do switch, nao do loop de parse. Agora usa `break parseLoop;` com label.
- **Dedup de lancamento de app**: Removido `--activity-clear-task` do `am start`. Apps existentes retomam em vez de reiniciar.
- **Bitrate**: Definido para 8Mbps CBR (ajustado de 12Mbps em releases posteriores para compatibilidade com dispositivos).
- **FPS configuravel**: Adicionado campo `targetFps` ao HandshakeRequest. Carro solicita 60fps. Servidor VD aceita FPS como argumento de linha de comando, usa para `KEY_FRAME_RATE` do codificador e `FRAME_INTERVAL_MS`.
- **Barra de nav**: 72dp → 76dp, icones 32dp → 40dp, altura da linha 52dp → 60dp, texto 12sp → 14sp.
- **Icones de apps do launcher**: 40dp → 64dp, celulas da grade 140dp → 160dp, texto bodyMedium → bodyLarge.
- **Teclado da barra de busca**: `windowSoftInputMode="adjustNothing"` + `imePadding()` no TextField. Teclado nao empurra a atividade, apenas a barra de busca se move.
- **Notificacoes**: Dedup por ID (atualizacoes de progresso substituem existentes), suporte a barra de progresso (determinada + indeterminada), toque-para-iniciar app proprietario no VD + mudar para visualizacao de espelho.
- **Apps recentes**: `pruneUnavailable()` remove apps que nao estao mais no celular quando a lista de apps e atualizada.
- **Armazenamento de chave USB ADB**: Ordem de prioridade: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Migracao busca em todos os locais.
- **Fluxo de atualizacao**: Celular envia mensagem `UPDATING_CAR`. Carro mostra status "Atualizando app do carro..." e nao reconecta.
- **Correcao de crash no fluxo de atualizacao**: Carro pula conexao de video/entrada quando a flag `updatingFromPhone` esta definida.
- **VideoDecoder/UsbAdbConnection logSink**: Logs do lado do carro encaminhados pelo protocolo para o FileLog do celular.

### v0.11.0–v0.11.3 — Pipeline Nao-Bloqueante + Correcao do Codificador (2026-04-24)

- **VideoConfig**: Constantes compartilhadas `TARGET_FPS` e `FRAME_INTERVAL_MS`. Todas as esperas/pollings do caminho de video limitadas ao intervalo de quadro.
- **Redesenho periodico do SurfaceScaler**: Sempre chama `glDrawArrays + eglSwapBuffers` a cada intervalo de quadro, mesmo sem novo quadro do VD. Apenas chama `updateTexImage` quando um novo quadro esta disponivel. Alimenta o codificador em conteudo estatico.
- **NIO do servidor VD**: Substituiu `DataOutputStream/DataInputStream` bloqueantes por fila de escrita NIO (`ConcurrentLinkedQueue<ByteBuffer>`) + leitor de comandos baseado em Selector. Sem I/O bloqueante em nenhum lugar do pipeline.
- **Polling do codificador**: Timeout de `dequeueOutputBuffer` reduzido de 100ms para `FRAME_INTERVAL_MS` (16ms a 60fps).
- **Polling da fila do VideoDecoder**: 100ms → `FRAME_INTERVAL_MS`.
- **Timeout de select do NioReader**: 100ms → `FRAME_INTERVAL_MS` (configuravel via parametro do construtor).
- **Park do escritor de Connection**: 50ms → `FRAME_INTERVAL_MS`.
- **Loop de accept do VirtualDisplayClient**: 100ms → `FRAME_INTERVAL_MS`.
- **Inicio antecipado do VideoDecoder**: Inicia em SurfaceTexture offscreen quando o primeiro quadro CONFIG chega (antes do MirrorScreen). MirrorScreen reinicia o decoder com a superficie TextureView real.
- **Fila do VideoDecoder**: 3 → 30 quadros. Quadros enfileirados mesmo antes de `start()` ser chamado.
- **FileLog**: Logger baseado em arquivo (`/sdcard/DiLinkAuto/client.log`) ignora filtragem de logcat do HyperOS. Rotacao: arquiva como `client-YYYYMMDD-HHmmss.log`, mantem no maximo 10.

### v0.10.0 — Arquitetura de 3 Conexoes (2026-04-24)

Dividiu a conexao TCP multiplexada unica em 3 conexoes dedicadas para eliminar interferencia entre canais que causava travamentos de video:
- **Conexao de controle** (porta 9637): handshake, heartbeat, comandos de app, canal DATA
- **Conexao de video** (porta 9638): apenas H.264 CONFIG + FRAME (celular → carro)
- **Conexao de entrada** (porta 9639): apenas eventos de toque (carro → celular)

Cada conexao tem sua propria instancia `Connection` com SocketChannel, NioReader e fila de escrita independentes. Heartbeat/watchdog apenas no controle.

### v0.9.2 — Build de Diagnostico (2026-04-23)

Log abrangente para investigar travamento de quadro de video apos ~420 quadros:
- **Loop de retransmissao de video**: Log antes/depois de readByte, tamanho da carga, msgTypes desconhecidos
- **NioReader**: Log quando channel.read() retorna 0 (a cada 100a ocorrencia com estado do buffer)
- **Escritor de Connection**: Log a cada 60 quadros de video (contagem, tamanho, profundidade da fila, travamentos), registra travamentos de escrita
- **Correcao de travamento do escritor**: `Thread.yield()` → `delay(1)` em writeBuffersToChannel — libera a thread de IO de volta ao pool de corrotinas em vez de espera ocupada (descoberta da investigacao: Thread.yield deixava a corrotina de retransmissao de video sem recursos)
- **Listeners de quadro**: Manipuladores de quadros nao-video despachados assincronamente (`scope.launch`) para que o processamento pesado (decodificacao de lista de apps) nao bloqueie o leitor de drenar o TCP

### v0.9.0-v0.9.1 — Investigacao de Travamento de Escrita (2026-04-23)

Investigando a causa raiz do travamento de video. Adicionado log de tamanho de buffer TCP, diagnosticos de travamento de escrita.
- Confirmado que buffer de envio TCP congela em 108.916 bytes restantes durante envio de lista de apps
- Confirmado que os quadros de video em si tem zero travamentos de escrita (fila=0 durante video)
- Confirmado que a chave USB ADB e estavel (LOADED fp=c4e88a05) — autenticacao repetida e comportamento do HyperOS

### v0.8.4-v0.8.8 — Correcoes de Bugs + Roteamento de Log (2026-04-23)

- **Roteamento de log do carro**: Todas as chamadas `Log.*` do lado do carro encaminhadas via `carLogSend()` que envia pelo canal DATA `CAR_LOG` para o celular. Celular registra com tag `CarLog` no logcat. Buffer de ate 200 mensagens antes da conexao ser estabelecida.
- **Lancamento do servidor VD revertido** para `shellNoWait` + `exec app_process` (abordagem v0.6.2). O desacoplamento `setsid`/`nohup` quebrou a conectividade localhost. Servidor VD morre na desconexao USB mas recupera na reconexao.
- **ServerSocket do VD**: `startListening()` abre sincronamente, `waitForVDServer()` pula se ja estiver esperando
- **Persistencia de chave USB ADB**: `getExternalFilesDir` com verificacao de gravacao + migracao de `getFilesDir` + log de fingerprint
- **ClosedSelectorException**: Verificacoes `selector.isOpen` no escritor e NioReader
- **Correcao de recursao infinita**: Substituicao em massa de Log→carLogSend acidentalmente atingiu o proprio carLogSend

### v0.8.3 — Refinamento Final + Correcao de Espera do VD + Diagnostico de Chave USB (2026-04-23)

Marco final + correcoes de bugs:
- **Guarda de espera do VD**: `waitForVDServer` pula se ja estiver esperando — previne fechar/reabrir ServerSocket quando o carro envia multiplos handshakes durante reconexao (causa raiz do servidor VD incapaz de conectar, confirmado via logcat do celular mostrando 4x `startListening` em 45s)
- **Diagnostico de chave USB**: Carro escreve informacoes da chave (`LOADED`/`GENERATED` + fingerprint + caminho) em `/data/local/tmp/car-adb-key.log` no celular apos USB ADB connect. Permite diagnosticar se a autenticacao se repete porque a chave muda ou o celular nao persiste "Sempre permitir".
- **M3**: Multi-toque em lote — novo tipo de mensagem `TOUCH_MOVE_BATCH` (0x04) transporta todos os ponteiros em um quadro. Eventos MOVE agrupados no lado do carro, desagrupados no lado do celular. Reduz chamadas de sistema de N*60/seg para 60/seg para gestos com N dedos.
- **M10**: Estado de ejecao persistido em SharedPreferences — sobrevive a finalizacao/reinicio do app do carro. Limpo na reconexao USB ou ACTION_START.
- **L4**: NioReader usa heap ByteBuffer em vez de direto — limpeza deterministica pelo GC.

### v0.8.2 — Refinamento + ServerSocket do VD + Persistencia de Chave USB (2026-04-23)

6 correcoes de refinamento + 2 correcoes criticas:
- **Hotfix**: VirtualDisplayClient dividido em `startListening()` (bind sincrono) + `acceptConnection()` (espera assincrona). ServerSocket abre ANTES da resposta do handshake — corrige servidor VD incapaz de conectar em localhost:19637.
- **Persistencia de chave USB**: Armazenamento de chave usa `getExternalFilesDir` com verificacao de gravacao + migracao de `getFilesDir`. Log de fingerprint em cada conexao para diagnosticar se a chave muda entre conexoes.
- **M12**: `checkStackEmpty` usa `grep -E` mais simples em vez de parse fragil com `sed` por secoes
- **L2**: Buffers do socket localhost do servidor VD definidos para 256KB
- **L3**: Decoder usa `System.nanoTime()/1000` para timestamps (era incremento fixo de 33ms)
- **L5**: `UsbAdbConnection.readFile()` usa loop de leitura + try-with-resources
- **L6**: SurfaceScaler HandlerThread adequadamente finalizada no stop
- **L7**: Icones de apps aumentados de 48x48 para 96x96px

### v0.8.1 — Toque + Performance do Decoder + Hotfixes (2026-04-23)

4 otimizacoes de performance + 2 correcoes de crash:
- **M2**: `checkStackEmpty()` executa em thread de fundo — leitor de comandos nao e mais bloqueado por 300ms+ apos pressionar Voltar
- **M4**: Pools pre-alocados de `PointerProperties[10]` + `PointerCoords[10]` no servidor VD — elimina pressao de GC por toque
- **M5**: Fila de quadros do decoder reduzida de 6 para 3 (limite de latencia de 200ms → 100ms)
- **M6**: `cmd display power-off 0` executa em thread dispara-e-esquece — removeu jitter do caminho de injecao de toque
- **Correcao de crash**: `ClosedSelectorException` no escritor de Connection + NioReader — adicionadas verificacoes `selector.isOpen` e bloco catch. Condicao de corrida: `disconnect()` fecha selectors enquanto corrotinas de leitura/escrita ainda executam.
- **Correcao de bug**: Reset de `usbConnecting` em `startConnection()` agora tambem protegido por `usbAdb == null` (segundo local da condicao de corrida de autenticacao USB, o primeiro foi corrigido em v0.7.3)

### v0.8.0 — Performance do Pipeline de I/O (2026-04-23)

3 otimizacoes de performance de I/O + hotfix:
- **H2**: Escritas agrupadas — `channel.write(ByteBuffer[])` une cabecalho+carga em uma unica syscall/segmento TCP
- **H3**: Retransmissao de video aloca `ByteArray(size)` de tamanho exato diretamente — remove `relayBuf` intermediario + `copyOf`
- **M1**: Servidor VD envolve DataOutputStream em `BufferedOutputStream(65536)` — une pequenas escritas no localhost
- **Hotfix**: `waitForVDServer()` chamado ANTES de enviar resposta do handshake — garante que o ServerSocket em :19637 esteja aberto antes do carro implantar o servidor VD (regressao v0.7.4: sequenciamento colocou espera do VD apos resposta, causando falha de conexao do servidor VD)

### v0.7.4 — Fila de Escrita + Sequenciamento de Fluxo (2026-04-23)

Mudanca na arquitetura de escrita + melhorias de fluxo:
- **Fila de escrita**: Substituiu `synchronized(outputLock)` por `ConcurrentLinkedQueue` lock-free + corrotina de escrita dedicada. Escritor usa `delay(1)` quando buffer de envio TCP esta cheio (libera thread de IO para o pool). Sem mais bloqueio de outras corrotinas durante escritas.
- **H10**: Handshake → auto-atualizacao → implantacao do VD sequenciados. Auto-atualizacao pausa a inicializacao, desconecta, aguarda reconexao do carro.
- **H11**: Mensagens progressivas de status do carro: "Preparando..." → "Iniciando..." → "Aguardando stream de video..."
- **H12**: Carro mostra "Verifique o dialogo de autorizacao no celular" durante a conexao USB ADB
- **Correcao de bug**: Servidor VD inicia atividade home no VD apos criacao — codificador obtem conteudo imediatamente
- **Correcao de bug**: `usbConnecting` so reseta quando `usbAdb == null` — previne dialogos duplicados de autenticacao USB-ADB

### v0.7.3 — Resiliencia de Rede + Correcao de Congelamento HyperOS (2026-04-23)

Resiliencia de rede + correcao critica do HyperOS descoberta via evidencias do logcat:
- **Isencao de bateria**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — previne que o "greeze" do HyperOS congele o app cliente enquanto a tela esta desligada. Causa raiz do bug "quadros apenas durante toque": servidor VD (processo shell) produzia mais de 960 quadros mas o NioReader do app cliente estava congelado pelo gerenciamento de energia do SO. Aviso mostrado no primeiro lancamento.
- **C4/M11**: Trilha WiFi do carro agora tenta gateway IP a cada 3s (era tentativa unica). Adicionado `ConnectivityManager.NetworkCallback` no carro que re-aciona a trilha WiFi quando o WiFi fica disponivel. Lida com hotspot ativado apos conexao USB.
- **H9**: Celular desconecta proativamente na perda de rede no estado CONNECTED/STREAMING (era: esperar 10s pelo timeout do heartbeat). `onLost` → `cleanupSession()` → loop de escuta reinicia. `onAvailable` so reseta quando WAITING (sem interrupcao de conexoes ativas).
- **Correcao de bug**: Servidor VD inicia atividade home no VD apos criacao (`am start --display <id> HOME`) — garante que o codificador tenha conteudo imediatamente.
- **Correcao de bug**: `usbConnecting` so reseta quando `usbAdb == null` — previne dialogos duplicados de autenticacao USB-ADB.

### v0.7.2 — Estabilidade do Lado do Carro + Correcao do Selector (2026-04-23)

5 correcoes de estabilidade + correcao critica do NioReader:
- **H1**: Substituiu polling `delay(1)` por **Selector** no NioReader — `selector.select(100)` acorda instantaneamente via epoll quando dados chegam. Corrige video nao transmitindo (quadros so fluiam durante toque no carro Android 10 devido a `delay(1)` levar 10-16ms). Adicionado `wakeup()`/`close()` para encerramento limpo na desconexao.
- **H7**: `@Volatile` em `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — previne estado CONNECTING travado
- **H8**: `VideoDecoder.stop()` aguarda thread de alimentacao (timeout 2s) antes de `codec.stop()` — previne crash nativo
- **M7**: Removeu `cleanup()` duplo no servidor VD — previne IllegalStateException na liberacao dupla
- **M8**: `cleanupSession()` reseta `_serviceState` para WAITING — previne UI estagnada durante atraso de reconexao
- **M9**: `onCreate()` limpa `activeConnection` estatico e `_serviceState` — previne estado estagnado no reinicio do servico

### v0.7.1 — Correcoes Criticas de Bugs (2026-04-23)

6 correcoes criticas/altas da revisao abrangente:
- **C1**: `writeAll()` com timeout de escrita de 5s — previne congelamento do sistema em buffer de envio cheio
- **C3**: Flag de tentativa de auto-atualizacao — quebra loop infinito de atualizacao/reinicio
- **C5**: WakeLock com liberacao automatica apos 4h — previne dreno de bateria em saida anormal
- **C6**: `@Volatile` no channel/reader do VirtualDisplayClient + escritas protegidas por timeout
- **H5**: `Connection.connect()` try/catch — fecha SocketChannel no cancelamento
- **H6**: Listener de desconexao envolvido em try/catch — previne propagacao de excecao

### v0.7.0 — NIO Completo + Correcao de Servico (2026-04-23)

Todas as operacoes de socket convertidas para NIO nao-bloqueante. Registro mDNS nao bloqueia mais o loop de escuta. Version code lido em runtime via PackageManager.

**Mudancas:**
- **NioReader**: Novo leitor bufferizado nao-bloqueante para SocketChannel (polling delay(1), cooperativo com corrotina)
- **Connection.kt**: SocketChannel permanece nao-bloqueante durante todo o tempo — sem mais configureBlocking(true) apos connect/accept
- **FrameCodec.kt**: Adicionados metodos NIO `readFrame(NioReader)` e `writeFrameToChannel(SocketChannel, Frame)`
- **VirtualDisplayClient.kt**: Leituras NIO (NioReader) + escritas ByteBuffer, canal permanece nao-bloqueante
- **VirtualDisplayServer.java**: NIO SocketChannel para connect (finishConnect nao-bloqueante com retry)
- **ConnectionService.kt**: probePort() convertido para NIO; registro mDNS lancado em background com timeout de 5s (corrige servico nao iniciando sem WiFi)
- **Version code**: Removida constante `APP_VERSION_CODE` — ambos os apps leem versionCode em runtime via `PackageManager.getPackageInfo()`

### v0.6.2 — Modelo de Conexao Paralela + Auto-Atualizacao (2026-04-23)

Grande reescrita da arquitetura: trilhas paralelas WiFi + USB, sockets NIO nao-bloqueantes, auto-atualizacao dirigida pelo celular, multi-toque via IInputManager.

**Funcionando:**
- **Maquina de estados de conexao paralela**: Descoberta WiFi + USB ADB executam simultaneamente, VD implanta quando ambos prontos
- **Auto-atualizacao**: Celular detecta app do carro desatualizado no handshake, envia atualizacao via WiFi ADB (dadb)
- **Celular implanta JAR VD**: Extraido para `/sdcard/DiLinkAuto/vd-server.jar` no lancamento (CRC32 verificado)
- **APK do carro incorporado no celular**: Sistema de build compila APK do carro nos assets do celular
- **Sockets NIO nao-bloqueantes**: Todos os accept/connect usam `ServerSocketChannel`/`SocketChannel` — cancelamento instantaneo, sem EADDRINUSE
- **Entrada multi-toque**: Injecao direta de MotionEvent via `ServiceManager → IInputManager` (suporta toque, deslize, pinça)
- **Gerenciamento de energia da tela**: `cmd display power-off 0` durante streaming, proximidade/levantar para acordar desabilitados, re-desligamento limitado apos injecao de toque
- **Recuperacao da maquina de estados**: `connectionScope` cancela todas as corrotinas na desconexao, reconexao com backoff exponencial
- **Desconexao do usuario**: Botao ejetar para a reconexao (permanece IDLE)
- **Busca de apps**: Campo de busca na parte inferior da grade de apps, apps ordenados alfabeticamente
- **Painel de notificacoes**: Icone de sino na barra de nav com contagem de badges
- **Barra de nav 72dp**: Icones maiores (32dp) e texto (12sp) para telas de carro
- **Verificacao de versao no handshake**: Campo `appVersionCode` no HandshakeRequest, `vdServerJarPath` no HandshakeResponse
- **Tratamento de mudanca de rede**: Celular reseta loop de escuta em mudancas de interface de rede (alternancia de hotspot)
- **Codificacao H.264**: 8Mbps CBR, perfil High, modo baixa latencia
- **Timeout de handshake**: Timeout de 10s com cancelamento adequado (sem timeouts estagnados)

**Mudancas de arquitetura desde v0.5.0:**
- Removido polling de SSID do hotspot/conexao WiFi automatica do carro (simplificado)
- Movido UsbAdbConnection + AdbProtocol para modulo protocol (compartilhado por ambos os apps)
- Servidor VD conecta AO celular (conexao reversa) em vez do celular conectar ao servidor VD
- Servidor VD sai na desconexao do celular (one-shot, carro re-implanta se necessario)
- Celular extrai JAR VD para armazenamento compartilhado, carro le o caminho do handshake

### v0.5.0 — USB ADB + Configuracao Automatizada (2026-04-22)

Grande mudanca de arquitetura: o **carro** implanta o servidor VD no celular via USB ADB. Depuracao Sem Fio eliminada.

### v0.4.0 — VirtualDisplay com Escala GPU (2026-04-22)

Apps renderizam no DPI nativo do celular (480dpi), GPU reduz para o viewport do carro. Pipeline EGL/GLES do SurfaceScaler.

### v0.3.0 — Barra de Navegacao Persistente (2026-04-21)

UI do carro com barra de nav esquerda sempre visivel, TextureView, icones reais de apps.

### v0.2.0–v0.2.3 — Fundacao do Virtual Display (2026-04-21)

Criacao de VD, auto-ADB, servidor resiliente, suporte a multiplos apps.

### v0.1.0–v0.1.1 — Implementacao Inicial (2026-04-21)

Projeto criado. Espelhamento de tela em emuladores.

---

## Rastreador de Correcoes

Revisao abrangente realizada em 2026-04-23 cobrindo performance, estabilidade e continuidade de fluxo.

### Fase 1 — Critico (corrigir antes do proximo release)

| ID | Categoria | Achado | Status |
|----|----------|---------|--------|
| C1 | Estabilidade/Perf | `writeAll()` gira indefinidamente em buffer de envio cheio — sem timeout, mantem `outputLock`, bloqueia todos os remetentes. Risco de congelamento do sistema | **v0.7.1** |
| C2 | Fluxo | Servidor VD morre na desconexao USB (`shellNoWait` vincula processo ao stream ADB). Reconexao completa 5-15s | REVERTIDO — `setsid`/`nohup` quebrou localhost. Usando `shellNoWait`+`exec` (abordagem v0.6.2). Reconecta na reconexao. |
| C3 | Fluxo | Auto-atualizacao sem quebra de loop — se `pm install` falha silenciosamente, ciclo infinito de reinicio | **v0.7.1** |
| C4 | Fluxo | Trilha WiFi do carro executa uma vez e desiste — hotspot ativado apos USB → travado para sempre | **v0.7.3** |
| C5 | Estabilidade | WakeLock adquirido sem timeout — dreno de bateria se servico morto sem `onDestroy()` | **v0.7.1** |
| C6 | Estabilidade | `VirtualDisplayClient.touch()` espera ocupada em escrita nao-bloqueante + campo `channel` nao volatile — condicao de corrida | **v0.7.1** |

### Fase 2 — Alto (latencia e estabilidade)

| ID | Categoria | Achado | Status |
|----|----------|---------|--------|
| H1 | Perf | Polling `delay(1)` NIO adiciona piso de latencia de 1-4ms por leitura + 1000 despertares/seg em idle. Usar Selector ou `runInterruptible` | **v0.7.2** |
| H2 | Perf | Duas syscalls por escrita de quadro (cabecalho 6 bytes + carga). Usar `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Perf | `ByteArray.copyOf()` por quadro na retransmissao de video (~30 alocacoes/seg de 10-100KB). Passar offset+length | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` serializa video+toque+heartbeat. Escrita de keyframe bloqueia toque ~200ms | **v0.7.4** |
| H5 | Estabilidade | `Connection.connect()` vaza SocketChannel no cancelamento — sem try/finally | **v0.7.1** |
| H6 | Estabilidade | `disconnectListener` invocado sincronamente no CAS — potencial deadlock | **v0.7.1** |
| H7 | Estabilidade | Flags de estado do carro (`wifiReady`, `usbReady`) nao volatile — pode travar em CONNECTING | **v0.7.2** |
| H8 | Estabilidade | `VideoDecoder.stop()` nao aguarda thread de alimentacao antes de `codec.stop()` — risco de crash nativo | **v0.7.2** |
| H9 | Fluxo | Callback de rede do celular ignora CONNECTED/STREAMING — alternancia de hotspot causa 10s de quadro congelado | **v0.7.3** |
| H10 | Fluxo | Handshake + auto-atualizacao + implantacao VD todos em condicao de corrida — deployAssets pode nao ter terminado, operacoes ADB concorrentes | **v0.7.4** |
| H11 | Fluxo | Sem feedback do usuario durante inicio de 5-12s do servidor VD — carro mostra spinner estatico | **v0.7.4** |
| H12 | Fluxo | Primeiro dialogo de autenticacao USB ADB no celular sem orientacao na tela do carro — timeout de 30s | **v0.7.4** |

### Fase 3 — Medio (problemas perceptiveis)

| ID | Categoria | Achado | Status |
|----|----------|---------|--------|
| M1 | Perf | Servidor VD faz flush apos cada quadro no localhost — syscall desnecessaria | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` bloqueia leitor de comandos 500ms — apagao de toque apos Voltar | **v0.8.1** |
| M3 | Perf | Multi-toque envia N quadros separados por MOVE — deveria agrupar todos os ponteiros | **v0.8.3** |
| M4 | Perf | MotionEvent PointerProperties/Coords alocados por injecao — usar pool destes | **v0.8.1** |
| M5 | Perf | Fila do decoder com 6 de profundidade (200ms) — reduzir para 2-3 para menor latencia | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` na thread de toque — mover para timer | **v0.8.1** |
| M7 | Estabilidade | `cleanup()` duplo no servidor VD — finally do handleClient + run ambos chamam | **v0.7.2** |
| M8 | Estabilidade | `cleanupSession()` nao reseta `_serviceState` — UI estagnada durante atraso | **v0.7.2** |
| M9 | Estabilidade | MutableStateFlow estatico no companion sobrevive a reinicios do servico — activeConnection estagnado | **v0.7.2** |
| M10 | Fluxo | Desconexao do usuario (ejetar) nao persistida — carro reconecta apos finalizacao do processo | **v0.8.3** |
| M11 | Fluxo | mDNS do carro + sondagem de gateway IP sao unica tentativa — precisa de repeticao periodica | **v0.7.3** |
| M12 | Fluxo | Parse de `dumpsys activity` no checkStackEmpty fragil entre versoes do Android | **v0.8.2** |

### Fase 4 — Baixo (refinamento)

| ID | Categoria | Achado | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` aloca ByteArray de 25 bytes por evento — nao pode usar pool com fila de escrita assincrona | WONTFIX |
| L2 | Perf | Socket localhost do servidor VD sem configuracao de tamanho de buffer de envio/recebimento | **v0.8.2** |
| L3 | Perf | Video decoder usa timestamp fixo de 33.333us — deveria usar relogio de parede | **v0.8.2** |
| L4 | Estabilidade | ByteBuffer direto do NioReader nao liberado deterministicamente | **v0.8.3** |
| L5 | Estabilidade | `UsbAdbConnection.readFile()` nao garante leitura completa | **v0.8.2** |
| L6 | Estabilidade | SurfaceScaler HandlerThread nunca finalizada | **v0.8.2** |
| L7 | Fluxo | Icones de apps 48x48px — borrados em telas de carro, precisam de 96-128px | **v0.8.2** |

---

## Problemas Conhecidos

| Problema | Impacto | Status |
|-------|--------|--------|
| Dialogo de autenticacao USB ADB na reconexao | Celular perguntava "Permitir depuracao USB?" a cada vez | **CORRIGIDO v0.13.1** — estava fazendo hash duplo do AUTH_TOKEN com SHA1withRSA. Agora usa NONEwithRSA + SHA-1 DigestInfo pre-hash. "Sempre permitir" persiste. |
| Servidor VD morre na desconexao USB | Stream para se USB desconectado | Aceito — desacoplamento `setsid`/`nohup` quebrou conectividade localhost. Carro re-implanta na reconexao. |
| Injecao de toque acorda a tela fisica | Tela liga brevemente durante interacao | Mitigado com re-desligamento limitado (1s, em thread de fundo) |
| Apps retrato com letterbox em VD paisagem | Tela inicial do Petal Maps estreita | Limitacao do Android |
| Hotspot deve ser ativado manualmente | Usuario ativa antes de conectar | Limitacao do Android 16 |

---

## Arquitetura (Atual)

```
Celular (Xiaomi 17 Pro Max, HyperOS 3, Android 16)
├── DiLink Auto Client App
│   ├── ConnectionService (accept em 3 portas: 9637/9638/9639)
│   │   ├── Controle (9637): handshake, heartbeat, comandos, dados, logs do carro
│   │   ├── Video (9638): retransmissao H.264 do servidor VD para o carro
│   │   ├── Entrada (9639): eventos de toque do carro, despachados em Dispatchers.IO
│   │   ├── Implante do JAR VD em /sdcard/DiLinkAuto/ (CRC32 verificado)
│   │   ├── Auto-atualizacao do carro: envia UPDATING_CAR, depois dadb push+install
│   │   ├── Callback de rede inteligente (ignora quedas de rede nao relacionadas)
│   │   ├── Isencao de bateria (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
│   │   └── FileLog: /sdcard/DiLinkAuto/client.log (rotacao, max 10)
│   ├── VirtualDisplayClient (videoConnection + controlConnection)
│   │   ├── startListening() — ServerSocket sincrono em localhost:19637
│   │   ├── acceptConnection() — accept NIO nao-bloqueante
│   │   ├── NioReader (baseado em Selector, timeout FRAME_INTERVAL_MS)
│   │   └── Retransmissao de video via videoConnection, pilha vazia via controlConnection
│   └── NotificationService (captura notificacoes do celular com progresso)
│
├── Servidor VD (app_process, shell UID 2000)
│   ├── Fila de escrita NIO (ConcurrentLinkedQueue) + leitor de comandos via Selector
│   ├── Injecao IInputManager (ServiceManager → injectInputEvent)
│   ├── Multi-toque: pools pre-alocados de PointerProperties/Coords (10 espacos)
│   ├── VirtualDisplay (TRUSTED + OWN_DISPLAY_GROUP + OWN_FOCUS)
│   ├── SurfaceScaler (reducao GPU via EGL/GLES, pula trabalho GL em idle, repeat-previous-frame do codificador)
│   ├── Codificador H.264 (8Mbps CBR, perfil Main, FPS configuravel, contrapressao em 6 quadros)
│   ├── Desligamento de tela (thread de fundo, proximidade/levantar desabilitados)
│   └── Conexao NIO reversa para o celular em localhost:19637
│
Carro (BYD DiLink 3.0, Android 10)
├── DiLink Auto Server App
│   ├── CarConnectionService — 3 conexoes + trilha USB paralela
│   │   ├── controlConnection (9637): heartbeat, comandos, dados
│   │   ├── videoConnection (9638): quadros de video → VideoDecoder
│   │   ├── inputConnection (9639): eventos de toque do MirrorScreen
│   │   ├── Trilha B (USB): UsbAdbConnection com logSink → carLogSend
│   │   ├── Tratamento de UPDATING_CAR: mostra status, pula reconexao
│   │   ├── Inicio antecipado do decoder: superficie offscreen no primeiro CONFIG
│   │   ├── carLogSend() + callbacks logSink → FileLog do celular
│   │   └── Estado de ejecao persistido em SharedPreferences
│   ├── VideoDecoder (fila=15, inicio antecipado, logSink, catchup de 4 zonas)
│   ├── PersistentNavBar (76dp, icones 40dp, texto 14sp, apps recentes podados)
│   ├── LauncherScreen (icones 64dp, grade 160dp, busca com imePadding)
│   ├── NotificationScreen (barras de progresso, toque-para-iniciar, dedup por ID)
│   └── MirrorScreen (TextureView + encaminhamento de toque, reinicio do decoder)
```

## Fluxo de Conexao

```
1. App do celular inicia → implanta JAR VD, rotaciona FileLog, solicita isencao de bateria
2. Celular conectado ao USB do carro → carro detecta USB_DEVICE_ATTACHED
3. Trilha A (WiFi): carro descobre celular via gateway IP (tentativa a cada 3s) ou mDNS
4. Trilha B (USB): carro conecta USB ADB (logSink para diagnosticos), inicia app do celular
5. Controle (9637): TCP connect → handshake (viewport + DPI + versao + targetFps)
6. Celular: verifica versao → se diferente, envia UPDATING_CAR → auto-atualiza via dadb
7. Video (9638) + Entrada (9639): carro conecta em paralelo apos handshake
8. Celular: aceita ambos, abre ServerSocket do VD em localhost:19637
9. USB: carro inicia servidor VD (shellNoWait + exec app_process, FPS como arg)
10. Servidor VD: VD + SurfaceScaler (redesenho periodico) + codificador → NIO connect localhost:19637
11. Carro: inicia VideoDecoder em superficie offscreen no primeiro quadro CONFIG
12. MirrorScreen exibe → decoder reinicia com superficie TextureView real
13. Video: VD → SurfaceScaler → codificador → fila de escrita NIO → localhost → NioReader do celular → videoConnection → WiFi TCP → NioReader do carro → VideoDecoder → TextureView
14. Toque: TextureView do carro → inputConnection → WiFi TCP → celular (Dispatchers.IO) → Selector NIO do servidor VD → injecao IInputManager
15. Logs do carro: carLogSend() + callbacks logSink → DATA CAR_LOG → FileLog do celular
```
