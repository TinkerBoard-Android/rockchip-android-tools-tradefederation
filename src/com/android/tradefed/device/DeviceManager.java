/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.device;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.CommandResult.CommandStatus;

import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@inheritDoc}
 */
public class DeviceManager implements IDeviceManager {

    private static final String LOG_TAG = "DeviceManager";

    /** max wait time in ms for fastboot devices command to complete */
    private static final long FASTBOOT_CMD_TIMEOUT = 1 * 60 * 1000;
    /**  time to wait in ms between  fastboot devices requests */
    private static final long FASTBOOT_POLL_WAIT_TIME = 10*1000;

    private static DeviceManager sInstance;

    /** A thread-safe map that tracks the devices currently allocated for testing.*/
    private Map<String, IManagedTestDevice> mAllocatedDeviceMap;
    /** A FIFO, thread-safe queue for holding devices visible on adb available for testing */
    private LinkedBlockingQueue<IDevice> mAvailableDeviceQueue;
    private IAndroidDebugBridge mAdbBridge;
    private final ManagedDeviceListener mManagedDeviceListener;
    private final FastbootMonitor mFastbootMonitor;

    /**
     * Package-private constructor, should only be used by this class and its associated unit test.
     * Use {@link #getInstance()} instead.
     */
    DeviceManager() {
        // use Hashtable since it is synchronized
        mAllocatedDeviceMap = new Hashtable<String, IManagedTestDevice>();
        // use LinkedBlockingQueue since it supports unlimited capacity
        mAvailableDeviceQueue = new LinkedBlockingQueue<IDevice>();
        mAdbBridge = createAdbBridge();
        mAdbBridge.init(false /* client support */);
        for (IDevice device : mAdbBridge.getDevices()) {
            addAvailableDevice(device);
        }
        mManagedDeviceListener = new ManagedDeviceListener();
        mAdbBridge.addDeviceChangeListener(mManagedDeviceListener);
        mFastbootMonitor = new FastbootMonitor();
        startFastbootMonitor();
    }

    /**
     * Start fastboot monitoring.
     * <p/>
     * Exposed for unit testing.
     */
    void startFastbootMonitor() {
        mFastbootMonitor.start();
    }

    private void addAvailableDevice(IDevice device) {
        try {
            mAvailableDeviceQueue.put(device);
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "interrupted while adding device");
            Log.e(LOG_TAG, e);
        }
    }

    /**
     * Return the {@link IDeviceManager} singleton, creating if necessary.
     */
    public synchronized static IDeviceManager getInstance() {
        if (sInstance == null) {
            sInstance = new DeviceManager();
        }
        return sInstance;
    }

    /**
     * {@inheritDoc}
     */
    public ITestDevice allocateDevice(IDeviceRecovery recovery) {
        IDevice allocatedDevice = takeAvailableDevice();
        if (allocatedDevice == null) {
            return null;
        }
        return createTestDevice(allocatedDevice, recovery);
    }

    /**
     * Retrieves and removes a IDevice from the available device queue, waiting indefinitely if
     * necessary until an IDevice becomes available.
     *
     * @return the {@link IDevice} or <code>null</code> if interrupted
     */
    private IDevice takeAvailableDevice() {
        try {
            return mAvailableDeviceQueue.take();
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "interrupted while taking device");
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public ITestDevice allocateDevice(IDeviceRecovery recovery, long timeout) {
        IDevice allocatedDevice = pollAvailableDevice(timeout);
        if (allocatedDevice == null) {
            return null;
        }
        return createTestDevice(allocatedDevice, recovery);
    }

    /**
     * Retrieves and removes a IDevice from the available device queue, waiting for timeout if
     * necessary until an IDevice becomes available.
     *
     * @param timeout the number of ms to wait for device
     *
     * @return the {@link IDevice} or <code>null</code> if interrupted
     */
    private IDevice pollAvailableDevice(long timeout) {
        try {
            return mAvailableDeviceQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "interrupted while polling for device");
            return null;
        }
    }

    private ITestDevice createTestDevice(IDevice allocatedDevice, IDeviceRecovery recovery) {
        // TODO: make background logcat capture optional
        IManagedTestDevice testDevice =  new TestDevice(allocatedDevice, recovery,
                new DeviceStateMonitor(allocatedDevice));
        testDevice.startLogcat();
        mAllocatedDeviceMap.put(allocatedDevice.getSerialNumber(), testDevice);
        return testDevice;
    }

    /**
     * Creates the {@link IAndroidDebugBridge} to use.
     * <p/>
     * Exposed so tests can mock this.
     * @returns the {@link IAndroidDebugBridge}
     */
    synchronized IAndroidDebugBridge createAdbBridge() {
        return new AndroidDebugBridgeWrapper();
    }

    /**
     * {@inheritDoc}
     */
    public void freeDevice(ITestDevice device) {
        if (device instanceof IManagedTestDevice) {
            ((IManagedTestDevice)device).stopLogcat();
        }
        if (mAllocatedDeviceMap.remove(device.getSerialNumber()) == null) {
            Log.w(LOG_TAG, String.format("freeDevice called with unallocated device %s",
                        device.getSerialNumber()));
        } else {
            addAvailableDevice(device.getIDevice());
        }

    }

    /**
     * {@inheritDoc}
     */
    public void terminate() {
        mAdbBridge.removeDeviceChangeListener(mManagedDeviceListener);
        mAdbBridge.terminate();
        mFastbootMonitor.terminate();
    }

    private class ManagedDeviceListener implements IDeviceChangeListener {

        /**
         * {@inheritDoc}
         */
        public void deviceChanged(IDevice device, int changeMask) {
            IManagedTestDevice testDevice = mAllocatedDeviceMap.get(device.getSerialNumber());
            if (testDevice != null && (changeMask & IDevice.CHANGE_STATE) != 0) {
                TestDeviceState newState = TestDeviceState.getStateByDdms(device.getState());
                testDevice.setDeviceState(newState);
            }
        }

        /**
         * {@inheritDoc}
         */
        public void deviceConnected(IDevice device) {
            IManagedTestDevice testDevice = mAllocatedDeviceMap.get(device.getSerialNumber());
            if (testDevice == null) {
                Log.i(LOG_TAG, String.format("Detected new device %s", device.getSerialNumber()));
                addAvailableDevice(device);
            } else {
                // this device is known already. However DDMS will allocate a new IDevice, so need
                // to update the TestDevice record with the new device
                Log.d(LOG_TAG, String.format("Updating IDevice for device %s",
                        device.getSerialNumber()));
                testDevice.setIDevice(device);
                TestDeviceState newState = TestDeviceState.getStateByDdms(device.getState());
                testDevice.setDeviceState(newState);
            }
        }

        /**
         * {@inheritDoc}
         */
        public void deviceDisconnected(IDevice disconnectedDevice) {
            Log.i(LOG_TAG, String.format("Detected device disconnect %s",
                    disconnectedDevice.getSerialNumber()));
            mAvailableDeviceQueue.remove(disconnectedDevice);
            IManagedTestDevice testDevice = mAllocatedDeviceMap.get(
                    disconnectedDevice.getSerialNumber());
            if (testDevice != null) {
                testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
            }
        }

    }

    private class FastbootMonitor extends Thread {

        private boolean mQuit = false;

        public void terminate() {
            mQuit = true;
        }

        @Override
        public void run() {
            while (!mQuit) {
                CommandResult fastbootResult = RunUtil.runTimedCmd(FASTBOOT_CMD_TIMEOUT, "fastboot",
                        "devices");
                if (fastbootResult.getStatus() == CommandStatus.SUCCESS) {
                    Log.v(LOG_TAG, String.format("fastboot devices returned %s",
                            fastbootResult.getStdout()));
                    Set<String> serials = DeviceManager.getDevicesOnFastboot(
                            fastbootResult.getStdout());
                    for (String serial: serials) {
                        IManagedTestDevice testDevice = mAllocatedDeviceMap.get(serial);
                        if (testDevice != null) {
                            testDevice.setDeviceState(TestDeviceState.FASTBOOT);
                        }
                    }
                    // now update devices that are no longer on fastboot
                    synchronized (mAllocatedDeviceMap) {
                        for (IManagedTestDevice testDevice : mAllocatedDeviceMap.values()) {
                            if (!serials.contains(testDevice.getSerialNumber()) &&
                                    testDevice.getDeviceState().equals(TestDeviceState.FASTBOOT)) {
                                testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
                            }
                        }
                    }
                }
                RunUtil.sleep(FASTBOOT_POLL_WAIT_TIME);
            }
        }
    }

    static Set<String> getDevicesOnFastboot(String fastbootOutput) {
        Set<String> serials = new HashSet<String>();
        Pattern fastbootPattern = Pattern.compile("([\\w\\d]+)\\s+fastboot\\s*");
        Matcher fastbootMatcher = fastbootPattern.matcher(fastbootOutput);
        while (fastbootMatcher.find()) {
            serials.add(fastbootMatcher.group(1));
        }
        return serials;
    }
}
