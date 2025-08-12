package lol.sylvie.parental;

import lol.sylvie.parental.command.ParentalControlsCommand;
import lol.sylvie.parental.config.Configuration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class ParentalControls implements ModInitializer {
    public static final String MOD_ID = "parentalcontrols";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int TICKS_PER_CHECK = 20; // Check every second (20 ticks)

    public static final HashMap<UUID, Integer> ticksUsedToday = new HashMap<>();
    public static final HashMap<UUID, Integer> accumulatedTicks = new HashMap<>();
    private LocalDateTime lastTickTime = LocalDateTime.now();
    private int tickCounter = 0;

    public static int ticksRemaining(UUID player) {
        int dailyAllowance = (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20);
        int usedToday = ticksUsedToday.getOrDefault(player, 0);
        int accumulated = Configuration.INSTANCE.allowTimeStacking ? accumulatedTicks.getOrDefault(player, 0) : 0;
        
        int remainingDaily = Math.max(0, dailyAllowance - usedToday);
        return remainingDaily + accumulated;
    }

    private static void consumeTime(UUID playerId, int ticksToConsume) {
        int dailyAllowance = (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20);
        int usedToday = ticksUsedToday.getOrDefault(playerId, 0);
        int accumulated = accumulatedTicks.getOrDefault(playerId, 0);
        
        int remainingDaily = Math.max(0, dailyAllowance - usedToday);
        
        if (remainingDaily >= ticksToConsume) {
            ticksUsedToday.put(playerId, usedToday + ticksToConsume);
        } else {
            if (remainingDaily > 0) {
                ticksUsedToday.put(playerId, usedToday + remainingDaily);
                ticksToConsume -= remainingDaily;
            }
            
            if (ticksToConsume > 0 && accumulated > 0) {
                int stackedToConsume = Math.min(ticksToConsume, accumulated);
                accumulatedTicks.put(playerId, accumulated - stackedToConsume);
            }
        }
    }

    public static boolean canPlayerJoin(ServerPlayerEntity player) {
        return ticksRemaining(player.getUuid()) > 0 || player.hasPermissionLevel(4) && Configuration.INSTANCE.excludeOperators;
    }

    private static void disconnect(ServerPlayNetworkHandler handler) {
        handler.disconnect(Text.literal(Configuration.INSTANCE.disconnectMessage));
    }

    @Override
    public void onInitialize() {
        Configuration.load();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            
            if (tickCounter >= TICKS_PER_CHECK) {
                tickCounter = 0;

                LocalDateTime currentTime = LocalDateTime.now();
                boolean midnightPassed = lastTickTime.toLocalDate().isBefore(currentTime.toLocalDate());
                if (midnightPassed) 
                    handleDayTransition();

                ArrayList<ServerPlayNetworkHandler> choppingBlock = new ArrayList<>(); // Avoids a concurrent modification error
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    UUID uuid = player.getUuid();
                    int usedToday = ticksUsedToday.getOrDefault(uuid, 0);

                    if (!canPlayerJoin(player)) {
                        choppingBlock.add(player.networkHandler);
                    } else {
                        consumeTime(uuid, TICKS_PER_CHECK);
                    }
                }

                choppingBlock.forEach(ParentalControls::disconnect);
                lastTickTime = currentTime;
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (canPlayerJoin(handler.getPlayer())) return;
            disconnect(handler);
		});

        CommandRegistrationCallback.EVENT.register(ParentalControlsCommand::register);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Configuration.INSTANCE.playerAccumulatedTicks.clear();
            Configuration.INSTANCE.playerAccumulatedTicks.putAll(accumulatedTicks);
            Configuration.save();
        }));
    }

    private void handleDayTransition() {
        if (Configuration.INSTANCE.allowTimeStacking) {
            int dailyAllowance = (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20);
            int maxAccumulated = (int) (Configuration.INSTANCE.maxStackedHours * 60 * 60 * 20);
            
            for (UUID playerId : ticksUsedToday.keySet()) {
                int usedToday = ticksUsedToday.get(playerId);
                int leftover = Math.max(0, dailyAllowance - usedToday);
                
                if (leftover > 0) {
                    int currentAccumulated = accumulatedTicks.getOrDefault(playerId, 0);
                    int newAccumulated = Math.min(maxAccumulated, currentAccumulated + leftover);
                    accumulatedTicks.put(playerId, newAccumulated);
                    
                    LOGGER.info("Player {} has {} leftover ticks, accumulated total: {}", playerId, leftover, newAccumulated);
                }
            }
            
            // Load any accumulated time from configuration
            Configuration.INSTANCE.playerAccumulatedTicks.forEach((playerId, ticks) -> {
                if (!accumulatedTicks.containsKey(playerId)) {
                    accumulatedTicks.put(playerId, ticks);
                }
            });
        }
        
        ticksUsedToday.clear();
        LOGGER.info("New day started - daily usage reset");
    }
}
