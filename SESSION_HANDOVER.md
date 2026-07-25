# DiLink-Auto — Session Handover (2026-07-25, ~13:00, sessão 4 no PC)

> Estado completo do projeto. Escrito para continuação em nova sessão. Branch: `feature/ndk-migration`.
> **Resumo**: **o modo AA está FUNCIONAL de ponta a ponta** (bancada; carro BYD em teste pelo usuário). (1) Mirror validado em sessão AA real: grid do launcher no VD, apps fullscreen em landscape, toque+drag+pinch, rail de navegação, stream estável. (2) **Injeção de input na HyperOS resolvida**: shell não injeta em VD e `su` é inacessível do daemon — o app injeta via root (`AaInput`); gestos multi-pointer via **injetor root dedicado** (app_process + socket localhost:19648). (3) **Root flavor dietado (AA-only)**: manifest sem ConnectionService/acessibilidade/notification/all-files/Shizuku, sem app-server embutido, sem .so nativo — APK 51MB (era 80MB). (4) Painel físico desliga na sessão (lockscreen desativado via root, re-power-off a 30s). **Commitado e pusheado nesta sessão.**
> **Pendente**: confirmação no carro BYD real (usuário testa a seguir).

---

## 1. Contexto e motivação

- **Autor/usuário**: Anderson (andersonlucasg3). Desenvolvedor sênior. Dogfooding diário.
- **Objetivo**: experiência tipo Android Auto no BYD DiLink (Destroyer 05 / King, BR) usando apps do telefone.
- **Motivação crítica**: o app do **banco** (BR) bloqueia o aparelho ao detectar ADB permanente + acessibilidade + notification access + all-files. Já causou 3 factory resets. **Todo design é avaliado primeiro por "o banco detecta isso?"**
- **Dispositivos**:
  - Xiaomi 17 Pro Max — ROM **Xiaomi.eu** (HyperOS c/ GMS + Android Auto), **rooteado via KernelSU** (2026-07-22). Su `su` funciona do adb shell (grant "Shell" no KSU). Contexto su: `u:r:ksu:s0`.
  - BYD DiLink 3.0 — **tem Android Auto** (gate confirmado).
  - POCO F5 e Galaxy S24 (testes secundários).
- **Decisão macro (2026-07-22)**: o projeto vai **100% Android Auto** — app do carro (app-server), dilink-car e o fluxo TCP (9637/9638/9639, hotspot, install-on-car) serão **descontinuados**. Usuários não-root seguem via **Shizuku** (mesmo uid shell), mas precisam de carro com AA.

## 2. Decisões estratégicas (travadas com o usuário)

1. **Modo AA é o modo principal** — `androidx.car.app` (NavigationTemplate + SurfaceCallback) renderiza nosso conteúdo dentro do AA stock. Precedente: Screen2Auto/AAMirror/AAStream.
2. **Zero streaming no modo AA**: o VD é criado **diretamente na Surface do host AA** — sem encoder H.264, sem TCP, sem decode. Latência de vsync, lossless.
3. **Daemon roda como SHELL (uid 2000)**, nunca como root — root é apenas o **launcher** (`su shell -c ...`). Shizuku idem para não-root.
4. **Transporte app↔daemon**: binder dentro de **broadcast Intent explícito** (ver pesquisa, seção 5 — ServiceManager foi descartado por SELinux).
5. **Seletor de apps**: home-model no v1 (grid ↔ app full-screen); dock estilo coolwalk é v2.
6. **Back nav**: ação "Voltar" no ActionStrip do NavigationTemplate.
7. **Flavor separado com manifest limpo** para o modo root (stealth contra SDK do banco) — Fase 4.
8. **Permissões**: em modo root, nunca pedir permissões que o root torna desnecessária (acessibilidade, all-files, notificações).

## 3. Estado do Git (feature/ndk-migration)

Working tree: **LIMPA** em 24/07 ~13:45 (commit `docs: handover` desta sessão). Commits novos desta sessão:

| Hash | Conteúdo |
|---|---|
| `e5f71f5` | `auto/DiLinkHomeScreen.kt` (PaneTemplate diagnóstico) + ação "Home" no strip do MirrorScreen + strings `aa_home`/`aa_open_mirror` |
| `9d3f042` | `scripts/termux-ndk-setup.sh` + handover (build nativo no Termux) |

Detalhe do que o commit `9bce045` inclui, para referência:

**Build nativo NO TERMUX — RESOLVIDO (24/07):** o SDK/NDK oficial só traz ferramentas de host x86_64, que não rodam no Termux (aarch64). Solução aplicada e **scriptada em `scripts/termux-ndk-setup.sh`** (re-rodar se NDK/build-tools forem reinstalados):
- `build-tools/34.0.0/aidl` e `zipalign` → binários aarch64 do Termux (`pkg install aidl zipalign`); originais em `*.x86_64.bak`.
- `cmake/3.22.1/bin/cmake` e `ninja` → symlinks do Termux.
- `~/.gradle/gradle.properties`: `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2`.
- NDK: 10 binários de host (`clang-21`, `lld`, `llvm-ar`, `llvm-objcopy`, etc.) → symlinks para os do Termux; scripts `aarch64-linux-androidNN-clang++` do NDK funcionam sem alteração.
- NDK `android-legacy.toolchain.cmake`: host `Android` (Termux) adicionado ao caso `Linux` (senão `ANDROID_HOST_TAG` fica vazio → `prebuilt/bin/clang++` inexistente).
- NDK sysroot: criado `libc++_shared.a` (arquivo REAL, linker script `GROUP ( libc++_static.a libc++abi.a )`) em cada `usr/lib/<abi>/` — o clang do Termux traduz `-static-libstdc++` para `-Bstatic -lc++_shared`, que não existe no NDK. **CUIDADO: nunca escrever esse arquivo através de symlink — destruiu o `libc++_static.a` uma vez (lld crasha com GROUP auto-referente) e exigiu reinstall do NDK.**
- Verificado: `assembleStandardDebug` + `assembleRootDebug` **completos no aparelho**, com `libdilinkd.so` e `libdilink-car.so` para arm64-v8a, armeabi-v7a e x86_64 (cross via Termux clang).


**Trabalho encontrado na branch errada (develop) — resolvido em 24/07:**
- Um agente anterior implementou um MVP antigo do app AA na `develop` (pacote `car/`: `DiLinkCarAppService`/`DiLinkSession`/`DiLinkHomeScreen` com PaneTemplate, category POI, minCarApiLevel=1, rows abrindo `MainActivity`/`ConnectionService`). Estava só na working tree da develop — salvo em **stash** ("WIP automotive car feature (antes do checkout ndk-migration)"), **não dar drop** (referência histórica).
- Era versão superada do que já existe aqui (pacote `auto/`, NavigationTemplate, category NAVIGATION, minCarApiLevel=2). **Única peça aproveitada**: a ideia do PaneTemplate como diagnóstico.
- **Port aplicado (commit `e5f71f5`)**: `auto/DiLinkHomeScreen.kt` (NOVO) — PaneTemplate mínimo ("Open mirror"→push `MirrorScreen`, "Exit"→finish). `MirrorScreen` ganhou ação **"Home"** no ActionStrip (3ª ação — seguro: `ACTIONS_CONSTRAINTS_NAVIGATION` permite até 4, verificado no bytecode da lib car-app 1.4.0). Strings novas `aa_home`/`aa_open_mirror` só em `values/strings.xml` (mesmo padrão de `aa_back`/`aa_exit`).
- **Uso no carro**: se o host BYD renderizar a Home (Pane) mas não o mirror (NavigationTemplate), o problema é o template; se nenhum renderizar, é validação de fonte de instalação (ver 6.5).


| Hash | Conteúdo |
|---|---|
| `f0a29b3` | Checkpoint: relay de comandos via lifecycle + decoder tardio + limpeza do daemon (23 arqs) |
| `f1686f3` | Fase 1: `RootManager` + `PrivilegeRouter` (root→Shizuku→ADB), `CONNECTION_METHOD_ROOT=3`, flavors `standard`/`root` |
| `f1a222c` | Modo AA MVP: `DilinkCarAppService`/`MirrorSession`/`MirrorScreen` (NavigationTemplate), `AaVideoClient` (MediaCodec→surface), `AaInputClient`, `DaemonDeployer` extraído |
| `8a6f533` | Permissões root-aware: onboarding/settings escondem prompts em modo root; `DaemonDeployer` staging via filesDir→/data/local/tmp (sem All Files); bateria via `dumpsys deviceidle whitelist` |
| `9f0021d` | POC bridge: bindService falha, ContentProvider.call falha, **ServiceManager.addService funciona (root e shell)** |
| `9e5b9e4` | **Bridge direta (Fase 2)**: AIDL `IAaDaemon`/`IAaAppCallback`; `AaDaemonMain`/`AaDaemonBridge` (daemon AA **puro Kotlin**); `AaDaemonClient` (getService+retry); hidden API via `setHiddenApiExemptions`; removidos clientes TCP do MVP |
| `720334d` | Fix crash loop: `VirtualDisplayClient.startListening` não lança mais exceção com porta 19647 ocupada (zumbi) |
| `9bce045` | Bridge por broadcast + DiLinkLauncher — cadeia validada no emulador (transporte, harness, DaemonEntry não-fatal, launcher no VD por componente explícito) |
| `90680b1` | docs: handover update |
| `71728b9` | Fix announce skipado na HyperOS — appOp=0 (OP_COARSE_LOCATION) no broadcastIntent; Android 15+ enforça delivery gating |
| `ae9c7f4` | docs: handover appOp |
| `c306e16` | Flavor `bridge` — harness sai do source set debug; standard/root voltam a 1 ícone |
| `58f3dcd` | docs: handover bridge flavor |
| `864cc21` | docs: handover investigação AA (spoof installer) |
| `c3a4c92` | docs: handover phenotype flags aplicadas |

**Working tree: LIMPA.** Artefatos NÃO versionados na raiz (deixar fora do git): `aa_12.9.apk` (AA 12.9.643804, 60MB, MD5 2b9313e67a181fc7a3b6e3edab5e97ea), `aa_17.3_backup/` (base+3 splits do AA 17.3.662814, p/ restaurar), `phenotype*.db`, `pw.db`, `verify.db`, `post_reboot.db`, `csd.db` (cópias do GMS/gearhead), `dilink_car.log` (68MB), `dhu_session.log` (logcat UTF-16 da sessão DHU de 24/07), `grab_cert.py` (captura de cert TLS do head unit server — em andamento, ver 6.1), `bridge_test_*.png`, `phone_now*.png`.

**Conteúdo do commit `9bce045` (referência):**
- `vd-server/.../DaemonEntry.kt` — init block do `.so` tornado **não-fatal** (aa-daemon é Kotlin puro e não precisa da lib). **SEM esse fix o daemon morre se libdilinkd.so não existir no path.**
- `protocol/.../AaBridge.kt` (NOVO) — constantes do bridge (ACTION_ANNOUNCE, EXTRA_BINDER, EXTRA_TOKEN, TOKEN_FILE, APP_PACKAGE, RECEIVER_FQCN)
- `vd-server/.../AaDaemonBridge.kt` — **reescrito**: `announce()` via `IActivityManager.broadcastIntent` (reflection por nome + args por tipo, userId=-2), token anti-spoof UUID→`/data/local/tmp/dilink.aa.token` (chmod 600). `publish()` removido.
- `vd-server/.../AaDaemonMain.kt` — announce em thread (`AaAnnounce`), `Binder.joinThreadPool()` na main (necessário para callbacks chegarem durante announce).
- `app-client/.../auto/AaDaemonReceiver.kt` (NOVO) — BroadcastReceiver exported, valida token via `PrivilegeRouter.execAndWait("cat ...")` (dev/emulator sem backend → skip), `goAsync` + thread.
- `app-client/.../auto/AaDaemonClient.kt` — **reescrito**: latch (CountDownLatch) em vez de getService; `onDaemonBinder(binder)` → `registerAppCallback`.
- `app-client/src/main/AndroidManifest.xml` — receiver `.auto.AaDaemonReceiver` exported + intent-filter `com.dilinkauto.client.AA_DAEMON`.
- `app-client/src/debug/AndroidManifest.xml` + `src/debug/java/.../debug/BridgeTestActivity.kt` (NOVOS) — **harness de teste** (source set debug): SurfaceView que vira host do VD; status TextView; onTouch→daemon.touch; hooks `AaDaemonClient.onDisplayReady/onError`.
- ~~ERRO DE COMPILAÇÃO PENDENTE~~ — resolvido em 23/07: `putExtra(String, IBinder)` é @hide → reflection no daemon, `Bundle.get` no app (ver seção 6).

## 4. Arquitetura final do Modo AA

```
Telefone (root + GMS)                                  Carro/DHU
────────────────────────────                           ──────────
AA host (Google) ──bind──► DilinkCarAppService  ◄── AA stock (nada instalado)
                              │ MirrorScreen (SurfaceCallback)
                              │ onSurfaceAvailable(surface,w,h,dpi)
                              ▼
                    DaemonDeployer.startAaDaemon()
                       · root: `su shell -c 'setsid env CLASSPATH=... DaemonEntry aa-daemon &'`
                       · Shizuku: mesmo comando direto
                              ▼
                    dilinkd (app_process, uid 2000, Kotlin puro)
                       · AaDaemonMain → AaDaemonBridge.announce()
                       · broadcastIntent(Intent com IAaDaemon stub + token)
                              ▼
                    App: AaDaemonReceiver (valida token) 
                       → AaDaemonClient.onDaemonBinder → registerAppCallback
                              ▼
                    App: daemon.setSurface(surface AA, w, h, dpi)
                              ▼
                    Daemon: NativeBridge.createVirtualDisplay(w,h,dpi,surface)
                       → VD renderiza DIRETO na surface do AA (sem streaming)
                       → am start --display N HOME (launcher do VD)
                              ▼
                    Tap no AA → SurfaceCallback.onClick 
                       → daemon.touch(DOWN/UP, xNorm, yNorm)
                       → NativeBridge.injectMotionEvent (IInputManager)
```

**Peças-chave existentes e estado:**
- `NativeBridge.kt` (vd-server): **já tem tudo** — `createVirtualDisplay(w,h,dpi,surface)` via DisplayManagerGlobal reflection (flags 0x6c49), `injectMotionEvent` completo via IInputManager (DOWN/MOVE/UP, pointerId), `execShell`, `launchApp(displayId,pkg)`, `setDisplayPower`.
- `FakeContext.kt` (vd-server): system context via ActivityThread (padrão scrcpy/Shizuku UserService).
- `DaemonDeployer.startAaDaemon(context)`: staging jar→/data/local/tmp, pkill, launch via root(`su shell -c`)/Shizuku.
- `PrivilegeRouter`/`RootManager`/`ShizukuManager`: seleção de backend; `RootManager.isAvailableFlow` (StateFlow) para Compose.
- `MirrorScreen.kt`: NavigationTemplate map-only + ActionStrip (Voltar→goBack, Sair→finish); onClick→DOWN+UP.

## 5. Pesquisas técnicas (descobertas — NÃO re-debugar)

**Transporte app↔daemon (sequência de provas):**
1. `bindService` de app_process → **FALHA**: "Unable to find app for caller" — app_process não tem ProcessRecord no AMS.
2. `ContentProvider.call` como root → mesma falha AMS. Como shell com resolver do system context → "Given calling package android does not match caller's uid 2000". Com `ContentResolver` custom → `AbstractMethodError` (acquireProvider é abstrata e sua implementação real passa pelo AMS). **`content call` CLI como shell FUNCIONA** (provider do app retorna binder — provado com `AaBridgeProvider`, depois removido).
3. `ServiceManager.addService` → **FUNCIONA como root E como shell** (provado no aparelho físico: `service list` mostrou `dilink.auto.test`). **MAS** `ServiceManager.getService` de `untrusted_app` para serviço custom → **SELinux denied { find }** (`tcontext=default_android_service`). Confirmado por dmesg no emulador. Política AOSP padrão → vale para o físico também. **ServiceManager descartado como lookup.**
4. **Escolhido: binder dentro de broadcast Intent explícito** (técnica do LSPosed/sistema). `IActivityManager.broadcastIntent` de shell funciona sem app-record; intents transportam IBinder nativamente; receiver manifest inicia app parado (FLAG_INCLUDE_STOPPED_PACKAGES).

**Ambiente/AA:**
- DHU 2.0 (2022, protocol 1.7) × AA **17.1.662414** (2026): DHU conecta ("connected.") e cai instantaneamente; gearhead não inicia sessão e não loga nada (mesmo com "Forçar registro de depuração"). Descartados: first-run ToS (aceitos), GSF (Wallet funciona), unknown sources (estava OFF — era a causa do app não aparecer; ligado), head unit server (escuta na 5277, processo certo), telefone acordado, declaração CarAppService (correta). **Suspeita: incompatibilidade de versão. Alternativa: testar no carro real ou downgrade do AA.**
- Head Unit Server: AA → ⋮ → Developer settings → "Iniciar servidor de head unit". AA settings via `am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity`.
- **Emulador (AVD `bridgetest`, android-34 google_apis x86_64, userdebug)**: hypervisor funciona (AEHD; WSL2 quebrado não impede). RTX 5070 acelera. `adb root` funciona. **Como shell, addService é SELinux-denied (diferente do físico!); como root funciona.** Daemon rodando como root publicou `dilink.auto.daemon` (entry 112).
- **DaemonEntry init block**: `System.load` do `.so` com 3 fallbacks — o terceiro NÃO era capturado → UnsatisfiedLinkError fatal se a lib faltar. Já corrigido na working tree (não commitado).
- **cmd.exe quoting**: pipes/aspas dentro de `adb shell "..."` quebram — usar greps simples (termo único, sem aspas internas) ou filtrar local com findstr. NUNCA usar `| more`.
- Android Auto **não funciona sem ToS/first-run**, e `untrusted_app` não acha serviços custom — as duas armadilhas que mais custaram tempo.

## 6. Estado exato AGORA (para retomar) — FINAL 2026-07-23

### 6.1 ONDE PARAMOS (ler primeiro) — ATUALIZADO 2026-07-24 00:30

**Última ação**: retry do DHU (sequência exata de 6.1 anterior: stop/start do `DeveloperHeadUnitNetworkService` + `adb forward tcp:5277` + DHU com stdin preso). Resultado: **protocolo 1.7 negociado, SSL completado, e falha de cert nos DOIS lados**:

- DHU (PC): `Verify returned: certificate has expired` → `Unrecoverable error -24` (o DHU rejeita o cert do telefone).
- Telefone: tela vermelha **"Erro de comunicação 14 — O software do seu carro foi reprovado nas verificações de segurança do Android Auto. Verifique se a data e a hora do carro estão configuradas corretamente"** (o telefone rejeita o cert do DHU).
- **Relógios verificados iguais**: PC e telefone ambos `2026-07-24 00:06 -03`. A hipótese "relógio do telefone" está morta.
- `am force-stop` do gearhead + restart do serviço **NÃO** regera o cert do telefone (2ª tentativa idêntica).
- Nenhum arquivo de cert no data do gearhead (`find` por cert/key/pem/p12/keystore = vazio) e nenhuma entrada no Android Keystore para o uid 10118 — local de armazenamento do cert do telefone **desconhecido**.
- sdkmanager só oferece DHU **2.0** (build 2022-03-30-438482292) — não existe versão mais nova no canal. Se o cert embutido do DHU expirou em tempo real, o DHU está permanentemente quebrado via canal oficial.

**Investigação em andamento (parada a pedido do usuário)**: script `grab_cert.py` (raiz do repo, NÃO commitar) fala o version exchange do AAP na porta 5277 (confirmado: envia VERSION_REQUEST 1.7, recebe `000300080002000100070000` = VERSION_RESPONSE 1.7) e tenta o TLS handshake para capturar o cert do telefone e ler as datas reais — handshake do Python deu timeout; estava para testar com `TLS1.2` + cipher `ECDHE-RSA-AES128-GCM-SHA256` (edit já aplicado ao script, não executado). Objetivo: confirmar se o cert do telefone está expirado (gerado no período de relógio errado e cacheado) e, depois, capturar o cert do DHU (client cert) para confirmar a expiração dele.

**PRÓXIMAS AÇÕES possíveis (decidir com o usuário)**:
1. Terminar o `grab_cert.py` (fix handshake) → datas exatas do cert do telefone. Se expirado: achar onde gearhead cacheia o cert e forçar regen (candidatos: `pm clear` do gearhead — perde dev mode/unknown sources prefs e teria que reativar; ou achar o arquivo).
2. Capturar/verificar o cert do DHU (cliente TLS) — se expirado (provável, build 2022), o DHU oficial está morto. Alternativas: head unit open-source (openauto/aasdk-based), ou...
3. **Pular o DHU e testar no carro BYD real** — o carro tem cert próprio (válido, presumivelmente); o erro 14 é específico do DHU. Boot logging já está armado (`/sdcard/dilink_boot.log`). AA 12.9 + phenotype flags + spoof já estão no telefone.
4. Teste cruzado rápido: rolar o relógio do PC para trás (ex.: 2022) e rodar o DHU — se passar do erro 14, confirma cert do DHU expirado (mas o DHU validando o cert do telefone com relógio de 2022 pode falhar por "not yet valid" — resultado ambíguo).

### 6.1a Estado anterior (superseded)

O retry com relógio corrigido era a ação pendente — executada, resultado acima.

### 6.2 Estado do telefone (popsicle — sessão toda via Termux no próprio aparelho) — ATUALIZADO 24/07 13:45

- **Android Auto: re-atualizado pelo Play para 17.3.662814** (o downgrade p/ 12.9 foi desfeito — confirmado: a vigilância do auto-update era justificada). Para o carro não importa (12.9 era só p/ DHU). Backup em `aa_17.3_backup/` segue lá.
- **Phenotype**: flags **intactas** pós-reboot e pós-update (verificado 24/07 com sqlite3 do Termux — `sqlite3` NÃO existe no PATH do root shell; usar `/data/data/com.termux/files/usr/bin/sqlite3`).
- **DiLink app**: root-debug **v0.19.0-dev-1 (build do Termux, com libdilinkd.so)** instalado com `pm install -t -i com.android.vending -r` — md5 do base.apk instalado confere com `app-client-root-debug.apk`. Spoof preservado. **KSU grant re-feito pelo usuário e VERIFICADO**: simulação com o uid real do app (`setpriv --reuid=10346 ... su -c 'id -u'` → `0`). Confirmação objetiva: `strings /data/adb/ksu/.allowlist | grep dilink` → presente.
- **ATENÇÃO — grant KSU não sobrevive a reinstall** (de novo!): o usuário achou que o app instalado era "a versão errada" porque o app estava sem root — era só o grant perdido no reinstall de 24/07. Se o app parecer "sem root", checar o allowlist ANTES de duvidar do build.
- **Flavors standard/root são idênticos em runtime** (backend escolhido em runtime pelo PrivilegeRouter; o flavor root existe p/ futuro manifest limpo — Fase 4). Não existe "versão errada" entre eles hoje.
- **Boot logging ativo**: `/data/adb/service.d/99-dilinklog.sh` → `/sdcard/dilink_boot.log` (rotação 4×16MB).
- **Reboot misterioso 19:55**: não repetiu nesta sessão.
- **Head unit server**: componente `com.google.android.projection.gearhead/.companion.DeveloperHeadUnitNetworkService`; porta 5277.
- **Termux no aparelho = ambiente de dev completo** (24/07): build completo (incl. nativo), install via `su -c 'pm install ...'`, logcat via `su -c 'logcat ...'`. Git identity configurada no repo (local). **NÃO mexer na UI via monkey/am start — pedir ao usuário.**
- **Mistério em aberto**: `/data/user/0/com.google.android.projection.gearhead` **não existe** (ls como root via KSU) embora `pm dump` aponte esse dataDir — impossível ler `app_notifier.xml`/prefs do gearhead. Causa desconhecida (namespace de mount do KSU su? FBE? dir só existe enquanto o processo vive?). Investigar quando retomar a investigação de listing.

### 6.3 DHU — mapa completo das falhas (não re-debugar)

1. **DHU sai instantaneamente sem log** → causa: stdin EOF no console interativo dele. Rodar SEMPRE com `tail -f /dev/null | desktop-head-unit.exe`. Com stdin preso ele fica vivo e mostra "waiting for phone" / a UI.
2. **AA 17.x**: sessão nem inicia (protocolo). Resolvido com downgrade p/ 12.9.
3. **Start manual do serviço** (`am start-foreground-service`): ATUALIZADO 24/07 — com AA 12.9 o start manual **chega ao SSL** (duas vezes, falhando só no cert). A observação antiga ("nunca engata, zero GH.* no logcat") era da era AA 17.x.
4. **certificate has expired (DHU) + Erro 14 (telefone)** = falha de cert nos dois sentidos com relógios corretos (24/07). Hipótese viva: cert embutido do DHU 2.0 (2022) expirado + cert do telefone possivelmente cacheado do período de relógio errado. Ver 6.1.
5. Telefone bloqueado/tela apagada = projeção não inicia ("waiting for phone" eterno).
6. **Head unit server wedgeia após a rejeição erro 14** (24/07): continua aceitando TCP mas não responde nem o version exchange (DHU trava em ">"). Fix: `am stopservice` + `am start-foreground-service` de novo — volta a responder (confirmado via netstat LISTEN + version exchange por script).

### 6.4 As 17 phenotype flags aplicadas (referência exata)

Semântica (fonte: `jcrutch-design/AA-Visibility-Enabler` PhenotypePatcher.kt): `INSERT INTO flag_overrides (config_package_id, config_package_name=NULL, account_id=0, active=1, name, value, type, source=0)` + DELETE do override ativo anterior + staging em `flag_overrides_to_commit`. type: bool-false=0("0"), bool-true=1("1"), string=4. IDs no telefone: gearhead=654, gms.car=230.

- gearhead (654): `AppValidation__should_bypass_validation=1`, `AppValidation__dhu_bypass_validation=1`, `AppValidation__play_install_api=0`, `AppValidation__swallow_play_api_exception=1`, `AppValidation__swallow_play_api_exception_return_value=1`, `AppValidation__allowed_package_list=""`, `AppValidation__blocked_packages_by_installer=""`, `CarProjectionValidator__filter_disabled_packages_in_ispackageallowed_method=0`, `UnknownSources__allow_full_screen_apps=1`, `CradleFeature__all_app_launcher_enabled=1`, `CradleFeature__allow_video_apps=1`, `WirelessProjection__enabled=1`, `WirelessProjection__enabled_for_projection=1`
- gms.car (230): `app_white_list=com.dilinkauto.client`, `car_connect_broadcast_whitelist=com.dilinkauto.client`, `should_bypass_validation=1`, `FrameworkCarProjectionValidatorFlags__log_reason_apps_not_allowed_all_apps=1` (diagnóstico — validador loga motivo de rejeição de apps no logcat durante sessão)

Procedimento usado (seguro, repetível): force-stop gms → pull `phenotype.db` → edit python sqlite3 → push → `chown u0_a142:u0_a142`, `chmod 660`, `restorecon` → force-stop gearhead. **GMS wipe/update desfaz — re-aplicar.**

### 6.5 Fatos verificados sobre o problema "DiLink não aparece no AA"

- Declaração correta: service exportado + categoria NAVIGATION + `automotive_app_desc` (`template`) + `minCarApiLevel=2` + `ALLOW_ALL_HOSTS_VALIDATOR`. Dev mode ON, unknown sources ON (sobreviveram ao downgrade — prefs em `action_developer_settings.xml`).
- `cmd package query-services -a androidx.car.app.CarAppService` (shell) **encontra** `com.dilinkauto.client` (junto com tuya, waze, morphe YT music, gearhead, gsa).
- **Apps de terceiros CarAppService aparecem no carro**: Tuya Smart (`SceneManualCarAppService`) está no launcher do AA do usuário (`LAUNCHER_APP_POSITIONS.xml`) — prova que o setup aceita terceiros; o gate é específico contra sideload.
- `app_notifier.xml` (gearhead) lista "observed_apps": whatsapp, gsa, sygic, maps, morphe YT, gearhead, tuya, here — **sem dilink** (gearhead nunca nos "observou").
- Emulador API 34 tem phenotype **schema antigo** (`Flags`/`FlagOverrides` camelCase, 80k flags reais) — incluindo `app_white_list` real, `app_black_list` (carstream/youtubeauto banidos!), `AppValidation__dhu_bypass_validation=1`, `FrameworkCarProjectionValidatorFlags__use_package_manager_api_for_installed_by_play_check=1` (o check que o spoof vending atende). Telefone tem schema novo e config packages **sem params baixados** (overrides criam as chaves do zero — teoria OK per AA-Visibility-Enabler, mas NÃO confirmado que gearhead honra flag_overrides na 17.3/12.9).
- O broadcast `com.google.android.gms.phenotype.FLAG_OVERRIDE` **não funciona** (testado root/userdebug, vários formatos — nunca insere row).
- AA-AIO-Tweaker/AA-Tweaker (comunidade) usam SQL de schema antigo → quebrariam neste GMS 2026; AA-Visibility-Enabler é a referência do schema novo.
- **NOVO (24/07): a tela "Personalizar apps" do AA NÃO roda o validador ao vivo** — captura de logcat completa enquanto o usuário abria a tela: zero entradas do validador sobre dilink (só ruído de launcher/recents). Ou seja, a lista é populada de estado prévio do gearhead (apps "observados"/validados em sessões), e a flag `log_reason_apps_not_allowed_all_apps` só produzirá logs numa **sessão real** (carro/DHU). Não repetir essa captura — não dá o motivo da rejeição.
- **NOVO (24/07): dataDir do gearhead inacessível** — `/data/user/0/com.google.android.projection.gearhead` não existe para o root via KSU su (ver 6.2). Sem acesso ao `app_notifier.xml` no momento.

### 6.6 Estado do bridge (já validado — não re-testar)

- Cadeia completa OK no emulador `bridgetest` (screenshots `bridge_test_3/4.png`): announce → callback → setSurface → VD na SurfaceView → DiLinkLauncher grid → tap injeta e abre app no VD.
- HyperOS: após fix appOp (`71728b9`), announce entregue, `app callback registered — bridge up`. VD/touch no físico = mesmo código do emulador.
- KernelSU grant do app: **feito** (daemon sobe sozinho no carro).
- **ATUALIZADO 24/07 (tarde, PC) — Fase 3 validada no AVD `bridgetab` (Pixel Tablet, API 34 google_apis x86_64, landscape 2560x1600 — melhor p/ testes que o Pixel 6 portrait)**:
  - **Back-stack vazia → launcher**: validado ponta a ponta (stack esvaziada por keyevents diretos no VD, bypass total do daemon → próximo touch UP religou o launcher; log `VD stack empty — relaunching launcher`).
  - **Re-announce**: `surfaceDestroyed` no daemon re-anuncia o binder (rotação/recriação de surface reconecta sozinho).
  - **VD leak fixado**: `NativeBridge.activeVd` + `releaseVirtualDisplay()` — VDs zumbis (displays órfãos com tasks migradas) não acumulam mais.
  - **Duplo announce**: broadcasts em voo simultâneo entregavam o MESMO binder 2× → `onDaemonConnected` disparava 2 `setSurface` na mesma surface → `BufferQueueProducer: already connected` → `BufferQueue has been abandoned` → surface do host destruída (era a causa do churn de surface no harness). Fix: `AaDaemonClient.onDaemonBinder` só dispara o hook quando o binder é NOVO.
  - **Re-push de surface**: `AaDaemonClient.onDaemonConnected` → harness e `MirrorScreen` re-enviam a surface guardada — daemon reiniciado recupera o VD sem recriar a activity.
  - **ARMADILHA do emulador**: `input tap`/`input keyevent` sem `-d` vão para o display FOCADO — que pode ser o VD (OWN_FOCUS), bypassando o harness. Usar sempre `-d 0` (harness) ou `-d N` (VD) explícito nos testes.
  - Harness: `onBackPressed` encaminha para `daemon.goBack()`; `configChanges` no manifest do flavor bridge evita recriação por rotação.

### 6.8 "Não aparece no AA" — RESOLVIDO DE VEZ (24/07 noite, sessão 3)

**O check da Finsky é LOCAL e tem 2 etapas** (fonte: Finsky 6.0.5 decompilada + microG `PlayGearheadService`, estrutura inalterada na 17.3):

1. `PackageStateRepository.get(pkg)` — lê **`localappstate.db`, tabela `appstate`**. Sem row → inválido imediato.
2. `Libraries.getAppOwners(pkg, certHashes)` — lê **`library.db`, tabela `ownership`**, iterando as libraries da conta; a row precisa ter `app_certificate_hash` **igual ao hash do cert do APK instalado**. Vazio → log "app owners empty" → `CAR.VALIDATOR: Package DENIED`.

**`app_certificate_hash` = `base64url(SHA-1 do cert DER)` sem padding** (27 chars). Validado empiricamente: SHA-1 do cert da Tuya (`apksigner verify --print-certs`) → base64url bate byte a byte com o valor real da row dela.

**Forges que FUNCIONARAM** (teste #6, 24/07 ~20:49 — app apareceu no launcher do AA):
- `localappstate.db/appstate`: row do dilink espelhando a da Tuya (account, first_download_ms, persistent_flags=1, permissions_version=1, install_reason='unknown', sandbox_version=1, desired_version=-1, installer_state=0, flags=0).
- `library.db/ownership`: **duas rows** imitando a Tuya — uma em `u-tpl` (sem hash) e uma na library `3` COM `app_certificate_hash` correto + shareability=2 + purchase_time.
- Backups: `finsky_localappstate_backup.db` (+ os anteriores). Procedimento: force-stop vending → sqlite3 (binário do Termux: `/data/data/com.termux/files/usr/bin/sqlite3`) → chown u0_a150:u0_a150, chmod 660, restorecon.
- **Vigilância**: sync da Finsky pode apagar as rows — se sumir do AA de novo, re-verificar/re-aplicar.
- KingInstaller/AAAD NÃO tocam DBs — só spoofam installer via intent (`EXTRA_INSTALLER_PACKAGE_NAME`); o phenotype patch é via secundária. O `initiatingPackageName` fica `com.android.shell` com `pm install -i` (pm não expõe flag p/ initiating; alternativas: hook Zygisk estilo Fermata em `InstallSourceInfo.getInitiatingPackageName` — NÃO foi preciso).

**Equipamento de teste caseiro (novo)**: **Headunit Revived** (open-source, `andreknieriem/headunit-revived`, APK no repo `headunit-revived_3.1.1.apk`) instalado num Redmi/TV Android via ADB. Conexão: phone com **head unit server ligado** (AA dev settings → "Iniciar servidor de head unit", porta 5277) + `am start -a android.intent.action.VIEW -d "headunit://connect?ip=<ip-do-phone>"` no head unit. **A validação roda igual ao carro** (sem bypass de DHU). Roteiro de reconexão: force-stop HUREV + `su -c 'am force-stop com.google.android.projection.gearhead'` + start-foreground-service do `DeveloperHeadUnitNetworkService` + acordar as duas telas + intent UMA vez (retries wedgeiam o server: aceita TCP mas não responde version exchange — diagnosticável do PC com `adb forward tcp:15277 tcp:5277` + enviar VERSION_REQUEST e esperar resposta). Se o server não responder nem com processo novo: o **toggle "Servidor de head unit" se desliga sozinho** (religar na UI do AA settings). HUREV tem modo Self/WiFiDirect — deixar em **WiFi client**.

### 6.9 Mirror no AA real — estado e armadilhas da HyperOS (24/07 noite)

**Funcionando ponta a ponta** (sessão via Headunit Revived): app abre → `MirrorScreen` (NavigationTemplate) → daemon via KSU → VD 1785×813@240 na surface do AA → `DiLinkLauncher` (UI do carro portada) → apps abrem no VD (`am start --display N --activity-multiple-task` — sem essa flag, apps com task no display 0 escapam pra tela do telefone) → toque injetado via root.

**Bugs reais encontrados SÓ no físico (emulador não pega)**:
1. **Permissões AA faltando** (crash imediato): `androidx.car.app.ACCESS_SURFACE` e `androidx.car.app.NAVIGATION_TEMPLATES` no manifest. Nunca tinham sido exercitadas — 1ª vez que o MirrorScreen rodou num host real.
2. **Race do probe de root**: surface chegava ~50ms antes do `RootManager` responder → "no backend". Fix: `MirrorScreen` aguarda `isAvailableFlow` (timeout 10s).
3. **FakeContext morria no binder thread** (`ExceptionInInitializerError` — ActivityThread precisa de Looper). Fix: `AaDaemonMain` força `FakeContext.get()` na main thread antes do `Binder.joinThreadPool()`.
4. **DiLinkLauncher `exported=false`** → shell não inicia activity não-exportada de outro uid (root podia — por isso funcionava no emulador). Fix: `exported=true` (sem intent-filter, só componente explícito).
5. **Crash loop DeadObjectException**: `daemon.setSurface` sem try/catch quando o daemon morria; o `DaemonDeployer.startAaDaemon` dava `pkill` no daemon saudável a cada churn de surface. Fixes: try/catch no setSurface (re-push via onDaemonConnected) + deployer **não reinicia daemon vivo** (`isBinderAlive`).
6. **HyperOS nega INJECT_EVENTS ao shell em VD** — `input -d N tap/keyevent` e `IInputManager.injectInputEvent` falham como uid 2000 (SecurityException), **e `su` é inacessível do contexto shell do KSU** ("inaccessible or not found" — o grant de shell não se propaga p/ subprocessos). Root injeta normal. **Solução: injeção pelo APP** (`auto/AaInput.kt` — o app tem grant KSU): `MirrorScreen.onClick` → `AaInput.tap(x,y)` via `input -d N tap` como root; Back idem keyevent. Fallback p/ daemon.touch quando sem root (Shizuku/AOSP).
7. **Stable/visible area do host tem origem offset** (ex.: `Rect(36,132-1749,795)`, e muda com chrome) — VD não pode ser deslocado na surface (renderiza em 0,0), então só shrink/restaura por W×H; offset = log + full surface.
8. **Daemon stderr vai p/ `/data/local/tmp/aa-daemon.log`** — ler pra debugar (touch, launchApp, VD events estão lá).

**PENDENTES/ABERTOS**:
- **Stall do stream ~15s após toque**: encoder do gearhead (`c2.qti.hevc.encoder`) zera frames por 10-15s → HUREV cai ("WiFi read timeout (15s)"). Maps nativo no AA não sofre. Causa não confirmada (throttle de conteúdo estático? vsync? focus do VD TRUSTED/OWN_FOCUS?). **Re-testar com o toque root funcionando** (VD passa a atualizar frames no toque — pode resolver sozinho).
- **Apps em fullscreen cobrem a nav bar**: strip do AA voltou a ter Back/Home/Exit (build de 00:30 NÃO instalado). **Ideia do usuário (a implementar)**: apps em **freeform com launch bounds = rect do viewport** (estilo DeX/Taskbar — "PIP" dentro do VD), mantendo a nav bar sempre visível. Caminho: `settings put global enable_freeform_support 1` (+force_resizable_activities) e launch com ActivityOptions(launchBounds, freeform) via reflection do daemon ou flags do `am`; launcher colapsa p/ coluna de nav quando app em foreground. Literal PIP do Android não serve (só o próprio app entra em PIP).
- **Duplo setSurface por conexão** (await path + onDaemonConnected) → 2 VDs; mitigar com dedupe de push (binder+dims+surface) no MirrorScreen — NÃO implementado ainda.
- **Keep-alive FGS** durante a sessão AA (HyperOS freezer) — NÃO implementado (avaliar se ainda morre com os fixes de crash).
- **Drag/gestures**: `onScroll/onFling` → `input swipe` via root (MOVE) — não implementado.
- **Commit**: tudo na working tree (ver resumo no topo).

---

<details><summary><b>Histórico da seção 6 (investigação AA — superseded por 6.1–6.6)</b></summary>

**Problema em aberto: DiLink NÃO aparece no Android Auto** (nem carro BYD, nem lista "Personalizar tela inicial" no telefone). Bridge app↔daemon validado na HyperOS, mas gearhead nunca escaneou o app.

**Verificado OK (não re-checar):** declaração manifest (service exportado + NAVIGATION + automotive_app_desc `template` + minCarApiLevel=2), `createHostValidator()=ALLOW_ALL_HOSTS_VALIDATOR`, dev mode AA ON, unknown sources ON, app fora de stopped state, gearhead force-stopped (re-scan não listou).

**Investigação web (fontes: AA-Tweaker/shmykelsa, AA-Visibility-Enabler/jcrutch-design, AA-Phenotype-Patcher/Eselter, XDA, Fermata discussions):**
- AA moderno esconde apps sideload por **fonte de instalação** + validação via Play API (`AppValidation__*` phenotype flags no gearhead). Head unit em dev mode (DHU/adaptadores AAWireless) bypassa — carro real não tem, então o patch é phone-side.
- **JÁ APLICADO (1)**: app reinstalado com `pm install -t -i com.android.vending -r` → `installerPackageName=com.android.vending` (flag do validador confirma: `FrameworkCarProjectionValidatorFlags__use_package_manager_api_for_installed_by_play_check=1`).
- **JÁ APLICADO (2) — 17 phenotype flags escritas no `flag_overrides` do telefone** (schema novo; semântica via AA-Visibility-Enabler: INSERT (config_package_id, NULL, 0, 1, name, value, type, 0), account_id=0, source=0, type: bool-false=0/bool-true=1/string=4, + staging em flag_overrides_to_commit):
  - gearhead (config_package_id=654): `AppValidation__should_bypass_validation=1`, `dhu_bypass_validation=1`, `play_install_api=0`, `swallow_play_api_exception=1`, `swallow_play_api_exception_return_value=1`, `allowed_package_list=""`, `blocked_packages_by_installer=""`, `CarProjectionValidator__filter_disabled_packages_in_ispackageallowed_method=0`, `UnknownSources__allow_full_screen_apps=1`, `CradleFeature__all_app_launcher_enabled=1`, `CradleFeature__allow_video_apps=1`, `WirelessProjection__enabled=1`, `WirelessProjection__enabled_for_projection=1`
  - gms.car (id=230): `app_white_list=com.dilinkauto.client`, `car_connect_broadcast_whitelist=com.dilinkauto.client`, `should_bypass_validation=1`, **`FrameworkCarProjectionValidatorFlags__log_reason_apps_not_allowed_all_apps=1`** (diagnóstico: validador loga o motivo de rejeição de cada app no logcat durante a sessão)
- Procedimento usado (seguro): force-stop gms → pull db → edit python → push → chown u0_a142, chmod 660, restorecon → force-stop gearhead. Backup pré-edit: `phenotype3.db` no repo (não commitar).
- Se AINDA não aparecer no carro: ler logcat da sessão (o validador agora loga o motivo exato). Fallbacks documentados: AA-AIO-Tweaker (app root que faz o mesmo), adaptador AAWireless/aa-proxy-rs (head unit MITM dev mode), downgrade AA ≤12.
- ADB WiFi: `192.168.1.11:5555` (cai quando tela apaga — `adb connect` de novo).

**Cadeia do bridge VALIDADA no emulador `bridgetest`** e **announce validado na HyperOS** (após fix appOp, commit `71728b9`). Jar+APK root-debug com todos os fixes instalados no físico.

**Descobertas da sessão (não re-debugar):**

**Cadeia do bridge VALIDADA no emulador `bridgetest`** (screenshots `bridge_test_3.png` = grid do launcher no VD, `bridge_test_4.png` = app Messages aberto no VD via tap injetado) e **announce validado na HyperOS** (após fix appOp). Detalhes do bug appOp nos bullets abaixo.

**Descobertas técnicas:**
- **`pkill -f app_process` via `adb shell "..."` mata o próprio shell** (a cmdline do `sh -c` contém o padrão) — tudo após o pkill na mesma linha NÃO executa. Usar `pkill -x app_process` ou chamada separada. Foi a causa do log fantasma "published".
- **`putExtra(String, IBinder)` e `getIBinderExtra` são @hide** (verificado via javap no android.jar): daemon usa reflection (shell não sofre hidden-API enforcement); app lê `intent.extras?.get(EXTRA_BINDER) as? IBinder` (API pública, deprecation warning apenas).
- **Intent HOME genérico no VD é sequestrado pelo launcher stock singleTask** do display 0 ("delivered to currently running top-most instance") — derruba o host app para background. Solução: sempre componente explícito no VD (`am start --display N -n pkg/.DiLinkLauncher`).
- O fix "DaemonEntry não-fatal" citado na versão anterior deste handover **nunca tinha sido aplicado** — aplicado nesta sessão (3º fallback do System.load com catch).
- `-no-snapshot-save` NÃO limpa o userdata do AVD — jar/APK/logs sobrevivem a reboots. Para boot limpo: `-no-snapshot-load`.
- **Android 15+/HyperOS enforçam delivery gating de broadcast por appOp** — mapear `appOp` errado (0 = OP_COARSE_LOCATION) faz o announce sumir silenciosamente (SKIPPED at enqueue, visível só via `dumpsys activity broadcasts`). Fix no commit `71728b9`.
- **KernelSU: grant do app NÃO persiste** entre reinstalls (ou nunca existiu para a assinatura debug) — verificar/grantar no KernelSU manager após cada install.
- **Flavor `bridge`** (commit `c306e16`): o harness Bridge Test saiu do source set `debug` e virou flavor próprio na dimensão `privilege` (standard/root/bridge). Motivo: todo debug build tinha 2 ícones no drawer ("DiLink" + "Bridge Test") e desinstalar "Bridge Test" removia o app inteiro. Para testes de harness: `assembleBridgeDebug`; standard/root têm 1 ícone só. `app-client/src/debug/` não existe mais — harness em `src/bridge/`.
- **Bridge NA HYPEROS VALIDADO** (23/07): após o fix do appOp, announce entregue e `app callback registered — bridge up` no físico. Restante do fluxo no físico (VD/touch) roda o mesmo código validado no emulador.

**Compilação**: `:app-client:assembleStandardDebug` + `:app-client:assembleRootDebug` — **AMBOS PASSAM**. `buildVdServer` roda via preBuild e refresca `app-client/src/main/assets/vd-server.jar`.

**Fase 3 (parcial)**: `DiLinkLauncher` mínimo implementado e validado (grid Compose de apps launchable, tap → `daemon.launchApp`). Falta: back-stack vazia → volta ao launcher; polish do grid.

**Emulador `bridgetest`**: fechado. Retomar: boot `-no-snapshot-save -no-snapshot-load` → `adb root` → rebuild → push jar → install bridge-debug → `pkill -x app_process` → start daemon → `am start .../.debug.BridgeTestActivity`.

**Telefone físico**: ADB WiFi em `192.168.1.11:5555` (cai — reconectar com `adb connect`). KernelSU OK (Shell grant; app grant PENDENTE após reinstall). AA dev mode + unknown sources OK. **App reinstalado com installer=com.android.vending (spoof)**. DHU segue quebrado (AA 17.1 × DHU 2.0). **App NÃO aparece no AA — ver topo desta seção.**

**PC (dev)**: JDK 21 em `~/.jdks/jdk-21.0.11+10` pinado em `~/.gradle/gradle.properties` (org.gradle.java.home). SDK em `%LOCALAPPDATA%\Android\Sdk` (platform-tools, android-34, build-tools 34.0.0, cmake 3.22.1, ndk 29.0.13846066, cmdline-tools, emulator, system-image, extras;google;auto). `local.properties` no repo aponta p/ ele. JDK 25 do sistema QUEBRA o build (Kotlin 1.9.22). Microsoft JDK 21 corrompido (sem bin). Sem Android Studio.

</details>

## 7. Próximos passos (ordem exata) — ATUALIZADO 2026-07-25 13:00

1. **Instalar o build de 00:30** (strip Back/Home/Exit + AaInput completo) e **re-testar no Redmi**: toque root, Back/Home, e observar se o **stall do stream** (encoder zerando ~15s após toque) persiste agora que o VD atualiza frames no toque. Se persistir: comparar com Maps nativo (não sofre) e investigar throttle/vsync do gearhead.
2. **Freeform "PIP" no VD (ideia do usuário, prioridade)**: apps abrem com launch bounds = rect do viewport (direita da nav bar), nav bar sempre visível. Habilitar `enable_freeform_support` (root) + launch com bounds via reflection/ActivityOptions; launcher colapsa p/ coluna nav com app em foreground. Ver 6.9 pendentes.
3. **Commit** de tudo (working tree — ver resumo no topo). Sugestão: `feat: modo AA ponta a ponta — Finsky forge, mirror no host real, UI do carro no VD, injeção root HyperOS` + `docs: handover`.
4. **Teste no carro BYD real**: o forge já provou na bancada (Headunit Revived); no carro é plugar e ver o DiLink no launcher do AA (Finsky quente antes — abrir Play Store).
5. **Dedupe do setSurface** (2 VDs por conexão) e **keep-alive FGS** se o freezer ainda matar o app.
6. **Gestures**: `onScroll/onFling` → `input swipe` root (drag).
## 7. Próximos passos (ordem exata) — ATUALIZADO 2026-07-25 13:00

1. **Teste no carro BYD real** (usuário executando): abrir Play Store antes (Finsky quente), plugar, DiLink deve aparecer no launcher do AA. Validar: mirror, toque/gestos, rail (menu no strip), painel do telefone apagado, apps em landscape.
2. **Known issues em aberto**: (a) **BiometricPrompt** (apps com digital, ex.: Revolut) derruba a sessão — prompt vai pro display físico; (b) **stall do stream**: resolvido com keep-alive pulse da rail, mas observar no carro (host BYD pode ter throttle diferente); (c) sync da Finsky pode apagar rows forjadas — se sumir do AA, re-aplicar (candidato a automatizar).
3. **Fase 4 restante**: remover de vez app-server/dilink-car/TCP do repo (a dieta do root flavor já os exclui do build), CI workflows, self-update sem REQUEST_INSTALL_PACKAGES? (root pode `pm install`).
4. **Fase 5 (polish)**: dpi dinâmico por host, recents com mais ações (fechar app), coolwalk dock, docs (docs/ ainda descreve o fluxo legado TCP — reescrever pro modo AA).
5. **Vigilância**: (a) KSU grant após reinstall (`strings /data/adb/ksu/.allowlist | grep dilink`); (b) toggle "Servidor de head unit" auto-desliga — religar na UI se o server parar de responder; (c) auto-update do AA no Play.
6. **DHU (morto)**: Headunit Revived cobre a bancada; não voltar.

### 6.10 Sessão 4 (25/07) — modo AA funcional: decisões e armadilhas

**Arquitetura final validada (bancada)**:
- `MirrorScreen` (NavigationTemplate, strip = 1 ação **ícone de menu → toggle da rail**) → VD na surface do AA → `DiLinkLauncher` fullscreen (grid portada do app-server, dados locais) → apps fullscreen via `am start --display N --activity-multiple-task` + `am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER <pkg>` (landscape forçado).
- **NavRailService** (overlay no VD): handle 10dp na borda esquerda, tap (ou botão de menu do strip) expande p/ 74dp com **app atual + 4 recentes + Home + Back**, auto-hide 5s. Sempre visível com **pulse de alpha 2,5×/s** = keep-alive do stream.
- **Input**: `AaInput` (app-side, root KSU) — taps/keys via `input -d N` root; gestos via **InputInjectorMain** (app_process ROOT, socket 127.0.0.1:19648, protocolo texto: display/tap/key/down/move/up/mdown/mmove/mup). Log: `/data/local/tmp/input-injector.log`.

**Armadilhas resolvidas (não re-debugar)**:
- **Stream morre em conteúdo estático**: o encoder do gearhead adapta o fps até zerar (~15s) → HUREV cai ("WiFi read timeout"). Fix: pulse da rail (VD nunca estático). `OWN_FOCUS` do VD removido por suspeita — não era a causa (mantido removido, flags=0x6849).
- **execShell do daemon só capturava output de `pm ` e `cmd `** — `dumpsys`/`am task` nunca retornavam nada (snap de freeform impossível). Corrigido, mas **freeform abandonado**: MIUI ignora `ActivityOptions.launchBounds` (posiciona onde quer) e janelas separadas por app. Apps são fullscreen; o VD não tem como receber offset (ancora em 0,0 — sem padding esquerdo possível).
- **Scroll travava na borda**: ponteiro injetado era clampado aos limites do VD. Fix: clamp de ±1 tela de overshoot + re-âncora no centro ao iniciar drag novo.
- **Drag vertical invertido**: `onScroll` do AA tem Y invertido vs coords do VD (`curY - dy`).
- **Back esvaziava a stack** (tela preta): `AaInput.back()` ignora quando a única task visível é o launcher (checa via dumpsys root).
- **Keyguard cobria a projeção ao bloquear**: sessão agora = PARTIAL_WAKELOCK + `settings put secure lockscreen_disabled 1` + `cmd display power-off 0` re-enforçado a 30s; restaura tudo no `onSurfaceDestroyed`. `cmd locksettings` NÃO existe na HyperOS (usar settings secure).
- **Root flavor diet (AA_ONLY)**: `buildConfigField AA_ONLY` (root=true); root manifest remove `ConnectionService`, `InputInjectionService`, `NotificationService`, `MANAGE_EXTERNAL_STORAGE`, Shizuku perm (tools:node="remove"); `embedServerApk`+`copyNativeLibs` só rodam p/ não-root (assets gerados em `build/generated/server-assets`, source set só dos flavors standard/bridge); `.so` nativos deletados do git (daemon AA é Kotlin puro); MainActivity esconde car-flow (install/status/start-stop) quando AA_ONLY; ensureAssets sem extração p/ sdcard. APK root=51MB, standard=74MB.
- **`su` inacessível do daemon shell (KSU)**: todo input passa pelo app (root). Daemon segue shell p/ VD/launch/announce (funciona).
- **MIUI wakepath**: startActivity cross-app do app (launcher) dispara `ConfirmStartActivity` no display 0 — launches SEMPRE via daemon (shell `am start`).

## 8. Comandos úteis

```bat
:: build (JDK vem do pin em ~/.gradle/gradle.properties — não usar JDK 25 do sistema)
gradlew.bat :app-client:assembleStandardDebug :app-client:assembleRootDebug --console=plain

:: emulador
"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd bridgetest -no-snapshot-save
adb -s emulator-5554 root

:: DHU (telefone físico: head unit server ON primeiro)
adb forward tcp:5277 tcp:5277
"%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe"

:: logcat filtrado (sem pipes internos no adb shell — quebram cmd)
adb logcat -v time -s BridgeTest AaDaemonClient AaDaemonReceiver DaemonDeployer NativeBridge

:: dmesg avc (sepolicy)
adb shell "dmesg | grep -i avc | tail -8"
```

## 9. Riscos / questões abertas

- **broadcastIntent da HyperOS**: não validado no físico (sepolicy MIUI pode negar de shell — se negar, fallback: daemon como root anuncia; ou provider-bootstrap estilo Shizuku que funciona como shell via `content call`, mas exige o binder no processo certo).
- **Sessão AA real nunca testada** (DHU quebrado; carro ainda não testado) — risco de quirks do host BYD com NavigationTemplate map-only.
- **Token anti-spoof**: validação exige backend privilegiado; no modo produção (root/Shizuku) OK, mas desenhar para o caso Shizuku-indisponível.
- **VD sobre Surface externa**: validado parcialmente (daemon cria VD via `createVirtualDisplay` com Surface arbitrária — createVirtualDisplayFromTexture provado no fluxo carro; a variante Surface-do-AA ainda não rodou ponta a ponta).
- **Hidden API**: `setHiddenApiExemptions("L")` no ClientApp (necessário para reflection em ServiceManager/system APIs do app side).
- **Memórias do assistente**: diretório `~/.qwen/projects/c--users-anderson-projetos-dilink-auto/memory/` contém notas duráveis (motivação banco, pivô AA, bridge, debug DHU) — próximas sessões com o mesmo assistente as carregam automaticamente.
