package app.peakpic.b2b;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;

/** Explicit one-shot command for an EMPTY database, never a migration of the existing app. */
public final class DatabaseInitializer {
    private DatabaseInitializer() {}
    static void initialize(Map<String, String> env) throws Exception {
        String url = env.getOrDefault("B2B_DB_URL", "");
        String project = env.getOrDefault("B2B_PROJECT_ID", "");
        String runtime = env.getOrDefault("B2B_RUNTIME_ID", "");
        if (!project.matches("bp_[a-z0-9]{4,32}") || !runtime.matches("[a-z0-9]{12,32}")) {
            throw new IllegalArgumentException("project/runtime binding required");
        }
        String expected = "b2b_" + project.substring(3) + "_" + runtime;
        if (!url.matches("jdbc:mysql://[^/]+/" + expected + "(?:\\?.*)?")) {
            throw new IllegalArgumentException("initializer only accepts the generated dedicated database name");
        }
        try (var connection = DriverManager.getConnection(url, env.get("B2B_DB_USERNAME"), env.get("B2B_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            try (var database = statement.executeQuery("select database()")) {
                if (!database.next() || !expected.equals(database.getString(1))) throw new IllegalStateException("database name mismatch");
            }
            try (var lock = statement.executeQuery("select get_lock('peakpic_b2b_schema_init', 0)")) {
                if (!lock.next() || lock.getInt(1) != 1) throw new IllegalStateException("another schema initialization is running");
            }
            try (var tables = connection.getMetaData().getTables(expected, null, "%", new String[]{"TABLE"})) {
                if (tables.next()) throw new IllegalStateException("refusing to initialize a nonempty database");
            }
            try (InputStream resource = DatabaseInitializer.class.getResourceAsStream("/db/schema.sql")) {
                if (resource == null) throw new IllegalStateException("schema resource missing");
                for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                    if (!sql.isBlank()) statement.execute(sql);
                }
            }
            System.out.println("B2B empty database schema initialized; deploy the runtime next.");
        }
    }
}
