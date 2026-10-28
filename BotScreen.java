package pl.novabot;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menu NovaBot w stylu NOVA.
 *  - zakładka Bot: u góry IP + nick + Połącz/Rozłącz; po lewej podgląd bota, po prawej konsola i pole komend
 *  - zakładka Gracze: lista graczy na serwerze (klik = /msg do gracza)
 *  - zakładka Ustawienia: hasło bota, auto-rejestracja, auto-reconnect, wygląd menu
 * Wszystko rysowane ręcznie (DrawContext), więc łatwo zmienić wygląd.
 */
public class BotScreen extends Screen {
    private static final int W = 460, H = 240;
    private static final String[] TABS = {"Bot", "Gracze", "Ustawienia"};
    private static final int[] ACCENTS = {0x2DD4BF, 0x3B82F6, 0xA78BFA, 0xF472B6, 0xFBBF24, 0xF87171};

    // Paleta (ciemny motyw NOVA)
    private static final int PANEL = 0xFF101318, BORDER = 0xFF232A35, PILL = 0xFF0A0C0F;
    private static final int CARD = 0xFF161A21, CARD_BORDER = 0xFF22262E;
    private static final int TEXT = 0xFFE5E7EB, SUB = 0xFF8B94A1, DIM = 0xFF4B5563, ON_ACCENT = 0xFF0B1012;
    private static final int GREEN = 0xFF22C55E, RED = 0xFFB91C1C, YELLOW = 0xFFFBBF24;

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}
    private record FieldBox(TextFieldWidget field, int x1, int y1, int x2, int y2) {}

    private final Screen parent;
    private final BotSession bot = BotSession.get();
    private final List<Hit> hits = new ArrayList<>();
    private final List<FieldBox> boxes = new ArrayList<>();

    private int tab = 0;
    private int scroll = 0, contentHeight = 0; // przewijanie listy graczy
    private int logScroll = 0;                 // przewijanie konsoli (0 = najnowsze)
    private int cx, cx2, vy1, vy2;
    private TextRenderer font;

    private TextFieldWidget ipField, nickField, inputField, passField;

    // podgląd bota
    private OtherClientPlayerEntity preview;
    private ClientWorld previewWorld;
    private String previewName = "";

    public BotScreen(Screen parent) {
        super(Text.literal("NovaBot"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Przy zmianie rozmiaru okna zachowaj wpisany tekst.
        String ip = ipField != null ? ipField.getText() : BotConfig.IP;
        String nick = nickField != null ? nickField.getText() : BotConfig.NICK;
        String input = inputField != null ? inputField.getText() : "";
        String pass = passField != null ? passField.getText() : BotConfig.PASSWORD;
        ipField = makeField(ip, 100);
        nickField = makeField(nick, 16);
        inputField = makeField(input, 256);
        passField = makeField(pass, 32);
    }

    private TextFieldWidget makeField(String text, int max) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, 0, 0, 100, 8, Text.empty());
        f.setDrawsBackground(false);
        f.setMaxLength(max);
        f.setText(text);
        f.setEditableColor(TEXT);
        addSelectableChild(f);
        return f;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------
    // RYSOWANIE
    // ------------------------------------------------------------------
    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        font = this.textRenderer;
        hits.clear();
        boxes.clear();

        c.fill(0, 0, width, height, 0x88000000);
        int px = (width - W) / 2, py = (height - H) / 2;
        rrBorder(c, px, py, px + W, py + H, 8, BORDER, PANEL);

        // --- nagłówek ---
        rr(c, px + 12, py + 10, px + 28, py + 26, 4, accent());
        txt(c, "N", px + 12 + (16 - font.getWidth("N")) / 2, py + 14, ON_ACCENT);
        txt(c, "NOVABOT", px + 34, py + 14, TEXT);

        int pillW = 6;
        int[] tw = new int[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            tw[i] = font.getWidth(TABS[i]) + 16;
            pillW += tw[i] + (i < TABS.length - 1 ? 2 : 0);
        }
        int pillX = px + (W - pillW) / 2;
        rr(c, pillX, py + 8, pillX + pillW, py + 30, 11, PILL);
        int tx = pillX + 3;
        for (int i = 0; i < TABS.length; i++) {
            boolean sel = i == tab;
            boolean hov = mx >= tx && mx < tx + tw[i] && my >= py + 11 && my < py + 27;
            if (sel) rr(c, tx, py + 11, tx + tw[i], py + 27, 8, accent());
            txt(c, TABS[i], tx + 8, py + 15, sel ? ON_ACCENT : (hov ? TEXT : SUB));
            final int idx = i;
            addHit(tx, py + 11, tx + tw[i], py + 27, () -> { tab = idx; scroll = 0; }, false);
            tx += tw[i] + 2;
        }

        String keyName = NovaBotClient.openKey == null ? "B" : NovaBotClient.openKey.getBoundKeyLocalizedText().getString();
        String closeTxt = "[" + keyName + "] zamknij";
        int closeX = px + W - 12 - font.getWidth(closeTxt);
        boolean closeHov = mx >= closeX && mx < px + W - 12 && my >= py + 12 && my < py + 26;
        txt(c, closeTxt, closeX, py + 15, closeHov ? TEXT : DIM);
        addHit(closeX, py + 12, px + W - 12, py + 26, this::close, false);

        // --- zawartość ---
        cx = px + 12;
        cx2 = px + W - 12;
        vy1 = py + 40;
        vy2 = py + H - 12;

        setVis(ipField, tab == 0);
        setVis(nickField, tab == 0);
        setVis(inputField, tab == 0);
        setVis(passField, tab == 2);

        int maxScroll = Math.max(0, contentHeight - (vy2 - vy1));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        c.enableScissor(cx - 1, vy1 - 1, cx2 + 1, vy2 + 1);
        int endY = switch (tab) {
            case 0 -> pageBot(c, mx, my, delta);
            case 1 -> pagePlayers(c, mx, my);
            default -> pageSettings(c, mx, my, delta);
        };
        c.disableScissor();
        contentHeight = endY + scroll - vy1;
    }

    // ------------------------------------------------------------------
    // STRONA: BOT
    // ------------------------------------------------------------------
    private int pageBot(DrawContext c, int mx, int my, float delta) {
        final int rowH = 18;
        int y = vy1;
        int ipW = 170, nickW = 100, btnW = 70;

        drawField(c, ipField, cx, y, ipW, rowH, "IP serwera (np. anarchia.gg)", mx, my, delta);
        drawField(c, nickField, cx + ipW + 6, y, nickW, rowH, "Nick bota", mx, my, delta);

        int bx = cx + ipW + nickW + 12;
        boolean off = bot.status == BotSession.Status.OFFLINE;
        rr(c, bx, y, bx + btnW, y + rowH, 5, off ? accent() : RED);
        String bl = off ? "Połącz" : "Rozłącz";
        txt(c, bl, bx + (btnW - font.getWidth(bl)) / 2, y + 5, off ? ON_ACCENT : 0xFFFFFFFF);
        addHit(bx, y, bx + btnW, y + rowH, this::toggleConnect, false);

        // status
        int sx = bx + btnW + 8;
        int dot = switch (bot.status) {
            case ONLINE -> GREEN;
            case CONNECTING -> YELLOW;
            default -> 0xFFEF4444;
        };
        circle(c, sx + 3, y + 9, 3, dot);
        txt(c, bot.status.label, sx + 10, y + 5, SUB);

        int top = y + rowH + 6;
        int bottom = vy2;
        int leftW = 150;

        // --- lewy panel: podgląd bota ---
        rrBorder(c, cx, top, cx + leftW, bottom, 6, CARD_BORDER, CARD);
        drawPreview(c, cx + 8, top + 6, cx + leftW - 8, bottom - 52, mx, my);

        String name = bot.status != BotSession.Status.OFFLINE ? bot.botName : nickField.getText();
        int ty = bottom - 48;
        txt(c, name, cx + (leftW - font.getWidth(name)) / 2, ty, TEXT);
        String hp = String.format("HP: %.1f   Głód: %d", bot.health, bot.food);
        txt(c, hp, cx + (leftW - font.getWidth(hp)) / 2, ty + 11, SUB);
        String xyz = "XYZ: " + (int) Math.floor(bot.x) + " " + (int) Math.floor(bot.y) + " " + (int) Math.floor(bot.z);
        txt(c, xyz, cx + (leftW - font.getWidth(xyz)) / 2, ty + 22, SUB);
        String up = "Czas: " + bot.uptime();
        txt(c, up, cx + (leftW - font.getWidth(up)) / 2, ty + 33, SUB);

        // --- prawy panel: konsola + wpisywanie komend ---
        int rx = cx + leftW + 6;
        int rw = cx2 - rx;
        rrBorder(c, rx, top, cx2, bottom, 6, CARD_BORDER, CARD);
        drawConsole(c, rx + 8, top + 6, cx2 - 8, bottom - 28);

        int iy = bottom - 22;
        int sendW = 48;
        drawField(c, inputField, rx + 6, iy, rw - 12 - sendW - 4, 16, "Napisz coś albo /komendę...", mx, my, delta);
        int sbx = cx2 - 6 - sendW;
        rr(c, sbx, iy, sbx + sendW, iy + 16, 5, accent());
        txt(c, "Wyślij", sbx + (sendW - font.getWidth("Wyślij")) / 2, iy + 4, ON_ACCENT);
        addHit(sbx, iy, sbx + sendW, iy + 16, this::sendInput, false);

        return vy2;
    }

    private void drawConsole(DrawContext c, int x1, int y1, int x2, int y2) {
        final int lineH = 10;
        int visible = (y2 - y1) / lineH;
        List<BotSession.LogLine> snap = bot.logSnapshot();
        if (snap.isEmpty()) {
            txt(c, "Wpisz IP, nick i kliknij Połącz.", x1, y1 + 2, DIM);
            return;
        }
        List<OrderedText> lines = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        int need = visible + logScroll + 1;
        for (int i = snap.size() - 1; i >= 0 && lines.size() < need; i--) {
            BotSession.LogLine l = snap.get(i);
            List<OrderedText> wrapped = font.wrapLines(Text.literal(l.time() + " " + l.text()), x2 - x1);
            int col = switch (l.kind()) {
                case BotSession.OWN -> accent();
                case BotSession.ERROR -> 0xFFF87171;
                case BotSession.INFO -> SUB;
                default -> TEXT;
            };
            for (int k = wrapped.size() - 1; k >= 0; k--) { // od dołu
                lines.add(wrapped.get(k));
                cols.add(col);
            }
        }
        logScroll = Math.min(logScroll, Math.max(0, lines.size() - visible));
        for (int j = 0; j < visible; j++) {
            int idx = j + logScroll;
            if (idx >= lines.size()) break;
            int yy = y2 - (j + 1) * lineH;
            c.drawText(font, lines.get(idx), x1, yy + 1, cols.get(idx), true);
        }
    }

    private void drawPreview(DrawContext c, int x1, int y1, int x2, int y2, int mx, int my) {
        rr(c, x1, y1, x2, y2, 5, PILL);
        String name = bot.status != BotSession.Status.OFFLINE ? bot.botName : nickField.getText();
        boolean validName = name.matches("[A-Za-z0-9_]{3,16}");
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));

        // Pełny model postaci (jak w ekwipunku) - tylko gdy jesteś w świecie.
        if (client != null && client.world != null && validName) {
            try {
                if (preview == null || previewWorld != client.world || !name.equals(previewName)) {
                    preview = new OtherClientPlayerEntity(client.world, new GameProfile(id, name));
                    previewWorld = client.world;
                    previewName = name;
                }
                int size = (int) ((y2 - y1) / 2.3f);
                InventoryScreen.drawEntity(c, x1, y1, x2, y2, size, 0.0625f, (float) mx, (float) my, preview);
                return;
            } catch (Throwable t) {
                preview = null; // spadamy do głowy
            }
        }
        // Na ekranie tytułowym: głowa z domyślnym skinem.
        PlayerSkinDrawer.draw(c, DefaultSkinHelper.getSkinTextures(id), (x1 + x2) / 2 - 24, (y1 + y2) / 2 - 24, 48);
    }

    // ------------------------------------------------------------------
    // STRONA: GRACZE
    // ------------------------------------------------------------------
    private int pagePlayers(DrawContext c, int mx, int my) {
        int y = vy1 - scroll;
        List<Map.Entry<UUID, String>> list = bot.playersSnapshot();
        if (bot.status != BotSession.Status.ONLINE) {
            txt(c, "Bot nie jest na serwerze.", cx + 2, y + 4, SUB);
            return y + 16;
        }
        txt(c, "Gracze na serwerze: " + list.size() + "  (klik = napisz do gracza)", cx + 2, y + 2, SUB);
        y += 14;
        for (Map.Entry<UUID, String> e : list) {
            row(c, y, 22, mx, my);
            PlayerSkinDrawer.draw(c, DefaultSkinHelper.getSkinTextures(e.getKey()), cx + 6, y + 3, 16);
            txt(c, e.getValue(), cx + 30, y + 7, TEXT);
            final String nm = e.getValue();
            addHit(cx, y, cx2, y + 22, () -> {
                inputField.setText("/msg " + nm + " ");
                tab = 0;
                setFocused(inputField);
            }, true);
            y += 25;
        }
        return y;
    }

    // ------------------------------------------------------------------
    // STRONA: USTAWIENIA
    // ------------------------------------------------------------------
    private int pageSettings(DrawContext c, int mx, int my, float delta) {
        int y = vy1;

        row(c, y, 22, mx, my);
        txt(c, "Hasło bota", cx + 8, y + 7, TEXT);
        drawField(c, passField, cx2 - 8 - 150, y + 3, 150, 16, "hasło", mx, my, delta);
        y += 25;

        y = toggleRow(c, mx, my, y, "Auto-rejestracja i logowanie", BotConfig.AUTO_REGISTER, () -> BotConfig.AUTO_REGISTER = !BotConfig.AUTO_REGISTER);
        y = toggleRow(c, mx, my, y, "Auto-reconnect (co 5 s)", BotConfig.AUTO_RECONNECT, () -> BotConfig.AUTO_RECONNECT = !BotConfig.AUTO_RECONNECT);
        y = toggleRow(c, mx, my, y, "Zaokrąglone ramki", BotConfig.ROUNDED, () -> BotConfig.ROUNDED = !BotConfig.ROUNDED);
        y = toggleRow(c, mx, my, y, "Dźwięki menu", BotConfig.MENU_SOUNDS, () -> BotConfig.MENU_SOUNDS = !BotConfig.MENU_SOUNDS);

        // Kolor GUI
        row(c, y, 22, mx, my);
        txt(c, "Kolor GUI", cx + 8, y + 7, TEXT);
        int startX = (cx + cx2) / 2 - (ACCENTS.length * 20 - 10) / 2;
        for (int i = 0; i < ACCENTS.length; i++) {
            int ccx = startX + i * 20 + 5;
            if (ACCENTS[i] == BotConfig.GUI_COLOR) circle(c, ccx, y + 11, 6, 0xFFFFFFFF);
            circle(c, ccx, y + 11, 5, 0xFF000000 | ACCENTS[i]);
            final int col = ACCENTS[i];
            addHit(ccx - 7, y + 4, ccx + 8, y + 18, () -> BotConfig.GUI_COLOR = col, false);
        }
        String hex = String.format("#%06X", BotConfig.GUI_COLOR);
        txt(c, hex, cx2 - 8 - font.getWidth(hex), y + 7, SUB);
        y += 25;

        txt(c, "Bot działa tylko na serwerach non-premium (bez weryfikacji konta).", cx + 2, y + 2, DIM);
        return y + 14;
    }

    // ------------------------------------------------------------------
    // AKCJE
    // ------------------------------------------------------------------
    private void syncConfig() {
        BotConfig.IP = ipField.getText().trim();
        BotConfig.NICK = nickField.getText().trim();
        BotConfig.PASSWORD = passField.getText();
        BotConfig.save();
    }

    private void toggleConnect() {
        syncConfig();
        if (bot.status == BotSession.Status.OFFLINE) bot.connect(BotConfig.IP, BotConfig.NICK);
        else bot.disconnect();
    }

    private void sendInput() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        bot.send(text);
        inputField.setText("");
        logScroll = 0;
    }

    // ------------------------------------------------------------------
    // ELEMENTY
    // ------------------------------------------------------------------
    private void drawField(DrawContext c, TextFieldWidget f, int x, int y, int w, int h,
                           String placeholder, int mx, int my, float delta) {
        boolean focused = f.isFocused();
        rrBorder(c, x, y, x + w, y + h, 4, focused ? accent() : CARD_BORDER, PILL);
        int ty = y + (h - 8) / 2;
        f.setPosition(x + 6, ty);
        f.setWidth(w - 12);
        f.render(c, mx, my, delta);
        if (f.getText().isEmpty() && !focused) c.drawText(font, placeholder, x + 6, ty, DIM, false);
        boxes.add(new FieldBox(f, x, y, x + w, y + h));
    }

    private void setVis(TextFieldWidget f, boolean v) {
        f.setVisible(v);
        if (!v) {
            if (f.isFocused()) f.setFocused(false);
            if (getFocused() == f) setFocused(null);
        }
    }

    private int toggleRow(DrawContext c, int mx, int my, int y, String label, boolean on, Runnable click) {
        row(c, y, 22, mx, my);
        txt(c, label, cx + 8, y + 7, TEXT);
        int sx = cx2 - 8 - 26;
        rr(c, sx, y + 5, sx + 26, y + 17, 6, on ? accent() : DIM);
        int kx = on ? sx + 15 : sx + 2;
        rr(c, kx, y + 7, kx + 9, y + 15, 4, 0xFFFFFFFF);
        addHit(cx, y, cx2, y + 22, click, true);
        return y + 25;
    }

    private void row(DrawContext c, int y, int h, int mx, int my) {
        boolean hov = mx >= cx && mx < cx2 && my >= Math.max(y, vy1) && my < Math.min(y + h, vy2);
        rrBorder(c, cx, y, cx2, y + h, 5, hov ? DIM : CARD_BORDER, CARD);
    }

    private void txt(DrawContext c, String s, int x, int y, int color) {
        c.drawText(font, s, x, y, color, true);
    }

    // ---- prymitywy rysowania ----
    private static void rr(DrawContext c, int x1, int y1, int x2, int y2, int r, int color) {
        if (!BotConfig.ROUNDED || r <= 0 || (y2 - y1) < 2 * r || (x2 - x1) < 2 * r) {
            c.fill(x1, y1, x2, y2, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            c.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            c.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
        c.fill(x1, y1 + r, x2, y2 - r, color);
    }

    private static void rrBorder(DrawContext c, int x1, int y1, int x2, int y2, int r, int border, int fill) {
        rr(c, x1, y1, x2, y2, r, border);
        rr(c, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(r - 1, 0), fill);
    }

    private static void circle(DrawContext c, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(r * r + r - dy * dy));
            c.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    private static int accent() {
        return 0xFF000000 | BotConfig.GUI_COLOR;
    }

    private void addHit(int x1, int y1, int x2, int y2, Runnable action, boolean scrollable) {
        if (scrollable) {
            y1 = Math.max(y1, vy1);
            y2 = Math.min(y2, vy2);
            if (y1 >= y2) return;
        }
        hits.add(new Hit(x1, y1, x2, y2, action));
    }

    private void playClick() {
        if (BotConfig.MENU_SOUNDS) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    // ------------------------------------------------------------------
    // WEJŚCIE
    // ------------------------------------------------------------------
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // pola tekstowe (większy obszar kliknięcia niż sam widżet)
            for (FieldBox b : boxes) {
                if (b.field().visible && mouseX >= b.x1() && mouseX < b.x2() && mouseY >= b.y1() && mouseY < b.y2()) {
                    setFocused(b.field());
                    b.field().mouseClicked(mouseX, b.field().getY(), button); // ustawia kursor
                    return true;
                }
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mouseX >= h.x1() && mouseX < h.x2() && mouseY >= h.y1() && mouseY < h.y2()) {
                    h.action().run();
                    playClick();
                    return true;
                }
            }
            setFocused(null); // klik w puste miejsce odznacza pole
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == 0) {
            logScroll = Math.max(0, logScroll + (verticalAmount > 0 ? 3 : -3)); // kółko w górę = starsze wiersze
        } else {
            int maxScroll = Math.max(0, contentHeight - (vy2 - vy1));
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - verticalAmount * 12));
        }
        return true;
    }

    private boolean typing() {
        return (ipField.visible && ipField.isFocused()) || (nickField.visible && nickField.isFocused())
                || (inputField.visible && inputField.isFocused()) || (passField.visible && passField.isFocused());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (tab == 0 && inputField.isFocused()) { sendInput(); return true; }
            if (tab == 0 && (ipField.isFocused() || nickField.isFocused())) { toggleConnect(); return true; }
        }
        // Klawisz menu (B) zamyka menu, ale nie podczas pisania.
        if (!typing() && NovaBotClient.openKey != null && NovaBotClient.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        syncConfig();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
