# Guia de Configuração

## Pré-requisitos

- **Telefone:** Qualquer dispositivo Android 10+ com Depuração USB ativada
- **Carro:** BYD DiLink 3.0+ (ou qualquer unidade principal Android 10+ com porta USB host)
- **Cabo USB:** Cabo do telefone para a porta USB do carro
- **Desenvolvimento:** Android Studio ou Gradle, JDK 17

**Nenhuma conexão com a internet é necessária** — O DiLink-Auto transmite tudo localmente pelo hotspot WiFi do seu telefone (o carro e o telefone se comunicam diretamente). Uma conexão com a internet é necessária apenas para os aplicativos em execução no seu telefone (ex.: mapas, música), não para o DiLink-Auto em si.

## Configuração do Telefone (Única)

1. **Instale o DiLink Auto Client**: `adb install app-client-debug.apk`
2. **Abra o aplicativo** — a tela de integração irá guiá-lo por cada permissão:
   - **Acesso a Todos os Arquivos** — implanta o servidor de exibição virtual no armazenamento
   - **Otimização de Bateria** — mantém o streaming ativo quando a tela está desligada
   - **Serviço de Acessibilidade** — permite o controle da tela sensível ao toque do carro
   - **Acesso às Notificações** — encaminha as notificações do telefone para a tela do carro
3. Cada etapa abre as configurações relevantes do sistema. Conceda a permissão e pressione Voltar para continuar.
4. Você pode pular qualquer etapa e configurá-la posteriormente na tela principal.

Pronto. Sem Depuração Sem Fio, sem códigos de pareamento, sem configuração especial de WiFi.

### Opcional: Shizuku (Conexão sem ADB)

O [Shizuku](https://github.com/RikkaApps/Shizuku) fornece privilégios de shell elevados para aplicativos normais, permitindo que o DiLink-Auto implante o servidor Virtual Display sem precisar de uma conexão USB ADB do carro. Esta é uma alternativa ao canal USB ADB — o carro precisa apenas de conectividade WiFi.

1. **Instale o Shizuku** pelo [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) ou Google Play
2. **Inicie o Shizuku** uma vez via ADB (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) ou via root
3. **Abra o DiLink Auto** → Configurações → Shizuku → toque para conceder permissão
4. Quando o Shizuku estiver ativo, o telefone implanta o servidor VD diretamente. Sem necessidade de USB ADB ou Depuração Sem Fio.

Com o Shizuku ativo, o carro só precisa conectar via WiFi — o cabo USB não é necessário para streaming.

## Configuração do Carro

**Nenhuma conexão com a internet é necessária.** O APK do carro está incorporado dentro do APK do telefone — o telefone o envia para o carro pela rede WiFi local. Não é necessária conexão com a internet em nenhum momento durante a configuração ou transmissão.

Não é necessária instalação manual no carro. O APK do carro está incorporado dentro do APK do telefone. Na primeira conexão (ou quando houver incompatibilidade de versão), o telefone envia `UPDATING_CAR` para o carro (que mostra o status "Atualizando...") e, em seguida, faz a instalação automática via dadb (WiFi ADB).

Alternativamente, use o botão "Instalar no Carro" no aplicativo do telefone para enviar manualmente o APK do carro.

Se for instalar manualmente: `adb install app-server-debug.apk` (requer acesso ADB ao carro).

## Uso Diário

1. **Conecte o telefone à porta USB do carro** — o aplicativo do carro é iniciado automaticamente (ou conecte-se via WiFi na mesma rede)
2. Carro e telefone conectam-se automaticamente via faixas paralelas WiFi + USB
3. O telefone implanta o servidor VD e o streaming começa a 60fps
4. Use a tela sensível ao toque do carro para interagir com os aplicativos do telefone

## Compilação

```bash
# Compilar APK do telefone (dispara a compilação do servidor + incorporação automaticamente)
./gradlew :app-client:assembleDebug

# Localização do APK:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (telefone -- inclui APK do carro incorporado)
```

O sistema de compilação compila automaticamente o app-server e o incorpora no app-client, então apenas um APK precisa ser instalado manualmente.

## Como Funciona

Quando o telefone está conectado ao carro:

1. **Carro inicia faixas paralelas** — descoberta WiFi e detecção USB são executadas simultaneamente
2. **Faixa A (WiFi):** IP do gateway + descoberta mDNS → conexão NIO à porta de controle do telefone (9637)
3. **Faixa B (USB):** varredura de dispositivos → conexão USB ADB → inicia o aplicativo do telefone via `am start`
4. **Handshake:** carro envia viewport + DPI + appVersionCode + targetFps; telefone envia informações do dispositivo + vdServerJarPath
5. **Verificação de versão:** telefone compara appVersionCode — se houver incompatibilidade, envia a mensagem UPDATING_CAR para o carro e, em seguida, atualiza automaticamente via dadb
6. **Configuração de 3 conexões:** carro abre conexões de vídeo (9638) + entrada (9639) após o handshake
7. **Telefone implanta servidor VD** — extrai vd-server.jar para `/sdcard/DiLinkAuto/`, inicia `app_process` como UID de shell com argumento FPS
8. **Servidor VD faz conexão reversa** com o telefone em localhost:19637 (NIO não bloqueante)
9. **Servidor VD cria VirtualDisplay** no DPI nativo do telefone (480dpi) com redução de escala por GPU e redesenho periódico
10. **Streaming de vídeo** via WiFi TCP (conexão de vídeo 9638) — H.264, perfil Main, 8Mbps CBR, configurável até 60fps

## Solução de Problemas

### O diálogo de autenticação ADB aparece todas as vezes (CORRIGIDO na v0.13.1)
Corrigido — o problema era o hash duplo do AUTH_TOKEN. O ADB envia um token bruto de 20 bytes que deve ser tratado como um digest SHA-1 pré-calculado. O código antigo usava `SHA1withRSA`, que fazia o hash novamente. Agora usa `NONEwithRSA` com prefixo SHA-1 DigestInfo (pré-calculado), correspondendo ao `RSA_sign(NID_sha1)` do AOSP. O telefone aceita AUTH_SIGNATURE na reconexão e "Sempre permitir" persiste corretamente.

Se o diálogo ainda aparecer na primeira conexão após a atualização, marque "Sempre permitir" — ele não deve aparecer novamente nas conexões subsequentes. Se persistir, verifique as Opções do Desenvolvedor do telefone para "Desativar tempo limite de autorização ADB" (Android 11+).

### O aplicativo do telefone não inicia
- Verifique se a Depuração USB está ativada no telefone
- Verifique se a porta USB do carro suporta modo host (nem todas as portas suportam)
- Tente um cabo USB diferente (alguns cabos são apenas para carregamento)

### Permissão de Acesso a Todos os Arquivos negada
- O aplicativo do telefone precisa de MANAGE_EXTERNAL_STORAGE para implantar vd-server.jar em `/sdcard/DiLinkAuto/`
- Vá para Configurações -> Apps -> DiLink Auto -> Permissões -> Acesso a Todos os Arquivos -> ATIVAR

### Vídeo não está transmitindo / tela preta
- Verifique se o telefone e o carro estão na mesma rede
- Verifique se ambos os aplicativos estão em execução (telefone mostra "Transmitindo", carro mostra vídeo)
- O servidor VD pode precisar de um momento para iniciar — aguarde 5-10 segundos após conectar
- O redesenho periódico do SurfaceScaler deve produzir quadros mesmo em conteúdo estático
- Verifique `/sdcard/DiLinkAuto/client.log` para informações de diagnóstico

### Quedas de conexão
- Anteriormente causado por quedas de rede não relacionadas (ciclagem de dados móveis) disparando desconexão proativa
- Corrigido na v0.12.5: callback de rede inteligente ignora quedas em redes que não transportam a conexão
- Se persistir, verifique `/sdcard/DiLinkAuto/client.log` para entradas "Network lost"

### Aplicativo do carro não atualiza
- O telefone atualiza automaticamente o aplicativo do carro quando uma incompatibilidade de versão é detectada durante o handshake
- O carro mostra o status "Atualizando aplicativo do carro..." durante a atualização
- Você também pode disparar uma atualização manualmente com o botão "Instalar no Carro" no aplicativo do telefone
- Verifique se o dadb consegue alcançar o carro via WiFi ADB

### Logs
- Logs do telefone: `/sdcard/DiLinkAuto/client.log` (sessão atual)
- Sessões anteriores: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- Logs do servidor VD: `/data/local/tmp/vd-server.log` (no telefone, legível via ADB)
- Logs do carro: encaminhados para client.log do telefone via canal DATA do protocolo (tag: `CarLog`)
- Obter logs: `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## Dicas para HyperOS (Xiaomi)

Para operação confiável no HyperOS:
1. Configurações -> Apps -> DiLink Auto -> Inicialização Automática -> Ativar
2. Configurações -> Bateria -> DiLink Auto -> Sem Restrições
3. Fixe o aplicativo nos Aplicativos Recentes (pressione longamente o cartão -> Fixar)
## Dicas para Samsung One UI

Dispositivos Samsung rodando One UI 5+ (Android 13+) têm recursos adicionais de segurança e economia de energia que podem impedir o DiLink-Auto de funcionar corretamente. Isso se aplica às séries Galaxy A, M, S, Z e Tab.

### Desabilitar Auto Blocker (Crítico para USB ADB)

O **Auto Blocker** bloqueia comandos USB e pode impedir o carro de conectar ao seu telefone via USB ADB. Este é o problema mais comum em dispositivos Samsung.

1. **Configurações → Segurança e privacidade → Auto Blocker → Desativado**
2. Se preferir manter o Auto Blocker ativado, no mínimo desabilite a opção **"Bloquear comandos via cabo USB"**

### Permitir Acesso a Todos os Arquivos

O Gerenciador de Permissões da Samsung pode revogar automaticamente permissões de apps que você não abre recentemente:

1. **Configurações → Apps → DiLink Auto → Permissões → Arquivos e mídia → Permitir gerenciamento de todos os arquivos**
2. Ative **"Permitir gerenciamento de todos os arquivos"**
3. Verifique se permanece ATIVADO após fechar as configurações (a Samsung pode mostrar um popup de confirmação)

### Desabilitar Otimização de Bateria

O gerenciamento de bateria da Samsung é mais agressivo que o Android padrão:

1. **Configurações → Apps → DiLink Auto → Bateria → Irrestrito**
2. **Configurações → Bateria → Limites de uso em segundo plano → Apps nunca em suspensão → Adicionar DiLink Auto**
3. **Configurações → Bateria → Limites de uso em segundo plano → Apps em suspensão profunda → Remover DiLink Auto** se listado

### Fixar App nos Recentes

O Samsung One UI pode matar apps em segundo plano para liberar memória:

1. Abra Apps Recentes (deslize para cima a partir da parte inferior com navegação de 3 botões, ou navegação por gestos)
2. Toque no ícone do DiLink Auto no topo do card
3. Selecione **"Manter aberto"**

### Desabilitar Otimização Automática do Device Care

O Device Care da Samsung pode parar automaticamente serviços em segundo plano:

1. **Configurações → Bateria e cuidado com o dispositivo → Automação → Otimização diária automática → Desativado**
2. **Configurações → Bateria e cuidado com o dispositivo → Automação → Reinicialização automática → Desativado**

### Se Você Ver um Popup de Permissão "DeX"

Alguns dispositivos Samsung mostram um popup sobre permissões "Samsung DeX" ou "tela externa" quando um app tenta criar um display virtual. Mesmo que as séries Galaxy A/M não suportem DeX, o diálogo pode aparecer. Simplesmente toque em **"Permitir"** ou **"Iniciar agora"**. Se o diálogo continuar reaparecendo, vá para **Configurações → Dispositivos conectados → Samsung DeX** e desabilite "Iniciar automaticamente quando HDMI for conectado."

### Considerações de Segurança do Knox

O Samsung Knox pode mostrar uma notificação de segurança quando o DiLink-Auto acessa:
- Superfície de display virtual (para codificação de vídeo)
- Ponte de depuração USB (para conexão ADB do carro)
- Armazenamento de todos os arquivos (para implantar o servidor VD)

Esses são comportamentos esperados. Toque em "Permitir" ou "OK" em qualquer aviso relacionado ao Knox. Se os avisos persistirem, você pode reduzir temporariamente a proteção Knox para "Média" em **Configurações → Segurança e privacidade → Samsung Knox**.
