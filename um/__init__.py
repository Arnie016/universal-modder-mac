"""universal-modder: tools any AI agent (Claude Code, Codex, Cursor, Gemini CLI, ...) uses to mod games.

Subcommands (see `um --help`):
  scan      find installed games and fingerprint one: engine, runtime, anti-cheat, mod loaders, routes
  fal       generate game assets with fal (sprites, textures, PBR, 3D, rigs, SFX, music, voice, video)
  sprite    cut out, fit, pixelate, recolor and pack 2D sprites
  render3d  render a GLB into sprite frames from a game's camera (Blender)
  video     compile styled showcase videos, trim, mux
  win       Windows (and WSL): screenshots, recording with game-only audio, input, processes
  backup    snapshot and restore save folders before you touch them
  publish   lint a mod folder before sharing: game files, decompiled code, secrets, credits
  kb        the knowledge base: search prior field notes, write your own, check it, open a PR
  scout     find open-source Mac mashup/mod projects and resolve shared posts; MCP server
  remix     change a game's rules through a reversible overlay; A/B bench
  lab       package a mechanic as a recipe, run acceptance tests in the real game, receipts, MCP
"""

__version__ = "0.4.0"
