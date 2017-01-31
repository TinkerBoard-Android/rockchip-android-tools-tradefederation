/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.suite.checker;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

/**
 * Check if the pid of system_server has changed from before and after a module run. A new pid would
 * mean a runtime restart occurred during the module run.
 */
public class SystemServerStatusChecker implements ISystemStatusChecker {

    private String mSystemServerPid = null;

    /** {@inheritDoc} */
    @Override
    public boolean preExecutionCheck(ITestDevice device) throws DeviceNotAvailableException {
        mSystemServerPid = null;
        mSystemServerPid = device.executeShellCommand("pidof system_server");
        if (mSystemServerPid == null) {
            CLog.w("Failed to get system_server pid.");
            return false;
        }
        if (!checkValidPid(mSystemServerPid.trim())) {
            CLog.w(
                    "Invalid pid response found: '%s'. Skipping the system checker.",
                    mSystemServerPid);
            mSystemServerPid = null;
            return true;
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean postExecutionCheck(ITestDevice device) throws DeviceNotAvailableException {
        if (mSystemServerPid == null) {
            CLog.d("No valid known value of system_server pid, skipping system checker.");
            return true;
        }
        String tmpSystemServerPid = device.executeShellCommand("pidof system_server").trim();
        if (mSystemServerPid.equals(tmpSystemServerPid)) {
            return true;
        }
        CLog.w(
                "system_server has a different pid after the module run. from %s to %s",
                mSystemServerPid, tmpSystemServerPid);
        return false;
    }

    /** Validate that pid is an integer and not empty. */
    private boolean checkValidPid(String output) {
        if (output.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(output);
        } catch (NumberFormatException e) {
            return false;
        }

        return true;
    }
}
