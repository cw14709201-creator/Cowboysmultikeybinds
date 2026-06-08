package com.cowboy.multikeybinds.client;

import net.minecraft.client.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A standalone options screen. Each row = one control: its name, its primary
 * key, and a button to assign a SECOND key. Click the button, then press any
 * key or mouse button; press it again with the small reset and it clears.
 *
 * Key capture is done by polling GLFW directly in {@link #tick()} instead of
 * overriding keyPressed/mouseClicked. Recent Minecraft versions changed those
 * method signatures to event objects, and polling is immune to that churn.
 */
public class MultiBindScreen extends Screen {

	// A curated set of valid GLFW key codes to poll while capturing.
	private static final int[] CANDIDATE_KEYS = buildCandidateKeys();

	private final Screen parent;
	private final List<KeyMapping> all = new ArrayList<>();

	private int page = 0;
	private int rowsPerPage = 8;

	private KeyMapping capturing = null;
	private Set<Integer> prevKeyDown = new HashSet<>();
	private Set<Integer> prevMouseDown = new HashSet<>();

	public MultiBindScreen(Screen parent) {
		super(Component.translatable("screen.cowboymkb.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		all.clear();
		if (this.minecraft != null) {
			for (KeyMapping km : this.minecraft.options.keyMappings) {
				all.add(km);
			}
		}

		int top = 48;
		int rowH = 24;
		int bottomReserve = 44;
		rowsPerPage = Math.max(1, (this.height - top - bottomReserve) / rowH);

		int totalPages = Math.max(1, (all.size() + rowsPerPage - 1) / rowsPerPage);
		if (page >= totalPages) {
			page = totalPages - 1;
		}

		int rightEdge = this.width - 20;
		int clearW = 22;
		int rebindW = 130;
		int gap = 4;
		int btnH = 20;

		int start = page * rowsPerPage;
		int end = Math.min(all.size(), start + rowsPerPage);

		for (int i = start; i < end; i++) {
			final KeyMapping km = all.get(i);
			int y = top + (i - start) * rowH;

			int clearX = rightEdge - clearW;
			int rebindX = clearX - gap - rebindW;

			Component label;
			if (capturing == km) {
				label = Component.literal("> … <");
			} else {
				List<InputConstants.Key> extra = MultiBindManager.getExtra(km);
				label = extra.isEmpty()
						? Component.translatable("button.cowboymkb.unset")
						: extra.get(0).getDisplayName();
			}

			addRenderableWidget(Button.builder(label, b -> beginCapture(km))
					.bounds(rebindX, y, rebindW, btnH)
					.build());

			Button clear = Button.builder(Component.literal("\u2715"), b -> {
				MultiBindManager.setExtra(km, null);
				rebuildWidgets();
			}).bounds(clearX, y, clearW, btnH).build();
			clear.active = !MultiBindManager.getExtra(km).isEmpty() && capturing != km;
			addRenderableWidget(clear);
		}

		// Footer: Prev / Done / Next
		int footY = this.height - 30;
		addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.prev"), b -> {
			if (page > 0) {
				page--;
				capturing = null;
				rebuildWidgets();
			}
		}).bounds(this.width / 2 - 160, footY, 100, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.done"), b -> onClose())
				.bounds(this.width / 2 - 50, footY, 100, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.next"), b -> {
			if (page < totalPages - 1) {
				page++;
				capturing = null;
				rebuildWidgets();
			}
		}).bounds(this.width / 2 + 60, footY, 100, 20).build());
	}

	private void beginCapture(KeyMapping km) {
		capturing = km;
		snapshotHeld();
		rebuildWidgets();
	}

	private void snapshotHeld() {
		prevKeyDown = new HashSet<>();
		prevMouseDown = new HashSet<>();
		if (this.minecraft == null) {
			return;
		}
		long h = this.minecraft.getWindow().getWindow();
		for (int kc : CANDIDATE_KEYS) {
			if (GLFW.glfwGetKey(h, kc) == GLFW.GLFW_PRESS) {
				prevKeyDown.add(kc);
			}
		}
		for (int mb = 1; mb <= 7; mb++) {
			if (GLFW.glfwGetMouseButton(h, mb) == GLFW.GLFW_PRESS) {
				prevMouseDown.add(mb);
			}
		}
	}

	@Override
	public void tick() {
		if (capturing == null || this.minecraft == null) {
			return;
		}
		long h = this.minecraft.getWindow().getWindow();

		// Esc cancels capture without binding.
		if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
			capturing = null;
			rebuildWidgets();
			return;
		}

		Set<Integer> nowKeys = new HashSet<>();
		for (int kc : CANDIDATE_KEYS) {
			if (GLFW.glfwGetKey(h, kc) == GLFW.GLFW_PRESS) {
				nowKeys.add(kc);
				if (!prevKeyDown.contains(kc)) {
					finishCapture(InputConstants.Type.KEYSYM.getOrCreate(kc));
					return;
				}
			}
		}

		Set<Integer> nowMouse = new HashSet<>();
		// Start at button 1 (right) so the left-click that drives the UI isn't captured.
		for (int mb = 1; mb <= 7; mb++) {
			if (GLFW.glfwGetMouseButton(h, mb) == GLFW.GLFW_PRESS) {
				nowMouse.add(mb);
				if (!prevMouseDown.contains(mb)) {
					finishCapture(InputConstants.Type.MOUSE.getOrCreate(mb));
					return;
				}
			}
		}

		prevKeyDown = nowKeys;
		prevMouseDown = nowMouse;
	}

	private void finishCapture(InputConstants.Key key) {
		MultiBindManager.setExtra(capturing, key);
		capturing = null;
		rebuildWidgets();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(g, mouseX, mouseY, partialTick);
		super.render(g, mouseX, mouseY, partialTick);

		g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		g.drawCenteredString(this.font, Component.translatable("screen.cowboymkb.subtitle"),
				this.width / 2, 26, 0xA0A0A0);

		int top = 48;
		int rowH = 24;
		int start = page * rowsPerPage;
		int end = Math.min(all.size(), start + rowsPerPage);

		for (int i = start; i < end; i++) {
			KeyMapping km = all.get(i);
			int y = top + (i - start) * rowH + 6;
			Component name = Component.translatable(km.getName());
			Component primary = km.getTranslatedKeyMessage();
			g.drawString(this.font, name, 20, y, 0xFFFFFF);
			g.drawString(this.font, Component.literal("[").append(primary).append("]"),
					20 + this.font.width(name) + 8, y, 0x7FBFFF);
		}

		if (capturing != null) {
			g.drawCenteredString(this.font, Component.translatable("screen.cowboymkb.press"),
					this.width / 2, this.height - 44, 0xFFE060);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	private static int[] buildCandidateKeys() {
		List<Integer> k = new ArrayList<>();
		// printable-ish keys
		k.add(GLFW.GLFW_KEY_SPACE);
		k.add(GLFW.GLFW_KEY_APOSTROPHE);
		k.add(GLFW.GLFW_KEY_COMMA);
		k.add(GLFW.GLFW_KEY_MINUS);
		k.add(GLFW.GLFW_KEY_PERIOD);
		k.add(GLFW.GLFW_KEY_SLASH);
		for (int c = GLFW.GLFW_KEY_0; c <= GLFW.GLFW_KEY_9; c++) k.add(c);
		k.add(GLFW.GLFW_KEY_SEMICOLON);
		k.add(GLFW.GLFW_KEY_EQUAL);
		for (int c = GLFW.GLFW_KEY_A; c <= GLFW.GLFW_KEY_Z; c++) k.add(c);
		k.add(GLFW.GLFW_KEY_LEFT_BRACKET);
		k.add(GLFW.GLFW_KEY_BACKSLASH);
		k.add(GLFW.GLFW_KEY_RIGHT_BRACKET);
		k.add(GLFW.GLFW_KEY_GRAVE_ACCENT);
		// control / navigation
		k.add(GLFW.GLFW_KEY_ENTER);
		k.add(GLFW.GLFW_KEY_TAB);
		k.add(GLFW.GLFW_KEY_BACKSPACE);
		k.add(GLFW.GLFW_KEY_INSERT);
		k.add(GLFW.GLFW_KEY_DELETE);
		k.add(GLFW.GLFW_KEY_RIGHT);
		k.add(GLFW.GLFW_KEY_LEFT);
		k.add(GLFW.GLFW_KEY_DOWN);
		k.add(GLFW.GLFW_KEY_UP);
		k.add(GLFW.GLFW_KEY_PAGE_UP);
		k.add(GLFW.GLFW_KEY_PAGE_DOWN);
		k.add(GLFW.GLFW_KEY_HOME);
		k.add(GLFW.GLFW_KEY_END);
		k.add(GLFW.GLFW_KEY_CAPS_LOCK);
		for (int c = GLFW.GLFW_KEY_F1; c <= GLFW.GLFW_KEY_F25; c++) k.add(c);
		// keypad
		for (int c = GLFW.GLFW_KEY_KP_0; c <= GLFW.GLFW_KEY_KP_EQUAL; c++) k.add(c);
		// modifiers + menu
		for (int c = GLFW.GLFW_KEY_LEFT_SHIFT; c <= GLFW.GLFW_KEY_MENU; c++) k.add(c);

		int[] out = new int[k.size()];
		for (int i = 0; i < out.length; i++) out[i] = k.get(i);
		return out;
	}
}
