package com.kalynx.centralindexer.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JUnit 5 composed annotation that skips the annotated test class when Docker is not
 * available on the current machine.
 *
 * <p>Apply to any integration test class that depends on {@link PostgresTestContainer}.
 * When {@code docker info} exits non-zero (Docker not installed or not running), the
 * entire class is skipped with a human-readable reason instead of failing with a
 * confusing error.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDockerCondition.class)
public @interface RequiresDocker {
}

