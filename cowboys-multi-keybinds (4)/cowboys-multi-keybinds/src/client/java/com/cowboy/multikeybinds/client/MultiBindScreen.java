package com.cowboy.multikeybinds.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Multi-Keybinds menu. Features:
 *  - Search bar to filter controls by name
 *  - Conflict detection: highlights extra keys that clash with an existing primary bind
 *  - Category grouping: controls are sorted and separated by their vanilla category
 *  - Reset All button: clears every extra bind at once
 *  - Pagination for large lists
 */
public class MultiBindScreen extends Screen {

    private static final int[] CANDIDATE_KEYS = buildCandidateKeys();

    private final Screen parent;

    // All controls (flat, filtered by search)
    private final List<KeyMapping> all = new ArrayList<>();
    // Displayed page slice (may include null = category separator)
    private final List<KeyMapping> pageRows = new ArrayList<>();

    private int page = 0;
    private int rowsPerPage = 8;
    private int totalPages = 1;

    private KeyMapping capturing = null;
    private int captureDelay = 0; // ticks to ignore after entering capture
    private boolean confirmingReset = false; // true after first click on Reset All
    private Set<Integer> prevKeyDown = new HashSet<>();
    private Set<Integer> prevMouseDown = new HashSet<>();

    private String searchText = "";
    private EditBox searchBox;

    // Quick lookup: primary key name -> KeyMapping, for conflict detection
    private final Map<String, KeyMapping> primaryKeyIndex = new HashMap<>();

    public MultiBindScreen(Screen parent) {
        super(Component.translatable("screen.cowboymkb.title"));
        this.parent = parent;
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        buildAllList();
        buildPrimaryKeyIndex();
        buildPageRows();

        int footY = this.height - 30;
        int searchY = 54;
        int searchW = Math.min(300, this.width - 40);
        int searchX = this.width / 2 - searchW / 2;

        // Instruction subtitle drawn in render() — nothing to init here

        // Search box
        searchBox = new EditBox(this.font, searchX, searchY, searchW, 16,
                Component.translatable("screen.cowboymkb.search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchText);
        searchBox.setHint(Component.translatable("screen.cowboymkb.search"));
        searchBox.setResponder(text -> {
            searchText = text;
            page = 0;
            capturing = null;
            confirmingReset = false;
            buildAllList();
            buildPageRows();
            rebuildWidgets();
        });
        addRenderableWidget(searchBox);

        addRowWidgets();

        // Footer buttons
        addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.prev"), b -> {
            if (page > 0) { page--; capturing = null; confirmingReset = false; buildPageRows(); rebuildWidgets(); }
        }).bounds(this.width / 2 - 190, footY, 80, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.cowboymkb.prev")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.done"), b -> onClose())
                .bounds(this.width / 2 - 40, footY, 80, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.cowboymkb.done")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("button.cowboymkb.next"), b -> {
            if (page < totalPages - 1) { page++; capturing = null; confirmingReset = false; buildPageRows(); rebuildWidgets(); }
        }).bounds(this.width / 2 + 110, footY, 80, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.cowboymkb.next")))
                .build());

        // Reset All button — requires a confirm click (see confirmingReset)
        addRenderableWidget(Button.builder(
                confirmingReset
                        ? Component.translatable("button.cowboymkb.reset_confirm")
                        : Component.translatable("button.cowboymkb.reset_all"),
                b -> {
                    if (confirmingReset) {
                        MultiBindManager.clearAll();
                        confirmingReset = false;
                        capturing = null;
                        buildPageRows();
                        rebuildWidgets();
                    } else {
                        confirmingReset = true;
                        rebuildWidgets();
                    }
                })
                .bounds(this.width - 100, footY, 90, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        confirmingReset
                                ? "tooltip.cowboymkb.reset_confirm"
                                : "tooltip.cowboymkb.reset_all")))
                .build());
    }

    private void addRowWidgets() {
        int top = 76;
        int rowH = 24;
        int rightEdge = this.width - 20;
        int clearW = 22;
        int rebindW = 130;
        int gap = 4;
        int btnH = 20;

        for (int i = 0; i < pageRows.size(); i++) {
            KeyMapping km = pageRows.get(i);
            if (km == null) continue; // category header row — rendered in render(), no widget

            int y = top + i * rowH;
            int clearX = rightEdge - clearW;
            int rebindX = clearX - gap - rebindW;

            Component label;
            if (capturing == km) {
                label = Component.literal("> \u2026 <");
            } else {
                List<InputConstants.Key> extra = MultiBindManager.getExtra(km);
                if (extra.isEmpty()) {
                    label = Component.translatable("button.cowboymkb.unset");
                } else {
                    // Conflict detection: red tint if extra key matches any primary bind
                    InputConstants.Key extraKey = extra.get(0);
                    boolean conflict = isConflict(km, extraKey);
                    if (conflict) {
                        label = Component.literal("\u26a0 ").append(extraKey.getDisplayName());
                    } else {
                        label = extraKey.getDisplayName();
                    }
                }
            }

            final KeyMapping finalKm = km;
            addRenderableWidget(Button.builder(label, b -> beginCapture(finalKm))
                    .bounds(rebindX, y, rebindW, btnH)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.cowboymkb.rebind")))
                    .build());

            Button clear = Button.builder(Component.literal("\u2715"), b -> {
                MultiBindManager.setExtra(finalKm, null);
                buildPageRows();
                rebuildWidgets();
            }).bounds(clearX, y, clearW, btnH)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.cowboymkb.clear")))
                    .build();
            clear.active = !MultiBindManager.getExtra(km).isEmpty() && capturing != km;
            addRenderableWidget(clear);
        }
    }

    // -------------------------------------------------------------------------
    // Data helpers
    // -------------------------------------------------------------------------

    // Maps category id -> the first KeyMapping in that category, used to get display name
    private final Map<String, KeyMapping> catRepresentative = new LinkedHashMap<>();

    private void buildAllList() {
        all.clear();
        catRepresentative.clear();
        if (this.minecraft == null) return;
        String q = searchText.toLowerCase(Locale.ROOT).trim();

        // Group by category — use getCategory().id() as key but store a representative
        // KeyMapping per category so we can get the translated display name later
        Map<String, List<KeyMapping>> byCat = new LinkedHashMap<>();
        for (KeyMapping km : this.minecraft.options.keyMappings) {
            String name = Component.translatable(km.getName())
                    .getString().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.contains(q)) {
                // Use the category's translated name as sort/display key
                String catDisplay = km.getCategory().id().toString();
                byCat.computeIfAbsent(catDisplay, k -> new ArrayList<>()).add(km);
                catRepresentative.putIfAbsent(catDisplay, km);
            }
        }
        // Sort: "key.categories.movement" first, rest alphabetically by translated name
        List<String> cats = new ArrayList<>(byCat.keySet());
        cats.sort((a, b) -> {
            // movement category gets priority
            boolean am = catRepresentative.get(a) != null &&
                    catRepresentative.get(a).getCategory().id().toString().contains("movement");
            boolean bm = catRepresentative.get(b) != null &&
                    catRepresentative.get(b).getCategory().id().toString().contains("movement");
            if (am) return -1;
            if (bm) return 1;
            return Component.translatable(a).getString()
                    .compareTo(Component.translatable(b).getString());
        });
        for (String cat : cats) {
            // null = category separator sentinel
            all.add(null);
            all.addAll(byCat.get(cat));
        }
    }

    private void buildPrimaryKeyIndex() {
        primaryKeyIndex.clear();
        if (this.minecraft == null) return;
        for (KeyMapping km : this.minecraft.options.keyMappings) {
            InputConstants.Key k = InputConstants.getKey(km.saveString());
            if (k != null && k != InputConstants.UNKNOWN) {
                primaryKeyIndex.put(k.getName(), km);
            }
        }
    }

    private void buildPageRows() {
        // rows per page based on available vertical space (header ~76px, footer ~44px)
        int top = 76;
        int rowH = 24;
        int bottomReserve = 44;
        rowsPerPage = Math.max(1, (this.height - top - bottomReserve) / rowH);

        totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) rowsPerPage));
        if (page >= totalPages) page = totalPages - 1;

        int start = page * rowsPerPage;
        int end = Math.min(all.size(), start + rowsPerPage);

        pageRows.clear();
        for (int i = start; i < end; i++) {
            pageRows.add(all.get(i));
        }
    }

    /**
     * Returns true if {@code extraKey} is already used as a primary bind by
     * any control OTHER than {@code owner}.
     */
    private boolean isConflict(KeyMapping owner, InputConstants.Key extraKey) {
        if (extraKey == null || extraKey == InputConstants.UNKNOWN) return false;
        String name = extraKey.getName();
        KeyMapping existing = primaryKeyIndex.get(name);
        return existing != null && existing != owner;
    }

    // -------------------------------------------------------------------------
    // Capture logic
    // -------------------------------------------------------------------------

    private void beginCapture(KeyMapping km) {
        confirmingReset = false;
        capturing = km;
        captureDelay = 8; // ignore input for 8 ticks (~400ms) so the click that opened capture clears fully
        snapshotHeld();
        buildPageRows();
        rebuildWidgets();
    }

    private void snapshotHeld() {
        prevKeyDown = new HashSet<>();
        prevMouseDown = new HashSet<>();
        long h = GLFW.glfwGetCurrentContext();
        for (int kc : CANDIDATE_KEYS) {
            if (GLFW.glfwGetKey(h, kc) == GLFW.GLFW_PRESS) prevKeyDown.add(kc);
        }
        for (int mb = 0; mb <= 7; mb++) {
            if (GLFW.glfwGetMouseButton(h, mb) == GLFW.GLFW_PRESS) prevMouseDown.add(mb);
        }
    }

    @Override
    public void tick() {
        if (duplicateWarningTicks > 0) {
            duplicateWarningTicks--;
            if (duplicateWarningTicks == 0) lastDuplicateOwner = null;
        }
        if (capturing == null) return;
        if (captureDelay > 0) { captureDelay--; return; }
        long h = GLFW.glfwGetCurrentContext();

        // Esc cancels capture
        if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
            capturing = null;
            captureDelay = 0;
            buildPageRows();
            rebuildWidgets();
            return;
        }
        // Left-click cancels capture only on a FRESH press (not if held from before)
        boolean leftNow = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean leftWas = prevMouseDown.contains(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (leftNow && !leftWas) {
            capturing = null;
            captureDelay = 0;
            buildPageRows();
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

    // Set briefly after a capture that collided with another control's extra key.
    // Cleared on the next render after a short timer so the warning doesn't linger forever.
    private KeyMapping lastDuplicateOwner = null;
    private int duplicateWarningTicks = 0;

    private void finishCapture(InputConstants.Key key) {
        KeyMapping owner = MultiBindManager.findExtraKeyOwner(key, capturing);
        MultiBindManager.setExtra(capturing, key);
        capturing = null;
        if (owner != null) {
            // Same key was already someone else's extra key — both will now fire
            // together. Not blocked (could be intentional), just flagged.
            lastDuplicateOwner = owner;
            duplicateWarningTicks = 100; // ~5 seconds at 20 ticks/sec
        }
        buildPageRows();
        rebuildWidgets();
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Title
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        // Instruction subtitle
        g.drawCenteredString(this.font,
                Component.translatable("screen.cowboymkb.how_to"),
                this.width / 2, 26, 0xA0A0A0);

        // Page indicator
        String pageStr = (page + 1) + " / " + totalPages;
        g.drawCenteredString(this.font, Component.literal(pageStr), this.width / 2, 36, 0x707070);

        // Duplicate extra-key warning (shows briefly after a colliding assignment)
        if (lastDuplicateOwner != null && duplicateWarningTicks > 0) {
            Component dupName = Component.translatable(lastDuplicateOwner.getName());
            Component warning = Component.translatable("screen.cowboymkb.duplicate_warning", dupName);
            g.drawCenteredString(this.font, warning, this.width / 2, 46, 0xFF6A6A);
        }

        int top = 76;
        int rowH = 24;

        for (int i = 0; i < pageRows.size(); i++) {
            KeyMapping km = pageRows.get(i);
            int y = top + i * rowH;

            if (km == null) {
                // Find the next real KeyMapping on this page to get the category name
                Component catLabel = Component.literal("?");
                for (int j = i + 1; j < pageRows.size(); j++) {
                    KeyMapping rep = pageRows.get(j);
                    if (rep != null) {
                        catLabel = Component.translatable(
                                rep.getCategory().id().toString());
                        break;
                    }
                }
                g.drawString(this.font, catLabel, 20, y + 7, 0xD8973F);
                int lineW = this.font.width(catLabel);
                g.fill(20, y + 17, 20 + lineW, y + 18, 0xFFD8973F);
                continue;
            }

            Component name = Component.translatable(km.getName());
            Component primary = km.getTranslatedKeyMessage();
            g.drawString(this.font, name, 20, y + 6, 0xFFFFFF);
            g.drawString(this.font,
                    Component.literal("[").append(primary).append("]"),
                    20 + this.font.width(name) + 8, y + 6, 0x7FBFFF);

            // If extra key has a conflict, show warning text under the button
            List<InputConstants.Key> extra = MultiBindManager.getExtra(km);
            if (!extra.isEmpty() && isConflict(km, extra.get(0))) {
                KeyMapping conflict = primaryKeyIndex.get(extra.get(0).getName());
                if (conflict != null) {
                    String warn = "conflicts with: " +
                            Component.translatable(conflict.getName()).getString();
                    g.drawString(this.font, Component.literal(warn),
                            this.width - 20 - 130 - 4 - 22 - 4,
                            y + 14, 0xFF4444);
                }
            }
        }

        // Capture prompt
        if (capturing != null) {
            g.drawCenteredString(this.font,
                    Component.translatable("screen.cowboymkb.press"),
                    this.width / 2, this.height - 44, 0xFFE060);
        }
    }



    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    // -------------------------------------------------------------------------
    // Key candidates
    // -------------------------------------------------------------------------

    private static int[] buildCandidateKeys() {
        List<Integer> k = new ArrayList<>();
        k.add(GLFW.GLFW_KEY_SPACE); k.add(GLFW.GLFW_KEY_APOSTROPHE);
        k.add(GLFW.GLFW_KEY_COMMA); k.add(GLFW.GLFW_KEY_MINUS);
        k.add(GLFW.GLFW_KEY_PERIOD); k.add(GLFW.GLFW_KEY_SLASH);
        for (int c = GLFW.GLFW_KEY_0; c <= GLFW.GLFW_KEY_9; c++) k.add(c);
        k.add(GLFW.GLFW_KEY_SEMICOLON); k.add(GLFW.GLFW_KEY_EQUAL);
        for (int c = GLFW.GLFW_KEY_A; c <= GLFW.GLFW_KEY_Z; c++) k.add(c);
        k.add(GLFW.GLFW_KEY_LEFT_BRACKET); k.add(GLFW.GLFW_KEY_BACKSLASH);
        k.add(GLFW.GLFW_KEY_RIGHT_BRACKET); k.add(GLFW.GLFW_KEY_GRAVE_ACCENT);
        k.add(GLFW.GLFW_KEY_ENTER); k.add(GLFW.GLFW_KEY_TAB);
        k.add(GLFW.GLFW_KEY_BACKSPACE); k.add(GLFW.GLFW_KEY_INSERT);
        k.add(GLFW.GLFW_KEY_DELETE); k.add(GLFW.GLFW_KEY_RIGHT);
        k.add(GLFW.GLFW_KEY_LEFT); k.add(GLFW.GLFW_KEY_DOWN);
        k.add(GLFW.GLFW_KEY_UP); k.add(GLFW.GLFW_KEY_PAGE_UP);
        k.add(GLFW.GLFW_KEY_PAGE_DOWN); k.add(GLFW.GLFW_KEY_HOME);
        k.add(GLFW.GLFW_KEY_END); k.add(GLFW.GLFW_KEY_CAPS_LOCK);
        for (int c = GLFW.GLFW_KEY_F1; c <= GLFW.GLFW_KEY_F25; c++) k.add(c);
        for (int c = GLFW.GLFW_KEY_KP_0; c <= GLFW.GLFW_KEY_KP_EQUAL; c++) k.add(c);
        for (int c = GLFW.GLFW_KEY_LEFT_SHIFT; c <= GLFW.GLFW_KEY_MENU; c++) k.add(c);
        int[] out = new int[k.size()];
        for (int i = 0; i < out.length; i++) out[i] = k.get(i);
        return out;
    }
}
