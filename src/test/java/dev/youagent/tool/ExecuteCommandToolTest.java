package dev.youagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteCommandToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void boundsOutputWhileReadingInsteadOfBufferingItAll(@TempDir Path workspace) throws Exception {
        try (ToolRegistry tools = ToolRegistry.standard(workspace, Duration.ofSeconds(5))) {
            ToolExecution result = tools.execute(commandCall(javaCommand(LargeOutput.class)));

            assertTrue(result.success());
            assertTrue(result.output().contains("[truncated at 64000 bytes]"));
            assertTrue(result.output().length() < 65_000);
        }
    }

    @Test
    void timeoutTerminatesParentAndDescendantProcesses(@TempDir Path workspace) throws Exception {
        Path pidFile = workspace.resolve("child.pid");
        ToolExecution result;
        try (ToolRegistry tools = ToolRegistry.standard(workspace, Duration.ofMillis(800))) {
            List<String> command = new ArrayList<>(javaCommand(SpawningParent.class));
            command.add(pidFile.toString());
            result = tools.execute(commandCall(command));
        }

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.errorCode());
        assertTrue(Files.isRegularFile(pidFile));
        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        try {
            assertTrue(waitUntilDead(childPid, Duration.ofSeconds(3)), "descendant process remained alive");
        } finally {
            ProcessHandle.of(childPid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private ToolCall commandCall(List<String> command) {
        var arguments = mapper.createObjectNode();
        var array = arguments.putArray("command");
        command.forEach(array::add);
        return new ToolCall("command", "execute_command", arguments);
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ArrayList<>(List.of(executable, "-cp", classpath, mainClass.getName()));
    }

    private static boolean waitUntilDead(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(50);
            } else {
                return true;
            }
        }
        return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static final class LargeOutput {
        public static void main(String[] args) {
            System.out.print("x".repeat(250_000));
        }
    }

    public static final class SpawningParent {
        public static void main(String[] args) throws Exception {
            List<String> command = javaCommand(SleepingChild.class);
            Process child = new ProcessBuilder(command).start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            Thread.sleep(60_000);
        }
    }

    public static final class SleepingChild {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}
