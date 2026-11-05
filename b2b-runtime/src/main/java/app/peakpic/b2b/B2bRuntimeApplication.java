package app.peakpic.b2b;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@SpringBootApplication(scanBasePackages = {"app.peakpic.b2b", "com.caestro.server"})
@EntityScan({"com.caestro.server.domain", "app.peakpic.b2b"})
@EnableJpaRepositories({"com.caestro.server.domain", "app.peakpic.b2b"})
@EnableJpaAuditing
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@EnableConfigurationProperties({RuntimeProperties.class, app.peakpic.b2b.auth.GuestAdmissionProperties.class})
public class B2bRuntimeApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--initialize-empty-db")) {
            try { DatabaseInitializer.initialize(System.getenv()); }
            catch (Exception failure) { throw new IllegalStateException("B2B database initialization failed", failure); }
            return;
        }
        SpringApplication.run(B2bRuntimeApplication.class, args);
    }
}
