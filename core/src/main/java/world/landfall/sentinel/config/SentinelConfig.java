package world.landfall.sentinel.config;

public class SentinelConfig {
    public MySQL mysql = new MySQL();
    public Discord discord = new Discord();
    public Tos tos = new Tos();
    public BypassServers bypassServers = new BypassServers();
    public Impersonation impersonation = new Impersonation();

    public static class MySQL {
        public String host = "localhost";
        public int port = 3306;
        public String database = "sentinel";
        public String username = "sentinel_user";
        public String password = "change_me";
        public String driverClassName = "com.mysql.cj.jdbc.Driver";
    }

    public static class Discord {
        public String token = "";
        public String linkedRole = ""; // Role ID to assign to linked players (optional)
        public String quarantineRole = ""; // Role ID that prevents login (optional)
        public String quarantineMessage = "Your account has been quarantined. Contact an administrator."; // Message shown to quarantined users
        public String[] staffRoles = new String[0]; // Role IDs that can use staff cmds (optional)
        public String tosAuditChannel = ""; // Channel ID to send ToS agreement confirmations (optional)
        public String moderationAuditChannel = ""; // Channel ID for moderation action logs (optional)
    }

    public static class Tos {
        public boolean enforcement = false; // Enable ToS enforcement
        public String version = "1.0.0"; // Current ToS version
        public String url = ""; // URL to full ToS document
        public String content = ""; // ToS content to display (supports multi-line)
        public boolean ipLogging = true; // Enable IP logging for login attempts
    }

    public static class BypassServers {
        public String[] servers = new String[0];
    }

    public static class Impersonation {
        public boolean enabled = false;
        public String[] allowedUsers = new String[0];
    }

}
