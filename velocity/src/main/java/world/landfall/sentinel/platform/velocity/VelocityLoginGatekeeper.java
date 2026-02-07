package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.DenialReason;
import world.landfall.sentinel.context.LoginContext;
import world.landfall.sentinel.context.LoginGatekeeper;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Velocity-specific login gatekeeper that renders DenialReason into styled Adventure Components
 * and calls event.setResult() to allow/deny logins on a Velocity proxy.
 */
public class VelocityLoginGatekeeper implements LoginGatekeeper {

    @Override
    public void allowLogin(LoginContext ctx) {
        LoginEvent event = ((VelocityLoginContext) ctx).getEvent();
        event.setResult(ComponentResult.allowed());
    }

    @Override
    public void denyLogin(LoginContext ctx, DenialReason reason) {
        LoginEvent event = ((VelocityLoginContext) ctx).getEvent();
        Component message = renderDenialMessage(reason);
        event.setResult(ComponentResult.denied(message));
    }

    @Override
    public boolean supportsBypassRouting() {
        return true;
    }

    private Component renderDenialMessage(DenialReason reason) {
        if (reason instanceof DenialReason.NotLinked r) {
            return renderNotLinked(r.linkCode());
        } else if (reason instanceof DenialReason.Quarantined r) {
            return renderQuarantined(r.reason(), r.timeRemaining(), r.permanent());
        } else if (reason instanceof DenialReason.TosNotAccepted r) {
            return renderTosNotAccepted(r.version());
        } else if (reason instanceof DenialReason.DiscordLeft r) {
            return renderDiscordLeft(r.linkCode());
        } else if (reason instanceof DenialReason.NeedsRelink r) {
            return renderNeedsRelink();
        } else if (reason instanceof DenialReason.ServerError r) {
            return renderServerError();
        }
        return Component.text("An unknown error occurred.").color(NamedTextColor.RED);
    }

    private Component renderNotLinked(String code) {
        return Component.text()
                .append(Component.text("⚠  ACCOUNT NOT LINKED\n")
                        .color(NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("This Minecraft account must be linked to Discord.\n\n")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("Your link code: ")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(code)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("\n\n🔗 How to link:\n")
                        .color(NamedTextColor.GREEN))
                .append(Component.text("   1. Join our Discord server\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("   2. Run ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("/link " + code)
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" in any channel\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("   3. You'll be able to join immediately!\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .color(NamedTextColor.GRAY))
                .build();
    }

    private Component renderQuarantined(String reason, String timeRemaining, boolean permanent) {
        var builder = Component.text();
        builder.append(Component.text("🚫 Your account is quarantined\n\n")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("Reason: ").color(NamedTextColor.YELLOW))
                .append(Component.text(reason + "\n").color(NamedTextColor.WHITE));

        if (permanent) {
            builder.append(Component.text("Duration: ")
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text("Permanent\n")
                            .color(NamedTextColor.RED)
                            .decorate(TextDecoration.BOLD));
        } else {
            builder.append(Component.text("Time remaining: ")
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(timeRemaining + "\n")
                            .color(NamedTextColor.GREEN));
        }

        builder.append(Component.text("\nContact an administrator for assistance.")
                .color(NamedTextColor.GRAY));

        return builder.build();
    }

    private Component renderTosNotAccepted(String version) {
        return Component.text()
                .append(Component.text("⚠  TERMS OF SERVICE UPDATE\n")
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("We have updated our Terms of Service.\n")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("You must accept the new terms to continue playing.\n\n")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("To accept the terms:\n")
                        .color(NamedTextColor.AQUA))
                .append(Component.text("   1. Go to our Discord server\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("   2. Run ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("/tos")
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" in any channel\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("   3. Click the 'I Agree' button\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .color(NamedTextColor.GRAY))
                .build();
    }

    private Component renderDiscordLeft(String code) {
        return Component.text()
                .append(Component.text("⚠  ACCOUNT NO LONGER LINKED\n")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("Your Discord account is no longer linked.\n\n")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("Your link code: ")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(code)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("\n\nTo re-link your account:\n")
                        .color(NamedTextColor.GREEN))
                .append(Component.text("   1. Join our Discord server\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("   2. Run ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("/link " + code)
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .color(NamedTextColor.GRAY))
                .build();
    }

    private Component renderNeedsRelink() {
        return Component.text("Your Discord account is no longer linked.\n" +
                "Please contact an administrator to relink your account.");
    }

    private Component renderServerError() {
        return Component.text()
                .append(Component.text("❌  SERVER ERROR\n")
                        .color(NamedTextColor.DARK_RED)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("A server error occurred.\n")
                        .color(NamedTextColor.RED))
                .append(Component.text("Please try again in a few moments.\n\n")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("If this issue persists, please contact an administrator.\n\n")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .color(NamedTextColor.GRAY))
                .build();
    }
}
