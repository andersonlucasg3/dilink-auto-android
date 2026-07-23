# DiLink-Auto — Session Handover (2026-07-23, atualizado)

> Estado completo do projeto ao fim da sessão de 2026-07-23.
> Escrito para continuação em nova sessão. Branch: `feature/ndk-migration`.
> **UPDATE 23/07: cadeia do bridge VALIDADA ponta a ponta no emulador** — VD renderiza dentro de Surface do app e touch injection funciona (ver seção 6).

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

Working tree: **LIMPA** (commit `9bce045`). Detalhe do que o commit inclui, para referência:

| Hash | Conteúdo |
|---|---|
| `f0a29b3` | Checkpoint: relay de comandos via lifecycle + decoder tardio + limpeza do daemon (23 arqs) |
| `f1686f3` | Fase 1: `RootManager` + `PrivilegeRouter` (root→Shizuku→ADB), `CONNECTION_METHOD_ROOT=3`, flavors `standard`/`root` |
| `f1a222c` | Modo AA MVP: `DilinkCarAppService`/`MirrorSession`/`MirrorScreen` (NavigationTemplate), `AaVideoClient` (MediaCodec→surface), `AaInputClient`, `DaemonDeployer` extraído |
| `8a6f533` | Permissões root-aware: onboarding/settings escondem prompts em modo root; `DaemonDeployer` staging via filesDir→/data/local/tmp (sem All Files); bateria via `dumpsys deviceidle whitelist` |
| `9f0021d` | POC bridge: bindService falha, ContentProvider.call falha, **ServiceManager.addService funciona (root e shell)** |
| `9e5b9e4` | **Bridge direta (Fase 2)**: AIDL `IAaDaemon`/`IAaAppCallback`; `AaDaemonMain`/`AaDaemonBridge` (daemon AA **puro Kotlin**); `AaDaemonClient` (getService+retry); hidden API via `setHiddenApiExemptions`; removidos clientes TCP do MVP |
| `720334d` | Fix crash loop: `VirtualDisplayClient.startListening` não lança mais exceção com porta 19647 ocupada (zumbi) |
| `9bce045` | **Bridge por broadcast + DiLinkLauncher — cadeia validada no emulador** (transporte, harness, DaemonEntry não-fatal, launcher no VD por componente explícito) |

**Working tree: LIMPA** (exceto screenshots `bridge_test_*.png`, artefatos de teste não versionados).

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

## 6. Estado exato AGORA (para retomar) — ATUALIZADO 2026-07-23 (3ª vez: investigação AA)

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

## 7. Próximos passos (ordem exata) — ATUALIZADO 2026-07-23

1. ~~Fix compilação~~ / ~~teste do bridge no emulador~~ — **FEITO e validado**.
2. **Validar no físico**: broadcastIntent de shell via `su shell` na HyperOS (sepolicy pode morder — observar avc). Se negar: daemon como root anuncia, ou fallback provider-bootstrap.
3. **Fase 3 (continuação)**: back-stack vazia no VD → volta ao DiLinkLauncher; wire do `MirrorScreen` (AA) para o mesmo fluxo do BridgeTest (surface do SurfaceCallback → daemon); polish do grid.
4. **Fase 4**: slim root flavor (MainActivity mínima, manifests por flavor, build por variante) + **remover app-server, dilink-car, ConnectionService, TCP flows** (pivô 100% AA). Não esquecer CI workflows.
5. **Fase 5**: polish — dpi dinâmico, gestures (onScroll/onFling → drag via injectMotionEvent MOVE — primitivo já existe), coolwalk dock, docs.
6. **AA no carro real** (BYD): validar sessão AA real (o DHU 2022 × AA 17.1 pode ser o único problema de emulação).

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
