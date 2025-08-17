package com.confect1on.sentinel.config;

public class SentinelConfig {
    public MySQL mysql = new MySQL();
    public Discord discord = new Discord();
    public BypassServers bypassServers = new BypassServers();
    public Impersonation impersonation = new Impersonation();

    public static class MySQL {
        public String host = "localhost";
        public int port = 3306;
        public String database = "sentinel";
        public String username = "sentinel_user";
        public String password = "change_me";
    }

    public static class Discord {
        public String token = "";
        public String linkedRole = ""; // Role ID to assign to linked players (optional)
        public String quarantineRole = ""; // Role ID that prevents login (optional)
        public String quarantineMessage = "Your account has been quarantined. Contact an administrator."; // Message shown to quarantined users
        public String[] staffRoles = new String[0]; // Role IDs that can use staff cmds (optional)
    }

    public static class BypassServers {
        public String[] servers = new String[0];
    }

    public static class Impersonation {
        public boolean enabled = false;
        public String[] allowedUsers = new String[0];
    }
}
