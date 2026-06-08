package com.cowboy.multikeybinds.mixin;

import com.cowboy.multikeybinds.client.MultiBindManager;
import com.cowboy.multikeybinds.client.MultiKey;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the two static methods the game calls for EVERY physical key/mouse
 * event while in-game:
 *
 *   KeyMapping.set(key, held)  -> updates the "is this binding held" flag
 *   KeyMapping.click(key)      -> bumps the "was this binding tapped" counter
 *
 * Vanilla only updates the ONE binding whose primary key matches. We add a
 * HEAD hook that also updates any binding that has this key registered as an
 * EXTRA key, so a single action can be triggered by two different keys.
 *
 * NOTE: this mixin shadows the private fields {@code isDown} and
 * {@code clickCount}. Those are the Mojang-mapped names on Minecraft 26.1.x.
 * If you retarget a much older/newer version and the build complains about a
 * missing field, check the current field names for KeyMapping and update the
 * two @Shadow lines below.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin implements MultiKey {

	@Shadow
	private boolean isDown;

	@Shadow
	private int clickCount;

	@Override
	public void mkb$setHeld(boolean held) {
		this.isDown = held;
	}

	@Override
	public void mkb$press() {
		this.clickCount++;
	}

	@Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V", at = @At("HEAD"))
	private static void mkb$onSet(InputConstants.Key key, boolean held, CallbackInfo ci) {
		for (KeyMapping km : MultiBindManager.bindingsForExtra(key)) {
			((MultiKey) (Object) km).mkb$setHeld(held);
		}
	}

	@Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V", at = @At("HEAD"))
	private static void mkb$onClick(InputConstants.Key key, CallbackInfo ci) {
		for (KeyMapping km : MultiBindManager.bindingsForExtra(key)) {
			((MultiKey) (Object) km).mkb$press();
		}
	}
}
