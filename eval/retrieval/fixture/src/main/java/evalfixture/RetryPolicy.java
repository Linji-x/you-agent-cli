package evalfixture;

public final class RetryPolicy {
    public boolean shouldRetry(int attempt, int maximumAttempts) {
        return attempt >= 0 && attempt < maximumAttempts;
    }
}
