package dev.isylxnt.duskcontracts.persistence;

import java.sql.DriverManager;

public final class RuntimeDriverSmoke {
    private RuntimeDriverSmoke() { }

    public static void main(String[] args) throws Exception {
        Class.forName("com.zaxxer.hikari.HikariDataSource");
        Class.forName("org.sqlite.JDBC");
        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("org.mariadb.jdbc.Driver");
        DriverManager.getDriver("jdbc:mysql://localhost/test");
        DriverManager.getDriver("jdbc:mariadb://localhost/test");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new IllegalStateException("SQLite runtime library returned an invalid result");
            }
        }
    }
}
