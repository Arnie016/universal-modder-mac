# Worlds Garden 0.4.4 — Java playtest package

## Play on this Mac

Open **Play Sling Siege.command**. The prepared lab world opens into the garden. Select the slot-9 compass and right-click for the chest-style game grid, or walk into a portal. Slot 8 is the guide book. Regular players use those menus; operator commands are only setup/debug tools. After an update, quit the older Minecraft session cleanly before relaunching.

The launcher verifies a stopped-world backup once per version. If the world is still open, it asks you to Save and Quit instead of starting a second copy. The new garden builds automatically on first entry: four waterfalls, a tall open arcade and three themed outer gates. West is Siege, east is Frostline, north is Bloom. A 12-chunk client and server view distance shows the wide courtyard; lower settings may fade the farther walls. The sky stays open. The hub is a safe rest area; its damage protection is removed when entering Bloom or racing.

| Mode | What happens | Controls |
|---|---|---|
| Sling Siege | Select birds, destroy supports, clear green pigs, automatic reset/next fort | Slots 1–3 birds, hold R/right-click to launch, 4–8 building kit, 9 compass |
| Frostline GP | Choose a handling setup, Ready, countdown, three laps against three paced opponents | W throttle, S brake, A/D steer, Space boost |
| Bloom | Enter the enclosed conservatory, burn three amber fungal roots, collect a specimen at the far altar, escape through the carpeted foyer | Click fire, R reload, G collect/extract, Shift crouch |
| Garden outfitter | Spend earned credits on expedition equipment; lifetime XP is retained | Compass → Upgrade shop |
| Achievements | See locked/earned badges, lifetime level, credits and best lap | Compass → Your achievements |

Bloom rewards 150 XP/credits; Frostline rewards 100 for completing all three laps within four minutes; each cleared Siege fort rewards 50. Frostline results show race time, best lap and the saved reward, then offer replay/setup/garden. Automatic replay begins after eight seconds. Incendiary rounds cost 120 credits and halve the shots required to destroy a root. Field supplies cost 160 credits and supply two golden apples each expedition. Purchases are permanent for that saved player profile. The optional Dooley Doo support page is in the book; there is no real-money payment integration or configured donation link.

## Three.js / Blender parts workshop

The original Aurora pod is defined in `src/main/resources/assets/slingcraft/pods/aurora.json`. Minecraft and the local Three.js workbench use this same geometry. The browser uses a studio material preview; Minecraft uses built-in block textures.

- Preview: `http://127.0.0.1:8767/tools/pod-workbench/` while the local server is running.
- Restart preview: from this project, `python3 -m http.server 8767 --bind 127.0.0.1`, then open the URL.
- Export from the preview, then drag the exported JSON onto **Import Pod Parts.command**. The importer validates bounded dimensions and keeps the previous model. Restart the game to rebuild.
- Editable scene and GLB: `artifacts/aurora/`. 25 named objects, four materials, no rig or animation clips. Geometry is authored cuboids, not a claimed photoreal vehicle scan.

## Server package

The ZIP contains our mod, the pinned Fabric API, a starter world configuration, a Docker Compose template and these notes. It does not contain Minecraft itself, paid shader packs, a copied commercial game, accounts or credentials.

Pins: Minecraft Java **26.3**, Fabric Loader **0.19.5**, Fabric API **0.161.0+26.3**, Java **25+**, Slingcraft **0.4.4**. Every desktop client needs the matching mod and Fabric API. The graphics, garage and custom handling depend on the client mod.

1. Use a dedicated empty server folder; place the packaged mods in its `mods` folder and the provided arena configuration in `world/slingcraft.properties` before first launch.
2. Install the exact Fabric version using its official server installer, or use the included container template. The template requires an explicit EULA setting before running. It has authentication and the whitelist enabled.
3. Add invited Minecraft usernames to the whitelist from the host console. Ordinary players should remain non-operators. Begin with a small private playtest.
4. Keep TCP 25565 as the game port; do not expose RCON. Point a DNS A record such as `play.your-domain` at the public IP once the host exists. An SRV record is only necessary to advertise another port without typing it.
5. Back up the entire `world` directory with the server stopped, including `slingcraft-players` (inventory archives, credits, upgrades and lap records). The first pre-hub inventory archive is retained separately from mode kits.

The server template is not a provisioned host. The Azure sponsorship subscription is now verified Enabled, with no existing VMs; its credit balance is unverified. No purchased domain, allocated public game address or public player join is verified. See `PUBLIC-LAUNCH.md` in the ZIP for the prepared hosting route and current prices. The container image tag should be pinned to a tested digest before production. Source: [Fabric container configuration](https://docker-minecraft-server.readthedocs.io/en/latest/types-and-platforms/server-types/fabric/).

## Boundaries before inviting the public

- Normal players can use every game, the shop and achievements without operator permission. Arena setup/debug commands remain operator-only; action commands also check the player's current mode. No player is automatically made an operator. The example caps newly granted operator permission at level 2; retain console access for administration.
- Five game badges and the combined Worlds explorer achievement reflect saved completions. Every 250 lifetime Worlds XP raises the displayed Worlds level. Spending credits leaves lifetime XP and badges intact. These levels are separate from the ordinary Minecraft experience bar. Existing 0.4.3 profiles load without a reset.

- Current racing opponents follow paced lines; they are not full tactical racing AI.
- Racing steering is client-authoritative. Competitive validation/anti-cheat and simultaneous multiplayer load tests remain open.
- Siege uses a normal inventory with unlimited placement of the four kit materials inside the fort. Block edits and TNT block damage are bounded; scenery and the launch pad are protected. A real non-operator client test covers placement/refills, forbidden edits, blast boundaries, the ordinary inventory, bird firing and ability cleanup.
- Siege still uses a shared arena. Bloom allocates a separate expedition space per player (eight spaced columns with further rows). Multiplayer balancing and reconnect testing need a real multi-client session.
- Inventory archives are preserved; automatic restoration to an unrelated survival world is not implemented. Use a dedicated server/world for these minigames.
- The new sounds decode correctly and route through Minecraft's **Hostile Creatures** and **Ambient** sliders. Human headphone/speaker approval is NOT_TESTED.

## Bedrock, mobile and a browser link

This build is **Java Edition**, including on macOS. Geyser cannot translate the custom client renderer, garage screen and vehicle-control code. Its own [mod compatibility guidance](https://geysermc.org/wiki/geyser/faq/#can-i-connect-to-a-modded-forgefabricneoforge-server) says client-required mods are unsupported.

A mobile release needs a separate Bedrock behavior/resource-pack implementation and mobile controls, or a server-only redesign with Bedrock models and UI mappings. That is a port, not a checkbox or a texture-pack conversion. A browser link to the Three.js workshop opens the workshop only; it does not run Minecraft Java.

## Asset record

- Aurora: original procedural geometry, shared source and Blender exports.
- Fungal mass: adapted from the existing After the Bloom `fungusmass.glb`, credited there to Higgsfield / Tripo. The original has 12,172 vertices and one combined static mesh. The adapter samples 1,480 surface cells and maps them to Minecraft materials. It is a reduced Minecraft representation; the original GLB and its PBR textures are not running directly in Minecraft.
- Infected: Minecraft zombies wearing that custom fungal growth model. The older game's animated infected rigs have not been ported.
- `bloom/call_*`: layered from After the Bloom's original `grind`/`gasp` and Kenney CC0 `creak_*` samples; filtered, delayed, limited, mono Vorbis. `bloom/air`: filtered finite excerpt of the existing Mixkit wind recording credited by After the Bloom.
- No ElevenLabs free-plan output, commercial game soundtrack, or actor voice is included in this new sound set.
- Three.js 0.186.1: MIT; license alongside the local vendor files. The shipped Minecraft textures are referenced by name, not redistributed.

No new Higgsfield credits were spent. The available 3D Jutsu catalog was queried; no fungus asset matched. Public commercial clearance is not implied by this local adaptation record; retain the source generation and attribution records before distribution.
