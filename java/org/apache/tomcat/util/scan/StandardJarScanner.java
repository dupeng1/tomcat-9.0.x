/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.scan;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.*;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.UriUtil;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.res.StringManager;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The default {@link JarScanner} implementation scans the WEB-INF/lib directory
 * followed by the provided classloader and then works up the classloader
 * hierarchy. This implementation is sufficient to meet the requirements of the
 * Servlet 3.0 specification as well as to provide a number of Tomcat specific
 * extensions. The extensions are:
 * <ul>
 *   <li>Scanning the classloader hierarchy (enabled by default)</li>
 *   <li>Testing all files to see if they are JARs (disabled by default)</li>
 *   <li>Testing all directories to see if they are exploded JARs
 *       (disabled by default)</li>
 * </ul>
 * All of the extensions may be controlled via configuration.
 */
public class StandardJarScanner implements JarScanner {

    private final Log log = LogFactory.getLog(StandardJarScanner.class); // must not be static

    /**
     * The string resources for this package.
     */
    private static final StringManager sm = StringManager.getManager(Constants.Package);

    private static final Set<ClassLoader> CLASSLOADER_HIERARCHY;

    static {
        Set<ClassLoader> cls = new HashSet<>();

        ClassLoader cl = StandardJarScanner.class.getClassLoader();
        while (cl != null) {
            cls.add(cl);
            cl = cl.getParent();
        }

        CLASSLOADER_HIERARCHY = Collections.unmodifiableSet(cls);
    }

    /**
     * Controls the classpath scanning extension.
     * 如果true，除了会扫描Web应用程序之外，还将扫描包括share和common类加载器以及系统类路径（但不包括引导类路径）。
     * 默认是true
     */
    private boolean scanClassPath = true;
    public boolean isScanClassPath() {
        return scanClassPath;
    }
    public void setScanClassPath(boolean scanClassPath) {
        this.scanClassPath = scanClassPath;
    }

    /**
     * Controls the JAR file Manifest scanning extension.
     */
    private boolean scanManifest = true;
    public boolean isScanManifest() {
        return scanManifest;
    }
    public void setScanManifest(boolean scanManifest) {
        this.scanManifest = scanManifest;
    }

    /**
     * Controls the testing all files to see of they are JAR files extension.
     * 如果true，将检查路径中找到的任何文件以查看它们是否是Jar文件，而不是依赖于文件扩展名.jar。默认是false。
     */
    private boolean scanAllFiles = false;
    public boolean isScanAllFiles() {
        return scanAllFiles;
    }
    public void setScanAllFiles(boolean scanAllFiles) {
        this.scanAllFiles = scanAllFiles;
    }

    /**
     * Controls the testing all directories to see of they are exploded JAR
     * files extension.
     * 如果true，还会检查扫描路径下所有的文件夹，以查看这些文件夹是否是解压后的JAR文件。默认是false。
     * Tomcat是通过查找一个文件夹下面是否有META-INF子文件夹来确定这个文件夹是否是解压后的JAR文件的。
     * 仅当存在META-INF子文件夹时，才假定该文件夹是解压后的JAR文件。
     */
    private boolean scanAllDirectories = true;
    public boolean isScanAllDirectories() {
        return scanAllDirectories;
    }
    public void setScanAllDirectories(boolean scanAllDirectories) {
        this.scanAllDirectories = scanAllDirectories;
    }

    /**
     * Controls the testing of the bootstrap classpath which consists of the
     * runtime classes provided by the JVM and any installed system extensions.
     * 如果scanClassPath是true，该属性也为true，那么还将扫描bootstrap类路径下是否有JAR文件。默认是false。
     */
    private boolean scanBootstrapClassPath = false;
    public boolean isScanBootstrapClassPath() {
        return scanBootstrapClassPath;
    }
    public void setScanBootstrapClassPath(boolean scanBootstrapClassPath) {
        this.scanBootstrapClassPath = scanBootstrapClassPath;
    }

    /**
     * Controls the filtering of the results from the scan for JARs
     */
    private JarScanFilter jarScanFilter = new StandardJarScanFilter();
    @Override
    public JarScanFilter getJarScanFilter() {
        return jarScanFilter;
    }
    @Override
    public void setJarScanFilter(JarScanFilter jarScanFilter) {
        this.jarScanFilter = jarScanFilter;
    }

    /**
     * Scan the provided ServletContext and class loader for JAR files. Each JAR
     * file found will be passed to the callback handler to be processed.
     *
     * @param scanType      The type of JAR scan to perform. This is passed to
     *                          the filter which uses it to determine how to
     *                          filter the results
     * @param context       The ServletContext - used to locate and access
     *                      WEB-INF/lib
     * @param callback      The handler to process any JARs found
     */
    @Override
    public void scan(JarScanType scanType, ServletContext context,
            JarScannerCallback callback) {

        if (log.isTraceEnabled()) {
            log.trace(sm.getString("jarScan.webinflibStart"));
        }
        /**
         * 如果/conf/catalina.properties中配置是这样的
         * tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*
         * 或者
         * tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar
         * 或者
         * tomcat.util.scan.StandardJarScanFilter.jarsToSkip=
         * 则，应用相关的jar都不会被扫描(jarScanFilter.isSkipAll() == true)
         * 扫描主要就是为了迎合Servlet3.0新特性比如 Servlet注解 和 web-fragment.xml
         */
        if (jarScanFilter.isSkipAll()) {
            return;
        }

        Set<URL> processedURLs = new HashSet<>();

        // Scan WEB-INF/lib 扫描应用WEB-INF/lib下面的jar包
        Set<String> dirList = context.getResourcePaths(Constants.WEB_INF_LIB);
        if (dirList != null) {
            for (String path : dirList) {
                boolean bMatch = getJarScanFilter().check(scanType, path.substring(path.lastIndexOf('/') + 1));
                if (path.endsWith(Constants.JAR_EXT) && bMatch) {
                    // Need to scan this JAR 如果path是jar且jar又不是需要跳过扫描的，那就进行处理
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("jarScan.webinflibJarScan", path));
                    }
                    URL url = null;
                    try {
                        /**拿到path的绝对资源路径*/
                        url = context.getResource(path);
                        /**添加到已处理列表中*/
                        processedURLs.add(url);
                        /**循环扫描并处理jar，并且把它当成web应用程序来处理，主要就是解析web-fragment.xml*/
                        process(scanType, callback, url, path, true, null);
                    } catch (IOException e) {
                        log.warn(sm.getString("jarScan.webinflibFail", url), e);
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace(sm.getString("jarScan.webinflibJarNoScan", path));
                    }
                }
            }
        }

        // Scan WEB-INF/classes 扫描应用WEB-INF/classes下面的class
        try {
            URL webInfURL = context.getResource(Constants.WEB_INF_CLASSES);
            if (webInfURL != null) {
                // WEB-INF/classes will also be included in the URLs returned
                // by the web application class loader so ensure the class path
                // scanning below does not re-scan this location.
                processedURLs.add(webInfURL);

                if (isScanAllDirectories()) {
                    URL url = context.getResource(Constants.WEB_INF_CLASSES + "/META-INF");
                    if (url != null) {
                        try {
                            callback.scanWebInfClasses();
                        } catch (IOException e) {
                            log.warn(sm.getString("jarScan.webinfclassesFail"), e);
                        }
                    }
                }
            }
        } catch (MalformedURLException e) {
            // Ignore. Won't happen. URLs are of the correct form.
        }

        // Scan the classpath
        if (isScanClassPath()) {
            doScanClassPath(scanType, context, callback, processedURLs);
        }
    }


    protected void doScanClassPath(JarScanType scanType, ServletContext context,
            JarScannerCallback callback, Set<URL> processedURLs) {
        if (log.isTraceEnabled()) {
            log.trace(sm.getString("jarScan.classloaderStart"));
        }

        ClassLoader stopLoader = null;
        if (!isScanBootstrapClassPath()) {
            // Stop when we reach the bootstrap class loader
            stopLoader = ClassLoader.getSystemClassLoader().getParent();
        }

        ClassLoader classLoader = context.getClassLoader();

        // JARs are treated as application provided until the common class
        // loader is reached.
        boolean isWebapp = true;

        // Use a Deque so URLs can be removed as they are processed
        // and new URLs can be added as they are discovered during
        // processing.
        Deque<URL> classPathUrlsToProcess = new LinkedList<>();

        while (classLoader != null && classLoader != stopLoader) {
            if (classLoader instanceof URLClassLoader) {
                if (isWebapp) {
                    isWebapp = isWebappClassLoader(classLoader);
                }

                classPathUrlsToProcess.addAll(
                        Arrays.asList(((URLClassLoader) classLoader).getURLs()));

                processURLs(scanType, callback, processedURLs, isWebapp, classPathUrlsToProcess);
            }
            classLoader = classLoader.getParent();
        }

        if (JreCompat.isJre9Available()) {
            // The application and platform class loaders are not
            // instances of URLClassLoader. Use the class path in this
            // case.
            addClassPath(classPathUrlsToProcess);
            // Also add any modules
            JreCompat.getInstance().addBootModulePath(classPathUrlsToProcess);
            processURLs(scanType, callback, processedURLs, false, classPathUrlsToProcess);
        }
    }


    protected void processURLs(JarScanType scanType, JarScannerCallback callback,
            Set<URL> processedURLs, boolean isWebapp, Deque<URL> classPathUrlsToProcess) {

        if (jarScanFilter.isSkipAll()) {
            return;
        }

        while (!classPathUrlsToProcess.isEmpty()) {
            URL url = classPathUrlsToProcess.pop();

            if (processedURLs.contains(url)) {
                // Skip this URL it has already been processed
                continue;
            }

            ClassPathEntry cpe = new ClassPathEntry(url);

            // JARs are scanned unless the filter says not to.
            // Directories are scanned for pluggability scans or
            // if scanAllDirectories is enabled unless the
            // filter says not to.
            if ((cpe.isJar() ||
                    scanType == JarScanType.PLUGGABILITY ||
                    isScanAllDirectories()) &&
                            getJarScanFilter().check(scanType,
                                    cpe.getName())) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("jarScan.classloaderJarScan", url));
                }
                try {
                    processedURLs.add(url);
                    process(scanType, callback, url, null, isWebapp, classPathUrlsToProcess);
                } catch (IOException ioe) {
                    log.warn(sm.getString("jarScan.classloaderFail", url), ioe);
                }
            } else {
                // JAR / directory has been skipped
                if (log.isTraceEnabled()) {
                    log.trace(sm.getString("jarScan.classloaderJarNoScan", url));
                }
            }
        }
    }


    protected void addClassPath(Deque<URL> classPathUrlsToProcess) {
        String classPath = System.getProperty("java.class.path");

        if (classPath == null || classPath.length() == 0) {
            return;
        }

        String[] classPathEntries = classPath.split(File.pathSeparator);
        for (String classPathEntry : classPathEntries) {
            File f = new File(classPathEntry);
            try {
                classPathUrlsToProcess.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                log.warn(sm.getString("jarScan.classPath.badEntry", classPathEntry), e);
            }
        }
    }


    /*
     * Since class loader hierarchies can get complicated, this method attempts
     * to apply the following rule: A class loader is a web application class
     * loader unless it loaded this class (StandardJarScanner) or is a parent
     * of the class loader that loaded this class.
     *
     * This should mean:
     *   the webapp class loader is an application class loader
     *   the shared class loader is an application class loader
     *   the server class loader is not an application class loader
     *   the common class loader is not an application class loader
     *   the system class loader is not an application class loader
     *   the bootstrap class loader is not an application class loader
     */
    private static boolean isWebappClassLoader(ClassLoader classLoader) {
        return !CLASSLOADER_HIERARCHY.contains(classLoader);
    }


    /*
     * Scan a URL for JARs with the optional extensions to look at all files
     * and all directories.
     */
    protected void process(JarScanType scanType, JarScannerCallback callback,
            URL url, String webappPath, boolean isWebapp, Deque<URL> classPathUrlsToProcess)
            throws IOException {

        if (log.isTraceEnabled()) {
            log.trace(sm.getString("jarScan.jarUrlStart", url));
        }

        if ("jar".equals(url.getProtocol()) || url.getPath().endsWith(Constants.JAR_EXT)) {
            try (Jar jar = JarFactory.newInstance(url)) {
                if (isScanManifest()) {
                    processManifest(jar, isWebapp, classPathUrlsToProcess);
                }
                /**
                 * {@link org.apache.tomcat.util.descriptor.web.FragmentJarScannerCallback}回调函数去扫描jar包
                 * 主要就是将扫描到的结果（应用下的，应用依赖的所有jar）填充到 Map<String,WebXml> fragments 对象中
                 * 如果jar带有fragment，key是jar包路径，则其对应的value WebXml就会有响应的内容
                 */
                callback.scan(jar, webappPath, isWebapp);
            }
        } else if ("file".equals(url.getProtocol())) {
            File f;
            try {
                f = new File(url.toURI());
                /**如果文件不符合jar结尾，且scanAllFiles（默认false）为true的话，那就把文件当成是jar文件进行处理*/
                if (f.isFile() && isScanAllFiles()) {
                    // Treat this file as a JAR
                    URL jarURL = UriUtil.buildJarUrl(f);
                    /**下面代码片段和处理jar文件流程一样*/
                    try (Jar jar = JarFactory.newInstance(jarURL)) {
                        if (isScanManifest()) {
                            processManifest(jar, isWebapp, classPathUrlsToProcess);
                        }
                        callback.scan(jar, webappPath, isWebapp);
                    }
                } else if (f.isDirectory()) {
                    if (scanType == JarScanType.PLUGGABILITY) {
                        /**如果当前file是文件夹，把它看作是jar包解压后的文件夹来处理*/
                        callback.scan(f, webappPath, isWebapp);
                    } else {
                        File metainf = new File(f.getAbsoluteFile() + File.separator + "META-INF");
                        if (metainf.isDirectory()) {
                            callback.scan(f, webappPath, isWebapp);
                        }
                    }
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // Wrap the exception and re-throw
                throw new IOException(t);
            }
        }
    }


    private void processManifest(Jar jar, boolean isWebapp,
            Deque<URL> classPathUrlsToProcess) throws IOException {

        // Not processed for web application JARs nor if the caller did not
        // provide a Deque of URLs to append to. 如果是web程序的话，就不处理了
        if (isWebapp || classPathUrlsToProcess == null) {
            return;
        }

        Manifest manifest = jar.getManifest();
        if (manifest != null) {
            Attributes attributes = manifest.getMainAttributes();
            String classPathAttribute = attributes.getValue("Class-Path");
            if (classPathAttribute == null) {
                return;
            }
            String[] classPathEntries = classPathAttribute.split(" ");
            for (String classPathEntry : classPathEntries) {
                classPathEntry = classPathEntry.trim();
                if (classPathEntry.length() == 0) {
                    continue;
                }
                URL jarURL = jar.getJarFileURL();
                URL classPathEntryURL;
                try {
                    URI jarURI = jarURL.toURI();
                    /*
                     * Note: Resolving the relative URLs from the manifest has the
                     *       potential to introduce security concerns. However, since
                     *       only JARs provided by the container and NOT those provided
                     *       by web applications are processed, there should be no
                     *       issues.
                     *       If this feature is ever extended to include JARs provided
                     *       by web applications, checks should be added to ensure that
                     *       any relative URL does not step outside the web application.
                     */
                    URI classPathEntryURI = jarURI.resolve(classPathEntry);
                    classPathEntryURL = classPathEntryURI.toURL();
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("jarScan.invalidUri", jarURL), e);
                    }
                    continue;
                }
                classPathUrlsToProcess.add(classPathEntryURL);
            }
        }
    }


    private static class ClassPathEntry {

        private final boolean jar;
        private final String name;

        public ClassPathEntry(URL url) {
            String path = url.getPath();
            int end = path.lastIndexOf(Constants.JAR_EXT);
            if (end != -1) {
                jar = true;
                int start = path.lastIndexOf('/', end);
                name = path.substring(start + 1, end + 4);
            } else {
                jar = false;
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                int start = path.lastIndexOf('/');
                name = path.substring(start + 1);
            }

        }

        public boolean isJar() {
            return jar;
        }

        public String getName() {
            return name;
        }
    }
}
