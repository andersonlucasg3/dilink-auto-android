# App Cliente do Celular (app-client)

## Visao Geral

O cliente do celular gerencia a implantacao do servidor VD, a auto-atualizacao do carro e a retransmissao de 3 conexoes. O app do celular:

1. Escuta por conexoes TCP do carro na porta 9637 (controle, NIO ServerSocketChannel)
2. Responde ao handshake com informacoes do dispositivo, vdServerJarPath e le o `targetFps`
3. Verifica o appVersionCode do handshake — envia `UPDATING_CAR` e auto-atualiza o app do carro via dadb se houver discrepancia de versao
4. Aceita conexoes de video (9638) e entrada (9639) do carro apos o handshake
5. Implanta vd-server.jar em `/sdcard/DiLinkAuto/` e inicia o servidor VD (com argumento FPS)
6. Aceita conexao reversa do servidor VD em localhost:19637 (NIO ServerSocketChannel)
7. Retransmite video H.264 do servidor VD para o carro via conexao de video
8. Retransmite eventos de toque do carro (conexao de entrada) para o servidor VD, despachados em `Dispatchers.IO`

**Sem captura de tela. Sem MediaProjection.** Todo o video vem do processo servidor VD.

## Componentes

### ClientApp

Classe Application. Cria canais de notificacao (`dilinkauto_service`, `dilinkauto_update`), inicializa `UpdateManager` no create.

### UpdateManager

Mecanismo de auto-atualizacao que verifica GitHub Releases por novas versoes.
- `checkForUpdate(force)`: Consulta `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest`, compara semver da tag name com o versionName instalado. Respeita intervalo de 6 horas, a menos que forçado.
- `downloadUpdate()`: Baixa APK via `HttpsURLConnection` com relatorio de progresso. Verifica via `PackageManager.getPackageArchiveInfo()`.
- `installUpdate(context)`: Abre o instalador de pacotes do sistema via URI do `FileProvider`.
- Estados: Idle, Checking, Available, Downloading, ReadyToInstall, UpToDate, Error. Expostos via `StateFlow`.

### MainActivity

Ponto de entrada com duas telas:

- **OnboardingScreen** (primeiro lancamento): Assistente de 7 etapas — Boas-vindas, Acesso a Todos os Arquivos, Otimizacao de Bateria, Servico de Acessibilidade, Acesso a Notificacoes, Configuracao do Carro, Concluido. Cada etapa de permissao explica o que quebra sem ela. Avanca automaticamente quando a permissao e concedida. O usuario pode pular qualquer etapa.
- **ClientScreen** (lancamentos subsequentes): cartao de status, botao iniciar/parar, Instalar no Carro, cartao de auto-atualizacao, botao Compartilhar Logs e status de permissoes restantes para as etapas puladas durante a integracao.

### ConnectionService

Servico em primeiro plano que gerencia o ciclo de vida da conexao celular-carro com 3 conexoes dedicadas. Inicia automaticamente quando o app do celular e aberto (ex.: via USB ADB do carro).

- **Conexao de controle** (porta 9637): Servidor NIO TCP em `0.0.0.0`, gerencia handshake, heartbeat, comandos de app, canal DATA
- **Conexao de video** (porta 9638): aceita apos handshake, passada ao VirtualDisplayClient para retransmissao de video
- **Conexao de entrada** (porta 9639): aceita apos handshake, listener de quadros INPUT despachado em `Dispatchers.IO` para evitar NetworkOnMainThreadException em escritas de toque no localhost
- `deployAssets()`: extrai vd-server.jar para o sdcard, app-server.apk para filesDir
- Detecta discrepancia de versao → envia `UPDATING_CAR` → auto-atualiza o app do carro via dadb (WiFi ADB, dadb 1.2.10)
- Callback de rede inteligente: `onLost` verifica se a rede perdida e a que a conexao utiliza, ignora quedas nao relacionadas (ciclagem de dados moveis)
- Retransmite quadros de video (H.264 CONFIG + FRAME) do VD para o carro via conexao de video
- Encaminha eventos de toque do carro (conexao de entrada) para o servidor VD
- Envia lista de apps com icones PNG 96x96 para o carro via conexao de controle
- Trata comandos LAUNCH_APP, GO_BACK, GO_HOME e os encaminha ao servidor VD
- Enfileira lancamentos de apps se o servidor VD ainda nao estiver conectado
- Registra servico mDNS para descoberta automatica pelo carro
- `FileLog.rotate()` ao iniciar o servico — arquiva o log da sessao anterior
- Botao "Instalar no Carro": instalacao manual + automatica ao detectar versao diferente no handshake

### VirtualDisplayClient

Aceita conexao reversa do processo servidor VD em `localhost:19637`. Recebe dois parametros Connection: `videoConnection` e `controlConnection`.

- Accept NIO ServerSocketChannel (nao-bloqueante) — servidor VD conecta AO celular
- Le: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Escreve: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Quadros de video retransmitidos via `videoConnection.sendVideo()` (isolados do trafego de controle)
- Sinal de pilha vazia (`MSG_STACK_EMPTY`) encaminhado ao carro via `controlConnection.sendControl()`
- Escritas de toque para localhost sao sincronas com `FrameCodec.writeAll()` sob `writeLock`
- Ao desconectar: restaura a tela fisica (`cmd display power-on 0` + `KEYCODE_WAKEUP`) como rede de seguranca quando o processo servidor VD e finalizado antes da limpeza

### AdbBridge

Auxiliar de comandos shell de fallback. Fornece `execShell()` e `execFast()` usando `Runtime.exec()` para operacoes do servidor VD e gerenciamento de energia da tela quando a reflexao direta da API falha.

### VirtualDisplayManager

Gerencia o lancamento de apps na tela fisica quando o VD nao esta em uso. Faz ponte com `InputInjectionService` para injecao de entrada baseada em gestos.

### VideoEncoder

Codificador H.264 via MediaProjection + MediaCodec usando virtual display `AUTO_MIRROR`. Caminho de codificacao alternativo (nao usado no pipeline de streaming principal, que flui pelo servidor VD).

### FileLog

Logger baseado em arquivo que ignora a filtragem do logcat do Android (HyperOS filtra `Log.i/d` para apps nao-sistema).

- Escreve em `/sdcard/DiLinkAuto/client.log`
- `rotate()`: arquiva o log atual como `client-YYYYMMDD-HHmmss.log`, inicia um novo
- `zipLogs()`: cria `dilinkauto-logs.zip` a partir de todos os arquivos `.log` para compartilhamento
- Mantem no maximo 10 logs (9 arquivados + atual)
- Thread-safe: ConcurrentLinkedQueue lock-free drenada por thread de escrita
- Tambem chama `android.util.Log.*` para saida padrao do logcat

### Retransmissao Multi-Toque

Eventos de toque chegam do carro via conexao de entrada como CMD_INPUT_TOUCH (0x32) com dados brutos de MotionEvent. O `handleInputFrame` e despachado em `Dispatchers.IO` (nao Main) para permitir escritas no socket localhost. O celular transmite eventos DOWN/MOVE/UP com pointerId diretamente para o servidor VD, que lida com a construcao completa do MotionEvent com todos os ponteiros ativos.

## Permissoes Necessarias

| Permissao | Proposito |
|-----------|-----------|
| MANAGE_EXTERNAL_STORAGE | Acesso a Todos os Arquivos para implantacao do JAR VD no sdcard |
| Servico de Acessibilidade | Injecao de entrada na tela fisica (fallback) |
| Acesso a Notificacoes | Encaminhar notificacoes para o carro (com progresso) |
| Depuracao USB | Necessaria para a trilha USB ADB do carro (Opcoes do Desenvolvedor) |

## Dependencias

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (WiFi ADB para auto-atualizacao do carro)
- Modulo Protocol (compartilhado com o app do carro)
