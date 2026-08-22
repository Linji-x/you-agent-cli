package dev.youagent.tool;

public record ToolExecution(boolean success, String output, String errorCode) {
    public ToolExecution {
        output = output == null ? "" : output;
        errorCode = errorCode == null ? "" : errorCode;
    }

    public static ToolExecution success(String output) {
        return new ToolExecution(true, output, "");
    }

    public static ToolExecution failure(String code, String message) {
        return new ToolExecution(false, message, code);
    }

    public String toModelText() {
        return success ? output : "ERROR[" + errorCode + "]: " + output;
    }
}
