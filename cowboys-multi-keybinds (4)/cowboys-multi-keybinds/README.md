# Cowboy's Multi Keybinds

Add a **second key (or mouse button) to any keybind** in Minecraft. Want
*Attack* on both left-click **and** `B`? Want *Jump* on `Space` **and** your
mouse side-button? This lets every control have a 2nd trigger.

Built for **Minecraft 1.21.11** (Fabric). _Note: 1.21.11 is the last obfuscated
Minecraft version; the next release is 26.1, and mods must be recompiled for it._

It's a **Fabric client mod**. It works fine on vanilla servers, Paper/Spigot
servers, realms, singleplayer — anywhere, because keybinds are handled entirely
on your own client. (See the note about Paper below.)

---

## How it works

- A tiny mixin hooks the two methods the game already calls for every key press
  (`KeyMapping.set` / `KeyMapping.click`). When your *extra* key fires, the
  matching control is triggered exactly the way its primary key would. That
  means hold-actions (move, sneak, attack) **and** tap-actions (inventory, drop,
  swap) both work.
- A menu lists every control with a button to assign its 2nd key.
- Assignments are saved to `config/cowboymkb.json` and reload automatically.

---

## Build it

You need **JDK 21** installed (1.21.11 runs on Java 21). Then, from this folder:

**Windows**
```
gradlew.bat build
```

**macOS / Linux**
```
./gradlew build
```

The finished mod jar lands in:
```
build/libs/cowboys-multi-keybinds-1.0.0.jar
```
(Use that file — ignore the `-sources` jar.)

> Heads up: I scaffolded this against the official Fabric template and verified
> the toolchain versions, but I couldn't run the Gradle build for you (the build
> downloads from Fabric's Maven, which my sandbox can't reach). The first
> `gradlew build` on your machine will fetch everything and compile.

---

## Build it in the cloud (no local setup) — easiest for Modrinth

This repo ships with a GitHub Actions workflow that compiles the jar for you:

1. Create a new GitHub repo and push these files (or upload the zip contents).
2. Go to the **Actions** tab — the **build** workflow runs automatically.
3. Open the finished run and download the **cowboys-multi-keybinds** artifact.
   Inside is your `cowboys-multi-keybinds-1.0.0.jar` — that's the file you upload
   to Modrinth.

### Optional: auto-publish to Modrinth
`.github/workflows/publish-modrinth.yml` will push the jar to Modrinth whenever
you publish a GitHub Release. Open that file and follow the 4 setup steps at the
top (create the Modrinth project, make a token, add the `MODRINTH_TOKEN` secret,
paste your project ID). After that, tagging a release publishes it for you.

---

## Install it

1. Install **Fabric Loader** for your Minecraft version (https://fabricmc.net/use/installer).
2. Drop **[Fabric API](https://modrinth.com/mod/fabric-api)** into your `mods` folder.
3. Drop the built `cowboys-multi-keybinds-x.x.x.jar` into the same `mods` folder.
4. Launch with the Fabric profile.

---

## Use it

- Press **`]`** (right bracket) to open the **Multi-Keybinds** menu. You can
  rebind that opener in vanilla **Options → Controls** under the
  *"Cowboy Multi Keybinds"* category.
- In the menu, click a control's button, then **press the key or mouse button**
  you want as its second trigger. The little **✕** clears it.
- Done. Both the original key and your new one now do the same thing.

---

## Targeting a different Minecraft version

This build targets **Minecraft 1.21.11** (Fabric Loom 1.14, Java 21). To retarget, edit `gradle.properties`
and change the four version lines to match your version (look them up on
https://fabricmc.net/develop):

```
minecraft_version=...
loader_version=...
loom_version=...
fabric_api_version=...
```

The mod uses very stable, long-standing methods, so it usually compiles across
versions with no code changes. The only version-sensitive spot is in
`KeyMappingMixin.java`: it shadows two private fields named `isDown` and
`clickCount`. If a build ever fails saying one of those can't be found, look up
the current field names on `KeyMapping` for your version and update the two
`@Shadow` lines — that's it.

You'll also want to adjust the `"minecraft": "~1.21.11"` line in
`src/main/resources/fabric.mod.json` to match.

---

## Notes & limits

- **Paper / Spigot:** this is a *client* mod, not a server plugin. A server
  (including Paper) has no access to your keyboard or controls screen, so a
  keybind feature like this can only live on the client. It works perfectly
  while you're connected to a Paper server — it just isn't installed *on* the
  server.
- **Vanilla:** unmodded Minecraft can't load mods at all; you need Fabric Loader.
  Once Fabric + Fabric API are installed, this works alongside a vanilla server.
- One extra key per control via the menu (the engine itself supports more if you
  hand-edit `config/cowboymkb.json` — add more entries to a control's array).

MIT licensed. Have fun.
