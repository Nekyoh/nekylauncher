<p align="center">
  <img src="branding/logo-512.png" width="200" alt="NekyLauncher">
</p>

<h1 align="center">NekyLauncher</h1>
<p align="center"><b>by Nekyoh</b> — launcher de Minecraft: Java Edition para Windows e Linux</p>

<p align="center">
  <img src="docs/screenshots/home.png" width="820" alt="Tela inicial">
</p>

## O que ele faz

- **Minecraft original** — login com a conta **Microsoft** que tem o jogo, em um clique pelo navegador (como no MultiMC/Prism).
- **Instâncias isoladas** — cada uma com sua versão, mod loader, mods, resource packs, shaders, mundos e configurações. O ícone padrão é o logo do loader (bigorna do Forge, raposa do NeoForge, Fabric, Quilt — baixados do GitHub oficial de cada projeto) e dá pra trocar por qualquer imagem.
- **Mod loaders nativos** — Fabric, Quilt, Forge e NeoForge instalados direto pelo launcher (o instalador do Forge/NeoForge roda em modo headless, com os processors, igual ao instalador oficial).
- **Abas de conteúdo** — *Mods*, *Resource packs*, *Shaders* e *Mundos*: **arraste e solte** o arquivo e ele vai pra pasta certa; ative/desative mods; veja nome, versão e ícone lidos de dentro do `.jar`/`.zip`.
- **Modrinth e CurseForge integrados** — busque e instale mods, resource packs e shaders compatíveis com a versão e o loader da instância, já com as **dependências obrigatórias** (as que já estão instaladas são reconhecidas e puladas); instale **modpacks** dos dois em um clique; botão “Instalar Iris” na aba de shaders. Arquivos que o autor só libera pelo site do CurseForge não são baixados por trás: o launcher mostra o link para baixar na mão.
- **Skins** — troque a skin direto no seu perfil do Minecraft (como no launcher oficial): envie o PNG (ou arraste), escolha **normal ou slim**, escolha uma das **capas da sua conta** e veja a prévia de frente e de costas. Salve combinações como **presets** para trocar em um clique.
- **Java certo automaticamente** — baixa o mesmo runtime que o launcher oficial usa para cada versão (Java 8, 17, 21…). Ou escolha um Java instalado, o **Java do launcher oficial** (é detectado) ou aponte para qualquer `javaw.exe`/`java`.
- **Exportar/Importar .zip** — exporte uma instância escolhendo o que vai junto; importe `.zip` do NekyLauncher, **`.mrpack` (Modrinth)**, instâncias do **Prism/MultiMC** e modpacks do **CurseForge**.
- **Console do jogo** com cores por nível de log (lê os eventos XML do log4j como o launcher oficial), botão de parar, abrir pasta de logs.
- **Atualização automática** — ao abrir, o launcher verifica no GitHub se saiu versão nova e atualiza em um clique, do mesmo jeito que foi instalado (instalador, portátil, .deb ou tar.gz), sem mexer em instâncias, mundos e contas.
- **Interface em Português, English e Español** (troca na hora, em Configurações).
- **Links da comunidade** na barra lateral: canais Nekyoh e Tekyoh no YouTube, nekyoh.tech, site do launcher e EpicCraft.
- Zero dependências externas e **nada de JavaScript**: Java 21 + Swing com UI 100% desenhada à mão (tema escuro, ícones vetoriais, fonte Inter). O pacote final traz o próprio Java — o usuário não precisa instalar nada.

<p align="center">
  <img src="docs/screenshots/instance-mods.png" width="49%" alt="Mods da instância">
  <img src="docs/screenshots/skins.png" width="49%" alt="Skins">
</p>

## Baixar e instalar

Baixe na página de [Releases](https://github.com/Nekyoh/nekylauncher/releases/latest):

| Sistema | Arquivo | Como usar |
|---|---|---|
| Windows | `NekyLauncher-<versão>.exe` | instalador: cria atalho no Menu Iniciar e aparece em *Aplicativos* para desinstalar |
| Windows | `NekyLauncher-windows-x64.zip` | portátil: descompacte e abra `NekyLauncher.exe` |
| Linux | `nekylauncher_<versão>_amd64.deb` | `sudo apt install ./nekylauncher_*.deb` (Debian, Ubuntu, Mint…) |
| Linux | `NekyLauncher-linux-x64.tar.gz` | portátil: extraia e rode `NekyLauncher/bin/NekyLauncher` |

Todos já trazem o Java; não precisa instalar nada além disso.

> [!WARNING]
> **Aviso do Windows:** o NekyLauncher ainda não tem assinatura digital, então o Windows pode mostrar
> *"O Windows protegeu o computador"* ou *"Fornecedor desconhecido"*. Clique em **Mais informações › Executar assim mesmo**.
> O código é aberto e pode ser conferido aqui no GitHub; os executáveis são gerados pelo GitHub Actions a partir dele.

### Atualizações

Ao abrir, o launcher verifica se existe versão nova e mostra um aviso. Em **Atualizar agora** ele baixa o arquivo certo para a sua instalação, confere o checksum, fecha e:

- **instalador do Windows**: abre o instalador da versão nova (só seguir; o launcher reabre no final);
- **portátil (Windows/Linux)**: troca os arquivos do programa e reabre — a pasta `data/` e o `portable.txt` ficam intactos;
- **.deb**: pede sua senha do sistema e instala o pacote novo.

Dá para desligar a verificação ou procurar na hora em **Configurações › Atualizações**. Instâncias, mundos, contas e presets de skin nunca são apagados.


## Login Microsoft (conta original)

Para quem usa o launcher é **um clique**: *Contas › Microsoft* abre o navegador na página da Microsoft, a pessoa entra e volta — pronto. (Também tem a opção "Entrar com código em outro dispositivo", via `microsoft.com/link`.) A senha nunca passa pelo launcher.


## Onde ficam os arquivos

| Sistema | Pasta |
|---|---|
| Windows | `%APPDATA%\.nekylauncher` |
| Linux | `~/.local/share/nekylauncher` (ou `$XDG_DATA_HOME/nekylauncher`) |
| Portátil | crie um arquivo vazio `portable.txt` ao lado do `NekyLauncher.exe` (no Linux, dentro da pasta `NekyLauncher/`, ao lado de `bin/`) → tudo vai para `data/` ali mesmo |

Para trocar o local (por exemplo para outro disco), use **Configurações › Armazenamento › Alterar local…**. O launcher move tudo (instâncias, mundos, Java, downloads) — no mesmo disco é instantâneo — e reinicia já usando a pasta nova. Se algo falhar no meio, os arquivos voltam para o lugar antigo. "Restaurar padrão" traz tudo de volta.

Bibliotecas, assets e runtimes Java são compartilhados entre instâncias; cada instância tem sua pasta `instances/<nome>/minecraft`.

## Estrutura do código

```
src/main/java/com/nekyoh/nekylauncher/
  game/      manifesto de versões, regras, bibliotecas, assets, natives, linha de comando, processo do jogo
  java/      detecção de Java e runtimes da Mojang/Temurin
  loader/    Fabric, Quilt, Forge, NeoForge (incluindo os processors do instalador)
  auth/      Microsoft (navegador ou código → Xbox → XSTS → Minecraft)
  skins/     API de perfil do Minecraft (skin, modelo, capas) e presets salvos
  instance/  instâncias, leitura de metadados de mods/packs, exportar/importar
  modrinth/  API do Modrinth com dependências
  curseforge/ API do CurseForge (busca, arquivos, dependências, fingerprints, modpacks)
  update/    verificação de versão no GitHub Releases e atualização automática
  ui/        janela, páginas, diálogos e o kit visual (tema, botões, ícones…)
src/main/resources/nekylauncher/i18n/  traduções (en, pt, es)
```

## Licença

Código sob a licença **MIT** (veja `LICENSE`). O nome e a logo do NekyLauncher não fazem parte da licença MIT (veja `NOTICE.md`).
A fonte Inter é distribuída sob a SIL Open Font License 1.1.

**NÃO É UM PRODUTO OFICIAL DO MINECRAFT. NÃO É APROVADO NEM ASSOCIADO À MOJANG OU À MICROSOFT.**
O launcher não distribui arquivos do jogo: tudo é baixado dos servidores oficiais pelo próprio computador do usuário.
