package evalfixture;

import java.time.Clock;
import java.time.Instant;

public final class AuthService {
    private final Clock clock;

    public AuthService(Clock clock) {
        this.clock = clock;
    }

    public boolean authenticateToken(String token) {
        return token != null && token.startsWith("usr-")
                && Instant.now(clock).isBefore(Instant.parse("2099-01-01T00:00:00Z"));
    }
}
