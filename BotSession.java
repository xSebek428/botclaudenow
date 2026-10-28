package pl.novabot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bot = drugie, osobne połączenie z serwerem (biblioteka MCProtocolLib), niezależne od twojej gry.
 *
 * Co robi: łączy się (tryb non-premium), sam wpisuje /register i /login, zbiera czat do konsoli,
 * odradza się po śmierci i pozwala wysyłać wiadomości i komendy z menu.
 *
 * Uwaga: pakiety czytamy po nazwie klasy i refleksją - dzięki temu drobne różnice
 * między wersjami biblioteki nie psują kompilacji.
 */
public final class BotSession {
    public enum Status {
        OFFLINE("Rozłączony"), CONNECTING("Łączenie..."), ONLINE("Na serwerze");
        public final String label;
        Status(String label) { this.label = label; }
    }

    /** Wiersz konsoli. kind: CHAT / INFO / OWN / ERROR. */
    public record LogLine(String time, String text, int kind) {}
    public static final int CHAT = 0, INFO = 1, OWN = 2, ERROR = 3;

    private static final String PKG = "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final BotSession INSTANCE = new BotSession();

    public static BotSession get() { return INSTANCE; }

    // ---- stan widoczny w menu ----
    public volatile Status status = Status.OFFLINE;
    public volatile String botName = "";
    public volatile double x, y, z;
    public volatile float health = 20f;
    public volatile int food = 20;
    public volatile long joinedAt;

    private final List<LogLine> log = new ArrayList<>();
    private final Map<UUID, String> players = new LinkedHashMap<>();

    private volatile ClientSession session;
    private volatile boolean wantConnected;
    private volatile String lastHost = "";
    private volatile int lastPort = 25565;
    private volatile String lastNick = "";
    private volatile int authAttempts;
    private volatile boolean authDone;
    private volatile long lastAuthMs;

    private BotSession() {}

    // ------------------------------------------------------------------
    // POŁĄCZENIE
    // ------------------------------------------------------------------
    /** address: "ip" albo "ip:port". */
    public synchronized void connect(String address, String nick) {
        if (status != Status.OFFLINE) return;
        address = address == null ? "" : address.trim();
        if (address.isEmpty()) { log(ERROR, "Wpisz IP serwera."); return; }
        if (nick == null || !nick.matches("[A-Za-z0-9_]{3,16}")) {
            log(ERROR, "Nick bota: 3-16 znaków (litery, cyfry, _).");
            return;
        }
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                host = address.substring(0, colon);
            } catch (NumberFormatException e) {
                log(ERROR, "Niepoprawny port.");
                return;
            }
        }
        wantConnected = true;
        startConnect(host, port, nick);
    }

    private void startConnect(String host, int port, String nick) {
        lastHost = host; lastPort = port; lastNick = nick;
        status = Status.CONNECTING;
        botName = nick;
        authAttempts = 0;
        authDone = false;
        health = 20f; food = 20; x = y = z = 0;
        synchronized (players) { players.clear(); }
        log(INFO, "Łączę z " + host + ":" + port + " jako " + nick + "...");

        Thread th = new Thread(() -> {
            try {
                MinecraftProtocol protocol = new MinecraftProtocol(nick);   // tryb non-premium (sam nick)
                ClientSession s = ClientNetworkSessionFactory.factory()
                        .setRemoteSocketAddress(new InetSocketAddress(host, port))
                        .setProtocol(protocol)
                        .create();
                s.addListener(new Listener(s));
                session = s;
                s.connect();
            } catch (Throwable t) {
                log(ERROR, "Błąd połączenia: " + t);
                status = Status.OFFLINE;
                scheduleReconnect();
            }
        }, "NovaBot-connect");
        th.setDaemon(true);
        th.start();
    }

    public synchronized void disconnect() {
        wantConnected = false;
        ClientSession s = session;
        session = null;
        status = Status.OFFLINE;
        synchronized (players) { players.clear(); }
        if (s != null) closeSession(s);
        log(INFO, "Bot rozłączony.");
    }

    private void scheduleReconnect() {
        if (!wantConnected || !BotConfig.AUTO_RECONNECT) return;
        Thread th = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            synchronized (BotSession.this) {
                if (wantConnected && status == Status.OFFLINE) {
                    log(INFO, "Ponowne łączenie...");
                    startConnect(lastHost, lastPort, lastNick);
                }
            }
        }, "NovaBot-reconnect");
        th.setDaemon(true);
        th.start();
    }

    private final class Listener extends SessionAdapter {
        private final ClientSession own;

        Listener(ClientSession own) { this.own = own; }

        @Override
        public void packetReceived(Session ses, Packet packet) {
            if (own != session) return; // stare połączenie - ignoruj
            try {
                handle(packet);
            } catch (Throwable t) {
                log(ERROR, "Błąd obsługi pakietu: " + t);
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            if (own != session) return;
            boolean wasOnline = status == Status.ONLINE;
            String reason = textOf(event.getReason());
            session = null;
            status = Status.OFFLINE;
            log(ERROR, "Rozłączono: " + (reason.isBlank() ? "(brak powodu)" : reason));
            if (!wasOnline) {
                log(INFO, "Jeśli serwer jest premium (online-mode), bot nie wejdzie - działa tylko na serwerach non-premium.");
            }
            scheduleReconnect();
        }
    }

    // ------------------------------------------------------------------
    // PAKIETY OD SERWERA
    // ------------------------------------------------------------------
    private void handle(Packet p) {
        switch (p.getClass().getSimpleName()) {
            case "ClientboundLoginPacket" -> {
                status = Status.ONLINE;
                joinedAt = System.currentTimeMillis();
                log(INFO, "Bot wszedł na serwer jako " + botName + ".");
            }
            case "ClientboundSystemChatPacket" -> {
                String text = textOf(call(p, "getContent"));
                if (!text.isBlank()) {
                    log(CHAT, text);
                    autoAuth(text);
                }
            }
            case "ClientboundPlayerChatPacket" -> {
                Object un = call(p, "getUnsignedContent");
                String msg = un instanceof Component c ? flatten(c) : "";
                if (msg.isBlank()) msg = textOf(call(p, "getContent"));
                String who = textOf(call(p, "getName"));
                if (who.isBlank()) {
                    Object sid = call(p, "getSender");
                    if (sid instanceof UUID u) synchronized (players) { who = players.getOrDefault(u, ""); }
                }
                log(CHAT, (who.isBlank() ? "" : "<" + who + "> ") + msg);
            }
            case "ClientboundDisguisedChatPacket" -> {
                String msg = textOf(call(p, "getMessage"));
                String who = textOf(call(p, "getName"));
                log(CHAT, (who.isBlank() ? "" : "<" + who + "> ") + msg);
            }
            case "ClientboundSetHealthPacket" -> {
                Object h = call(p, "getHealth");
                Object f = call(p, "getFood");
                if (h instanceof Number n) health = n.floatValue();
                if (f instanceof Number n) food = n.intValue();
                if (health <= 0) respawn();
            }
            case "ClientboundPlayerPositionPacket" -> readPosition(p);
            case "ClientboundPlayerInfoUpdatePacket" -> readPlayerInfo(p);
            case "ClientboundPlayerInfoRemovePacket" -> {
                Object ids = call(p, "getProfileIds");
                if (ids instanceof Collection<?> col) synchronized (players) {
                    for (Object o : col) if (o instanceof UUID u) players.remove(u);
                }
            }
            default -> { }
        }
    }

    private void readPosition(Packet p) {
        Object pos = call(p, "getPosition");
        Object src = pos != null ? pos : p;
        Object ox = call(src, "getX", "x");
        Object oy = call(src, "getY", "y");
        Object oz = call(src, "getZ", "z");
        if (ox instanceof Number a && oy instanceof Number b && oz instanceof Number c) {
            x = a.doubleValue(); y = b.doubleValue(); z = c.doubleValue();
        }
    }

    private void readPlayerInfo(Packet p) {
        Object es = call(p, "getEntries");
        List<Object> list = new ArrayList<>();
        if (es instanceof Object[] arr) list.addAll(Arrays.asList(arr));
        else if (es instanceof Collection<?> col) list.addAll(col);
        synchronized (players) {
            for (Object e : list) {
                Object prof = call(e, "getProfile");
                Object name = prof == null ? null : call(prof, "getName", "name");
                Object id = call(e, "getProfileId");
                if (id == null && prof != null) id = call(prof, "getId", "id");
                if (id instanceof UUID u && name instanceof String s) players.put(u, s);
            }
        }
    }

    // ------------------------------------------------------------------
    // AUTO-REJESTRACJA / LOGOWANIE
    // ------------------------------------------------------------------
    private void autoAuth(String text) {
        if (!BotConfig.AUTO_REGISTER || authDone) return;
        String low = text.toLowerCase(Locale.ROOT);

        // Komunikaty o sukcesie - kończymy.
        if (low.contains("pomyślnie") || low.contains("successfully") || low.contains("zalogowano")
                || low.contains("zarejestrowano") || low.contains("logged in") || low.contains("registered")) {
            authDone = true;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAuthMs < 2500 || authAttempts >= 6) return;
        if (joinedAt > 0 && now - joinedAt > 90_000) return; // tylko zaraz po wejściu

        String pass = BotConfig.PASSWORD;
        if (low.contains("/register") || low.contains("zarejestruj")) {
            lastAuthMs = now;
            authAttempts++;
            // raz z powtórzeniem hasła, raz bez (różne pluginy wymagają różnie)
            String cmd = (authAttempts % 2 == 1) ? "register " + pass + " " + pass : "register " + pass;
            log(INFO, "Auto-rejestracja...");
            sendCommand(cmd);
        } else if (low.contains("/login") || low.contains("zaloguj")) {
            lastAuthMs = now;
            authAttempts++;
            log(INFO, "Auto-logowanie...");
            sendCommand("login " + pass);
        }
    }

    // ------------------------------------------------------------------
    // WYSYŁANIE
    // ------------------------------------------------------------------
    /** Wiadomość albo komenda (zaczynająca się od "/") wpisana w konsoli menu. */
    public void send(String text) {
        if (status != Status.ONLINE || session == null) {
            log(ERROR, "Bot nie jest na serwerze.");
            return;
        }
        log(OWN, "> " + text);
        if (text.startsWith("/")) sendCommand(text.substring(1));
        else sendMessage(text);
    }

    private void sendCommand(String cmd) {
        ClientSession s = session;
        if (s == null) return;
        try {
            Class<?> k = Class.forName(PKG + "ServerboundChatCommandPacket");
            s.send((Packet) k.getConstructor(String.class).newInstance(cmd));
        } catch (Throwable t) {
            log(ERROR, "Nie mogę wysłać komendy: " + t);
        }
    }

    private void sendMessage(String text) {
        ClientSession s = session;
        if (s == null) return;
        try {
            Class<?> k = Class.forName(PKG + "ServerboundChatPacket");
            for (Constructor<?> c : k.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 0 || pt[0] != String.class) continue;
                Object[] args = new Object[pt.length];
                int longs = 0;
                for (int i = 0; i < pt.length; i++) {
                    Class<?> t = pt[i];
                    if (t == String.class) args[i] = text;
                    else if (t == long.class) args[i] = (longs++ == 0) ? System.currentTimeMillis() : 0L; // czas, potem salt
                    else if (t == Instant.class) args[i] = Instant.now();
                    else if (t == int.class) args[i] = 0;
                    else if (t == byte.class) args[i] = (byte) 0;
                    else if (t == boolean.class) args[i] = false;
                    else if (t == BitSet.class) args[i] = new BitSet();
                    else if (List.class.isAssignableFrom(t)) args[i] = new ArrayList<>();
                    else args[i] = null; // podpis wiadomości = brak (serwery non-premium)
                }
                s.send((Packet) c.newInstance(args));
                return;
            }
            log(ERROR, "Nie znaleziono pakietu czatu.");
        } catch (Throwable t) {
            log(ERROR, "Nie mogę wysłać wiadomości: " + t);
        }
    }

    private void respawn() {
        ClientSession s = session;
        if (s == null) return;
        try {
            Class<?> cmd = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.ClientCommand");
            Class<?> pk = Class.forName(PKG + "ServerboundClientCommandPacket");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object respawn = Enum.valueOf((Class<Enum>) cmd, "RESPAWN");
            s.send((Packet) pk.getConstructor(cmd).newInstance(respawn));
            log(INFO, "Bot zginął - odradzam.");
        } catch (Throwable t) {
            log(ERROR, "Nie mogę odrodzić bota: " + t);
        }
    }

    private static void closeSession(ClientSession s) {
        for (Method m : s.getClass().getMethods()) {
            if (!m.getName().equals("disconnect") || m.getParameterCount() != 1) continue;
            Class<?> t = m.getParameterTypes()[0];
            try {
                if (t == String.class) m.invoke(s, "NovaBot: rozłączono");
                else if (t.isAssignableFrom(Component.class)) m.invoke(s, Component.text("NovaBot: rozłączono"));
                else continue;
                return;
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // LOGI, GRACZE
    // ------------------------------------------------------------------
    public void log(int kind, String text) {
        LogLine line = new LogLine(LocalTime.now().format(TIME), text, kind);
        synchronized (log) {
            log.add(line);
            while (log.size() > 300) log.remove(0);
        }
    }

    public List<LogLine> logSnapshot() {
        synchronized (log) { return new ArrayList<>(log); }
    }

    public void clearLog() {
        synchronized (log) { log.clear(); }
    }

    public List<Map.Entry<UUID, String>> playersSnapshot() {
        synchronized (players) { return new ArrayList<>(players.entrySet()); }
    }

    public String uptime() {
        if (status != Status.ONLINE || joinedAt == 0) return "--:--:--";
        long s = (System.currentTimeMillis() - joinedAt) / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    // ------------------------------------------------------------------
    // POMOCNICZE
    // ------------------------------------------------------------------
    private static Object call(Object o, String... names) {
        if (o == null) return null;
        for (String nm : names) {
            try {
                Method m = o.getClass().getMethod(nm);
                m.setAccessible(true);
                return m.invoke(o);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String textOf(Object o) {
        if (o == null) return "";
        if (o instanceof Component c) return flatten(c);
        return String.valueOf(o);
    }

    /** Zamienia komponent tekstu z serwera na zwykły tekst (bez kolorów). */
    public static String flatten(Component c) {
        StringBuilder sb = new StringBuilder();
        append(c, sb);
        return sb.toString().replaceAll("§.", "");
    }

    private static void append(Component c, StringBuilder sb) {
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        } else if (c instanceof TranslatableComponent tr) {
            List<TranslationArgument> args = tr.arguments();
            String key = tr.key();
            if (key.equals("chat.type.text") && args.size() >= 2) {
                sb.append('<');
                appendArg(args.get(0), sb);
                sb.append("> ");
                appendArg(args.get(1), sb);
            } else {
                String fb = tr.fallback();
                sb.append(fb != null ? fb : key);
                for (TranslationArgument a : args) {
                    sb.append(' ');
                    appendArg(a, sb);
                }
            }
        }
        for (Component ch : c.children()) append(ch, sb);
    }

    private static void appendArg(TranslationArgument a, StringBuilder sb) {
        Object v = a.value();
        if (v instanceof Component c) append(c, sb);
        else sb.append(v);
    }
}
