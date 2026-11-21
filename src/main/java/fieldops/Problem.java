package fieldops;

/** A request error whose message can be shown to the person making the request. */
public final class Problem extends RuntimeException {
    public final int status;

    public Problem(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
