# Meme Bubble

Animated GIF meme emotes with sound in speech bubbles over players' heads for Minecraft **1.21.11** вЂ”
a server-driven pair of projects in one repo:

```
common/   shared wire protocol (EmoteProto + EmoteDef) вЂ” compiled into both sides,
          so the byte layout can never drift
plugin/   Paper server plugin  -> plugin/build/libs/meme-1.1.0.jar
mod/      Fabric client mod    -> mod/build/libs/meme-1.1.0.jar
tools/    shrink_gif.py вЂ” a small GIF compressor for emote assets
```

The **plugin** keeps all emote assets (GIFs, speech-bubble images, OGG sounds) in a server folder and
streams them to modded clients over plugin messages, chunked and cached by SHA-1 content hash вЂ” players
install one mod and download nothing twice, no resource pack involved. The **mod** renders emotes as
billboards above players, plays positional sound and adds the UI. Vanilla players join as usual вЂ” they
simply don't see the emotes.

## Features

- Emote panel on **G**: animated thumbnail grid, play/stop, per-emote hotkeys
- Quick-access wheel on **B**: 4 pages of 8 slots, bindings are per emote id and work across servers
- Built-in shop: per-emote prices charged through **Vault / ExcellentEconomy / EssentialsX / PlayerPoints**
- Player-selectable speech bubbles (any bubble the server offers, or none)
- Viewer-side controls: master volume, mute-all, per-player volume/mute
- Zip emote packs: a whole emote set as one file in `emotes/packs/`; loose files win id clashes
- Optional player-side packs (`player-emotes`): the server relays metadata only, never files вЂ”
  a viewer sees the emote only if they own the same files (same content hashes)
- Live admin tuning: `/memes set <id> <param> <value>` pushes changes to online players instantly
- Full client UI localisation (English default, Russian included)

## Building

JDK 21+ is required (`options.release` pins the bytecode to Java 21 on both sides).

```bash
./plugin/gradlew -p plugin build
./mod/gradlew    -p mod    build
```

Optional bundled starter pack: create `pack/{gifs,bubbles,sounds}` at the repo root and both builds
zip it into the jar as `meme-pack.zip`; the plugin installs it into `emotes/packs/` on first run and
the mod pre-caches it on the clients. Builds succeed without `pack/`.

## Server setup

1. Drop the plugin jar into `plugins/` and restart.
2. Add an emote: put a `.gif` (or `.png`/`.jpg`) into `plugins/Meme/emotes/gifs/` вЂ” the file name
   becomes the emote id (`hello.gif` в†’ `/meme hello`). Optional extras by convention:
   `sounds/<id>.ogg` (OGG Vorbis only) and `bubbles/<id>.png`. An `.ogg` without a matching image
   becomes a standalone sound-only emote.
3. Whole sets ship as zips: drop a zip with `gifs/`, `bubbles/`, `sounds/` folders inside into
   `plugins/Meme/emotes/packs/`. Delete the zip to remove its emotes.
4. `/memes reload` re-scans folders and configs live вЂ” online players get the updated catalogue.
5. Tune emotes in `emotes.yml` (name, price, access, bubble scale/anchor/offset, gif seat, sound
   volume/pitch/range, loops) or in game via `/memes set` вЂ” including `set defaults <param> <value>`
   for server-wide baselines.

### Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/meme <id>` / `/meme stop` | play / stop your emote | `meme.use` (default: everyone) |
| `/memes reload` | re-scan folders and configs | `meme.admin` (default: op) |
| `/memes set <id\|defaults> <param> <value>` | live tuning, persisted to emotes.yml | admin |
| `/memes list`, `/memes info <id>` | list / details | admin |
| `/memes mute <player> [min]` / `unmute` | block a player's emotes | admin |
| `/memes grant <player> <id\|*>` / `revoke` | give / take access | admin |
| `/memes price <id> <amount>` | set a price | admin |
| `/memes play <player> <id>`, `/memes stopall` | force play / clear all | admin |
| `/memes playeremotes [on\|off]` | allow players' own local packs | admin |

`meme.emote.*` grants access to every emote regardless of price.

### Selling emotes

`config.yml`:

```yaml
economy: vault            # vault | excellenteconomy | essentials | playerpoints
economy-currency: ''      # excellenteconomy only: currency id (file name from currencies/)
```

A missing plugin or unknown currency disables purchases with a console warning вЂ” purchases never
silently fall back to a currency you did not pick. An economy can also be provided through the API
(`MemeApi.setEconomyProvider`).

## Wire protocol

Both sides compare `EmoteProto.PROTOCOL_VERSION` in the HELLO exchange and warn the player on
mismatch. When changing `EmoteDef` fields or opcodes: bump the version, add new fields at the END of
`write()`/`read()` (the format has no per-field tags), and ship both jars together.

Serverbound plugin messages are limited to 32 KB, clientbound to 1 MiB вЂ” assets travel in 28000-byte
chunks, throttled per player (`chunks-per-tick`), with a hard cap per download on the client side.

## License

All rights reserved. The source code is published for reference: you may read it, report issues and
suggest changes, but you may not redistribute the code or publish modified builds without the
author's permission.
