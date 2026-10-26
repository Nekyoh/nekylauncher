# NOTICE

## NekyLauncher name and logo
The source code of NekyLauncher is licensed under the MIT License (see `LICENSE`).
The **NekyLauncher name and logo** (files in `branding/`, `packaging/icon.*` and
`src/main/resources/nekylauncher/img/`) are **not** covered by the MIT License.
They belong to Nekyoh. Forks must use a different name and logo.

## Minecraft
NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

NekyLauncher does not contain or redistribute any Minecraft game files. Game files,
libraries, assets and Java runtimes are downloaded by the user's computer directly from
Mojang's servers (and from the official mod loader / Modrinth / CurseForge / Adoptium servers),
exactly like the official launcher does. "Minecraft" is a trademark of Mojang Synergies AB.
Use of the game is subject to the Minecraft EULA: https://www.minecraft.net/eula

## Third-party components
| Component | Where | License |
|---|---|---|
| Inter font (Regular, Medium, SemiBold, Bold) © The Inter Project Authors | `src/main/resources/nekylauncher/fonts` | SIL Open Font License 1.1 (`fonts/OFL.txt`) |
| OpenJDK runtime (bundled by jpackage builds) | packaged apps only | GPLv2 with Classpath Exception |

No other third-party libraries are used: HTTP, JSON, ZIP, XML and the UI are built on the JDK.

## Services used at runtime
- Mojang / Minecraft services (versions, libraries, assets, Java runtimes, Microsoft/Xbox sign-in)
- Fabric, Quilt, Forge and NeoForge metadata and maven repositories
- Mojang / Minecraft services profile API (skin upload, arm model and cape selection)
- Modrinth API (search and downloads)
- CurseForge Core API (search and downloads; files whose authors disallow third-party
  distribution are never downloaded by the launcher, the user is sent to the CurseForge page)
- Eclipse Adoptium API (only when Mojang has no Java runtime for the platform)
