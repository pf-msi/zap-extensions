/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2021 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.callhome;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Supplier;
import net.sf.json.JSONObject;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.httpclient.URI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpSender;
import org.parosproxy.paros.network.HttpStatusCode;
import org.zaproxy.zap.ZAP;
import org.zaproxy.zap.extension.autoupdate.ExtensionAutoUpdate;
import org.zaproxy.zap.extension.autoupdate.InvalidCfuUrlException;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

public class ExtensionCallHome extends ExtensionAdaptor {

    // The name is public so that other extensions can access it
    public static final String NAME = "ExtensionCallHome";

    protected static final String PREFIX = "callhome";

    private static final String ZAP_CFU_SERVICE = "https://cfu.zaproxy.org/ZAPcfu";

    private static final String ISSUE_FILE = "/etc/issue";

    private static final Logger LOGGER = LogManager.getLogger(ExtensionCallHome.class);

    private static final String BACK_BOX_ID = "BackBox";

    public enum OS {
        WINDOWS,
        LINUX,
        MAC_OS,
        KALI,
        BACK_BOX,
        UNKNOWN;

        @Override
        public String toString() {
            if (MAC_OS.equals(this)) {
                return "MacOS";
            } else if (BACK_BOX.equals(this)) {
                return BACK_BOX_ID;
            }
            // First letter capitalised, rest lower case
            return this.name().substring(0, 1) + this.name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    private HttpSender httpSender = null;

    private static OS os;
    private static Boolean onBackBox = null;

    public ExtensionCallHome() {
        super(NAME);
        setI18nPrefix(PREFIX);
    }

    @Override
    public void postInit() {
        this.setAutoUpdateSupplier(this::get);
    }

    @Override
    public void unload() {
        this.setAutoUpdateSupplier(null);
    }

    private void setAutoUpdateSupplier(Supplier<ZapXmlConfiguration> supplier) {
        // XXX Change to not use reflection after 2.12.0
        ExtensionAutoUpdate extAu =
                Control.getSingleton().getExtensionLoader().getExtension(ExtensionAutoUpdate.class);
        if (extAu != null) {
            try {
                Method setSupplierMethod =
                        extAu.getClass().getMethod("setCheckForUpdatesSupplier", Supplier.class);
                LOGGER.debug("Setting CheckForUpdates supplier: {}", supplier);
                setSupplierMethod.invoke(extAu, supplier);
            } catch (Exception e) {
                LOGGER.debug(
                        "Failed to set CheckForUpdates supplier - expected to fail at 2.11.0", e);
            }
        }
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    private JSONObject getMandatoryRequestData() {
        JSONObject json = new JSONObject();
        json.put("zapVersion", Constant.PROGRAM_VERSION);
        json.put("os", getOS().toString());
        json.put(
                "osVersion",
                System.getProperty("os.name") + " : " + System.getProperty("os.version"));
        json.put("javaVersion", System.getProperty("java.version"));
        json.put("zapType", ZAP.getProcessType().name());
        json.put("container", Constant.isInContainer() ? Constant.getContainerName() : "");
        return json;
    }

    public ZapXmlConfiguration getCheckForUpdatesData()
            throws IOException, ConfigurationException, InvalidCfuUrlException {
        LOGGER.debug("Getting latest version info from {}", ZAP_CFU_SERVICE);

        HttpMessage msg = new HttpMessage(new URI(ZAP_CFU_SERVICE, true));
        msg.getRequestHeader().setMethod(HttpRequestHeader.POST);
        msg.getRequestHeader().setHeader(HttpHeader.CONTENT_TYPE, HttpHeader.JSON_CONTENT_TYPE);
        msg.getRequestBody().setBody(getMandatoryRequestData().toString());
        msg.getRequestHeader().setContentLength(msg.getRequestBody().length());

        getHttpSender().sendAndReceive(msg, true);
        if (msg.getResponseHeader().getStatusCode() != HttpStatusCode.OK) {
            throw new IOException(
                    "Expected '200 OK' but got '"
                            + msg.getResponseHeader().getStatusCode()
                            + " "
                            + msg.getResponseHeader().getReasonPhrase()
                            + "'");
        }
        if (!msg.getRequestHeader().isSecure()) {
            // Only access the cfu page over https
            throw new InvalidCfuUrlException(msg.getRequestHeader().getURI().toString());
        }

        ZapXmlConfiguration config = new ZapXmlConfiguration();
        config.setDelimiterParsingDisabled(true);
        config.load(new StringReader(msg.getResponseBody().toString()));

        return config;
    }

    private ZapXmlConfiguration get() {
        try {
            return getCheckForUpdatesData();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return null;
    }

    public static OS getOS() {
        if (os == null) {
            if (Constant.isWindows()) {
                os = OS.WINDOWS;
            } else if (Constant.isKali()) {
                os = OS.KALI;
            } else if (isBackBox()) {
                os = OS.BACK_BOX;
            } else if (Constant.isLinux()) {
                os = OS.LINUX;
            } else if (Constant.isMacOsX()) {
                os = OS.MAC_OS;
            } else {
                os = OS.UNKNOWN;
            }
        }
        return os;
    }

    // XXX: Use Constant.isBackBox() from 2.12.0
    private static boolean isBackBox() {
        if (onBackBox == null) {
            onBackBox = Boolean.FALSE;
            File issueFile = new File(ISSUE_FILE);
            if (Constant.isLinux() && !Constant.isDailyBuild() && issueFile.exists()) {
                // Ignore the fact we're on BackBox if this is a daily build - they will only have
                // been installed manually
                try {
                    String content = new String(Files.readAllBytes(issueFile.toPath()));
                    if (content.startsWith(BACK_BOX_ID)) {
                        onBackBox = Boolean.TRUE;
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        return onBackBox;
    }

    private HttpSender getHttpSender() {
        if (httpSender == null) {
            httpSender =
                    new HttpSender(
                            Model.getSingleton().getOptionsParam().getConnectionParam(),
                            true,
                            HttpSender.CHECK_FOR_UPDATES_INITIATOR);
        }
        return httpSender;
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString(PREFIX + ".desc");
    }
}
