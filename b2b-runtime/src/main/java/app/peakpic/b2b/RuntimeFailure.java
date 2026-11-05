package app.peakpic.b2b;

public class RuntimeFailure extends RuntimeException {
    private final int status;
    public RuntimeFailure(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
