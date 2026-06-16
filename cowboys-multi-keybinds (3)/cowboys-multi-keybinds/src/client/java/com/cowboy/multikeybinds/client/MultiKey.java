package com.cowboy.multikeybinds.client;

/**
 * Implemented (via mixin) on every {@code KeyMapping}. Lets the engine flip a
 * binding's internal "held" flag and bump its "click count" when one of its
 * EXTRA keys fires, exactly the way vanilla does for the primary key.
 */
public interface MultiKey {
	void mkb$setHeld(boolean held);

	void mkb$press();
}
