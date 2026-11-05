package app.peakpic.b2b;

import com.caestro.server.domain.b2b.entity.B2bProject;
import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RuntimeBootstrap implements ApplicationRunner {
    private final RuntimeProperties properties;
    private final RuntimeBindingRepository bindings;
    private final B2bProjectRepository projects;
    private final UserRepository users;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Environment environment;
    public RuntimeBootstrap(RuntimeProperties properties, RuntimeBindingRepository bindings,
            B2bProjectRepository projects, UserRepository users, TransactionTemplate transactions, Clock clock, Environment environment) {
        this.properties = properties; this.bindings = bindings; this.projects = projects; this.users = users;
        this.transactions = transactions; this.clock = clock; this.environment = environment;
    }
    @Override public void run(ApplicationArguments args) {
        require("server.servlet.context-path", "/projects/" + properties.slug());
        require("fill.public-base-url", properties.publicBaseUrl());
        require("catalog.public-base-url", properties.publicBaseUrl());
        if (properties.environment().equals("prod")) {
            require("spring.jpa.hibernate.ddl-auto", "validate");
            require("fill.image-store", "s3"); require("catalog.asset-store", "s3");
            String jdbc = environment.getRequiredProperty("spring.datasource.url");
            if (!jdbc.matches("jdbc:mysql://[^/]+/b2b_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalStateException("a dedicated b2b_ MySQL database is required");
            }
        }
        if (environment.getProperty("fill.image-store", "s3").equals("s3")) {
            require("fill.s3.bucket", properties.bucket()); require("catalog.s3.bucket", properties.bucket());
            require("fill.s3.key-prefix", properties.prefix() + "/fills");
            require("catalog.s3.key-prefix", properties.prefix() + "/frames");
        }
        // Concurrent first-start replicas may race on the singleton row; retry in a fresh transaction.
        for (int attempt = 0; ; attempt++) {
            try { transactions.executeWithoutResult(ignored -> bind()); return; }
            catch (DataIntegrityViolationException race) { if (attempt >= 2) throw race; }
        }
    }
    private void bind() {
        var existing = bindings.findById(1);
        if (existing.isPresent()) {
            existing.get().requireMatch(properties);
            if (projects.count() != 1 || projects.findById(properties.projectId())
                    .filter(p -> p.getSlug().equals(properties.slug())).isEmpty()) {
                throw new IllegalStateException("runtime project missing or database contains foreign projects");
            }
            return;
        }
        if (projects.count() != 0 || users.count() != 0) throw new IllegalStateException("bootstrap requires an empty dedicated database");
        bindings.saveAndFlush(new RuntimeBinding(properties));
        var now = LocalDateTime.now(clock);
        projects.saveAndFlush(B2bProject.builder().id(properties.projectId()).slug(properties.slug())
                .name(properties.slug()).description("").status(B2bProject.Status.DRAFT).createdAt(now).updatedAt(now).build());
    }
    private void require(String name, String value) {
        if (!value.equals(environment.getProperty(name))) throw new IllegalStateException("invalid isolated setting: " + name);
    }
}
