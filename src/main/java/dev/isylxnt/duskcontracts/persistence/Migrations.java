package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.config.StorageConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.List;

public final class Migrations {
    public static final int CURRENT = 3;
    private Migrations() {}

    public static void migrate(Connection connection, StorageConfig.Type dialect) throws SQLException {
        if (dialect == StorageConfig.Type.SQLITE) configureSqlite(connection);
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS dc_schema_history (version INTEGER PRIMARY KEY, description VARCHAR(200) NOT NULL, installed_at BIGINT NOT NULL, checksum VARCHAR(64) NOT NULL)");
        }
        verifyHistory(connection);
        int installed = installed(connection);
        if (installed < 1) applyV1(connection, dialect);
        if (installed < 2) applyV2(connection);
        if (installed < 3) applyV3(connection);
        if (installed > CURRENT) throw new SQLException("Database schema " + installed + " is newer than supported " + CURRENT);
    }

    private static int installed(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM dc_schema_history")) { return rs.next() ? rs.getInt(1) : 0; }
    }

    private static void verifyHistory(Connection c) throws SQLException {
        Map<Integer, String> expected = Map.of(1, "builtin-v1", 2, "builtin-v2", 3, "builtin-v3");
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery("SELECT version,checksum FROM dc_schema_history")) {
            while (rs.next()) {
                int version = rs.getInt(1);
                String checksum = rs.getString(2);
                String known = expected.get(version);
                if (known != null && !known.equals(checksum))
                    throw new SQLException("Database migration checksum mismatch at version " + version);
            }
        }
    }

    private static void applyV1(Connection c, StorageConfig.Type dialect) throws SQLException {
        String blob = dialect == StorageConfig.Type.SQLITE ? "BLOB" : "LONGBLOB";
        List<String> sql = List.of(
            "CREATE TABLE IF NOT EXISTS dc_contracts (id VARCHAR(36) PRIMARY KEY, short_id VARCHAR(16) NOT NULL UNIQUE, creator_id VARCHAR(36) NOT NULL, creator_name VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, status VARCHAR(32) NOT NULL, material VARCHAR(128) NOT NULL, match_mode VARCHAR(16) NOT NULL, total_amount BIGINT NOT NULL, delivered_amount BIGINT NOT NULL DEFAULT 0, reward_type VARCHAR(16) NOT NULL, reward_minor BIGINT NOT NULL DEFAULT 0, target_id VARCHAR(36) NULL, fulfillment_mode VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS dc_contract_items (contract_id VARCHAR(36) NOT NULL, role VARCHAR(16) NOT NULL, payload " + blob + " NOT NULL, checksum VARCHAR(64) NOT NULL, PRIMARY KEY(contract_id, role), FOREIGN KEY(contract_id) REFERENCES dc_contracts(id))",
            "CREATE TABLE IF NOT EXISTS dc_escrow_assets (id VARCHAR(36) PRIMARY KEY, contract_id VARCHAR(36) NOT NULL, asset_type VARCHAR(16) NOT NULL, amount_minor BIGINT NOT NULL DEFAULT 0, item_payload " + blob + " NULL, state VARCHAR(32) NOT NULL, operation_id VARCHAR(36) NOT NULL UNIQUE, created_at BIGINT NOT NULL, FOREIGN KEY(contract_id) REFERENCES dc_contracts(id))",
            "CREATE TABLE IF NOT EXISTS dc_operations (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(160) NOT NULL UNIQUE, operation_type VARCHAR(32) NOT NULL, state VARCHAR(32) NOT NULL, actor_id VARCHAR(36) NULL, contract_id VARCHAR(36) NULL, correlation_id VARCHAR(64) NOT NULL, evidence TEXT NULL, admin_note TEXT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, asset_minor BIGINT NOT NULL DEFAULT 0, asset_payload " + blob + " NULL, asset_owner_id VARCHAR(36) NULL)",
            "CREATE TABLE IF NOT EXISTS dc_contributions (id VARCHAR(36) PRIMARY KEY, operation_id VARCHAR(36) NOT NULL UNIQUE, contract_id VARCHAR(36) NOT NULL, contributor_id VARCHAR(36) NOT NULL, contributor_name VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, payout_minor BIGINT NOT NULL, created_at BIGINT NOT NULL, item_payload " + blob + " NOT NULL, FOREIGN KEY(contract_id) REFERENCES dc_contracts(id))",
            "CREATE TABLE IF NOT EXISTS dc_claims (id VARCHAR(36) PRIMARY KEY, recipient_id VARCHAR(36) NOT NULL, contract_id VARCHAR(36) NULL, operation_id VARCHAR(36) NOT NULL, claim_type VARCHAR(32) NOT NULL, state VARCHAR(32) NOT NULL, money_minor BIGINT NOT NULL DEFAULT 0, item_payload " + blob + " NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, failure_reason TEXT NULL, version BIGINT NOT NULL DEFAULT 0, UNIQUE(operation_id, claim_type, recipient_id))",
            "CREATE TABLE IF NOT EXISTS dc_audit_log (id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NULL, contract_id VARCHAR(36) NULL, operation_id VARCHAR(36) NULL, action VARCHAR(64) NOT NULL, details TEXT NULL, created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS dc_player_preferences (player_id VARCHAR(36) PRIMARY KEY, notifications INTEGER NOT NULL DEFAULT 1, updated_at BIGINT NOT NULL)"
        );
        boolean previous = c.getAutoCommit(); c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            for (String statement : sql) s.executeUpdate(statement);
            createIndex(c, "dc_contracts_browse_idx", "dc_contracts", "status, expires_at, created_at");
            createIndex(c, "dc_contracts_creator_idx", "dc_contracts", "creator_id, status");
            createIndex(c, "dc_claims_recipient_idx", "dc_claims", "recipient_id, state, created_at");
            createIndex(c, "dc_operations_state_idx", "dc_operations", "state, updated_at");
            createIndex(c, "dc_contributions_player_idx", "dc_contributions", "contributor_id, created_at");
            s.executeUpdate("INSERT INTO dc_schema_history(version, description, installed_at, checksum) VALUES (1, 'initial transactional schema', " + Instant.now().toEpochMilli() + ", 'builtin-v1')");
            c.commit();
        } catch (SQLException ex) { c.rollback(); throw ex; }
        finally { c.setAutoCommit(previous); }
    }
    private static void configureSqlite(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static void applyV2(Connection c) throws SQLException {
        List<String> sql = List.of(
            "CREATE TABLE IF NOT EXISTS dc_participations (contract_id VARCHAR(36) NOT NULL, player_id VARCHAR(36) NOT NULL, joined_at BIGINT NOT NULL, PRIMARY KEY(contract_id, player_id), FOREIGN KEY(contract_id) REFERENCES dc_contracts(id))"
        );
        boolean previous = c.getAutoCommit(); c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            for (String statement : sql) s.executeUpdate(statement);
            createIndex(c, "dc_participations_player_idx", "dc_participations", "player_id, joined_at");
            s.executeUpdate("INSERT INTO dc_schema_history(version, description, installed_at, checksum) VALUES (2, 'assassination participations', " + Instant.now().toEpochMilli() + ", 'builtin-v2')");
            c.commit();
        } catch (SQLException ex) { c.rollback(); throw ex; }
        finally { c.setAutoCommit(previous); }
    }

    private static void applyV3(Connection c) throws SQLException {
        List<String> sql = List.of(
            "CREATE TABLE IF NOT EXISTS dc_assassination_kills (id VARCHAR(36) PRIMARY KEY, killer_id VARCHAR(36) NOT NULL, victim_id VARCHAR(36) NOT NULL, completed_at BIGINT NOT NULL)"
        );
        boolean previous = c.getAutoCommit(); c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            for (String statement : sql) s.executeUpdate(statement);
            createIndex(c, "dc_assassination_kills_pair_idx", "dc_assassination_kills", "killer_id, victim_id, completed_at");
            createIndex(c, "dc_assassination_kills_time_idx", "dc_assassination_kills", "completed_at");
            createIndex(c, "dc_audit_created_idx", "dc_audit_log", "created_at");
            s.executeUpdate("INSERT INTO dc_schema_history(version, description, installed_at, checksum) VALUES (3, 'maintenance and assassination anti-farming', " + Instant.now().toEpochMilli() + ", 'builtin-v3')");
            c.commit();
        } catch (SQLException ex) { c.rollback(); throw ex; }
        finally { c.setAutoCommit(previous); }
    }

    private static void createIndex(Connection c, String index, String table, String columns) throws SQLException {
        if (indexExists(c, table, index)) return;
        try (Statement statement = c.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + index + " ON " + table + "(" + columns + ")");
        }
    }

    private static boolean indexExists(Connection c, String table, String expected) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(java.util.Locale.ROOT), table.toLowerCase(java.util.Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, candidate, false, false)) {
                while (rs.next()) if (expected.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }
}
