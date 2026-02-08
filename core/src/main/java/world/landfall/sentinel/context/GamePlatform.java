package world.landfall.sentinel.context;

public enum GamePlatform {
    MINECRAFT, HYTALE;

    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
