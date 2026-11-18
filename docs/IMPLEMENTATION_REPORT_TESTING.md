# DiLink-Auto Test Coverage Completion — Main Implementation Report

Date: 2026-10-09 Scope: all 6 Gradle modules in the repository (`:protocol-core` / `:protocol` / `:app-client` / `:app-server` / `:app-desktop` / `:vd-server`)

## 0. Execution approach
First, 6 read-only audit subagents were dispatched in parallel (one per module) to produce coverage-gap reports, and tests were then added module by module based on those reports. All new tests **pass in measured runs on the local machine in Gradle offline mode** (`/>module/:test`, including both the debug and release variants for Android modules). The test stack follows the project's existing conventions: **JUnit4 + kotlinx-coroutines-test**, with no Robolectric/Mockito/MockK; Android modules rely on `isReturnDefaultValues=true` plus hand-written fakes (`ContextWrapper(null)`, capturing `OutputStream`, canned lambda).

## 1. New test inventory (all verify green)

| Module | New test files | Production objects covered | Case count |
|---|---|---|---|
| `:protocol` | `AdbProtocolTest`, `AdbCryptoTest` | ADB frame header/magic/checksum/round-trip/constants; `bigIntToLEPadded`/`SHA1_DIGEST_INFO`/`fingerprint`/`signAuthToken`/`buildAuthReply` | 25 |
| `:protocol-core` | `VideoConfigTest`, `VdDeployArgsFormatTest`, `ImeRestoreGuardTest`, `H264NalParserRefIdcTest`, `AsyncLogQueueTest`, `VdDeployConstantsTest`, `WifiGatewayIpTest` | `calculateOptimalDpi` all branches; `VdDeployArgs.format` layout/proportional clamping/bitrate boundaries; `shouldRestoreIme`; H264 `nal_ref_idc` restoration; `AsyncLogQueue` overflow/FIFO/suspension/close; kill/stop/probe commands locked word for word; gateway IP byte order | 34 |
| `:vd-server` | `AdaptiveBitrateEdgeTest`, `DisplayPowerControllerRestoreTest`, `TouchInjectorFallbackTest`, `CarCommandRouterDispatchTest` | adaptive bitrate threshold boundaries/truncation/sync latch; S-M10 sentinel fallback `restoreIme`; touch normalized scaling + shell fallback; S-02 shell quoting/`am/pm/input` command routing/`SET_DISPLAY_POWER` | 28 |
| `:app-client` | `VersioningTest`, `AppCategorizerTest`, `InstallStatusTest` | version parse/compare (including sentinel/SNAPSHOT boundaries); app categorization ordered keywords; install status string classification + phase index (including the localized misclassification hazard) | 38 |
| `:app-server` | `WifiGatewayProbeTest`, `CarCrashReportTest` | gateway lookup unavailable fallback; crash report format contract (header/timestamp/thread/stack/Process State/Caused by/Suppressed) | 8 |
| `:app-desktop` | `HandshakeFactoryTest`, `DesktopConfigTest` | handshake field mapping + `evenAlign` (lower bound 2, fallback for negative values); config defaults must come from protocol constants + `copy` changes only named fields | 6 |
| Total | **18 new files** | | **139** |

> Counting basis: the figures above are measured per-file `@Test` annotation counts (re-checked 2026-10-10); some methods contain multiple assertions. First batch 139 + later seam batch 44 = **183** new tests in the whole batch.

### build.gradle.kts changes
- `:protocol`: added `testOptions { unitTests.isReturnDefaultValues = true }` + `testImplementation("junit:junit:4.13.2")` (this module previously had **no test source set at all**).

## 2. Key cross-module invariants locked down
- **ADB wire**: `magic=cmd^0xFFFFFFFF`, additive checksum, and the MAX_PAYLOAD upper bound is not blocked at encode / is blocked at parse.
- **VD deploy argv** ↔ both ends; the `[P]ipelineServer` bracket trick in `kill/stop/probe` prevents accidentally killing the wrapping shell.
- **Screen timeout sentinel**: the write side writes the sentinel 2147483647 → the read side must fall back to 60000 and must never write the sentinel back.
- **H264 keyframes**: real encoders emit `0x65` (ref_idc=3), recognized via `and 0x1F`.
- **touch normalization→pixels** scaling and the `input -d tap` fallback.
- **Crash report format** ↔ consumed line by line on the phone side.
- **port/dimension/bitrate/dpi** defaults must come from `:protocol-core` constants (`Ports`/`VideoConfig`/`DimAlign`).

## 3. Production defects/risks found by the audit (**characterization tests pin the current behavior**; changes are product decisions and were not made unilaterally)
1. **`Versioning` zero-version sentinel** (`C-5`): `readInstalledVersion` returns `"0"` when nothing is installed, while `versionName="0/0.0/0.0.0"` compares equal to `"0"` → it is treated as "already up to date" and **the first install that should happen is skipped**. `-SNAPSHOT` also compares equal to the corresponding release. → `VersioningTest` (hazard case pins it).
2. **`InstallStatus` classifies only by English substrings** (`C-6`): the status strings are produced localized by `context.getString`, so every non-English `values-*` falls into `IDLE` → no progress/error display; `"not reachable"`/`"Invalid IP"` are not classified as ERROR either. → `InstallStatusTest` (hazard case pins it). Recommendation: classify through a **structured status enum** instead of localized text.
3. **`CarCommandRouter.dispatch`'s `LAUNCH_APP`/`APP_UNINSTALL` decode is unprotected** (`S-02`): a malicious package name makes `LaunchAppMessage.decode` throw directly, escaping into the frame-reading thread (`APP_INFO` has try/catch, these two do not). → `CarCommandRouterDispatchTest` (pins "throws an exception and no `am start`").
4. **`AppCategorizer` puts common apps in OTHER**: e.g. `com.google.android.youtube` matches no keyword. This is a UX gap, not a crash.
5. **WifiGatewayIp byte order**: the current LSB-first matches the `Formatter.formatIpAddress` convention and is **judged correct** (it had previously been listed separately as a suspected bug, but it is in fact consistent).

## 4. Verified run results
```
:protocol:test                                   BUILD SUCCESSFUL
:protocol-core:test                              BUILD SUCCESSFUL
:vd-server:test                                  BUILD SUCCESSFUL
:app-client:testDebugUnitTest                    BUILD SUCCESSFUL
:app-server:testDebugUnitTest                    BUILD SUCCESSFUL
:app-desktop:test                                BUILD SUCCESSFUL
```

## 5. Remaining high-value work (seams that need "refactoring for testability"; authorized, listed as follow-up)

### 5.1 Completed ("refactoring for testability" seams; all behavior unchanged + regression tests added + verified per module)
- ✅ **FeedGate ↔ VideoStreamPipe invariant** (app-desktop): a full pipe "dropping the oldest" evicts the CONFIG that FeedGate just delivered (silent corruption, pinned by a hazard test) + the recovery path that rebuilds a new pipe and replays `initialConfig`. `1b6092c`
- ✅ **AdbCrypto.encodePublicKey** `android.util.Base64 → java.util.Base64` (byte-identical, including the single `=`): the ANDROID_PUBKEY structure (modulus/exponent/words) can now be strictly validated on the JVM. `1b6092c`
- ✅ **TcpAdbConnection**: extracted the companion `negotiateMaxPayload`/`readMessageFrom(InputStream)`: socket-free coverage of connect negotiation + CRC/bad magic. `1b6092c`
- ✅ **CarConnectionService**: extracted `reconnectBackoffMs`/`iconBudgetAccepts` (pure functions): the previously untested reconnect backoff formula is now under regression. `6d25390`
- ✅ **Connection** `start()` injects `heartbeatIntervalMs`/`heartbeatTimeoutMs` (defaults still 3s/10s): automatic heartbeat ACK / watchdog kicks a silent peer / an active link is not killed by mistake. `fa00527`
- ✅ **AssetDeployer** `AssetSource` seam (`AssetManager` is final and cannot be subclassed): all branches of the CRC freshness gate extract/ensureCurrent for vd-server.jar (including the A-L15 fallback, no tmp leftovers). `bbd6fe0`
- ✅ **CarConnectionService install flow `CarInstaller` seam** (app-client): extracted the `CarInstaller` interface + the pure function `parseInstalledVersion`; all branches of the install state machine (connect → wait for authorization → version decision → push install) are testable. `5d576e8`

Net addition: the 7 seams above add roughly +44 test methods (including the appended cases in `AdbCryptoTest`); together with the first batch of 139, that makes **183 new test methods**, all verified passing with offline gradle across all 6 modules.


Following the "low risk, behavior unchanged, regression tests included" principle, work proceeds according to the refactor lists in the 6 audit reports:
- `:protocol-core`: `Connection` heartbeat/watchdog/graceful EOF-flush tests (requires promoting `HEARTBEAT_*`/`FLUSH_GRACE_MS` to injectable parameters or consts).
- `:protocol`: `TcpAdbConnection` connect negotiation/readMessage CRC/shell closed loop (using a `TestSockets`-style fake server harness; recommended to extract `negotiateMaxPayload` / `readMessageFrom(InputStream)`, and to swap `AdbCrypto.encodePublicKey`'s `android.util.Base64` for `java.util.Base64` — byte equivalent).
- `:app-client`: `CarInstallCoordinator` (extract the `CarInstaller` interface + `parseInstalledVersion`), `AssetDeployer` (extract `AssetSource`, since `AssetManager` is final and cannot be subclassed), `AllowlistSeeder`/`filterByAllowlist`, `LogArchiver` (pure java.io, high ROI, can be added without refactoring).
- `:app-server`: `PinnedAppsRepository`/`CarPrefs`/`ManualConnectState` (add a `FakePrefs` fixture), `CarConnectionService`'s `logToggleEnabled`/`reconnectBackoff`/`iconBudget`/`resolvePhoneDpi` (extract pure functions), `CarTouchSender.shouldLog`, `CarLogWriter` bounded buffer; `CarCrashHandler` second-level archive name collision fix (a real bug).
- `:app-desktop`: `DesktopPaths`/`FrameDumper`/`VideoDecodePipeline` gate injection, the `FeedGate`↔`VideoStreamPipe` CONFIG eviction silent-corruption cross-file invariant, `SessionStatsLogger` format.
- `:vd-server`: `VirtualDisplayCreator.dtaAttached`, `GlPipeline.writeFrame`, `LifecycleWriter.encode`, `PipelineServer.displayReadyBytes/isAllowedPeer` (make internal/extract).

## 6. Conclusion
The highest-risk pure-logic surfaces that previously had **zero or weak coverage** (protocol frames, crypto primitives, deploy parameters, bitrate control, shell injection gates, version gates, status classification, crash format, cross-module constant contracts) are now locked down by **183 tests verified passing** (first batch 139 + seam batch 44), and several real defects are left with executable regression guardrails plus fix recommendations. Continuing to deepen the work per the seam list in §5 can bring the remaining branches/state machines into the regression net one by one without changing external behavior.
