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

package com.android.tradefed.targetprep;

import com.android.tradefed.util.MultiMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A class that parses out required versions of auxiliary image files needed to flash a device.
 * (e.g. bootloader, baseband, etc)
 */
public class FlashingResourcesParser implements IFlashingResourcesParser {

    private static final String ANDROID_INFO_FILE_NAME = "android-info.txt";
    /**
     * Some resource files use "require-foo=bar", others use "foo=bar". This expression handles
     * both.
     */
    private static final Pattern REQUIRE_PATTERN = Pattern.compile("(?:require\\s)?(.*?)=(.*)");
    /**
     * Some resource files have special product-specific requirements, for instance:
     * {@code require-for-product:product1 version-bootloader=xyz} would only require bootloader
     * {@code xyz} for device {@code product1}.  This pattern matches the require-for-product line
     */
    private static final Pattern PRODUCT_REQUIRE_PATTERN =
            Pattern.compile("require-for-product:(\\S+) +(.*?)=(.*)");

    // expected keys
    static final String PRODUCT_KEY = "product";
    static final String BOARD_KEY = "board";
    static final String BOOTLOADER_VERSION_KEY = "version-bootloader";
    static final String BASEBAND_VERSION_KEY = "version-baseband";

    // key-value pairs of build requirements
    private AndroidInfo mReqs;

    /**
     * A typedef for {@code Map<String, MultiMap<String, String>>}.  Useful parsed format for
     * storing the data encoded in ANDROID_INFO_FILE_NAME
     */
    public static class AndroidInfo extends HashMap<String, MultiMap<String, String>> {}

    public FlashingResourcesParser(File deviceImgZipFile) throws TargetSetupError {
        mReqs = getBuildRequirements(deviceImgZipFile);
    }

    /**
     * Constructs a FlashingResourcesParser with the supplied AndroidInfo Reader
     * <p/>
     * Exposed for unit testing
     *
     * @param infoReader a {@link BufferedReader} containing the equivalent of android-info.txt to
     *        parse
     */
    public FlashingResourcesParser(BufferedReader infoReader) throws TargetSetupError,
            IOException {
        mReqs = parseAndroidInfo(infoReader);
    }

    /**
     * {@inheritDoc}
     */
    public String getRequiredBootloaderVersion() {
        // by convention, get the first version listed.
        return getRequiredImageVersion(BOOTLOADER_VERSION_KEY);
    }

    /**
     * {@inheritDoc}
     */
    public String getRequiredBasebandVersion() {
        return getRequiredImageVersion(BASEBAND_VERSION_KEY);
    }

    /**
     * {@inheritDoc}
     */
    public String getRequiredImageVersion(String imageVersionKey) {
        // Use null to designate the global product requirements
        return getRequiredImageVersion(imageVersionKey, null);
    }

    /**
     * {@inheritDoc}
     */
    public String getRequiredImageVersion(String imageVersionKey, String productName) {
        MultiMap<String, String> productReqs = mReqs.get(productName);

        if (productReqs == null && productName != null) {
            // There aren't any product-specific requirements for productName.  Fall back to global
            // requirements.
            return getRequiredImageVersion(imageVersionKey, null);
        }

        // by convention, get the first version listed.
        String result = getFirst(productReqs.get(imageVersionKey));

        if (result != null) {
            // If there's a result, return it
            return result;
        }
        if (result == null && productName != null) {
            // There aren't any product-specific requirements for this particular imageVersionKey
            // for productName.  Fall back to global requirements.
            return getRequiredImageVersion(imageVersionKey, null);
        }

        // Neither a specific nor a global result exists; return null
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Collection<String> getRequiredBoards() {
        Collection<String> all = new ArrayList<String>();
        MultiMap<String, String> boardReqs = mReqs.get(null);
        if (boardReqs == null) {
            return null;
        }

        Collection<String> board = boardReqs.get(BOARD_KEY);
        Collection<String> product = boardReqs.get(PRODUCT_KEY);

        // board overrides product here
        if (board != null) {
            all.addAll(board);
        } else if (product != null) {
            all.addAll(product);
        } else {
            return null;
        }

        return all;
    }

    /**
     * Gets the first element in the given {@link List} or <code>null</code>
     */
    private static String getFirst(List<String> values) {
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return null;
    }

    /**
     * This parses android-info.txt from system image zip and returns key value pairs of required
     * image files.
     * <p/>
     * Expects the following syntax:
     * <p/>
     * <i>[require] key=value1[|value2]</i>
     *
     * @returns a {@link Map} of parsed key value pairs, or <code>null</code> if data could not be
     * parsed
     */
    static AndroidInfo getBuildRequirements(File deviceImgZipFile) throws TargetSetupError {
        ZipFile deviceZip = null;
        BufferedReader infoReader = null;
        try {
            deviceZip = new ZipFile(deviceImgZipFile);
            ZipEntry androidInfoEntry = deviceZip.getEntry(ANDROID_INFO_FILE_NAME);
            if (androidInfoEntry == null) {
                throw new TargetSetupError(String.format("Could not find %s in device image zip %s",
                        ANDROID_INFO_FILE_NAME, deviceImgZipFile.getName()));
            }
            infoReader = new BufferedReader(new InputStreamReader(
                    deviceZip.getInputStream(androidInfoEntry)));

            return parseAndroidInfo(infoReader);
        } catch (ZipException e) {
            throw new TargetSetupError(String.format("Could not read device image zip %s",
                    deviceImgZipFile.getName()), e);
        } catch (IOException e) {
            throw new TargetSetupError(String.format("Could not read device image zip %s",
                    deviceImgZipFile.getName()), e);
        } finally {
            if (deviceZip != null) {
                try {
                    deviceZip.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (infoReader != null) {
                try {
                    infoReader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Returns the current value for the provided key if one exists, or creates and returns a new
     * value if one does not exist.
     */
    private static MultiMap<String, String> getOrCreateEntry(AndroidInfo map, String key) {
        if (map.containsKey(key)) {
            return map.get(key);
        } else {
            MultiMap<String, String> value = new MultiMap<String, String>();
            map.put(key, value);
            return value;
        }
    }

    /**
     * Parses the required build attributes from an android-info data source.
     * <p/>
     * Exposed as package-private for unit testing.
     *
     * @param infoReader the {@link BufferedReader} to read android-info text data from
     * @return a Map of parsed attribute name-value pairs
     * @throws IOException
     */
    static AndroidInfo parseAndroidInfo(BufferedReader infoReader)
            throws IOException {
        AndroidInfo requiredImageMap = new AndroidInfo();

        boolean eof = false;
        while (!eof) {
            String line = infoReader.readLine();
            if (line != null) {
                Matcher matcher = PRODUCT_REQUIRE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String product = matcher.group(1);
                    String key = matcher.group(2);
                    String values = matcher.group(3);
                    // Requirements specific to product {@code product}
                    MultiMap<String, String> reqs = getOrCreateEntry(requiredImageMap, product);
                    for (String value : values.split("\\|")) {
                        reqs.put(key, value);
                    }
                } else {
                    matcher = REQUIRE_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String key = matcher.group(1);
                        String values = matcher.group(2);
                        // Use a null product identifier to designate requirements for all products
                        MultiMap<String, String> reqs = getOrCreateEntry(requiredImageMap, null);
                        for (String value : values.split("\\|")) {
                            reqs.put(key, value);
                        }
                    }
                }
            } else {
                eof = true;
            }
        }
        return requiredImageMap;
    }
}
