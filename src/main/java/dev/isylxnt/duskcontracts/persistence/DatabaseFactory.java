package dev.isylxnt.duskcontracts.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.isylxnt.duskcontracts.config.StorageConfig;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;

public final class DatabaseFactory {
    private DatabaseFactory() {}
    public static HikariDataSource create(JavaPlugin plugin, StorageConfig value) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("DuskContracts-Storage");
        config.setConnectionTimeout(value.connectionTimeoutMs());
        config.setValidationTimeout(value.validationTimeoutMs());
        config.setAutoCommit(true);
        switch (value.type()) {
            case SQLITE -> {
                File db = new File(plugin.getDataFolder(), value.file());
                config.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
                config.setDriverClassName("org.sqlite.JDBC");
                config.setMaximumPoolSize(1);
                config.addDataSourceProperty("busy_timeout", "5000");
            }
            case MYSQL -> {
                config.setJdbcUrl("jdbc:mysql://" + value.host() + ":" + value.port() + "/" + value.database()
                        + "?sslMode=" + mysqlTls(value.tlsMode()) + "&serverTimezone=UTC&characterEncoding=utf8");
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                credentials(config, value);
            }
            case MARIADB -> {
                config.setJdbcUrl("jdbc:mariadb://" + value.host() + ":" + value.port() + "/" + value.database()
                        + "?sslMode=" + mariaTls(value.tlsMode()) + "&useUnicode=true&characterEncoding=utf8");
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                credentials(config, value);
            }
        }
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }
    private static void credentials(HikariConfig config, StorageConfig value) {
        config.setUsername(value.username()); config.setPassword(value.password());
        config.setMaximumPoolSize(value.poolMaximum()); config.setMinimumIdle(Math.min(2, value.poolMaximum()));
    }
    private static String mysqlTls(StorageConfig.TlsMode mode) {
        return switch (mode) {
            case DISABLED -> "DISABLED";
            case PREFERRED -> "PREFERRED";
            case REQUIRED -> "REQUIRED";
            case VERIFY_IDENTITY -> "VERIFY_IDENTITY";
        };
    }
    private static String mariaTls(StorageConfig.TlsMode mode) {
        return switch (mode) {
            case DISABLED -> "disable";
            case PREFERRED -> "trust";
            case REQUIRED -> "trust";
            case VERIFY_IDENTITY -> "verify-full";
        };
    }
}
