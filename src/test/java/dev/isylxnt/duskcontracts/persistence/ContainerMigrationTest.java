package dev.isylxnt.duskcontracts.persistence;

import dev.isylxnt.duskcontracts.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContainerMigrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.2").withDatabaseName("dc");
    @Container static final MariaDBContainer<?> MARIA=new MariaDBContainer<>("mariadb:11.5.2").withDatabaseName("dc");
    @Test void migratesMysql() throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())){Migrations.migrate(c,StorageConfig.Type.MYSQL);assertThat(Migrations.CURRENT).isEqualTo(3);}}
    @Test void migratesMariaDb() throws Exception{try(var c=DriverManager.getConnection(MARIA.getJdbcUrl(),MARIA.getUsername(),MARIA.getPassword())){Migrations.migrate(c,StorageConfig.Type.MARIADB);assertThat(Migrations.CURRENT).isEqualTo(3);}}
}
