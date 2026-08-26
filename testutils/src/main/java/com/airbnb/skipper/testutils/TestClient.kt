package com.airbnb.skipper.testutils

/**
 * Interface for a test client to express action execution based on a given name. This interface is
 * designed for use in testing environments where it is necessary to simulate or verify that specific
 * actions are triggered during the test.
 *
 * The `action` method is invoked by test code to simulate the execution of a designated action,
 * enabling tests to validate that the correct code paths are activated under various conditions.
 * Mocks of this interface can be injected both into the code under test and into the test methods
 * themselves, providing a mechanism for verifying that the `action` method is called as expected.
 */
interface TestClient {
    fun action(actionName: String)
}
