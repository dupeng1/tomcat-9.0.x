/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.util;

import java.util.Locale;

/**
 * Utility class to manage context names so there is one place where the
 * conversions between baseName, path and version take place.
 * 实用工具类来管理上下文名称，这样就有一个地方可以进行baseName、路径和版本之间的转换。
 */
public final class ContextName {
    public static final String ROOT_NAME = "ROOT";
    private static final String VERSION_MARKER = "##";
    private static final char FWD_SLASH_REPLACEMENT = '#';

    /**应用名*/
    private final String baseName;
    /**应用访问path*/
    private final String path;
    /**应用版本，fileName 如果带有##，则后面的内容即为版本*/
    private final String version;
    /**如果版本空，等同于path，否者就是path+##+version*/
    private final String name;


    /**
     * Creates an instance from a context name, display name, base name,
     * directory name, WAR name or context.xml name.
     *
     * @param name  The name to use as the basis for this object
     * @param stripFileExtension    If a .war or .xml file extension is present
     *                              at the end of the provided name should it be
     *                              removed?
     * 如果.war或.xml文件扩展名出现在所提供的名称的末尾，是否应该将其删除?
     */
    public ContextName(String name, boolean stripFileExtension) {

        String tmp1 = name;

        // Convert Context names and display names to base names

        // Strip off any leading "/"
        if (tmp1.startsWith("/")) {
            tmp1 = tmp1.substring(1);
        }

        // Replace any remaining /
        tmp1 = tmp1.replace('/', FWD_SLASH_REPLACEMENT);

        // Insert the ROOT name if required
        if (tmp1.startsWith(VERSION_MARKER) || tmp1.isEmpty()) {
            tmp1 = ROOT_NAME + tmp1;
        }

        // Remove any file extensions
        if (stripFileExtension &&
                (tmp1.toLowerCase(Locale.ENGLISH).endsWith(".war") ||
                        tmp1.toLowerCase(Locale.ENGLISH).endsWith(".xml"))) {
            tmp1 = tmp1.substring(0, tmp1.length() -4);
        }

        baseName = tmp1;

        String tmp2;
        // Extract version number
        int versionIndex = baseName.indexOf(VERSION_MARKER);
        if (versionIndex > -1) {
            version = baseName.substring(versionIndex + 2);
            tmp2 = baseName.substring(0, versionIndex);
        } else {
            version = "";
            tmp2 = baseName;
        }

        if (ROOT_NAME.equals(tmp2)) {
            path = "";
        } else {
            path = "/" + tmp2.replace(FWD_SLASH_REPLACEMENT, '/');
        }

        if (versionIndex > -1) {
            this.name = path + VERSION_MARKER + version;
        } else {
            this.name = path;
        }
    }

    /**
     * Construct an instance from a path and version.
     *
     * @param path      Context path to use
     * @param version   Context version to use
     */
    public ContextName(String path, String version) {
        // Path should never be null, '/' or '/ROOT'
        if (path == null || "/".equals(path) || "/ROOT".equals(path)) {
            this.path = "";
        } else {
            this.path = path;
        }

        // Version should never be null
        if (version == null) {
            this.version = "";
        } else {
            this.version = version;
        }

        // Name is path + version
        if (this.version.isEmpty()) {
            name = this.path;
        } else {
            name = this.path + VERSION_MARKER + this.version;
        }

        // Base name is converted path + version
        StringBuilder tmp = new StringBuilder();
        if (this.path.isEmpty()) {
            tmp.append(ROOT_NAME);
        } else {
            tmp.append(this.path.substring(1).replace('/',
                    FWD_SLASH_REPLACEMENT));
        }
        if (!this.version.isEmpty()) {
            tmp.append(VERSION_MARKER);
            tmp.append(this.version);
        }
        this.baseName = tmp.toString();
    }

    public String getBaseName() {
        return baseName;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        StringBuilder tmp = new StringBuilder();
        if ("".equals(path)) {
            tmp.append('/');
        } else {
            tmp.append(path);
        }

        if (!version.isEmpty()) {
            tmp.append(VERSION_MARKER);
            tmp.append(version);
        }

        return tmp.toString();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }


    /**
     * Extract the final component of the given path which is assumed to be a
     * base name and generate a {@link ContextName} from that base name.
     *
     * @param path The path that ends in a base name
     *
     * @return the {@link ContextName} generated from the given base name
     */
    public static ContextName extractFromPath(String path) {
        // Convert '\' to '/'
        path = path.replaceAll("\\\\", "/");
        // Remove trailing '/'. Use while just in case a value ends in ///
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        int lastSegment = path.lastIndexOf('/');
        if (lastSegment > 0) {
            path = path.substring(lastSegment + 1);
        }

        return new ContextName(path, true);
    }
}
