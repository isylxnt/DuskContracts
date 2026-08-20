package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationIntegrityTest {
    @TempDir Path temporary;

    @Test void rejectsTamperedMigrationHistory() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("tampered.db"))) {
            Migrations.migrate(connection, StorageConfig.Type.SQLITE);
            connection.createStatement().executeUpdate("UPDATE dc_schema_history SET checksum='tampered' WHERE version=2");
            assertThatThrownBy(() -> Migrations.migrate(connection, StorageConfig.Type.SQLITE))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("checksum mismatch");
        }
    }

    @Test void resumesAfterSchemaThreeDdlWasAppliedBeforeHistoryCommit() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("resume.db"))) {
            Migrations.migrate(connection, StorageConfig.Type.SQLITE);
            connection.createStatement().executeUpdate("DELETE FROM dc_schema_history WHERE version=3");
            Migrations.migrate(connection, StorageConfig.Type.SQLITE);
            try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM dc_schema_history WHERE version=3")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
}
