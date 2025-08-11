package lol.sylvie.parental.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import lol.sylvie.parental.ParentalControls;
import lol.sylvie.parental.config.Configuration;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.commons.lang3.time.DurationFormatUtils;
import java.util.UUID;

public class ParentalControlsCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("parental");

        root.then(CommandManager.literal("remaining").executes(ctx -> {
            ServerCommandSource source = ctx.getSource();

            ServerPlayerEntity player = source.getPlayerOrThrow();
            if (player.hasPermissionLevel(4) && Configuration.INSTANCE.excludeOperators) {
                source.sendError(Text.literal("You are immune to the time limit."));
                return 0;
            }

            UUID playerId = player.getUuid();
            int ticksRemaining = ParentalControls.ticksRemaining(playerId);
            String formatted = DurationFormatUtils.formatDuration((ticksRemaining / 20) * 1000L, "HH:mm:ss");
            StringBuilder message = new StringBuilder("You have §l" + formatted + "§r remaining.");

            if (Configuration.INSTANCE.allowTimeStacking) {
                int dailyAllowance = (int) (Configuration.INSTANCE.minutesAllowed * 60 * 20);
                int usedToday = ParentalControls.ticksUsedToday.getOrDefault(playerId, 0);
                int accumulated = ParentalControls.accumulatedTicks.getOrDefault(playerId, 0);
                
                int dailyRemaining = Math.max(0, dailyAllowance - usedToday);
                
                String dailyFormatted = DurationFormatUtils.formatDuration((dailyRemaining / 20) * 1000L, "HH:mm:ss");
                String accumulatedFormatted = DurationFormatUtils.formatDuration((accumulated / 20) * 1000L, "HH:mm:ss");
                
                message.append("\n§7Daily: §f").append(dailyFormatted);
                message.append(" §7| Stacked: §f").append(accumulatedFormatted);
            }

            source.sendMessage(Text.literal(message.toString()));
            return 1;
        }));

        root.then(CommandManager.literal("reload").requires(s -> s.hasPermissionLevel(4)).executes(ctx -> {
            ServerCommandSource source = ctx.getSource();
            if (Configuration.load()) {
                source.sendFeedback(() -> Text.literal("§aSuccessfully reloaded!"), true);
            } else {
                source.sendError(Text.literal("There was an error while trying to load the configuration! Check console for details."));
            }
            return 1;
        }));

        dispatcher.register(root);
    }
}
