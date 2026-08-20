package dev.isylxnt.duskcontracts.config;

public record StorageConfig(Type type, String file, String host, int port, String database, String username,
                            String password, TlsMode tlsMode, int poolMaximum, long connectionTimeoutMs, long validationTimeoutMs) {
    public enum Type { SQLITE, MYSQL, MARIADB }
    public enum TlsMode { DISABLED, PREFERRED, REQUIRED, VERIFY_IDENTITY }
}
