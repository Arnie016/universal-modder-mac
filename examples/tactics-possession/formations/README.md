# Make a formation, share a battle

A formation gives eight guardians different positions around the spot you command. It changes actual movement in Tactics Possession. The host still owns collision, combat and saves. It does not add a game hook or execute code.

## Try one

Open **Choose a formation** before entering the valley, or **Formation** during a battle. Try Shield line, Spear wedge or Gate column. **Use this formation** affects your next movement command; it does not teleport soldiers already fighting. Command an empty part of the path to see the shape settle.

**Download layout** saves only the selected layout. **Copy play link** shares that layout with a fresh encounter at the same game address. The link contains positions, not your saved battle or account data. A localhost link still requires that local server; use the public game when sharing with friends. No uploaded layout is stored on a server.

## Make your own

1. Download one of the included layouts and edit its JSON.
2. Set a unique lowercase `id` and readable `name`.
3. Give `slots` exactly eight `{ "x": number, "z": number }` positions. X is sideways; positive Z is behind the target towards the sanctuary. Values must stay within -8 to 8 metres; leave at least 1.1 metres between soldiers.
4. Import the file in the workshop. Files must be smaller than 4 KB. Extra keys, scripts, invalid names and overlapping positions are rejected.
5. Use it, issue a movement order, play, save/load and try the shared link in a fresh tab.

A safe centre is not enough: every assigned position must avoid walls and the battlefield edge. If an order is rejected, choose a more open location or a narrower formation.

## Contribute

Fork the Universal Modder Mac source and add the reviewed layout under this folder. Give it its own gameplay test in `tests/formation.test.mjs`: a real donor movement command must change destinations and move the eight living guardians without collisions. Keep the existing acceptance tests unchanged. Run `npm test`; on a Mac also run `npm run native:verify`. Include exact reproduction steps and a screenshot from actual play. State what was designed versus adapted and record its terms. A maintainer should reproduce the contribution independently.

Included layouts and interpreter are original GPL-2.0-or-later source. Preserve the pinned donor and notices. This is one supported data extension, not an arbitrary plugin sandbox or a claim that another game's code can load.

## Native boundary

The native JavaScriptCore adapter understands the same layout and persists it, covered by its runtime verifier. The current native window does not yet have a formation picker; the visible workshop is browser-only. Browser and native sessions remain independent. Minecraft remains unimplemented and paused.
