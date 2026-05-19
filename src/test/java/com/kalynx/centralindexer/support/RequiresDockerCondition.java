package com.kalynx.centralindexer.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.TimeUnit;

/**
 * JUnit 5 {@link ExecutionCondition} that enables test execution only when Docker is
 * available on the current machine.
 *
 * <p>Availability is determined by running {@code docker info} via {@link ProcessBuilder}.
 * A non-zero exit code or any {@link Exception} during the check causes the condition to
 * return {@code disabled} with a descriptive reason.
 *
 * <p>Do not reference this class directly — use the {@link RequiresDocker} annotation
 * instead.
 */
public class RequiresDockerCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");
    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled(
                    "Docker is not available — skipping integration test. " +
                    "Install Docker Desktop (macOS/Windows) or Docker Engine (Linux) to run this test.");

    /**
     * Evaluates whether Docker is available by running {@code docker info}.
     *
     * @param context the current extension context; not used
     * @return {@code enabled} when {@code docker info} exits 0; {@code disabled} otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return isDockerAvailable() ? ENABLED : DISABLED;
    }

    /**
     * Returns {@code true} if {@code docker info} completes with exit code 0.
     *
     * @return {@code true} when Docker is running and reachable
     */
    public boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}


