package pl.novabot;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Główna klasa moda (entrypoint "main"). Cała praca dzieje się w NovaBotClient i BotSession. */
public class NovaBotMod implements ModInitializer {
    public static final String MOD_ID = "novabot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("NovaBot załadowany.");
    }
}
