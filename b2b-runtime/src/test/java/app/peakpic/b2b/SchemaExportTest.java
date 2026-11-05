package app.peakpic.b2b;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import static org.assertj.core.api.Assertions.*;

/** Produces new-database DDL without connecting to any database. */
class SchemaExportTest {
    @Test void exportStandaloneMysqlSchema() throws Exception {
        Path target = Path.of("build/generated/b2b-schema.sql").toAbsolutePath();
        Files.createDirectories(target.getParent()); Files.deleteIfExists(target);
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPackagesToScan("com.caestro.server.domain", "app.peakpic.b2b");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "jakarta.persistence.schema-generation.database.action", "none",
                "jakarta.persistence.schema-generation.scripts.action", "create",
                "jakarta.persistence.schema-generation.scripts.create-target", target.toString()));
        factory.afterPropertiesSet(); factory.destroy();
        String sql = Files.readString(target);
        assertThat(sql).contains("create table b2b_runtime_binding", "create table fill_sessions", "create table b2b_projects");
        assertThat(sql).doesNotContain("create table payments", "create table signaling");
        assertThat(sql).contains("project_id", "runtime_id", "created_at", "frame_json");
    }
}
