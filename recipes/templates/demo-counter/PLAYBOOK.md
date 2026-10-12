# Playbook: demo-counter (adapter template)

This recipe exists to show the format and to test `adapter_template.py`. It is not a game integration.

1. Read `../adapter_template.py` (the lab runs it, so read it before you allow that).
2. `um lab validate recipes/templates/demo-counter`
3. `um lab accept recipes/templates/demo-counter --allow-recipe-cmd`

Expected: every check passes, including the envelope rules (duplicate, gap, stale session, stale epoch, bad
envelope, resume after a disconnect, restart) and the `count-to-five` fixture twice with identical results.

To start a real recipe, copy this folder and the adapter, then follow `recipes/CONTRIBUTING.md`.
