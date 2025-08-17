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
import java.util.HashSet;
import java.util.UUID;

public class ParentalControls implements ModInitializer {
    public static final String MOD_ID = "parentalcontrols";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int TICKS_PER_CHECK = 20; // Check every second (20 ticks)
    private static final int WARNING_THRESHOLD_TICKS = 300 * 20; // 5 minutes warning

    private static int dailyAllowance;
    private static int maxAccumulated;

    public static final HashMap<UUID, Integer> ticksUsedToday = new HashMap<>();
    public static final HashMap<UUID, Integer> accumulatedTicks = new HashMap<>();
    private static final HashSet<UUID> playersWarned = new HashSet<>();
    private LocalDateTime lastTickTime = LocalDateTime.now();
    private int tickCounter = 0;

    public static void updateTimeConstants() {
        dailyAllowance = (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20);
        maxAccumulated = (int) (Configuration.INSTANCE.maxStackedHours * 60 * 60 * 20);
    }

    public static void loadAccumulatedTicksFromConfig() {
        Configuration.INSTANCE.playerAccumulatedTicks.forEach((playerId, ticks) -> {
            accumulatedTicks.put(playerId, ticks);
            LOGGER.info("Loaded {} accumulated ticks for player {} from configuration", ticks, playerId);
        });
    }

    public static int ticksRemaining(UUID player) {
        int usedToday = ticksUsedToday.getOrDefault(player, 0);
        int accumulated = Configuration.INSTANCE.allowTimeStacking ? accumulatedTicks.getOrDefault(player, 0) : 0;
        
        int remainingDaily = Math.max(0, dailyAllowance - usedToday);
        return remainingDaily + accumulated;
    }

    private static void consumeTime(UUID playerId, int ticksToConsume) {
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

    private static void checkAndWarnPlayer(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        
        if (playersWarned.contains(playerId)) {
            return;
        }
        if (player.hasPermissionLevel(4) && Configuration.INSTANCE.excludeOperators) {
            return;
        }

        int remaining = ticksRemaining(playerId);
        
        if (remaining <= WARNING_THRESHOLD_TICKS && remaining > 0) {
            int remainingSeconds = WARNING_THRESHOLD_TICKS / 20;
            int minutesLeft = remainingSeconds / 60;
            int secondsLeft = remainingSeconds % 60;
            
            String timeMessage;
            if (minutesLeft > 0) {
                if (secondsLeft > 0) {
                    timeMessage = minutesLeft + " minute" + (minutesLeft == 1 ? "" : "s") + 
                                 " and " + secondsLeft + " second" + (secondsLeft == 1 ? "" : "s");
                } else {
                    timeMessage = minutesLeft + " minute" + (minutesLeft == 1 ? "" : "s");
                }
            } else {
                timeMessage = secondsLeft + " second" + (secondsLeft == 1 ? "" : "s");
            }
            
            player.sendMessage(Text.literal("§6⚠ Warning: You have " + timeMessage + " remaining before you're disconnected!"), false);
            playersWarned.add(playerId);
            
            LOGGER.info("Warned player {} with {} ticks remaining", playerId, remaining);
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
                        checkAndWarnPlayer(player);
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

            for (UUID playerId : new HashSet<>(accumulatedTicks.keySet())) {
                if (ticksUsedToday.containsKey(playerId)) {
                    continue;
                }
                
                int leftover = dailyAllowance;
                int currentAccumulated = accumulatedTicks.get(playerId);
                int newAccumulated = Math.min(maxAccumulated, currentAccumulated + leftover);
                accumulatedTicks.put(playerId, newAccumulated);
                
                LOGGER.info("Player {} (offline) received {} leftover ticks, accumulated total: {}", 
                        playerId, leftover, newAccumulated);
            }

            Configuration.INSTANCE.playerAccumulatedTicks.clear();
            Configuration.INSTANCE.playerAccumulatedTicks.putAll(accumulatedTicks);
            Configuration.save();
        }
        
        ticksUsedToday.clear();
        playersWarned.clear();
        LOGGER.info("New day started - daily usage reset");
    }
}
