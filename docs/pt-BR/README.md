# DiLink-Auto

Use os aplicativos do seu celular na tela do carro. Código aberto, sem necessidade de Google Services.

Uma alternativa open-source ao Android Auto para **qualquer celular Android 10+** combinado com sistemas de infoentretenimento **BYD DiLink 3.0+**. Motivado originalmente pela lacuna do Xiaomi HyperOS / ROM Chinesa, mas funciona universalmente.

[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-pink?logo=github)](https://github.com/sponsors/andersonlucasg3)
[![Pix](https://img.shields.io/badge/Pix-Brazil-00C2A0)](https://nubank.com.br/cobrar/5gf35/69ed4939-b2c0-4071-b75d-3b430ab70a5d)

## Documentation / Documentação / Документация / Documentatio / Hujjatlar

O GitHub **não** exibe automaticamente a documentação no idioma do usuário. Selecione seu idioma abaixo:

| Language | README | Setup | Architecture | Client | Server | Protocol | Progress |
|----------|--------|-------|-------------|--------|--------|----------|----------|
| English | [README](./README.md) | [Setup](./setup.md) | [Arch](./architecture.md) | [Client](./client.md) | [Server](./server.md) | [Proto](./protocol.md) | [Progress](./progress.md) |
| Português (BR) | [README](./pt-BR/README.md) | [Setup](./pt-BR/setup.md) | [Arch](./pt-BR/architecture.md) | [Client](./pt-BR/client.md) | [Server](./pt-BR/server.md) | [Proto](./pt-BR/protocol.md) | [Progress](./pt-BR/progress.md) |
| Français | [README](./fr/README.md) | [Setup](./fr/setup.md) | [Arch](./fr/architecture.md) | [Client](./fr/client.md) | [Server](./fr/server.md) | [Proto](./fr/protocol.md) | [Progress](./fr/progress.md) |
| Русский | [README](./ru/README.md) | [Setup](./ru/setup.md) | [Arch](./ru/architecture.md) | [Client](./ru/client.md) | [Server](./ru/server.md) | [Proto](./ru/protocol.md) | [Progress](./ru/progress.md) |
| Беларуская | [README](./be/README.md) | [Setup](./be/setup.md) | [Arch](./be/architecture.md) | [Client](./be/client.md) | [Server](./be/server.md) | [Proto](./be/protocol.md) | [Progress](./be/progress.md) |
| Қазақша | [README](./kk/README.md) | [Setup](./kk/setup.md) | [Arch](./kk/architecture.md) | [Client](./kk/client.md) | [Server](./kk/server.md) | [Proto](./kk/protocol.md) | [Progress](./kk/progress.md) |
| Українська | [README](./uk/README.md) | [Setup](./uk/setup.md) | [Arch](./uk/architecture.md) | [Client](./uk/client.md) | [Server](./uk/server.md) | [Proto](./uk/protocol.md) | [Progress](./uk/progress.md) |
| Oʻzbekcha | [README](./uz/README.md) | [Setup](./uz/setup.md) | [Arch](./uz/architecture.md) | [Client](./uz/client.md) | [Server](./uz/server.md) | [Proto](./uz/protocol.md) | [Progress](./uz/progress.md) |

## O Que Faz

O DiLink-Auto espelha os aplicativos do seu celular na tela do carro com interação por toque completa. Inicie navegação, música, mensagens — qualquer app do seu celular — diretamente da tela do carro. As notificações aparecem na barra de navegação do carro com indicadores de progresso. Vídeo H.264 a até 60fps, 8Mbps CBR, com a tela do celular desligada para economizar bateria.

**Motivação original:** preencher a lacuna quando seu celular não pode executar o Android Auto (ROM Chinesa, sem Google Play Services), mas o carro só oferece suporte ao Android Auto (sem CarWith, CarPlay ou Carlife). Mas o DiLink-Auto funciona com qualquer celular Android — com ou sem Google Services.

| Dispositivo | Problema |
|-------------|----------|
| Xiaomi 17 Pro Max (HyperOS 3, ROM Chinesa) | Sem Android Auto — sem Google Play Services |
| BYD Destroyer 05 / King (mercado brasileiro) | Apenas Android Auto na central multimídia |
| Qualquer celular Android 10+ | Funciona independentemente da ROM ou Play Services |

## Requisitos

**Celular:**
- Qualquer celular Android 10+
- Depuração USB ativada (Opções do Desenvolvedor)
- Permissão de Acesso a Todos os Arquivos (solicitada na primeira execução)

**Carro:**
- BYD DiLink 3.0 ou mais recente
- Uma porta USB-A livre

**O hotspot do celular deve estar ativado** — o carro se conecta ao hotspot WiFi do seu celular. Sem códigos de pareamento, sem necessidade de conta Google.

**Nenhuma conexão com a internet é necessária.** O DiLink-Auto transmite tudo localmente pelo hotspot WiFi do seu telefone — o carro e o telefone se comunicam diretamente. Uma conexão com a internet é necessária apenas para os aplicativos em execução no seu telefone (ex.: navegação, streaming de música), não para o DiLink-Auto em si.

## Como Funciona

1. **Ative o hotspot** — Ligue o hotspot WiFi do seu celular. O carro se conecta a ele.
2. **Conecte o cabo** — Conecte seu celular à porta USB do carro
3. **Instalação automática** — O celular instala o app do carro via WiFi ADB (apenas na primeira vez, um toque)
4. **Conexão automática** — 3 streams WiFi TCP dedicados: vídeo (porta 9638), entrada de toque (porta 9639) e controle (porta 9637)
5. **Use seus apps** — Inicie qualquer app pela tela de launcher do carro. Ele é executado no celular, aparece no carro e responde ao toque

O celular executa seus aplicativos em um display virtual, codifica a tela como vídeo H.264 e o transmite para o carro. Os toques na tela do carro são enviados de volta ao celular e injetados como eventos de toque reais. A tela física do celular permanece desligada (economia de bateria) e pode ser usada de forma independente.

## Instalação

<a href="https://github.com/andersonlucasg3/dilink-auto-android/releases/latest"><img src="https://img.shields.io/github/v/release/andersonlucasg3/dilink-auto-android?label=Download%20Latest%20Release" alt="Download Latest Release"></a>

Baixe a versão mais recente ou compile a partir do código-fonte:

1. **Compilar:** `./gradlew :app-client:assembleDebug`
2. **Instalar** o APK em `app-client/build/outputs/apk/debug/app-client-debug.apk` apenas no seu celular
3. **Ative a Depuração USB** no seu celular (Configurações → Opções do Desenvolvedor)
4. **Abra o DiLink-Auto** no celular e conceda Acesso a Todos os Arquivos quando solicitado
5. **Ative o hotspot, depois conecte ao USB do carro** — o app do carro é instalado automaticamente na primeira execução via WiFi ADB

O APK do carro e o JAR do servidor VD já vêm empacotados dentro do APK do celular — você nunca precisa instalar nada no carro manualmente.

## Status Atual

**Funcionando:**
- Streaming de vídeo H.264 a 60fps (8Mbps CBR, perfil Main, configurável via handshake)
- Entrada de toque completa (multi-touch, pinça para zoom)
- Launcher de apps com busca, ordenação alfabética, ícones de 64dp
- Notificações na tela do carro com barras de progresso, toque para abrir
- Auto-atualização via GitHub Releases (release) ou pre-releases (debug)
- Atualização automática: o celular detecta app do carro desatualizado e o atualiza via WiFi ADB
- Tela do celular desligada durante o streaming (economia de bateria)
- Onboarding guiado para todas as permissões necessárias
- Internacionalização: Inglês, Português, Russo, Bielorrusso, Francês, Cazaque, Ucraniano, Uzbeque
- Restauração do display após desconexão USB (v0.14.0+)
- Testado no BYD DiLink 3.0 (1920x990) + Xiaomi 17 Pro Max (Android 16) + POCO F5

**Em breve:** streaming de áudio, controles de mídia, widgets de navegação

**Limitações conhecidas:**
- O processo do servidor VD reinicia ao desconectar o USB (reconecta automaticamente).
- O hotspot precisa ser ativado manualmente (limitação do Android 16).
- Artefatos visuais ocasionais — corrida de reinicialização do decodificador, recupera no próximo keyframe (~1s).
- Latência de streaming de ~100-200ms sob carga. CBR 8Mbps.
- O display pode permanecer desligado após desconexão USB abrupta (corrigido na v0.14.0).
- **Alguns apps não preenchem a tela (letterbox/apenas retrato).** O DiLink-Auto espelha uma tela virtual em paisagem para o display do carro. Apps que não suportam orientação paisagem aparecerão com faixas laterais ou estreitos — isso é totalmente controlado por cada app, não pelo DiLink-Auto. Nada pode ser feito do lado do espelhamento.

## Documentação

| Documento | Público | Descrição |
|-----------|---------|-----------|
| [Guia de Configuração](./setup.md) | Usuários | Instalação detalhada e solução de problemas |
| [Arquitetura](./architecture.md) | Desenvolvedores | Design dos módulos, fluxo de conexão, decisões de design |
| [Especificação do Protocolo](./protocol.md) | Desenvolvedores | Formato wire, tipos de mensagem, atribuição de portas |
| [App Cliente (Celular)](./client.md) | Desenvolvedores | ConnectionService, deploy do VD JAR, auto-atualização |
| [App Servidor (Carro)](./server.md) | Desenvolvedores | Máquina de estados, USB ADB, VideoDecoder, UI do carro |
| [Rastreador de Progresso](./progress.md) | Contribuidores | Status de funcionalidades, marcos, roadmap |

## Estrutura do Projeto

O APK do celular (`app-client`) incorpora tanto o APK do carro (`app-server`) quanto o JAR do servidor VD. Quando você instala o app do celular, tudo o que é necessário já vem empacotado dentro dele.

```
DiLink-Auto/
├── protocol/       Biblioteca compartilhada (framing, mensagens, descoberta, USB ADB)
├── app-client/     APK do celular — relay, deploy do VD, auto-atualização do carro, FileLog
├── app-server/     APK do carro — UI, máquina de estados de conexão, decodificador de vídeo
├── vd-server/      Servidor VirtualDisplay (compilado para JAR, enviado pelo celular)
├── docs/           Documentação
└── gradle/         Sistema de build
```

## Apoio

Este projeto é desenvolvido de forma independente e depende do apoio da comunidade. Cada contribuição ajuda a cobrir o tempo de desenvolvimento, dispositivos de teste e a manter o projeto ativo.

## Contribuindo

PRs são bem-vindos. Consulte [Arquitetura](./architecture.md) e [Protocolo](./protocol.md) para contexto técnico. Compile com `./gradlew :app-client:assembleDebug` (JDK 17+, Android SDK 34).

### Modelo de Branching (Git-Flow + Tipos de Issue)

As branches são criadas automaticamente pelo agente de issues com base no **template de issue** utilizado:

| Template | Label | Padrão de Branch | Finalidade |
|----------|-------|-----------------|------------|
| Bug Fix | `bug` | `fix/N-agent` | Correções de bugs |
| New Feature | `feature` | `feature/N-agent` | Novas funcionalidades |
| Investigation | `investigation` | `investigate/N-agent` | Investigação do código |
| Documentation | `documentation` | `docs/N-agent` | Atualizações de documentação |
| Release | `release` | `release/vX.Y.Z` | Preparação de release |
| Agent Task (genérico) | — | `issue/N-agent` | Uso geral |

Todas as branches são mescladas na `develop` via PR, exceto `release/*` que tem como destino a `main`.

### Workflows de CI

| Workflow | Gatilho | Ação |
|----------|---------|------|
| `build.yml` | Push/PR para `main` | Validação: compilar APK de release |
| `build-develop.yml` | Push/PR para `develop`, `release/*` | Validação: compilar APK de debug |
| `build-pre-release.yml` | Tag `vX.Y.Z-dev-NN` | Compilar APK de debug + pre-release no GitHub |
| `build-release.yml` | Tag `vX.Y.Z` | Compilar APK de release assinado + GitHub Release |
| `sync-main-to-develop.yml` | Push para `main` | Mesclar `main` → `develop` (back-sync do git-flow) |
| `issue-agent.yml` | Issue aberta / comentário | Agente autônomo: branch, build, PR |

Todo o CI é executado em **runners WSL self-hosted**.

**Processo de release:** Crie uma issue de Release a partir do template. O agente cria `release/vX.Y.Z`, prepara as alterações e aplica a tag `vX.Y.Z-dev-NN`. O push da branch de release aciona `build-pre-release.yml`, que localiza a tag `-dev` no commit via `git tag --points-at HEAD` e publica uma pre-release. Quando estiver pronto, `release/vX.Y.Z` é mesclada na `main` com uma tag `vX.Y.Z` no commit de merge. O push para `main` aciona `build-release.yml` (compila APK assinado + cria GitHub Release) e `sync-main-to-develop.yml` (mescla automaticamente `main` de volta na `develop`).

**Atualizações de pre-release:** Usuários no canal Pre-release recebem builds `-dev`. Usuários no canal Release recebem apenas builds estáveis. O canal é configurável nas Configurações.

## Licença

MIT — consulte [LICENSE](../LICENSE)
