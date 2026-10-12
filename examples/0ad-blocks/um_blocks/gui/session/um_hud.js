/**
 * Universal Modder crossover: hero mode.
 *
 * Play one hero in first person, with a hotbar, hearts, hunger and XP, and give your squad orders
 * from the hotbar. The simulation is the authority: every action here is a typed "um-*" command
 * sent with Engine.PostNetworkCommand. A script over the RL interface, or the MCP bridge, sends
 * the same JSON. The HUD only draws what the simulation reports through UmObserve.
 *
 * Keys in hero mode: W walk, S stop, A/D turn, 1-9 hotbar, left click use, right click break,
 * V first/third person, Tab tactical map, Esc leave.
 */
var g_UmHud = {
	"on": false,
	"view": "fps",
	"slot": 0,
	"heading": 0,
	"walking": false,
	"keys": {},
	"src": "gui-" + Math.floor(Math.random() * 1000000000),
	"seq": 0,
	"obs": null,
	"hadHero": false,
	"nextMove": 0,
	"nextTurn": 0,
	"lastTick": 0,
	"flashUntil": 0,
	"itemUntil": 0,
	"laidOut": false
};

var g_UmSlots = [
	{ "id": "block", "name": "Granite block: place it where you look", "icon": "block_granite" },
	{ "id": "pickaxe", "name": "Pickaxe: break the block you look at", "icon": "pickaxe" },
	{ "id": "sword", "name": "Sword: you and your squad attack", "icon": "sword" },
	{ "id": "banner", "name": "Banner: your squad marches where you look", "icon": "banner" },
	{ "id": "horn", "name": "Horn: your squad follows you", "icon": "horn" },
	{ "id": "box", "name": "Box formation", "icon": "form_box" },
	{ "id": "line", "name": "Line formation", "icon": "form_line" },
	{ "id": "shield", "name": "Shield: your squad holds its ground", "icon": "shield" },
	{ "id": "map", "name": "Map: tactical overview (Tab)", "icon": "map" }
];

// In hero mode W/A/S/D move the hero, so the camera keeps only the arrow keys. These go into the
// "mod" config namespace, which is never written to the player's user.cfg.
var g_UmCameraKeys = {
	"hotkey.camera.up": ["UpArrow"],
	"hotkey.camera.down": ["DownArrow"],
	"hotkey.camera.left": ["LeftArrow"],
	"hotkey.camera.right": ["RightArrow"]
};

var g_UmPanels = ["minimapPanel", "supplementalSelectionDetails", "selectionDetails", "unitCommands"];

var g_UmKey = { "tab": 9, "escape": 27, "one": 49, "nine": 57, "a": 97, "d": 100, "s": 115, "v": 118, "w": 119 };

var g_UmPlaceReach = [8, 12];   // metres ahead of the hero where a block lands (past the squad)
var g_UmTurnRate = Math.PI / 2; // radians per second while A or D is held

function umOffset(n)
{
	return n < 0 ? String(n) : "+" + n;
}

function umPost(type, fields)
{
	let obs = g_UmHud.obs;
	if (!obs)
		return null;
	let cmd = { "type": type, "src": g_UmHud.src, "seq": ++g_UmHud.seq, "session": obs.session, "epoch": obs.epoch };
	for (let key in fields)
		cmd[key] = fields[key];
	Engine.PostNetworkCommand(cmd);
	return cmd;
}

function umObserve()
{
	g_UmHud.obs = Engine.GuiInterfaceCall("UmObserve");
	return g_UmHud.obs;
}

function umFlash(text, ok)
{
	let colour = ok ? "255 255 255" : "255 140 120";
	Engine.GetGUIObjectByName("umFlash").caption = '[color="' + colour + '"]' + escapeText(text) + "[/color]";
	g_UmHud.flashUntil = Date.now() + 2500;
}

function umToggle()
{
	if (g_UmHud.on)
		umLeave();
	else
		umEnter();
}

function umEnter()
{
	let obs = umObserve();
	if (!obs || g_IsObserver)
		return;
	g_UmHud.on = true;
	g_UmHud.view = "fps";
	g_UmHud.keys = {};
	if (obs.avatar)
		g_UmHud.heading = obs.avatar.angle;
	else
		umPost("um-spawn", {});
	umBindCamera(true);
	g_Selection.reset();
	umSelect(g_UmHud.slot);
	umShow();
}

function umLeave(reason)
{
	g_UmHud.on = false;
	g_UmHud.hadHero = false;
	g_UmHud.keys = {};
	umBindCamera(false);
	Engine.CameraFollow(0);
	umShow();
	if (reason)
		umFlash(reason, false);
}

function umBindCamera(hero)
{
	for (let key in g_UmCameraKeys)
		if (hero)
			Engine.ConfigDB_CreateValues("mod", key, g_UmCameraKeys[key]);
		else
			Engine.ConfigDB_RemoveValue("mod", key);
	Engine.ReloadHotkeys();
}

function umSetView(view)
{
	let hero = g_UmHud.obs && g_UmHud.obs.avatar;
	g_UmHud.view = view;
	g_UmHud.keys = {};
	if (view == "map")
	{
		umBindCamera(false);
		Engine.CameraFollow(0);
		if (hero)
			Engine.CameraMoveTo(hero.x, hero.z);
	}
	else
	{
		umBindCamera(true);
		g_Selection.reset();
		if (hero && view == "fps")
			Engine.CameraFollowFPS(hero.id);
		else if (hero)
			Engine.CameraFollow(hero.id);
	}
	umShow();
}

function umShow()
{
	let hero = g_UmHud.on;
	let map = g_UmHud.view == "map";
	Engine.GetGUIObjectByName("umHero").hidden = !hero;
	Engine.GetGUIObjectByName("umCrosshair").hidden = !hero || g_UmHud.view != "fps";
	let button = Engine.GetGUIObjectByName("umPlayButton");
	button.caption = hero ? "Back to hero (Tab)" : "Play as hero";
	button.hidden = hero && !map;
	for (let name of g_UmPanels)
		Engine.GetGUIObjectByName(name).hidden = hero && !map;
	Engine.GetGUIObjectByName("umHint").caption = !hero ? "" : map ?
		"Tactical map: normal RTS controls. Tab: back to your hero" :
		"W walk, S stop, A/D turn\n1-9 items, click to use, right click to break\nV camera, Tab map, Esc leave";
}

function umSelect(slot)
{
	g_UmHud.slot = slot;
	Engine.GetGUIObjectByName("umSlotSelected").size = "50%" + umOffset(-200 + slot * 44) + " 100%-52 50%" + umOffset(-200 + slot * 44 + 48) + " 100%-4";
	Engine.GetGUIObjectByName("umItemName").caption = g_UmSlots[slot].name;
	g_UmHud.itemUntil = Date.now() + 2500;
}

function umLayout()
{
	g_UmHud.laidOut = true;
	for (let i = 0; i < 10; ++i)
	{
		Engine.GetGUIObjectByName("umHeart[" + i + "]").size = "50%" + umOffset(-198 + i * 18) + " 100%-80 50%" + umOffset(-180 + i * 18) + " 100%-62";
		Engine.GetGUIObjectByName("umFood[" + i + "]").size = "50%" + umOffset(18 + i * 18) + " 100%-80 50%" + umOffset(36 + i * 18) + " 100%-62";
	}
	for (let i = 0; i < 9; ++i)
	{
		Engine.GetGUIObjectByName("umSlot[" + i + "]").size = "50%" + umOffset(-198 + i * 44) + " 100%-50 50%" + umOffset(-198 + i * 44 + 44) + " 100%-6";
		Engine.GetGUIObjectByName("umSlotIcon[" + i + "]").sprite = "stretched:um/" + g_UmSlots[i].icon + ".png";
	}
	umSelect(0);
	umShow();
}

/** Where the player is aiming: the crosshair in first person, else the mouse. */
function umAim(mouseX, mouseY)
{
	let hero = g_UmHud.obs.avatar;
	let x = mouseX;
	let y = mouseY;
	if (g_UmHud.view == "fps")
	{
		let size = Engine.GetGUIObjectByName("umHud").getComputedSize();
		x = (size.left + size.right) / 2;
		y = (size.top + size.bottom) / 2;
	}
	let ground = Engine.GetTerrainAtScreenPoint(x, y);
	let dx = ground.x - hero.x;
	let dz = ground.z - hero.z;
	let dist = Math.hypot(dx, dz);
	if (dist < 0.5)
	{
		dx = Math.sin(g_UmHud.heading);
		dz = Math.cos(g_UmHud.heading);
		dist = 1;
	}
	let reach = Math.min(Math.max(dist, g_UmPlaceReach[0]), g_UmPlaceReach[1]);
	let aim = {
		"x": hero.x + dx / dist * reach,
		"z": hero.z + dz / dist * reach,
		"far": { "x": ground.x, "z": ground.z },
		"block": 0,
		"enemy": 0
	};
	let picked = Engine.PickEntityAtPoint(x, y);
	let state = picked > 0 && GetEntityState(picked);
	if (state && state.template && state.template.startsWith("um/block_"))
		aim.block = picked;
	else if (state && state.player > 0 && g_Players[g_ViewedPlayer] && g_Players[g_ViewedPlayer].isEnemy[state.player])
		aim.enemy = picked;
	return aim;
}

function umUse(slot, mouseX, mouseY)
{
	if (!g_UmHud.obs || !g_UmHud.obs.avatar)
		return;
	let aim = umAim(mouseX, mouseY);
	switch (g_UmSlots[slot].id)
	{
	case "block":
		umPost("um-place", { "x": aim.x, "z": aim.z, "block": "granite" });
		break;
	case "pickaxe":
		umPost("um-break", aim.block ? { "entity": aim.block } : { "x": aim.x, "z": aim.z });
		break;
	case "sword":
		umPost("um-squad", { "op": "attack", "target": aim.enemy });
		break;
	case "banner":
		umPost("um-squad", { "op": "rally", "x": aim.far.x, "z": aim.far.z });
		break;
	case "horn":
		umPost("um-squad", { "op": "follow" });
		break;
	case "box":
		umPost("um-squad", { "op": "formation", "formation": "box" });
		break;
	case "line":
		umPost("um-squad", { "op": "formation", "formation": "line_closed" });
		break;
	case "shield":
		umPost("um-squad", { "op": "hold" });
		break;
	case "map":
		umSetView("map");
		break;
	}
}

function umStop()
{
	let hero = g_UmHud.obs && g_UmHud.obs.avatar;
	if (hero && g_UmHud.walking)
		umPost("um-move", { "x": hero.x, "z": hero.z, "stop": true });
	g_UmHud.walking = false;
}

/** Runs before the normal session input; returns true when hero mode used the event. */
function umHandleInput(ev, hoveredObject)
{
	if (!g_UmHud.on)
		return false;
	if (ev.type == "keydown" || ev.type == "keyup")
	{
		let sym = ev.keysym.sym;
		let down = ev.type == "keydown";
		if (sym == g_UmKey.escape)
		{
			if (down)
				umLeave();
			return true;
		}
		if (sym == g_UmKey.tab)
		{
			if (down)
				umSetView(g_UmHud.view == "map" ? "fps" : "map");
			return true;
		}
		if (g_UmHud.view == "map")
			return false;
		if (sym == g_UmKey.v)
		{
			if (down)
				umSetView(g_UmHud.view == "fps" ? "third" : "fps");
			return true;
		}
		if (sym >= g_UmKey.one && sym <= g_UmKey.nine)
		{
			if (down)
				umSelect(sym - g_UmKey.one);
			return true;
		}
		for (let key of ["w", "a", "s", "d"])
			if (sym == g_UmKey[key])
			{
				if ((key == "s" && down) || (key == "w" && !down))
					umStop();
				let turning = key == "a" || key == "d";
				if (turning && down && !g_UmHud.keys[key])
					g_UmHud.nextTurn = 0;
				g_UmHud.keys[key] = down;
				if (turning && !down && !g_UmHud.keys.w)
					umPost("um-face", { "angle": g_UmHud.heading });
				return true;
			}
		return false;
	}
	if (g_UmHud.view == "map" || hoveredObject)
		return false;
	if (ev.type == "mousebuttondown" && (ev.button == SDL_BUTTON_LEFT || ev.button == SDL_BUTTON_RIGHT))
	{
		umUse(ev.button == SDL_BUTTON_RIGHT ? 1 : g_UmHud.slot, ev.x, ev.y);
		return true;
	}
	return ev.type == "mousebuttonup" && (ev.button == SDL_BUTTON_LEFT || ev.button == SDL_BUTTON_RIGHT);
}

/** Every frame: keep the camera on the hero and turn held keys into commands. */
function umTick()
{
	if (!g_UmHud.laidOut)
		umLayout();
	let now = Date.now();
	if (now > g_UmHud.flashUntil)
		Engine.GetGUIObjectByName("umFlash").caption = "";
	if (now > g_UmHud.itemUntil)
		Engine.GetGUIObjectByName("umItemName").caption = "";
	let hero = g_UmHud.on && g_UmHud.obs && g_UmHud.obs.avatar;
	if (!hero || g_UmHud.view == "map")
		return;
	for (let name of g_UmPanels)
		Engine.GetGUIObjectByName(name).hidden = true;
	if (Engine.GetFollowedEntity() != hero.id)
	{
		if (g_UmHud.view == "fps")
			Engine.CameraFollowFPS(hero.id);
		else
			Engine.CameraFollow(hero.id);
	}
	// Turning follows the time the key is held, not the frame rate (a software renderer may draw
	// only a few frames a second).
	let keys = g_UmHud.keys;
	let elapsed = Math.min(now - (g_UmHud.lastTick || now), 500);
	g_UmHud.lastTick = now;
	if (keys.a || keys.d)
	{
		g_UmHud.heading += (keys.d ? 1 : -1) * g_UmTurnRate * elapsed / 1000;
		if (now >= g_UmHud.nextTurn)
		{
			g_UmHud.nextTurn = now + 150;
			g_UmHud.nextMove = 0;
			if (!keys.w)
				umPost("um-face", { "angle": g_UmHud.heading });
		}
	}
	if (keys.w && now >= g_UmHud.nextMove)
	{
		umPost("um-move", { "x": hero.x + Math.sin(g_UmHud.heading) * 10, "z": hero.z + Math.cos(g_UmHud.heading) * 10 });
		g_UmHud.walking = true;
		g_UmHud.nextMove = now + 500;
	}
}

/** Every simulation update: read the authority's state and redraw the HUD. */
function umRefresh()
{
	let obs = umObserve();
	if (!obs)
		return;
	if ((obs.lastSeq[g_UmHud.src] || 0) > g_UmHud.seq)
		g_UmHud.seq = obs.lastSeq[g_UmHud.src];
	if (!g_UmHud.on)
		return;
	if (obs.avatar && !g_UmHud.hadHero)
	{
		g_UmHud.hadHero = true;
		g_UmHud.heading = obs.avatar.angle;
	}
	else if (!obs.avatar && g_UmHud.hadHero)
	{
		umLeave("Your hero fell. Press Play as hero to start again.");
		return;
	}
	let failedSpawn = obs.acks.find(ack => ack.src == g_UmHud.src && ack.type == "um-spawn" && !ack.ok && ack.code != "already");
	if (!obs.avatar && failedSpawn)
	{
		umLeave(failedSpawn.text);
		return;
	}
	let hud = obs.hud;
	for (let i = 0; i < 10; ++i)
	{
		let heart = hud.halfHearts - i * 2;
		Engine.GetGUIObjectByName("umHeart[" + i + "]").sprite = "stretched:um/" +
			(heart >= 2 ? "heart_full" : heart == 1 ? "heart_half" : "heart_empty") + ".png";
		let food = hud.halfFood - (9 - i) * 2;
		Engine.GetGUIObjectByName("umFood[" + i + "]").sprite = "stretched:um/" +
			(food >= 2 ? "food_full" : food == 1 ? "food_half" : "food_empty") + ".png";
	}
	Engine.GetGUIObjectByName("umXpFill").size = "0 0 " + hud.xpInLevel * 10 + "% 100%";
	Engine.GetGUIObjectByName("umLevel").caption = hud.level > 0 ? String(hud.level) : "";
	Engine.GetGUIObjectByName("umSlotCount[0]").caption = String(hud.blocksInHand);
}

g_NotificationsTypes["um-feedback"] = function(notification, player)
{
	if (notification.text && g_UmHud.on)
		umFlash(notification.text, notification.ok);
};

registerSimulationUpdateHandler(umRefresh);

var umSessionHandleInputBeforeGui = handleInputBeforeGui;
handleInputBeforeGui = function(ev, hoveredObject)
{
	return umHandleInput(ev, hoveredObject) || umSessionHandleInputBeforeGui(ev, hoveredObject);
};

var umSessionOnTick = onTick;
onTick = function()
{
	umSessionOnTick();
	umTick();
};
