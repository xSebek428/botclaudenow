package pl.novabot;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

/** Ustawienia moda - zapisują się w config/novabot.properties. */
public final class BotConfig {
    private BotConfig() {}

    public static String IP = "";                                       // ostatnio wpisane IP
    public static String NICK = "NovaBot" + (100 + new Random().nextInt(900)); // nick bota
    public static String PASSWORD = "NovaBot123";                      // hasło do /register i /login
    public static boolean AUTO_REGISTER = true;                         // sam wpisuje /register i /login
    public static boolean AUTO_RECONNECT = false;                       // wraca po rozłączeniu (co 5 s)
    public static boolean ROUNDED = true;                               // zaokrąglone ramki menu
    public static boolean MENU_SOUNDS = true;                           // dźwięki kliknięć
    public static int GUI_COLOR = 0x2DD4BF;                             // kolor akcentu (RRGGBB)

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("novabot.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Properties p = new Properties();
            p.load(r);
            IP = p.getProperty("ip", IP);
            NICK = p.getProperty("nick", NICK);
            PASSWORD = p.getProperty("password", PASSWORD);
            AUTO_REGISTER = Boolean.parseBoolean(p.getProperty("autoRegister", "true"));
            AUTO_RECONNECT = Boolean.parseBoolean(p.getProperty("autoReconnect", "false"));
            ROUNDED = Boolean.parseBoolean(p.getProperty("rounded", "true"));
            MENU_SOUNDS = Boolean.parseBoolean(p.getProperty("menuSounds", "true"));
            GUI_COLOR = Integer.parseInt(p.getProperty("guiColor", "2DD4BF"), 16);
        } catch (IOException | NumberFormatException e) {
            NovaBotMod.LOGGER.warn("Nie udało się wczytać configu", e);
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("ip", IP);
        p.setProperty("nick", NICK);
        p.setProperty("password", PASSWORD);
        p.setProperty("autoRegister", String.valueOf(AUTO_REGISTER));
        p.setProperty("autoReconnect", String.valueOf(AUTO_RECONNECT));
        p.setProperty("rounded", String.valueOf(ROUNDED));
        p.setProperty("menuSounds", String.valueOf(MENU_SOUNDS));
        p.setProperty("guiColor", String.format("%06X", GUI_COLOR));
        try (Writer w = Files.newBufferedWriter(file())) {
            p.store(w, "NovaBot");
        } catch (IOException e) {
            NovaBotMod.LOGGER.warn("Nie udało się zapisać configu", e);
        }
    }
}
