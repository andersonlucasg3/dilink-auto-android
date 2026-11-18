# Guia de configuração

## Pré-requisitos

- **Celular:** qualquer aparelho Android 10+ (`minSdk 29`) com Depuração USB ativada
- **Carro:** central multimídia BYD DiLink 3.0+ rodando Android 8.0+ (API 26+). O app da central tem como alvo `minSdk 26`; a biblioteca compartilhada de protocolo tem o mesmo piso. Carro testado: BYD Qin PLUS DM-i 2023 Champion 55KM Leading trim — central DiLink 4.0 de baixa especificação (Snapdragon 439, 1280x800, Android 9 / API 28, WiFi somente 2,4 GHz, decodificação H.264 por hardware limitada a 1080p). Outras centrais DiLink 3.0+ devem funcionar, mas não são exercitadas regularmente.
- **Cabo USB:** do celular até a porta USB do carro (necessário apenas para o caminho via ADB USB; o caminho por WiFi dispensa cabo)
- **Desenvolvimento:** Android Studio ou Gradle, JDK 17, Android SDK Platform 34 (`compileSdk 34`)

**Não é necessária conexão com a internet** — o DiLink-Auto transmite pelo hotspot WiFi do seu celular (o carro se conecta diretamente ao celular). A conexão com a internet só é necessária para os apps que rodam no celular (por exemplo, mapas, música), não para o DiLink-Auto em si.

## Configuração do celular (uma vez só)

1. Instale o APK do cliente DiLink Auto (`app-client/build/outputs/apk/debug/app-client-debug.apk`). Builds já compilados ficam em [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest); compilar a partir do código-fonte é explicado abaixo.
2. Abra o app — a tela de onboarding percorre cada permissão, uma de cada vez:
   - **Acesso a todos os arquivos** — necessário para fazer o deploy do jar do servidor de tela virtual em `/sdcard/DiLinkAuto/`
   - **Otimização de bateria** — isente o app para que o streaming continue ativo com a tela desligada
   - **Serviço de acessibilidade** — habilita a injeção de toque da tela da central na tela virtual
   - **Configuração do carro** — envia o APK embutido da central para a central multimídia via ADB por WiFi (veja Configuração do carro abaixo)
3. Cada etapa abre a tela de configurações do sistema correspondente. Conceda a permissão e pressione Voltar para retornar; o app verifica de novo ao retomar e avança sozinho assim que detecta a permissão.
4. Qualquer etapa pode ser pulada e concluída depois na tela principal.

Não são necessários códigos de pareamento da Depuração sem fio nem configuração especial de WiFi. O Shizuku (abaixo) é o único caminho opcional de shell privilegiado.

### Opcional: Shizuku (conexão sem ADB)

O [Shizuku](https://github.com/RikkaApps/Shizuku) dá a apps comuns privilégios de shell elevados, permitindo que o DiLink-Auto faça o deploy do servidor de Tela Virtual sem uma conexão ADB USB a partir do carro. Com o Shizuku ativo, o celular faz o deploy do servidor VD diretamente e o carro só precisa de conectividade WiFi — o cabo USB não é necessário para o streaming.

1. Instale o Shizuku pelo [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) ou pelo Google Play
2. Inicie o Shizuku uma vez via ADB (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) ou via root
3. No DiLink Auto: Configurações → cartão Shizuku → toque para abrir o app Shizuku e autorizar o DiLink Auto
4. Com o Shizuku em execução, o celular faz o deploy do servidor VD diretamente — não é necessário ADB USB nem Depuração sem fio

## Configuração do carro

**Não é necessária conexão com a internet.** O APK da central é embutido no APK do celular como asset; o celular o envia para o carro pela WiFi local. Nenhuma instalação manual no carro é necessária para o fluxo normal.

Instale o app da central pela etapa "Configuração do carro" do onboarding do celular ou pelo botão "Instalar na central" da tela principal; os dois acionam o mesmo caminho (dadb via ADB por WiFi na porta 5555).

Instalação manual (apenas se você tiver acesso ADB ao carro e quiser ignorar o envio automático): `adb install app-server-debug.apk`.

## Uso diário

1. Conecte o celular na porta USB do carro — o app da central inicia automaticamente — ou conecte o celular e o carro à mesma rede WiFi.
2. Celular e carro se conectam pelo caminho WiFi (o USB só serve para inicializar o ADB; o streaming em si é por TCP sobre WiFi).
3. O celular faz o deploy do servidor VD (`vd-server.jar` extraído para `/sdcard/DiLinkAuto/`, iniciado como `app_process` rodando com UID shell) e o streaming começa a 20 fps, 4 Mbps CBR, H.264 perfil Main.
4. Use a tela de toque da central para interagir com os apps do celular. A barra de navegação fixa na parte inferior da tela da central tem três botões: **Ejetar** (desconectar a sessão), **Início** e **Voltar**. Não há botão de notificação, trilho de apps recentes, relógio nem informação de rede — tudo isso foi removido neste fork.
5. Pressione e segure o tile de um app no launcher da central para fixá-lo no topo (ou desafixar). A tela Allowlist do lado do celular (`AllowlistScreen`) controla quais apps instalados aparecem no launcher da central.

### Substituição de DPI no lado da central

Centrais DiLink 4.0 de baixa especificação como a BYD 1280x800 testada vêm com uma densidade padrão que renderiza a UI da central — e a tela virtual que ela hospeda — pequena demais para o uso por toque dentro do carro. O app da central expõe uma substituição de **DPI inicial** no ConnectionStatusCard em `CarLaunchScreen`:

- Deixe o campo vazio (ou `0`) para **Automático** — o celular escolhe um DPI seguro via `VideoConfig.calculateOptimalDpi`, que limita a densidade para que apps somente-retrato (Amap, WeChat etc.) obtenham pelo menos ~360dp de largura lógica dentro da tela virtual em paisagem.
- Informe um valor na faixa **[120, 480]** para forçar um DPI específico na próxima sessão. A central coage a entrada por meio de `VdDeployArgs.coerceDpiOverride`, de modo que celular e central sempre concordam sobre o que conta como substituição válida.
- A substituição fica gravada em `startup_dpi` nas SharedPreferences da central e é aplicada na conexão WiFi inicial e também no novo handshake de rotação no meio do streaming. Alterá-la não afeta uma sessão em andamento — reconecte para aplicar.

## Modelo de segurança (fronteiras de confiança)

**O DiLink-Auto foi projetado apenas para uma rede local confiável.** Toda a comunicação entre o celular, o app da central e o cliente desktop é TCP em texto claro, sem segredo de pareamento, e o servidor VD roda como `shell UID (2000)` — o mesmo nível de privilégio de `adb shell`. Trate o SSID do hotspot do celular como equivalente a ter acesso de depuração USB ao aparelho.

O que isso significa na prática:

- **Qualquer pessoa que alcance as portas `9637/9638/9639` pode controlar a sessão espelhada**: abrir apps, injetar toque, desinstalar pacotes e apagar a tela física do celular. As mitigações entregues na rodada de endurecimento de segurança (S-01/S-02):
  - o servidor VD faz bind em `0.0.0.0:9638/9639`, mas **recusa accepts de qualquer IP que não seja o receptor para o qual foi iniciado** (slot de argv `CAR_HOST`, preenchido pelo deployer a partir do endereço remoto da conexão de controle ativa);
  - todo nome de pacote/componente que chega a `am start` / `pm uninstall` é validado quanto à forma e escapado para o shell (`requirePackageName` / `requireComponentName` + `VdDeploy.shellQuote`);
  - o listener de ciclo de vida do celular na porta `19647` faz bind **apenas em loopback** e rejeita peers que não sejam de loopback;
  - os decodificadores de protocolo rejeitam frames malformados em vez de quebrar, e o reader derruba a conexão (não o processo) em qualquer frame inválido.
- **Shizuku (opcional)** concede por projeto privilégios equivalentes aos de shell ao app do celular — qualquer coisa capaz de se vincular ao Shizuku pode disparar comandos de shell. Só pareie o Shizuku com apps em que você confia. O provider Shizuku no manifesto é protegido por `android.permission.INTERACT_ACROSS_USERS_FULL` (signature|privileged).
- **Não exponha essas portas** a um hotspot compartilhado com dispositivos não confiáveis, a uma WiFi pública ou a um AP hostil. Use um SSID dedicado para o enlace com o carro quando possível.
- **Sem criptografia nem integridade**: um MITM na mesma LAN pode ver a tela espelhada e injetar entrada. Ainda não existe protocolo de pareamento.
- O **keystore de assinatura de release nunca é commitado** (veja `docs/release.md`). Builds de release exigem `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` e falham rápido quando elas faltam; o keystore em si existe apenas nos secrets do CI.
- O **par de chaves ADB** do app da central fica no `filesDir` privado do app (não em `/sdcard`, legível por todos), de modo que um app instalado junto não consegue roubar a credencial ADB do celular.

## Compilação

```bash
# Build phone APK (triggers server build + embed automatically)
./gradlew :app-client:assembleDebug

# Phone APK location:
#   app-client/build/outputs/apk/debug/app-client-debug.apk
#   (includes the embedded car APK and vd-server.jar as assets)
```

A tarefa `buildVdServer` compila `vd-server/` para `vd-server.dex`, empacota o resultado como `vd-server.jar` e o copia para `app-client/src/main/assets/`. A tarefa `embedServerApk` embute `app-server.apk` nos assets do cliente da mesma forma. Apenas o APK do celular precisa ser instalado manualmente — o APK da central e o jar do servidor VD são enviados/embutidos automaticamente.

Builds de release exigem que as variáveis de ambiente `RELEASE_KEYSTORE_PASSWORD` e `RELEASE_KEY_PASSWORD` estejam definidas; se qualquer uma faltar, o build falha imediatamente em vez de produzir um APK sem assinatura. O keystore em si nunca é commitado — ele existe apenas nos secrets do CI. Versão atual: `0.19.0` (versionCode `26111724`). Veja [../release.md](../release.md) para o procedimento completo de assinatura e release.

> Atualizar de um build assinado em debug para o assinado oficialmente exige desinstalar o app antigo antes — os certificados são diferentes, então a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Como funciona

Quando o celular se conecta ao carro:

1. **A central inicia caminhos em paralelo** — a descoberta por WiFi e a detecção USB rodam simultaneamente.
2. **Caminho A (WiFi):** IP do gateway + descoberta mDNS → conexão NIO na porta de controle do celular `9637`.
3. **Caminho B (USB):** varredura de dispositivos → conexão ADB USB → abertura do app do celular via `am start`.
4. **Handshake:** a central envia as dimensões do viewport + substituição de DPI + appVersionCode + targetFps; o celular responde com informações do dispositivo e `vdServerJarPath`.
5. **Montagem da conexão direta:** após o handshake a central abre as conexões de vídeo (`9638`), entrada (`9639`) e áudio (`9640`, se negociado); o servidor VD se reconecta ao celular em localhost:`19647` para o canal de ciclo de vida.
6. **O celular faz o deploy do servidor VD** — extrai `vd-server.jar` para `/sdcard/DiLinkAuto/`, inicia `app_process` com UID shell passando as dimensões da VD, o DPI, as dimensões de codificação e o FPS como argumentos.
7. **O servidor VD cria a VirtualDisplay** no DPI negociado (Automático ou substituição) e roda o pipeline GL: relógio de frames → render GL → drenagem do encoder → escrita TCP, tudo em uma única thread com temporização por `System.nanoTime()` / `LockSupport.parkNanos()`.
8. **O vídeo é transmitido** por TCP sobre WiFi na porta `9638` — H.264 perfil Main, 4 Mbps CBR, 20 fps, dimensões de codificação limitadas a 1920x1080. O bitrate adaptativo cai para 75% (nunca abaixo do piso de 1,5 Mbps) quando a central para de drenar, e depois volta a subir em passos de 0,5 Mbps após cada 2 s de escritas limpas.

Referência de portas: `9637` controle+dados, `9638` vídeo, `9639` entrada de toque, `9640` áudio, `19647` ciclo de vida (servidor VD → localhost do celular), `5555` ADB TCP.

## Solução de problemas

### O diálogo de autenticação ADB aparece toda vez

Isso era um bug real no upstream — o AUTH_TOKEN era hasheado duas vezes. O ADB envia um token bruto de 20 bytes que precisa ser tratado como um digest SHA-1 já pré-hasheado; o código antigo usava `SHA1withRSA`, que o hasheava de novo. A correção usa `NONEwithRSA` com um prefixo DigestInfo de SHA-1 (prehashed), igual ao `RSA_sign(NID_sha1)` do AOSP. O celular agora aceita AUTH_SIGNATURE na reconexão e o "Sempre permitir" persiste corretamente.

Se o diálogo aparecer na primeira conexão após a atualização, marque "Sempre permitir" — ele não deve reaparecer. Se persistir, verifique nas Opções do desenvolvedor do celular a opção "Desativar tempo limite de autorização de ADB" (Android 11+).

### O app do celular não inicia

- Garanta que a Depuração USB esteja ativada no celular
- Verifique se a porta USB do carro suporta modo host (nem todas suportam)
- Tente outro cabo USB (alguns cabos só carregam)

### Permissão de acesso a todos os arquivos negada

- O app do celular precisa de `MANAGE_EXTERNAL_STORAGE` para fazer o deploy de `vd-server.jar` em `/sdcard/DiLinkAuto/`
- Configurações → Apps → DiLink Auto → Permissões → Acesso a todos os arquivos → ATIVADO

### Vídeo não transmite / tela preta

- Garanta que celular e carro estejam na mesma rede
- Verifique se os dois apps estão rodando (o celular mostra "Transmitindo", a central mostra o vídeo)
- O servidor VD pode precisar de um momento para iniciar — espere de 5 a 10 segundos depois de conectar
- Na API 26-28, o fallback de shell `cmd display power-reset`/`power-off` não está disponível (somente API 29+); uma falha de reflexão em DisplayControl significa que o painel físico não é restaurado, e a falha agora é registrada em log em vez de mascarada silenciosamente. Procure erros de `DisplayControl` no log.
- Verifique `/sdcard/DiLinkAuto/client.log` para informações de diagnóstico
- **Se o servidor VD falhar ao iniciar** (por exemplo, o Shizuku morreu no meio do deploy), o celular agora reporta a falha honestamente e encerra a sessão em vez de mostrar um estado falso de "iniciado" — procure `VD server launch failed` em `client.log`, restaure o Shizuku (ou use o caminho ADB USB) e reconecte
- **Tela preta sustentada (>3s)**: o app da central agora detecta isso automaticamente e pede ao celular a reconstrução da VD via novo handshake. Procure `[BLACK] requesting VD rebuild via re-handshake` nos logs. Isso deve se autocorrigir sem intervenção manual. A janela de sustentação fica no `protocol-core` (`BlackScreenDetector.BLACK_SCREEN_SUSTAIN_MS = 3000`), compartilhada com o receptor Windows (que usa uma janela de 10 s).

### A rotação causa tela preta no meio da sessão

Uma condição de corrida costumava deixar o decodificador incapaz de reiniciar após a rotação: `onCarViewportChanged` colocava o estado em CONNECTING e destruía a VD para novo deploy, mas o gate do layout de streaming dependia de `isConnected` (falso durante os ~2 s de redeploy), o que devolvia o CarShell para `CarLaunchScreen` e removia a SurfaceView antes que a nova surface pudesse reiniciar o decodificador. O gate agora também aceita `state == CONNECTING && appList.isNotEmpty()`, de modo que o layout de streaming permanece montado durante o redeploy da rotação. Se ainda acontecer, reconecte.

### Quedas de conexão

- As novas tentativas de reconexão não matam mais uma sessão ativa — a central para de tentar após 3 tentativas consecutivas sem ADB (log: `No ADB after 3 attempts — stopping reconnect`), e o ADB TCP reconecta quando o IP do celular muda em vez de derrubar o stream em execução.
- Se persistir, procure entradas "Network lost" em `/sdcard/DiLinkAuto/client.log`.
- **Vazamento de VD na reconexão**: corrigido originalmente na v0.18.0-dev-13 e **corrigido de novo em 2026-10-10** depois que medições no MI 9 mostraram que os mecanismos originais não funcionavam em aparelho real: o `app_process` do ART não executa o shutdown hook da JVM em SIGTERM (o "stop em duas etapas" era um kill forçado puro, e `PipelineServer.cleanup()` nunca rodava), e o toybox rejeita `pkill -0` (as duas sondas de vivacidade sempre reportavam GONE, então o "esperar pela saída" nunca esperava). A parada agora é um **sentinela de pedido de parada** (`gracefulStopCommand` escreve `/sdcard/DiLinkAuto/stop-request`; o watchdog do motor o consome e sai pelo caminho normal, de modo que o `cleanup()` completo roda), as sondas usam `pgrep -f`, e a sequência de deploy espera até 12 s antes de cair no `killCommandForce` (medido no MI 9, do sentinela até o processo desaparecer levam 6,1~6,3 s — a estimativa anterior de "cleanup 2~4 s" enxergava apenas os comandos de shell internos do cleanup; o orçamento de 6 s ficou 130 ms curto, e cada reconexão ainda levava mais um -9, cortando a restauração de `screen_off_timeout`). Uma guarda de idempotência do cleanup continua impedindo teardown duplicado. Para verificar a correção, use `scripts/verify-blackscreen-fix.sh` em um zip de share-logs.

### O app da central não instala

- Gatilho manual: botão "Instalar na central" no app do celular
- Garanta que o dadb consiga alcançar o carro via ADB por WiFi (porta 5555)

### A UI da central é pequena demais / os apps ficam apertados

Este é o sintoma para o qual a substituição de **DPI inicial** existe. Na central BYD 1280x800 testada, a densidade padrão deixa apps somente-retrato com apenas ~132dp de largura lógica dentro da tela virtual em paisagem. Deixe o campo DPI inicial em Automático (vazio) para o `VideoConfig.calculateOptimalDpi` escolher um limite seguro, ou informe um valor em `[120, 480]` para forçar uma densidade específica. Reconecte para aplicar.

### Os apps aparecem com faixas pretas ou não preenchem a tela

Isso é normal. O DiLink-Auto espelha os apps em uma tela virtual em paisagem que corresponde à tela da central. Alguns apps (especialmente os pensados principalmente para celular) só suportam a orientação retrato e aparecerão com faixas pretas ou estreitos. O modo DPI Automático existe para dar a esses apps largura lógica suficiente para serem utilizáveis; forçar uma substituição de DPI alta pode deixá-los apertados de novo.

### Logs

- Logs do celular: `/sdcard/DiLinkAuto/client.log` (sessão atual)
- Sessões anteriores: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- Logs do servidor VD: `/data/local/tmp/vd-server.log` (no celular, legível via ADB)
- Logs da central: roteados para o `client.log` do celular pelo canal DATA do protocolo (tag: `CarLog`)
- Baixar logs: `adb shell "cat /sdcard/DiLinkAuto/client.log"`
- A chave **Logs de diagnóstico** fica em **Configurações → Debug**. Quando desligada, nenhuma linha de log é gravada em disco. A escolha se propaga para a central pela conexão de controle ativa via mensagem de dados `LOG_TOGGLE`, de modo que o relay de log do lado da central (`carLogEnabled`) segue a configuração do celular. Padrões: ATIVADO em builds debug/pré-release, DESATIVADO em release; depois que você alterna a chave, sua escolha persiste.

## Verificação da correção do vazamento de VD

Depois de atualizar, compartilhe os logs do celular (Configurações → Compartilhar logs) e rode o script de verificação:

```bash
unzip dilinkauto-logs.zip -d /tmp/vdcheck
bash scripts/verify-blackscreen-fix.sh /tmp/vdcheck
```

O script verifica:
- **Vazamento de VD**: contagem de inícios de VD vs contagem de cleanups completos (deveriam ser iguais); ele também imprime a sequência de displayId, que não deveria ficar subindo a cada reconexão
- **Idempotência do cleanup**: contagem de desconexões vs contagem de "Force-waking physical display" (deveria ser ~1:1)
- **Restauração do Shizuku**: qualquer linha `Shizuku display restore failed: Job was cancelled` é sinalizada — uma restauração cancelada significa que o painel físico pode ter ficado desligado
- **Poluição do tempo limite de tela**: a última escrita de `screen_off_timeout` não deveria ser o sentinela `2147483647`
- **Autocorreção de tela preta**: detecção de tela preta sustentada vs gatilho de reconstrução da VD
- **Auditoria do caminho de parada**: contagens de sucesso/falha de CMD_STOP, resultados da sonda de saída da VD e ocorrências do timeout "still alive after"

Se `client.log` estiver ausente (já rotacionado), o script usa como fallback o maior `client-*.log` rotacionado no diretório.

Código de saída 0 = todas as verificações passaram; 1 = uma ou mais verificações falharam.

### Verificação da reconexão / limpeza no fechamento (totalmente automatizada, 2026-10-10)

O sentinela de pedido de parada, o orçamento de 12 s para a saída graciosa e a restauração de `screen_off_timeout` são cobertos por uma **regressão ponta a ponta que conduz uma sessão real**: `scripts/e2e-reconnect-cleanup.sh` — gate de build (`./gradlew test` + os dois APKs, com asserção de 0 falhas de teste) → checagem de símbolos dex no `assets/vd-server.jar` do APK (`ShellSyncPoint` / `__DILINK_SYNC__` / `awaitIdle` / `pgrep -f` / `stop-request`) → instalação + relançamento do app do celular → sessão no desktop com um `DILINK_DESKTOP_HOME` isolado → 「显示 → 应用并重连」 conduzido pela UI e fechamento da janela → asserções ao vivo no dispositivo (sentinela `screen_off_timeout` 2147483647 durante a sessão, restauração para a linha de base 300000 após o cleanup, motor ausente) → asserções sobre os logs coletados via `scripts/verify-reconnect-cleanup.sh` (transições de sonda ALIVE→GONE, 「引擎已优雅退出」 **sem** 「强制 kill」, tempo decorrido < orçamento de 12 s, eco de drenagem `__DILINK_SYNC__`, `Cleanup complete`, um segundo handshake). Exige um celular em ADB e um desktop Windows **desbloqueado e ocioso** — as etapas de UI movem o mouse real e clicam na janela, então nada mais pode roubar o foco nesse meio-tempo; artefatos em `debug-logs/e2e/<timestamp>/`; código de saída 0 = passou. Opções: `--skip-build`, `--no-install`, `--serial <adb-serial>`. A primeira execução ao vivo (2026-10-10) revelou seis falhas de robustez do script — o lock de primeiro plano do Windows engoliu os cliques injetados, e a sondagem por contagem de iterações nesta máquina de ~1 s por fork esticou um orçamento nominal de 150 s para 626 s; depois do endurecimento (orçamentos em tempo real decorrido, foco de janela baseado em `AttachThreadInput`, verificação do efeito do clique com uma nova tentativa) a execução final passou **32/32 em 2m48s**, com as duas saídas graciosas em 6462/6499ms contra o orçamento de 12 s.

## Dicas para HyperOS (Xiaomi)

Para operação confiável no HyperOS:

1. Configurações → Apps → DiLink Auto → Início automático → Ativar
2. Configurações → Bateria → DiLink Auto → Sem restrições
3. Fixe o app nos Apps recentes (pressione e segure o cartão → Fixar)

## Dicas para Samsung One UI

Aparelhos Samsung com One UI 5+ (Android 13+) têm recursos extras de segurança e economia de energia que podem impedir o DiLink-Auto de funcionar corretamente. Isso vale para as séries Galaxy A, M, S, Z e Tab.

### Desative o Auto Blocker (crítico para ADB USB)

O **Auto Blocker** bloqueia comandos USB e pode impedir que a central se conecte ao seu celular via ADB USB. Este é o problema específico de Samsung mais comum.

1. Configurações → Segurança e privacidade → Auto Blocker → Desativado
2. Se preferir manter o Auto Blocker ativado, desative no mínimo a opção **"Bloquear comandos por cabo USB"**

### Permitir acesso a todos os arquivos

O Permission Manager da Samsung pode revogar automaticamente permissões de apps que você não abriu recentemente:

1. Configurações → Apps → DiLink Auto → Permissões → Arquivos e mídia → Permitir gerenciamento de todos os arquivos
2. Ative **"Permitir gerenciamento de todos os arquivos"**
3. Confirme que continua ativado depois de fechar as configurações (a Samsung pode mostrar um popup de confirmação)

### Desative a otimização de bateria

O gerenciamento de bateria da Samsung é mais agressivo que o do Android puro:

1. Configurações → Apps → DiLink Auto → Bateria → Sem restrições
2. Configurações → Bateria → Limites de uso em segundo plano → Apps que nunca dormem → Adicionar DiLink Auto
3. Configurações → Bateria → Limites de uso em segundo plano → Apps em suspensão profunda → Remover o DiLink Auto se estiver na lista

### Fixe o app nos recentes

O Samsung One UI pode encerrar apps em segundo plano para liberar memória:

1. Abra os Apps recentes (deslize de baixo para cima com navegação por 3 botões, ou com navegação por gestos)
2. Toque no ícone do DiLink Auto no topo do cartão do app
3. Selecione **"Manter aberto"**

### Desative a otimização automática do Samsung Device Care

O Device Care da Samsung pode parar serviços em segundo plano automaticamente:

1. Configurações → Bateria e cuidados do dispositivo → Automação → Otimizar automaticamente todos os dias → Desativado
2. Configurações → Bateria e cuidados do dispositivo → Automação → Reinício automático → Desativado

### Se aparecer um popup de permissão "DeX"

Alguns aparelhos Samsung mostram um popup sobre permissões do "Samsung DeX" ou de "tela externa" quando um app tenta criar uma tela virtual. Mesmo que as séries Galaxy A/M não suportem DeX, o diálogo pode aparecer. Basta tocar em **"Permitir"** ou **"Iniciar agora"**. Se o diálogo continuar reaparecendo, vá em Configurações → Dispositivos conectados → Samsung DeX e desative "Iniciar automaticamente quando o HDMI estiver conectado".

### Considerações sobre o Samsung Knox

O Samsung Knox pode mostrar uma notificação de segurança quando o DiLink-Auto acessa:

- A superfície da tela virtual (para codificação de vídeo)
- A ponte de depuração USB (para a conexão ADB com a central)
- O armazenamento de todos os arquivos (para o deploy do servidor VD)

Esses comportamentos são esperados. Toque em "Permitir" ou "OK" em qualquer prompt relacionado ao Knox. Se os prompts persistirem, você pode baixar temporariamente a proteção do Knox para "Média" em Configurações → Segurança e privacidade → Samsung Knox.
