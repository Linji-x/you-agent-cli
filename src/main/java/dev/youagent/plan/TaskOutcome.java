package dev.youagent.plan;

public record TaskOutcome(boolean success, String output) {
    public static TaskOutcome success(String output) {
        return new TaskOutcome(true, output);
    }

    public static TaskOutcome failure(String output) {
        return new TaskOutcome(false, output);
    }
}
