/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.testrunner.TestResult.TestStatus;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

/** Unit tests for {@link TestRunResult} */
@RunWith(JUnit4.class)
public class TestRunResultTest {

    /** Check basic storing of results when events are coming in. */
    @Test
    public void testGetNumTestsInState() {
        TestDescription test = new TestDescription("FooTest", "testBar");
        TestRunResult result = new TestRunResult();
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        result.testStarted(test);
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        assertEquals(1, result.getNumTestsInState(TestStatus.INCOMPLETE));
        result.testEnded(test, Collections.emptyMap());
        assertEquals(1, result.getNumTestsInState(TestStatus.PASSED));
        assertEquals(0, result.getNumTestsInState(TestStatus.INCOMPLETE));
        // Ensure our test was recorded.
        assertNotNull(result.getTestResults().get(test));
    }

    /** Check basic storing of results when events are coming in and there is a test failure. */
    @Test
    public void testGetNumTestsInState_failed() {
        TestDescription test = new TestDescription("FooTest", "testBar");
        TestRunResult result = new TestRunResult();
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        result.testStarted(test);
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        assertEquals(1, result.getNumTestsInState(TestStatus.INCOMPLETE));
        result.testFailed(test, "I failed!");
        // Test status immediately switch to failure.
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        assertEquals(1, result.getNumTestsInState(TestStatus.FAILURE));
        assertEquals(0, result.getNumTestsInState(TestStatus.INCOMPLETE));
        result.testEnded(test, Collections.emptyMap());
        assertEquals(0, result.getNumTestsInState(TestStatus.PASSED));
        assertEquals(1, result.getNumTestsInState(TestStatus.FAILURE));
        assertEquals(0, result.getNumTestsInState(TestStatus.INCOMPLETE));
        // Ensure our test was recorded.
        assertNotNull(result.getTestResults().get(test));
    }

    /** Test that we are able to specify directly the start and end time of a test. */
    @Test
    public void testSpecifyElapsedTime() {
        TestDescription test = new TestDescription("FooTest", "testBar");
        TestRunResult result = new TestRunResult();
        result.testStarted(test, 5L);
        assertEquals(5L, result.getTestResults().get(test).getStartTime());
        result.testEnded(test, 25L, Collections.emptyMap());
        assertEquals(25L, result.getTestResults().get(test).getEndTime());
    }

    /**
     * Test that when a same {@link TestRunResult} is re-run (new testRunStart/End) we keep the
     * failure state, since we do not want to override it.
     */
    @Test
    public void testMultiRun() {
        TestRunResult result = new TestRunResult();
        // Initially not failed and not completed
        assertFalse(result.isRunFailure());
        assertFalse(result.isRunComplete());
        result.testRunStarted("run", 0);
        result.testRunFailed("failure");
        result.testRunEnded(0, Collections.emptyMap());
        assertTrue(result.isRunFailure());
        assertEquals("failure", result.getRunFailureMessage());
        assertTrue(result.isRunComplete());
        // If a re-run is triggered.
        result.testRunStarted("run", 0);
        // Not complete anymore, but still failed
        assertFalse(result.isRunComplete());
        assertTrue(result.isRunFailure());
        result.testRunEnded(0, Collections.emptyMap());
        assertTrue(result.isRunFailure());
        assertEquals("failure", result.getRunFailureMessage());
        assertTrue(result.isRunComplete());
    }

    /**
     * Test that when logging of files occurs during a test case in progress, files are associated
     * to the test case results.
     */
    @Test
    public void testLogSavedFile_testCases() {
        TestDescription test = new TestDescription("FooTest", "testBar");
        TestRunResult result = new TestRunResult();
        result.testStarted(test);
        // Check that there is no logged file at first.
        TestResult testRes = result.getTestResults().get(test);
        assertEquals(0, testRes.getLoggedFiles().size());
        result.testLogSaved("test", new LogFile("path", "url", LogDataType.TEXT));
        assertEquals(1, testRes.getLoggedFiles().size());
        result.testFailed(test, "failure");
        result.testLogSaved("afterFailure", new LogFile("path", "url", LogDataType.TEXT));
        assertEquals(2, testRes.getLoggedFiles().size());
        result.testEnded(test, Collections.emptyMap());
        // Once done, the results are still available.
        assertEquals(2, testRes.getLoggedFiles().size());
    }

    /**
     * Ensure that files logged from outside a test case (testStart/testEnd) are tracked by the run
     * itself.
     */
    @Test
    public void testLogSavedFile_runLogs() {
        TestRunResult result = new TestRunResult();
        result.testRunStarted("run", 1);
        result.testLogSaved("outsideTestCase", new LogFile("path", "url", LogDataType.TEXT));

        TestDescription test = new TestDescription("FooTest", "testBar");
        result.testStarted(test);
        // Check that there is no logged file at first.
        TestResult testRes = result.getTestResults().get(test);
        assertEquals(0, testRes.getLoggedFiles().size());

        result.testLogSaved("insideTestCase", new LogFile("path", "url", LogDataType.TEXT));
        result.testLogSaved("insideTestCase2", new LogFile("path", "url", LogDataType.TEXT));
        result.testEnded(test, Collections.emptyMap());
        result.testLogSaved("outsideTestCase2", new LogFile("path", "url", LogDataType.TEXT));
        // Once done, the results are still available and the test cases has its two files.
        assertEquals(2, testRes.getLoggedFiles().size());
        assertTrue(testRes.getLoggedFiles().containsKey("insideTestCase"));
        assertTrue(testRes.getLoggedFiles().containsKey("insideTestCase2"));
        // the Run has its file too
        assertEquals(2, result.getRunLoggedFiles().size());
        assertTrue(result.getRunLoggedFiles().containsKey("outsideTestCase"));
        assertTrue(result.getRunLoggedFiles().containsKey("outsideTestCase2"));
    }
}
