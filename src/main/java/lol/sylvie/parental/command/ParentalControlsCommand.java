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

            int ticksRemaining = ParentalControls.ticksRemaining(player.getUuid());
            String formatted = DurationFormatUtils.formatDuration((ticksRemaining / 20) * 1000L, "HH:mm:ss");
            source.sendMessage(Text.literal("You have §l" + formatted + "§r remaining."));
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
