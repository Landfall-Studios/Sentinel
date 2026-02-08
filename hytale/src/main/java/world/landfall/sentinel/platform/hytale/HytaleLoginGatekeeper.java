package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.DenialReason;
import world.landfall.sentinel.context.LoginContext;
import world.landfall.sentinel.context.LoginGatekeeper;

/**
 * Hytale-specific login gatekeeper.
 *
 * Unlike Velocity which can cancel the login event before connection,
 * Hytale has no cancellable login event. Instead, we kick the player
 * after they connect via playerRef.getPacketHandler().disconnect().
 *
 * Denial messages are rendered as plain ASCII text (no Adventure Components).
 * Hytale's disconnect handler only supports basic ASCII — no Unicode box-drawing or emoji.
 */
public class HytaleLoginGatekeeper implements LoginGatekeeper {

    @Override
    public void allowLogin(LoginContext ctx) {
        // No-op: Hytale connections proceed by default
    }

    @Override
    public void denyLogin(LoginContext ctx, DenialReason reason) {
        HytaleLoginContext hCtx = (HytaleLoginContext) ctx;
        String message = renderDenialMessage(reason);
        hCtx.getPlayerRef().getPacketHandler().disconnect(message);
    }

    @Override
    public boolean supportsBypassRouting() {
        return false;
    }

    private String renderDenialMessage(DenialReason reason) {
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
        return "An unknown error occurred.";
    }

    private String renderNotLinked(String code) {
        return "You must link your account to our Discord!\n" +
                "Your link code: " + code + "\n" +
                "How to link:\n" +
                "  1. Join our Discord server\n" +
                "  2. Run /link " + code + " in any channel\n" +
                "  3. You'll be able to join immediately!";
    }

    private String renderQuarantined(String reason, String timeRemaining, boolean permanent) {
        StringBuilder sb = new StringBuilder();
        sb.append("ACCOUNT QUARANTINED\n\n");
        sb.append("Reason: ").append(reason).append("\n");

        if (permanent) {
            sb.append("Duration: Permanent\n");
        } else {
            sb.append("Time remaining: ").append(timeRemaining).append("\n");
        }

        sb.append("\nContact an administrator for assistance.");
        return sb.toString();
    }

    private String renderTosNotAccepted(String version) {
        return "TERMS OF SERVICE UPDATE\n\n" +
                "We have updated our Terms of Service.\n" +
                "You must accept the new terms to continue playing.\n\n" +
                "To accept the terms:\n" +
                "  1. Go to our Discord server\n" +
                "  2. Run /tos in any channel\n" +
                "  3. Click the 'I Agree' button";
    }

    private String renderDiscordLeft(String code) {
        return "ACCOUNT NO LONGER LINKED\n\n" +
                "Your Discord account is no longer linked.\n\n" +
                "Your link code: " + code + "\n\n" +
                "To re-link your account:\n" +
                "  1. Join our Discord server\n" +
                "  2. Run /link " + code;
    }

    private String renderNeedsRelink() {
        return "ACCOUNT NEEDS RELINK\n\n" +
                "Your Discord account is no longer linked.\n" +
                "Please contact an administrator to relink your account.";
    }

    private String renderServerError() {
        return "SERVER ERROR\n\n" +
                "A server error occurred.\n" +
                "Please try again in a few moments.\n\n" +
                "If this issue persists, please contact an administrator.";
    }
}
