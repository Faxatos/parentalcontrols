package lol.sylvie.parental;

import lol.sylvie.parental.command.ParentalControlsCommand;
import lol.sylvie.parental.config.Configuration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.UUID;

public class ParentalControls implements ModInitializer {
    public static final String MOD_ID = "parentalcontrols";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final HashMap<UUID, Integer> ticksPerPlayer = new HashMap<>();
    private LocalDateTime lastTickTime = LocalDateTime.now();

    public static int ticksRemaining(UUID player) {
        return (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20) - ticksPerPlayer.getOrDefault(player, 0);
    }

    public static boolean canPlayerJoin(ServerPlayerEntity player) {
        return ticksRemaining(player.getUuid()) > 0 || player.hasPermissionLevel(4) && Configuration.INSTANCE.excludeOperators;
    }

    private void disconnect(ServerPlayNetworkHandler handler) {
        handler.disconnect(Text.literal(Configuration.INSTANCE.disconnectMessage));
    }

    @Override
    public void onInitialize() {
        Configuration.load();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LocalDateTime currentTime = LocalDateTime.now();
            boolean midnightPassed = lastTickTime.getDayOfYear() != currentTime.getDayOfYear();
            if (midnightPassed) ticksPerPlayer.clear();

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();
                int ticks = ticksPerPlayer.getOrDefault(uuid, 0);

                if (!canPlayerJoin(player)) {
                    disconnect(player.networkHandler);
                } else {
                    ticksPerPlayer.put(uuid, ticks + 1);
                }
            }

            lastTickTime = currentTime;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (canPlayerJoin(handler.getPlayer())) return;
            disconnect(handler);
        });

        CommandRegistrationCallback.EVENT.register(ParentalControlsCommand::register);

        Runtime.getRuntime().addShutdownHook(new Thread(Configuration::save));
    }
}
