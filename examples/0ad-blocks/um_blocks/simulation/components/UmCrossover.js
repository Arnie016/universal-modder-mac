/**
 * Universal Modder crossover: play one hero in first person and command a squad, with
 * Minecraft-style blocks, hearts, hunger and XP.
 *
 * This system component owns only the crossover's own state: which unit is the hero, the squad,
 * XP, and which typed commands were already applied. The stock components keep everything else:
 * movement and pathfinding (UnitAI, UnitMotion), collision (Obstruction), health and combat
 * (Health, Attack), resources (Player), and saving (the engine serializes this component along
 * with the rest of the simulation).
 *
 * Commands come in as "um-*" entries in g_Commands (simulation/helpers/UmCommands.js). Each one
 * carries an envelope:
 *   src      who sent it ("gui-123", "mcp", "minecraft"...)
 *   seq      a number that grows by one per command from that sender
 *   session  this game's session number, from Observe()
 *   epoch    how many times this game was loaded, from Observe()
 * A command from another session or from before the last load is refused ("stale-session",
 * "stale-epoch"), and a repeated seq is ignored ("duplicate"), so a sender can resend safely.
 */
function UmCrossover() {}

UmCrossover.prototype.Schema = "<a:component type='system'/><empty/>";

const UM_REACH = 16;                  // metres from the hero to the centre of a block cell
const UM_CELL = 4;                    // block edge, metres; blocks snap to a 4 m grid
const UM_BLOCK_COST = { "stone": 10 };
const UM_BLOCKS = ["granite", "limestone", "sandstone"];
const UM_FORMATIONS = ["null", "box", "line_closed", "column_closed"];
const UM_SQUAD_RADIUS = 40;
const UM_SQUAD_MAX = 12;
const UM_HUNGER_MS = 10000;           // the hero eats 1 food from the stockpile every 10 s
const UM_STARVE_HP = 5;
const UM_ACKS = 32;
const UM_SOURCES = 32;
const UM_NOBODY = { "avatar": 0, "squad": [], "formation": "null", "xp": 0, "placed": 0, "broken": 0 };

UmCrossover.prototype.Init = function()
{
	this.session = randIntInclusive(1, 2147483646);
	this.epoch = 0;
	this.players = {};
	this.lastSeq = {};
	this.acks = [];
	this.hungerTimer = 0;
};

UmCrossover.prototype.Serialize = function()
{
	return {
		"session": this.session,
		"epoch": this.epoch,
		"players": this.players,
		"lastSeq": this.lastSeq,
		"acks": this.acks,
		"hungerTimer": this.hungerTimer
	};
};

UmCrossover.prototype.Deserialize = function(data)
{
	for (let key in data)
		this[key] = data[key];
	// Every load starts a new epoch, so a sender still holding the old one has to observe again.
	++this.epoch;
};

/** A bridge may tag the game with its own session number when it attaches. */
UmCrossover.prototype.SetSession = function(session)
{
	this.session = session;
	return this.session;
};

UmCrossover.prototype.TimeMs = function()
{
	return Engine.QueryInterface(SYSTEM_ENTITY, IID_Timer).GetTime();
};

UmCrossover.prototype.Fail = function(code, text)
{
	return { "ok": false, "code": code, "text": text };
};

UmCrossover.prototype.Ok = function(code, text, extra)
{
	let result = { "ok": true, "code": code, "text": text };
	for (let key in extra || {})
		result[key] = extra[key];
	return result;
};

UmCrossover.prototype.Admit = function(player, cmd)
{
	if (cmd.session !== this.session)
		return this.Fail("stale-session", "That command was for another game session");
	if (cmd.epoch !== this.epoch)
		return this.Fail("stale-epoch", "That command was sent before the last load");
	if (typeof cmd.src != "string" || !cmd.src || !Number.isInteger(cmd.seq) || cmd.seq < 1)
		return this.Fail("bad-envelope", "A command needs src and a positive whole seq");
	let key = cmd.src + ":" + player;
	let last = this.lastSeq[key] || 0;
	if (cmd.seq <= last)
		return this.Fail("duplicate", "");
	delete this.lastSeq[key];
	this.lastSeq[key] = cmd.seq;
	let keys = Object.keys(this.lastSeq);
	if (keys.length > UM_SOURCES)
		delete this.lastSeq[keys[0]];
	return { "ok": true, "gap": last > 0 && cmd.seq > last + 1 };
};

UmCrossover.prototype.Handle = function(player, cmd)
{
	let verdict = this.Admit(player, cmd);
	let result = verdict.ok ? this.Apply(player, cmd) : verdict;
	let ack = {
		"timeMs": this.TimeMs(),
		"player": player,
		"type": cmd.type,
		"src": cmd.src,
		"seq": cmd.seq
	};
	for (let key in result)
		ack[key] = result[key];
	if (verdict.gap)
		ack.gap = true;
	this.acks.push(ack);
	if (this.acks.length > UM_ACKS)
		this.acks.shift();
	print("UMX " + JSON.stringify(ack) + "\n");
	if (result.text)
		Engine.QueryInterface(SYSTEM_ENTITY, IID_GuiInterface).PushNotification({
			"type": "um-feedback",
			"players": [player],
			"ok": result.ok,
			"code": result.code,
			"text": result.text
		});
	return ack;
};

UmCrossover.prototype.Apply = function(player, cmd)
{
	switch (cmd.type)
	{
	case "um-spawn": return this.Spawn(player, cmd);
	case "um-possess": return this.Possess(player, cmd.entity);
	case "um-face": return this.Face(player, cmd);
	case "um-move": return this.Move(player, cmd);
	case "um-place": return this.Place(player, cmd);
	case "um-break": return this.Break(player, cmd);
	case "um-squad": return this.Squad(player, cmd);
	}
	return this.Fail("unknown-type", "Unknown command " + cmd.type);
};

UmCrossover.prototype.Me = function(player)
{
	if (!this.players[player])
		this.players[player] = clone(UM_NOBODY);
	return this.players[player];
};

UmCrossover.prototype.Owned = function(ent, player)
{
	let cmpOwnership = Engine.QueryInterface(ent, IID_Ownership);
	return !!cmpOwnership && cmpOwnership.GetOwner() == player;
};

UmCrossover.prototype.Where = function(ent)
{
	let cmpPosition = Engine.QueryInterface(ent, IID_Position);
	if (!cmpPosition || !cmpPosition.IsInWorld())
		return null;
	let pos = cmpPosition.GetPosition2D();
	return { "x": pos.x, "z": pos.y, "angle": cmpPosition.GetRotation().y };
};

/** The hero, or null when there is none or it died. Read-only: safe to call from Observe. */
UmCrossover.prototype.Avatar = function(player)
{
	let me = this.players[player];
	if (!me || !me.avatar || !this.Owned(me.avatar, player))
		return null;
	let at = this.Where(me.avatar);
	return at && { "id": me.avatar, "x": at.x, "z": at.z, "angle": at.angle };
};

UmCrossover.prototype.HasClass = function(ent, cls)
{
	let cmpIdentity = Engine.QueryInterface(ent, IID_Identity);
	return !!cmpIdentity && cmpIdentity.HasClass(cls);
};

UmCrossover.prototype.TemplateOf = function(ent)
{
	return Engine.QueryInterface(SYSTEM_ENTITY, IID_TemplateManager).GetCurrentTemplateName(ent) || "";
};

UmCrossover.prototype.IsBlock = function(ent)
{
	return this.TemplateOf(ent).startsWith("um/block_");
};

/** Where a new hero appears: beside the player's soldiers, else beside the civic centre. */
UmCrossover.prototype.HomeSpot = function(player)
{
	let cmpRangeManager = Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager);
	let soldiers = [];
	let centre = null;
	for (let ent of cmpRangeManager.GetEntitiesByPlayer(player))
	{
		let at = this.Where(ent);
		if (!at)
			continue;
		if (this.HasClass(ent, "Soldier") && Engine.QueryInterface(ent, IID_UnitAI))
			soldiers.push(at);
		else if (!centre && this.HasClass(ent, "CivCentre"))
			centre = at;
	}
	if (soldiers.length)
		return { "x": soldiers[0].x + 3, "z": soldiers[0].z + 3, "angle": soldiers[0].angle };
	return centre && { "x": centre.x + 20, "z": centre.z + 20, "angle": 0 };
};

UmCrossover.prototype.Spawn = function(player, cmd)
{
	if (this.Avatar(player))
		return this.Fail("already", "You already have a hero");
	let at = Number.isFinite(cmd.x) && Number.isFinite(cmd.z) ?
		{ "x": cmd.x, "z": cmd.z, "angle": Number.isFinite(cmd.angle) ? cmd.angle : 0 } :
		this.HomeSpot(player);
	if (!at)
		return this.Fail("no-home", "There is nowhere to put your hero");
	let ent = Engine.AddEntity("um/player");
	Engine.QueryInterface(ent, IID_Ownership).SetOwner(player);
	let cmpPosition = Engine.QueryInterface(ent, IID_Position);
	cmpPosition.JumpTo(at.x, at.z);
	cmpPosition.SetYRotation(at.angle);
	this.Me(player).avatar = ent;
	this.StartHunger();
	let squad = this.Recruit(player, UM_SQUAD_RADIUS);
	if (squad.length)
		ProcessCommand(player, { "type": "guard", "entities": squad, "target": ent, "queued": false });
	return this.Ok("spawned", "You are the hero. " + squad.length + " soldiers follow you", { "entity": ent, "squad": squad.length });
};

UmCrossover.prototype.Possess = function(player, ent)
{
	if (!Number.isInteger(ent) || !this.Owned(ent, player) || !Engine.QueryInterface(ent, IID_UnitAI))
		return this.Fail("not-yours", "You can only take over one of your own units");
	let me = this.Me(player);
	me.avatar = ent;
	me.squad = me.squad.filter(e => e != ent);
	this.StartHunger();
	return this.Ok("possessed", "You took over unit " + ent, { "entity": ent });
};

UmCrossover.prototype.Face = function(player, cmd)
{
	let hero = this.Avatar(player);
	if (!hero)
		return this.Fail("no-hero", "You have no hero");
	if (!Number.isFinite(cmd.angle))
		return this.Fail("bad-args", "angle must be a number of radians");
	Engine.QueryInterface(hero.id, IID_Position).TurnTo(cmd.angle);
	return this.Ok("faced", "", { "angle": cmd.angle });
};

UmCrossover.prototype.Move = function(player, cmd)
{
	let hero = this.Avatar(player);
	if (!hero)
		return this.Fail("no-hero", "You have no hero");
	if (!Number.isFinite(cmd.x) || !Number.isFinite(cmd.z))
		return this.Fail("bad-args", "x and z must be numbers of metres");
	if (cmd.stop)
		ProcessCommand(player, { "type": "stop", "entities": [hero.id], "queued": false });
	else
		ProcessCommand(player, { "type": "walk", "entities": [hero.id], "x": cmd.x, "z": cmd.z, "queued": false });
	return this.Ok(cmd.stop ? "stopped" : "moving", "", { "x": cmd.x, "z": cmd.z });
};

/** Units of any player whose centre is inside the 4 m cell, looked up around the hero. */
UmCrossover.prototype.UnitsInCell = function(hero, cell)
{
	let players = Engine.QueryInterface(SYSTEM_ENTITY, IID_PlayerManager).GetAllPlayers();
	let near = Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).ExecuteQuery(
		hero.id, 0, UM_REACH + UM_CELL, players, IID_UnitMotion, false);
	near.push(hero.id);
	let half = UM_CELL / 2 + 0.8;
	return near.filter(ent => {
		let at = this.Where(ent);
		return at && Math.abs(at.x - cell.x) < half && Math.abs(at.z - cell.z) < half;
	});
};

UmCrossover.prototype.BlockNear = function(hero, x, z, within)
{
	let near = Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).ExecuteQuery(
		hero.id, 0, UM_REACH + UM_CELL * 2, [0], IID_Obstruction, false);
	let best = 0;
	let bestDist = within;
	for (let ent of near)
	{
		if (!this.IsBlock(ent))
			continue;
		let at = this.Where(ent);
		let dist = at ? Math.hypot(at.x - x, at.z - z) : Infinity;
		if (dist < bestDist)
		{
			best = ent;
			bestDist = dist;
		}
	}
	return best;
};

UmCrossover.prototype.Place = function(player, cmd)
{
	let hero = this.Avatar(player);
	if (!hero)
		return this.Fail("no-hero", "You have no hero");
	if (!Number.isFinite(cmd.x) || !Number.isFinite(cmd.z))
		return this.Fail("bad-args", "x and z must be numbers of metres");
	let block = cmd.block || "granite";
	if (UM_BLOCKS.indexOf(block) == -1)
		return this.Fail("bad-args", "block must be one of " + UM_BLOCKS.join(", "));
	let cell = {
		"x": (Math.floor(cmd.x / UM_CELL) + 0.5) * UM_CELL,
		"z": (Math.floor(cmd.z / UM_CELL) + 0.5) * UM_CELL
	};
	let reach = Math.hypot(cell.x - hero.x, cell.z - hero.z);
	if (reach > UM_REACH)
		return this.Fail("too-far", "Too far: " + reach.toFixed(1) + " m (reach " + UM_REACH + " m)");
	if (this.UnitsInCell(hero, cell).length)
		return this.Fail("occupied", "Someone is standing there");
	if (this.BlockNear(hero, cell.x, cell.z, 1))
		return this.Fail("occupied", "There is a block there already");
	let cmpPlayer = QueryPlayerIDInterface(player);
	if (!cmpPlayer || !cmpPlayer.TrySubtractResources(UM_BLOCK_COST))
		return this.Fail("no-stone", "You need 10 stone for a block");
	let ent = Engine.AddEntity("um/block_" + block);
	Engine.QueryInterface(ent, IID_Ownership).SetOwner(0);
	let cmpPosition = Engine.QueryInterface(ent, IID_Position);
	cmpPosition.JumpTo(cell.x, cell.z);
	cmpPosition.SetYRotation(0);
	let cmpObstruction = Engine.QueryInterface(ent, IID_Obstruction);
	let check = cmpObstruction ? cmpObstruction.CheckFoundation("default") : "success";
	if (check != "success")
	{
		Engine.DestroyEntity(ent);
		cmpPlayer.AddResources(UM_BLOCK_COST);
		return this.Fail("blocked", "Something solid is in the way (" + check + ")");
	}
	let me = this.Me(player);
	++me.placed;
	me.xp += 1;
	return this.Ok("placed", "Placed a " + block + " block (-10 stone)", { "entity": ent, "x": cell.x, "z": cell.z });
};

UmCrossover.prototype.Break = function(player, cmd)
{
	let hero = this.Avatar(player);
	if (!hero)
		return this.Fail("no-hero", "You have no hero");
	let ent = Number.isInteger(cmd.entity) && cmd.entity > 0 ? cmd.entity :
		Number.isFinite(cmd.x) && Number.isFinite(cmd.z) ? this.BlockNear(hero, cmd.x, cmd.z, UM_CELL * 0.75) : 0;
	if (!ent || !this.IsBlock(ent))
		return this.Fail("no-block", "There is no block there");
	let at = this.Where(ent);
	let reach = at ? Math.hypot(at.x - hero.x, at.z - hero.z) : Infinity;
	if (reach > UM_REACH)
		return this.Fail("too-far", "Too far: " + reach.toFixed(1) + " m (reach " + UM_REACH + " m)");
	Engine.DestroyEntity(ent);
	QueryPlayerIDInterface(player).AddResources(UM_BLOCK_COST);
	let me = this.Me(player);
	++me.broken;
	me.xp += 2;
	return this.Ok("broken", "Broke the block (+10 stone)", { "entity": ent, "x": at.x, "z": at.z });
};

UmCrossover.prototype.Recruit = function(player, radius)
{
	let hero = this.Avatar(player);
	let me = this.Me(player);
	if (!hero)
		return me.squad;
	let near = Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).ExecuteQuery(
		hero.id, 0, radius, [player], IID_UnitAI, false);
	me.squad = near.filter(ent => ent != hero.id && this.HasClass(ent, "Soldier") && !this.HasClass(ent, "Hero"))
		.map(ent => [ent, this.Where(ent)])
		.filter(pair => pair[1])
		.sort((a, b) => Math.hypot(a[1].x - hero.x, a[1].z - hero.z) - Math.hypot(b[1].x - hero.x, b[1].z - hero.z))
		.slice(0, UM_SQUAD_MAX)
		.map(pair => pair[0]);
	return me.squad;
};

UmCrossover.prototype.NearestEnemy = function(player, hero)
{
	let enemies = QueryPlayerIDInterface(player).GetEnemies().filter(p => p != 0);
	let near = Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).ExecuteQuery(
		hero.id, 0, UM_SQUAD_RADIUS, enemies, IID_Health, false);
	let best = 0;
	let bestScore = Infinity;
	for (let ent of near)
	{
		let at = this.Where(ent);
		if (!at)
			continue;
		// Prefer soldiers over buildings: a building counts as 100 m further away.
		let score = Math.hypot(at.x - hero.x, at.z - hero.z) + (Engine.QueryInterface(ent, IID_UnitAI) ? 0 : 100);
		if (score < bestScore)
		{
			best = ent;
			bestScore = score;
		}
	}
	return best;
};

UmCrossover.prototype.Squad = function(player, cmd)
{
	let hero = this.Avatar(player);
	if (!hero)
		return this.Fail("no-hero", "You have no hero");
	let me = this.Me(player);
	if (cmd.op == "recruit")
	{
		let radius = Number.isFinite(cmd.radius) ? Math.min(Math.max(cmd.radius, 4), 80) : UM_SQUAD_RADIUS;
		let squad = this.Recruit(player, radius);
		return this.Ok("recruited", squad.length + " soldiers in your squad", { "squad": squad.length });
	}
	me.squad = me.squad.filter(ent => this.Owned(ent, player) && this.Where(ent));
	if (!me.squad.length)
		return this.Fail("no-squad", "Your squad is empty");
	let order = { "entities": me.squad, "queued": false };
	let send = extra => {
		let command = clone(order);
		for (let key in extra)
			command[key] = extra[key];
		ProcessCommand(player, command);
	};
	switch (cmd.op)
	{
	case "follow":
		send({ "type": "guard", "target": hero.id });
		return this.Ok("following", "Squad: follow me", { "squad": me.squad.length });
	case "rally":
		if (!Number.isFinite(cmd.x) || !Number.isFinite(cmd.z))
			return this.Fail("bad-args", "rally needs x and z");
		send({ "type": "walk", "x": cmd.x, "z": cmd.z, "formation": "special/formations/" + me.formation });
		return this.Ok("rallying", "Squad: to the banner", { "x": cmd.x, "z": cmd.z });
	case "attack":
	{
		let target = Number.isInteger(cmd.target) && cmd.target > 0 ? cmd.target : this.NearestEnemy(player, hero);
		if (!target || !Engine.QueryInterface(target, IID_Health))
			return this.Fail("no-target", "No enemy within " + UM_SQUAD_RADIUS + " m");
		send({ "type": "attack", "target": target, "allowCapture": false, "entities": me.squad.concat([hero.id]) });
		return this.Ok("attacking", "Squad: attack!", { "target": target });
	}
	case "formation":
	{
		let formation = cmd.formation || "box";
		if (UM_FORMATIONS.indexOf(formation) == -1)
			return this.Fail("bad-args", "formation must be one of " + UM_FORMATIONS.join(", "));
		send({ "type": "formation", "formation": "special/formations/" + formation });
		me.formation = formation;
		return this.Ok("formation", "Squad formation: " + formation.replace("_", " "), { "formation": formation });
	}
	case "hold":
		send({ "type": "stop" });
		send({ "type": "stance", "name": "standground" });
		return this.Ok("holding", "Squad: hold this ground");
	}
	return this.Fail("bad-args", "op must be recruit, follow, rally, attack, formation or hold");
};

UmCrossover.prototype.StartHunger = function()
{
	if (this.hungerTimer)
		return;
	this.hungerTimer = Engine.QueryInterface(SYSTEM_ENTITY, IID_Timer).SetInterval(
		SYSTEM_ENTITY, IID_UmCrossover, "Hunger", UM_HUNGER_MS, UM_HUNGER_MS, null);
};

/** Timer callback: the hero eats from the stockpile; with no food left, it starves. */
UmCrossover.prototype.Hunger = function()
{
	for (let player in this.players)
	{
		let hero = this.Avatar(+player);
		if (!hero)
			continue;
		let cmpPlayer = QueryPlayerIDInterface(+player);
		if (cmpPlayer && cmpPlayer.GetResourceCounts().food >= 1)
			cmpPlayer.TrySubtractResources({ "food": 1 });
		else
		{
			let cmpHealth = Engine.QueryInterface(hero.id, IID_Health);
			if (cmpHealth)
				cmpHealth.Reduce(UM_STARVE_HP);
		}
	}
};

/** Everything a HUD, script or agent needs, without changing anything. */
UmCrossover.prototype.Observe = function(player)
{
	let me = this.players[player] || UM_NOBODY;
	let hero = this.Avatar(player);
	let cmpHealth = hero && Engine.QueryInterface(hero.id, IID_Health);
	let cmpPlayer = QueryPlayerIDInterface(player);
	let res = cmpPlayer ? cmpPlayer.GetResourceCounts() : {};
	let hp = cmpHealth ? cmpHealth.GetHitpoints() : 0;
	let maxHp = cmpHealth ? cmpHealth.GetMaxHitpoints() : 0;
	let squad = [];
	for (let ent of me.squad)
	{
		let at = this.Owned(ent, player) && this.Where(ent);
		if (!at)
			continue;
		let cmpUnitHealth = Engine.QueryInterface(ent, IID_Health);
		let cmpUnitAI = Engine.QueryInterface(ent, IID_UnitAI);
		squad.push({
			"id": ent,
			"x": at.x,
			"z": at.z,
			"hp": cmpUnitHealth ? cmpUnitHealth.GetHitpoints() : 0,
			"inFormation": !!cmpUnitAI && cmpUnitAI.GetFormationController() != INVALID_ENTITY
		});
	}
	let lastSeq = {};
	for (let key in this.lastSeq)
		if (key.endsWith(":" + player))
			lastSeq[key.slice(0, key.lastIndexOf(":"))] = this.lastSeq[key];
	return {
		"v": 1,
		"session": this.session,
		"epoch": this.epoch,
		"timeMs": this.TimeMs(),
		"player": player,
		"avatar": hero && {
			"id": hero.id,
			"x": hero.x,
			"z": hero.z,
			"angle": hero.angle,
			"hp": hp,
			"maxHp": maxHp
		},
		"squad": squad,
		"formation": me.formation,
		"resources": { "food": res.food || 0, "wood": res.wood || 0, "stone": res.stone || 0, "metal": res.metal || 0 },
		"hud": {
			"halfHearts": maxHp ? Math.ceil(hp / maxHp * 20) : 0,
			"halfFood": Math.min(20, Math.floor((res.food || 0) / 10)),
			"xp": me.xp,
			"level": Math.floor(me.xp / 10),
			"xpInLevel": me.xp % 10,
			"blocksInHand": Math.floor((res.stone || 0) / UM_BLOCK_COST.stone)
		},
		"counters": { "placed": me.placed, "broken": me.broken },
		"lastSeq": lastSeq,
		"acks": this.acks.filter(ack => ack.player == player).slice(-8)
	};
};

Engine.RegisterSystemComponentType(IID_UmCrossover, "UmCrossover", UmCrossover);

// Let the GUI read the crossover state through the stock GuiInterface (read-only). GuiInterface.js
// keeps its list of callable names in a script-local variable, so wrap its dispatcher instead.
var umGuiScriptCall = GuiInterface.prototype.ScriptCall;
GuiInterface.prototype.ScriptCall = function(player, name, args)
{
	if (name == "UmObserve")
		return Engine.QueryInterface(SYSTEM_ENTITY, IID_UmCrossover).Observe(player);
	return umGuiScriptCall.call(this, player, name, args);
};
