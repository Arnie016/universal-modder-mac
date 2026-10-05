package dev.arnav.macarcade;

import com.mojang.blaze3d.platform.InputConstants;

/** Minecraft key code -> the key names umhelper understands (see keyNames in um/swift/umhelper.swift). */
final class Keys {
	private Keys() { }

	static String name(int k) {
		if (k >= InputConstants.KEY_A && k <= InputConstants.KEY_Z) return String.valueOf((char) ('a' + k - InputConstants.KEY_A));
		if (k >= InputConstants.KEY_0 && k <= InputConstants.KEY_9) return String.valueOf((char) ('0' + k - InputConstants.KEY_0));
		if (k >= InputConstants.KEY_F1 && k <= InputConstants.KEY_F12) return "f" + (k - InputConstants.KEY_F1 + 1);
		return switch (k) {
			case InputConstants.KEY_SPACE -> "space";
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "return";
			case InputConstants.KEY_ESCAPE -> "escape";
			case InputConstants.KEY_TAB -> "tab";
			case InputConstants.KEY_BACKSPACE -> "backspace";
			case InputConstants.KEY_LEFT -> "left";
			case InputConstants.KEY_RIGHT -> "right";
			case InputConstants.KEY_UP -> "up";
			case InputConstants.KEY_DOWN -> "down";
			case InputConstants.KEY_LSHIFT -> "shift";
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> "ctrl";
			case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> "alt";
			case InputConstants.KEY_MINUS -> "-";
			case InputConstants.KEY_EQUALS -> "=";
			case InputConstants.KEY_COMMA -> ",";
			case InputConstants.KEY_PERIOD -> ".";
			case InputConstants.KEY_SLASH -> "/";
			default -> null;
		};
	}
}
