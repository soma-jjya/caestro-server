package app.peakpic.b2b;

import jakarta.persistence.*;

@Entity
@Table(name = "b2b_runtime_binding")
public class RuntimeBinding {
    @Id private Integer id;
    @Column(nullable = false, length = 40) private String projectId;
    @Column(nullable = false, length = 40) private String slug;
    @Column(nullable = false, length = 32) private String runtimeId;
    @Column(nullable = false, length = 10) private String environment;
    protected RuntimeBinding() {}
    public RuntimeBinding(RuntimeProperties p) {
        id = 1; projectId = p.projectId(); slug = p.slug(); runtimeId = p.runtimeId(); environment = p.environment();
    }
    public void requireMatch(RuntimeProperties p) {
        if (!projectId.equals(p.projectId()) || !slug.equals(p.slug()) || !runtimeId.equals(p.runtimeId())
                || !environment.equals(p.environment())) throw new IllegalStateException("database belongs to another runtime");
    }
}
