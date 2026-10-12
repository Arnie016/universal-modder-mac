// Universal Modder crossover commands. Each "um-*" command is plain JSON with an envelope
// (src, seq, session, epoch); the GUI, a script over the RL interface and the MCP bridge all
// send the same objects. UmCrossover (simulation/components/UmCrossover.js) checks and applies them.
for (let type of ["um-spawn", "um-possess", "um-face", "um-move", "um-place", "um-break", "um-squad"])
	g_Commands[type] = function(player, cmd, data)
	{
		Engine.QueryInterface(SYSTEM_ENTITY, IID_UmCrossover).Handle(player, cmd);
	};
