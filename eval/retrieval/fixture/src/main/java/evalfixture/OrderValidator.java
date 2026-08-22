package evalfixture;

import java.math.BigDecimal;

public final class OrderValidator {
    public void validateTotal(BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            throw new IllegalArgumentException("order total must be positive");
        }
    }
}
