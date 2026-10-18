package dev.projecthim.him.director;

/**
 * Server wide token bucket limiting how much horror happens in total, independent of the number of
 * players. It keeps a busy server from turning into a haunted house where something happens every
 * few seconds.
 */
public final class GlobalBudget {

    private double capacity;
    private double refillPerMinute;
    private double tokens;
    private long updatedAt;

    public GlobalBudget(double capacity, double refillPerMinute, long now) {
        configure(capacity, refillPerMinute);
        this.tokens = capacity;
        this.updatedAt = now;
    }

    public void configure(double capacity, double refillPerMinute) {
        this.capacity = Math.max(0.1, capacity);
        this.refillPerMinute = Math.max(0, refillPerMinute);
        this.tokens = Math.min(this.tokens, this.capacity);
    }

    private void refill(long now) {
        if (now > updatedAt) {
            tokens = Math.min(capacity, tokens + (now - updatedAt) / 60_000.0 * refillPerMinute);
            updatedAt = now;
        }
    }

    public static double cost(double intensity) {
        return 0.4 + intensity;
    }

    public boolean canAfford(double cost, long now) {
        refill(now);
        return tokens >= cost;
    }

    public boolean tryConsume(double cost, long now) {
        refill(now);
        if (tokens < cost) {
            return false;
        }
        tokens -= cost;
        return true;
    }

    /** Returns tokens for an event that was chosen but could not actually start. */
    public void refund(double cost) {
        tokens = Math.min(capacity, tokens + cost);
    }

    public double tokens(long now) {
        refill(now);
        return tokens;
    }

    public double capacity() {
        return capacity;
    }
}
