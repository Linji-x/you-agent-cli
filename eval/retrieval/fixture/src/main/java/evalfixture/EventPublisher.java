package evalfixture;

public final class EventPublisher {
    public void publish(OrderEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
    }

    public record OrderEvent(String orderId) {
    }
}
