# Publicação e assinatura (Release Guide)

> Para mantenedores e agentes. Este documento registra a **assinatura oficial** e a **cadeia de release** do DiLink-Auto, além das armadilhas encontradas ao montar essa base.
> Relacionados: fluxo do repositório em [AGENTS.md](../../AGENTS.md) §3, regras de PR em [PR_GUIDE.md](./PR_GUIDE.md).

## 0. Resumo em uma frase

- Os pacotes de release são assinados pelo keystore `dilink-auto-release` (certificado `CN=DiLink-Auto`); o keystore **não entra no repositório** e existe só no backup da máquina do publicador e nos secrets do GitHub Actions (`RELEASE_KEYSTORE_BASE64`).
- Publicar = subir `app.versionName`/`app.versionCode` em `main`, criar a tag semver `vX.Y.Z`, enviar ao origin → `Build (Release)` compila em runner GitHub-hosted (`ubuntu-latest`) e cria o GitHub Release.
- Quando o certificado de assinatura muda, **pacotes novos e antigos não se instalam por cima**: é preciso desinstalar (celular e central).
- Pacotes do fork `ID-VerNe/dilink-auto-android` (1.0.x) usam **outro** certificado (`84417b25…36a`); para migrar deles para os pacotes deste repositório, desinstale primeiro.

## 1. Regras de versão e tag

O repositório usa **versionamento semântico** (`MAJOR.MINOR.PATCH`), como nas tags históricas `v0.17.1` e `v0.19.0-dev-N`.

| Item | Local / regra |
|---|---|
| Fonte única da versão | `gradle.properties`: `app.versionName` (`0.19.0`) e `app.versionCode` (`60`; inteiro, monotônico) |
| `versionCode` | Manual, **sempre crescente** — `pm install -r` recusa downgrade. Suba junto com a versão |
| Tag de release | `vX.Y.Z` (ex.: `v0.19.0`), dispara `Build (Release)` |
| Tag de pré-release | `vX.Y.Z-dev-N` (ex.: `v0.19.1-dev-3`), dispara `Build (Pre-Release)` |
| String de build | `BUILD_REVISION` é calculado em `build.gradle.kts` com `git rev-parse --short HEAD` + `git status --porcelain`; **arquivo não rastreado** já deixa a string com `-dirty` |

O build valida o formato da versão: `app.versionName` precisa casar com `MAJOR.MINOR.PATCH` ou `MAJOR.MINOR.PATCH-dev-N`, e `app.versionCode` precisa existir. A versão é calculada uma única vez na raiz do build e compartilhada por `app-client` / `app-server` / `vd-server` (via `rootProject.extra`), então os três nunca divergem no mesmo build.

O `Versioning` do `app-client` ainda entende o formato antigo `YYYYMMDD-HHMM` (herdado do fork) para comparar versões de pacotes antigos; isso **não** é mais usado para gerar versão. Ver `docs/client.md`.

A versão de código atual está no topo do [README.md](../../README.md). Tags históricas são somente leitura — **nunca faça force push de tag já publicada**; corrija com uma versão nova.

Exceção conhecida: o asset do `v1.0.1` foi construído **antes** da assinatura oficial (assinado com debug). A tag não é recriada; os assets com assinatura oficial começam no `v1.0.2` (do fork). O primeiro release deste repositório com a cadeia oficial é o `v0.19.0`.

## 2. Keystore oficial

| Item | Valor |
|---|---|
| Caminho local | `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks` (**fora do repositório**; houve uma cópia antiga com o mesmo nome no histórico do fork, removida no merge de 2026-11) |
| Tipo / alias | PKCS12 / `dilinkauto` |
| Chave | RSA 2048 (SHA384withRSA), válida até 2051-04-20 |
| DN do certificado | `CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR` |
| SHA-256 do certificado | `e91b0551d832aa08af964398f644b002f9669031e3426e47075889bb6cdfd726` |

> O SHA-256 é o critério único de verificação: se o asset do Release não bater com esse fingerprint, a publicação falhou.

### 2.1 Geração (só ao trocar o certificado; depois não muda)

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks" `
  -storetype PKCS12 -alias dilinkauto -keyalg RSA -keysize 2048 -validity 9125 `
  -dname "CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR"
```

Guarde a senha; não escreva em commit, issue, PR ou chat.

### 2.2 Injeção no build local

Coloque as quatro variáveis em `%USERPROFILE%\.dilink-auto\release-signing.env` (formato uma linha `KEY=VAL`) e carregue na sessão do PowerShell:

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
$env:RELEASE_KEYSTORE_PASSWORD; $env:RELEASE_KEY_ALIAS   # confirme que carregou
```

Semântica das variáveis (lidas em `app-client/build.gradle.kts` e `app-server/build.gradle.kts`):

| Variável | Obrigatória | Descrição |
|---|---|---|
| `RELEASE_KEYSTORE_PASSWORD` | sim | Sem ela (e sem liberação explícita) → `GradleException` na **fase de configuração** |
| `RELEASE_KEY_PASSWORD` | sim | Idem |
| `RELEASE_KEYSTORE_FILE` | não | Se ausente, usa o caminho relativo padrão `app-client/dilink-auto-release.keystore` (arquivo que não está no repositório; no CI é obrigatório definir) |
| `RELEASE_KEY_ALIAS` | não | Padrão `dilinkauto` |
| `ALLOW_DEBUG_SIGNED_RELEASE=1` | não | Só emergência local: permite assinar a variante release com a chave debug; **não use** em pacote publicado |

### 2.3 Secrets do GitHub Actions

`Build (Release)` e `Build (Pre-Release)` precisam dos quatro (maiúsculas/minúsculas exatas):

```
RELEASE_KEYSTORE_BASE64      # jks em base64 de uma linha, decodificado em $RUNNER_TEMP
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Como gerar o `RELEASE_KEYSTORE_BASE64` (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks")) |
  Set-Content -NoNewline "$env:USERPROFILE\.dilink-auto\keystore.b64" -Encoding ascii

$env:NO_PROXY=""
gh secret set RELEASE_KEYSTORE_BASE64 -R andersonlucasg3/dilink-auto-android < "$env:USERPROFILE\.dilink-auto\keystore.b64"
```

## 3. Build e verificação local

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
.\gradlew.bat :app-server:assembleRelease :app-client:assembleRelease
```

Verifique a assinatura (**rode antes de cada publicação**):

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$sdk\apksigner.bat" verify --print-certs app-client\build\outputs\apk\release\app-client-release.apk |
  Select-String "certificate DN", "SHA-256"
& "$sdk\apksigner.bat" verify --print-certs app-server\build\outputs\apk\release\app-server-release.apk |
  Select-String "certificate DN", "SHA-256"
```

As duas linhas devem mostrar `CN=DiLink-Auto, …` e `e91b0551…d726`.

## 4. Fluxo de publicação (pode seguir à risca)

1. **Ajuste a versão**: mude `app.versionName` (semver) e `app.versionCode` (monotônico) em `gradle.properties`. Sincronize as referências de versão em `README.md`, `docs/README.md`, `docs/setup.md` e `docs/client.md`.
2. **Abra PR para `main`** (os cinco passos do AGENTS.md §3), com o CI `Build` verde.
3. **Confirme `main` limpa**: `git status --porcelain` vazio; caso contrário a string de versão do pacote ganha `-dirty`.
4. **Crie e envie a tag** (a tag precisa ser exatamente `v` + `app.versionName`; a pipeline valida):
   ```powershell
   git fetch origin main
   git tag v0.19.0 <merge-commit>
   git push origin v0.19.0
   ```
5. **Acompanhe a pipeline**: `gh run list -R andersonlucasg3/dilink-auto-android --limit 3` e `gh run watch <id>` até success.
6. **Aceite os assets**: baixe `DiLinkAuto-Phone.apk` / `DiLinkAuto-Car.apk` do Release e rode o `apksigner` da seção 3; o fingerprint precisa bater. Se não bater, despublique/reverta.
7. **Cole a evidência na issue**: link do run + saída bruta do `apksigner`.

## 5. Lista de armadilhas (todas realmente encontradas)

| # | Armadilha | Sintoma / consequência | Tratamento |
|---|---|---|---|
| 1 | `runs-on: self-hosted` | O repositório não tem runner self-hosted, então a publicação por tag fica **eternamente queued**, parecendo "publiquei e nada aconteceu" | Usar `ubuntu-latest` (runner GitHub-hosted) em todos os workflows (`build.yml` / `build-release.yml` / `build-pre-release.yml` / `sync-main-to-develop.yml`) |
| 2 | Actions desabilitadas | Sem "I understand my workflows… enable them", push/PR fica **sem check nenhum** e `gh pr checks` vem vazio | Habilitar na página Actions; `actions/permissions.enabled=true` ≠ ações habilitadas |
| 3 | Construir release sem keystore | Sem a senha, o build lança `GradleException` **na fase de configuração**, em vez de gerar pacote unsigned | No CI, o passo `Decode release keystore` grava o arquivo e define `RELEASE_KEYSTORE_FILE`; se o secret faltar, o passo faz `exit 1` |
| 4 | Variante debug também recebe assinatura oficial | Com `RELEASE_*` preenchidas, o `app-client/build.gradle.kts` aplica o `signingConfig` de release também em debug — **a pipeline de pré-release também precisa do keystore** | `build-pre-release.yml` também decodifica o keystore antes de compilar |
| 5 | Arquivo não rastreado sujando a versão | Se `.tmp/` (worktree) não estiver ignorado, `BUILD_REVISION` fica `-dirty` para sempre | `/.tmp/` já está no `.gitignore`; ainda assim confira `git status` antes de publicar |
| 6 | Instalação por cima após troca de assinatura | Pacote antigo instalado → novo pacote dá `INSTALL_FAILED_UPDATE_INCOMPATIBLE`/assinatura divergente | Na primeira migração para a assinatura oficial, **desinstale o antigo**; ao sair do fork 1.0.x (outro certificado), também desinstale |
| 7 | Variante do APK da central embutido | Comportamento antigo embutia sempre `app-server-debug.apk` (audit B-05); com assinatura oficial, a autoatualização mandaria um pacote debug e conflitaria com a central já assinada | **Corrigido (issue #37)**: `embedServerApk` escolhe `app-server-release.apk` / `app-server-debug.apk` conforme a variante do cliente; o passo `Verify embedded car APK signature` do `build-release.yml` compara o digest dos dois artefatos e falha se divergir |
| 8 | `environment: release` sem regra de proteção | O environment existe, mas **não tem** required reviewers; tag enviada publica direto | Para ter aprovação, configure reviewers em Settings → Environments; não presuma que `environment:` cria um portão |
| 9 | Semântica do `+` no filtro de tag do GitHub | No filtro, `+` significa "o caractere anterior uma ou mais vezes"; `v[0-9]+.[0-9]+.[0-9]+` casa `v1.0.2`, mas não é regex | Escreva as regras de tag com essa semântica, não com instinto de regex |
| 10 | Assets de tag antiga não se atualizam | Depois de mudar configuração de build, o Release de uma tag antiga continua com o pacote antigo | Publique uma versão nova; **não** apague e reenvie a tag |
| 11 | PowerShell lendo `local.properties` | O valor de `sdk.dir` vem com escape `C\:\\…` e quebra o `Get-ChildItem -Directory` | Monte o caminho com `$env:LOCALAPPDATA\Android\Sdk` ou use `-LiteralPath` |
| 12 | `gh` falhando por proxy | O proxy local faz o `gh` travar | Antes de chamar: `$env:NO_PROXY=""` |
| 13 | Continuidade do `versionCode` após o merge do fork | A build `v20261117-2315` (esquema de data, descartado) saiu com `versionCode` 26111723. Reiniciar o contador semver no `60` pré-fork seria um **downgrade** e o `pm install -r` recusaria | **Tratado**: o release semver mantém o nome semântico mas o código continua em `26111724`, então o `v0.19.0` instala por cima daquela build. Não "arrume" o número de volta para um valor pequeno — a instalação passaria a exigir desinstalar |
| 14 | Tag semver de pré-release na pipeline errada | `v0.19.1-dev-3` não dispara `Build (Release)` (o filtro exclui `*dev*`), e `v0.19.0` não dispara a de pré-release | Use o formato certo para o canal certo; a pipeline de release também rejeita tag com `-dev` |

## 6. Contingência

| Situação | O que fazer |
|---|---|
| Tag enviada errada (versão não bate) | Apague a tag remota e o Release correspondente e publique uma versão nova com bump; **tag já publicada não é recriada** |
| Assinatura do asset errada | Marque o Release como draft / apague os assets, verifique se o `Decode release keystore` usou o base64 certo e rode a publicação de novo |
| Secrets apagados | Confira os quatro com `gh secret list -R andersonlucasg3/dilink-auto-android` e recrie o que faltar (ver 2.3) |
| Pipeline sempre queued | Verifique se o `runs-on` ainda é `ubuntu-latest` (GitHub-hosted; o repositório não tem runner self-hosted) e se as Actions do repositório estão habilitadas |
| Usuário relata conflito de assinatura na atualização | Peça para desinstalar a versão antiga e instalar de novo; não tente "compatibilizar" outra assinatura |
