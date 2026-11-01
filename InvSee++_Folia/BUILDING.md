# Isolated Folia 26.2 / 26.3 build

This module is independent of the legacy adapter reactor. It does not implement
the old InvSee++ API and must not run alongside the legacy plugin or its addons.
Release candidate **0.31.23-folia.1-rc1** enables Folia support after the scoped
paired acceptance recorded in `../test-harness/PARITY_RESULTS.md`. This is not
a blanket production-safety or future-version guarantee.

Verified build path: JDK 25, Python 3, the exact bootstrapped official runtime,
and the two Maven Central test libraries described below. The GitHub Actions
workflow reproduces this path. Maven 3.9+ is optional; the legacy NMS bootstrap
shown here is retained for diagnostics and currently fails for calendar versions.
Run from the repository root:

```sh
# Explicit bootstrap: downloads and executes the third-party NMS Maven plugin
# from https://repo.bytecode.space/repository/maven-public/.
# Review/approve that source and applicable Minecraft terms first.
mvn -f InvSee++_Folia/pom.xml -Pnms-bootstrap \
  ca.bkaw:paper-nms-maven-plugin:1.4.11-SNAPSHOT:init

# Builds this module only, not historical platform adapters.
mvn -f InvSee++_Folia/pom.xml clean verify
```

The bootstrap uses Paper `26.2.build.99-stable`, pinned in this module's POM.
The isolated build output is
`InvSee++_Folia/target/InvSee++-Folia.jar`.
Minecraft/Paper dependencies are provided by the server, not bundled.
Do not use `mvn deploy` or publish this experimental artifact as production-ready.

## Runtime acceptance

Use a disposable world and bind the server to localhost. Review the
[Minecraft EULA](https://www.minecraft.net/en-us/eula) and
[Microsoft Services Agreement](https://www.microsoft.com/servicesagreement)
before downloading/running Minecraft server software or setting `eula=true`.
Obtain Folia only from [PaperMC](https://papermc.io/downloads/folia).
Record its exact version, build, source commit and SHA-256.

A successful startup is **not** evidence of cross-region safety.
For additional versions or deployments, the acceptance checklist includes:

- Two distinct player regions, simultaneous viewers and competing inventory changes.
- Permission denial/revocation and exemptions; no edit through drag/hotbar/shift paths.
- Full-stack/partial-stack transfers with item conservation and no live-object sharing.
- Disconnect/retirement, teleport/region migration, plugin shutdown during each phase.
- Offline file load/save, concurrent login, malformed NBT, stale file changes.
- Crash boundaries and durable recovery records; ambiguous state quarantined, not replayed.
- A subsequent clean restart with persisted inventory contents reconciled.

Later server versions require their own API/NMS compilation and runtime acceptance;
the pinned 26.2 dependency is not a promise of automatic future compatibility.

## Verified dependency preparation fallback: official Folia runtime

The currently pinned NMS bootstrap plugin does not resolve the calendar-style
Paper build coordinate correctly in this environment: it treats
`26.2.build.99-stable` as the Minecraft version. Do not fabricate a successful
Maven result or install unrelated classes under that coordinate.

After an authorized official Folia startup has generated its `versions/` and
`libraries/` directories, build directly against those exact dependencies:

```sh
export JAVA_HOME=/absolute/path/to/jdk-25
./InvSee++_Folia/build-against-runtime.sh \
  /absolute/path/to/isolated-folia-runtime \
  /absolute/path/to/junit-platform-console-standalone-1.10.2.jar \
  /absolute/path/to/annotations-26.0.2.jar
```

Obtain JUnit Console and JetBrains annotations 26.0.2 from Maven Central; version 1.10.2 matches the parent
project's JUnit 5.10.2. The script compiles main/test sources, runs all module
JUnit tests, filters resource version fields, and packages only module-owned
classes/resources. It preserves the committed descriptor; it does not override the support gate.

Test dependency baseline: Folia **26.2 build 7 BETA**, source commit
`14b7fee5c866fca9a40ede4a58998fb928140f65`.
Official downloaded server launcher SHA-256:
`128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`.
The script records Java version and extracted-server/JUnit checksums under
`target/runtime-build/`. Keep the original download metadata alongside the
isolated runtime. Never commit or redistribute Minecraft/Folia runtime jars
as part of this plugin source or deliverable.

Additional verified dependency: Folia **26.3 build 6 ALPHA**, launcher SHA-256
`3d0a694905ddd9ec956af52259d555274ab8d3191871296d7094c290b686920d`.
Both classpaths pass 101 tests. Live checks use the same 26.2-compiled plugin on
both runtimes; see the committed reports for tested scenarios and limitations.

Before any disposable server startup, opt out of built-in server analytics:

```sh
mkdir -p plugins/bStats
printf '%s\n' 'enabled: false' 'serverUuid: 00000000-0000-0000-0000-000000000000' \
  'logFailedRequests: false' > plugins/bStats/config.yml
grep -qx 'enabled: false' plugins/bStats/config.yml
```

CI performs this setup before launching Java, binds localhost, stops the server,
and retains only the plugin and verification logs for three days. Runtime jars
are never included in the published plugin artifact.
