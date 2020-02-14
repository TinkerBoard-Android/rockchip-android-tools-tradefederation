/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static org.mockito.Mockito.mock;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.ddmlib.testrunner.TestResult.TestStatus;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/** Unit tests for {@link ConsoleResultReporterTest} */
@RunWith(JUnit4.class)
public class ConsoleResultReporterTest {
    private ConsoleResultReporter mResultReporter;
    private TestDescription mTest;
    private ByteArrayOutputStream mOutput;

    @Before
    public void setUp() throws IOException, ConfigurationException {
        mOutput = new ByteArrayOutputStream();
        mTest = new TestDescription("FooTest", "testFoo");
        mResultReporter = new ConsoleResultReporter(new PrintStream(mOutput, true));
        mResultReporter.invocationStarted(mock(IInvocationContext.class));
    }

    @Test
    public void testPassed() throws IOException {
        mResultReporter.testResult(mTest, createTestResult(TestStatus.PASSED));
        Truth.assertThat(mOutput.toString()).contains("FooTest#testFoo");
    }

    @Test
    public void testPassed_suppressedPassed() throws ConfigurationException {
        new OptionSetter(mResultReporter).setOptionValue("suppress-passed-tests", "true");
        mResultReporter.testResult(mTest, createTestResult(TestStatus.PASSED));
        Truth.assertThat(mOutput.toString()).hasLength(0);
    }

    @Test
    public void testFailed() throws IOException {
        TestResult result = createTestResult(TestStatus.FAILURE);
        result.setStackTrace("this is a trace");
        mResultReporter.testResult(mTest, result);

        Truth.assertThat(mOutput.toString()).contains("FooTest#testFoo");
        Truth.assertThat(mOutput.toString()).contains("this is a trace");
    }

    @Test
    public void testFailed_suppressPassed() throws ConfigurationException {
        new OptionSetter(mResultReporter).setOptionValue("suppress-passed-tests", "true");
        TestResult result = createTestResult(TestStatus.FAILURE);
        result.setStackTrace("this is a trace");
        mResultReporter.testResult(mTest, result);

        Truth.assertThat(mOutput.toString()).contains("FooTest#testFoo");
        Truth.assertThat(mOutput.toString()).contains("this is a trace");
    }

    @Test
    public void testSummary() throws IOException {
        mResultReporter.testResult(mTest, createTestResult(TestStatus.PASSED));
        mResultReporter.invocationEnded(0);
        Truth.assertThat(mOutput.toString()).contains("results: 1 Tests 1 Passed");
    }

    private TestResult createTestResult(TestStatus status) {
        TestResult result = new TestResult();
        result.setStatus(status);
        return result;
    }
}
