package com.kalynx.centralindexer.it.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RequiresDockerCondition}.
 */
class RequiresDockerConditionTest {

    @Test
    void evaluateExecutionCondition_dockerAvailable_conditionEnabled() {
        RequiresDockerCondition condition = new AlwaysAvailableCondition();
        ExtensionContext context = mock(ExtensionContext.class);
        assertFalse(condition.evaluateExecutionCondition(context).isDisabled());
    }

    @Test
    void evaluateExecutionCondition_dockerUnavailable_conditionDisabled() {
        RequiresDockerCondition condition = new NeverAvailableCondition();
        ExtensionContext context = mock(ExtensionContext.class);
        assertTrue(condition.evaluateExecutionCondition(context).isDisabled());
    }

    private static final class AlwaysAvailableCondition extends RequiresDockerCondition {
        @Override
        public boolean isDockerAvailable() {
            return true;
        }
    }

    private static final class NeverAvailableCondition extends RequiresDockerCondition {
        @Override
        public boolean isDockerAvailable() {
            return false;
        }
    }
}


