package world.landfall.sentinel;

public sealed interface DenialReason {
    record NotLinked(String linkCode) implements DenialReason {}
    record Quarantined(String reason, String timeRemaining, boolean permanent) implements DenialReason {}
    record TosNotAccepted(String version) implements DenialReason {}
    record DiscordLeft(String linkCode) implements DenialReason {}
    record NeedsRelink() implements DenialReason {}
    record ServerError() implements DenialReason {}
}
