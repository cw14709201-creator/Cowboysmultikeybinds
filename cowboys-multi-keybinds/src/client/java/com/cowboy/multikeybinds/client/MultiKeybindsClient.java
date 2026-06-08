package com.cowboy.multikeybinds.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiKeybindsClient implements ClientModInitializer {

	public static final String MOD_ID = "cowboymkb";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private KeyMapping openMenuKey;

	@Override
	public void onInitializeClient() {
		// Keybind categories are now Category objects (1.21.9+), not strings.
		KeyMapping.Category category = KeyMapping.Category.register(
				ResourceLocation.fromNamespaceAndPath(MOD_ID, "main"));

		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.cowboymkb.open_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_BRACKET,
				category
		));

		// Load saved assignments, then bind them to the real KeyMapping objects
		// once the game (and its options) are fully up.
		MultiBindManager.load();
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				MultiBindManager.resolve(client.options));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.consumeClick()) {
				openMenu(client);
			}
		});

		LOGGER.info("Cowboy's Multi Keybinds ready — press ] (or rebind it) to open the menu.");
	}

	private void openMenu(Minecraft client) {
		// Keep current bindings fresh in case anything changed since startup.
		MultiBindManager.resolve(client.options);
		client.setScreen(new MultiBindScreen(client.screen));
	}
}
