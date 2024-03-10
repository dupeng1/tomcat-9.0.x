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
package org.apache.catalina.startup;

import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.digester.Digester;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * Catalina 的引导加载程序。 该应用程序构建了一个类加载器，
 * 用于加载 Catalina 内部类（通过累积在“catalina.home”下的“server”目录中找到的所有 JAR 文件），
 * 并启动容器的常规执行。 这种迂回方法的目的是将 Catalina 内部类（以及它们所依赖的任何其他类，例如 XML 解析器）
 * 保留在系统类路径之外，因此对应用程序级类不可见。
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);
    /**
     * Daemon object used by main.
     */
    private static final Object daemonLock = new Object();

    /**引导类程序（实例）*/
    private static volatile Bootstrap daemon = null;

    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;
    private static final Pattern PATH_PATTERN = Pattern.compile("(\"[^\"]*\")|(([^,])*)");

    /**Bootstrap类最先执行的是类的这个静态代码块*/

    static {
        // Will always be non-null ，先获取到用户工作目录
        String userDir = System.getProperty("user.dir");

        // Home first ，再拿到catalina.home变量值
        String home = System.getProperty(Constants.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(
                Constants.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // Then base
        String base = System.getProperty(Constants.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(
                Constants.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference. Catalina容器（实例）对象指针
     */
    private Object catalinaDaemon = null;

    /**
     * 以应用类加载器为父类，是tomcat顶层的公用类加载器，
     * 其路径由conf/catalina.properties中的common.loader指定，默认指向${catalina.home}/lib下的包。
     */
    ClassLoader commonLoader = null;
    /**
     * 以Common类加载器为父类，是用于加载Tomcat应用服务器的类加载器，
     * 其路径由server.loader指定，默认为空，此时tomcat使用Common类加载器加载应用服务器。
     */
    ClassLoader catalinaLoader = null;
    /**
     * 以Common类加载器为父类，是所有Web应用的父类加载器，
     * 其路径由shared.loader指定，默认为空，
     * 此时tomcat使用Common类加载器作为Web应用的父加载器。
     */
    ClassLoader sharedLoader = null;

    // -------------------------------------------------------- Private Methods


    private void initClassLoaders() {
        try {
            commonLoader = createClassLoader("common", null);
             if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                // 假设在catalina.properties配置文件中没有配置common.loader，则commonLoader就是AppClassLoader
                commonLoader = this.getClass().getClassLoader();
            }
            /**
             * 第二个参数是传入的父加载器，如果server和shared没有配置的话，默认返回的实例等同于commonLoader，
             * 否则就创建一个UrlClassLoader类型的实例，其父类加载器指向commonLoader，
             * 而commonLoader的父指向{@link sun.misc.Launcher.AppClassLoader}
             */
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
            System.out.println("sharedLoader: "+sharedLoader.hashCode()+","+sharedLoader);
            /**默认配置下，common 、catalina 和 sharedLoader是同一个*/
            System.out.println("commonLoader == catalinaLoader is :"+(commonLoader.equals(catalinaLoader)));
            System.out.println("commonLoader == sharedLoader is :"+(commonLoader.equals(sharedLoader)));
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    /**
     * 创建类加载器
     * @param name 加载所指定的配置名称
     * @param parent 上一层类加载器
     * @return 如果name没有配置，那就直接返回parent，对于server和shared来说，父类加载器就是CommonLoader
     * @throws Exception
     */
    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {
        /**
         * 在catalina.properties中有三个name.loader选项，如下：
         * common.loader="${catalina.base}/lib","${catalina.base}/lib/*.jar","${catalina.home}/lib","${catalina.home}/lib/*.jar"
         * server.loader= , 这个server.loader没有值，tomcat作者说了，如果这个留空，那server的类加载器就是commonLoader
         * (以Common类加载器为父类，适用于加载Tomcat自身依赖的Jar的类加载器，其路径由server.loader指定，默认为空，此时tomcat使用Common类加载器加载自身所需的jar。)
         * shared.loader= , 这个shared.loader没有值，tomcat作者说了，如果这个留空，那shared的类加载器就是commonLoader
         * 以Common类加载器为父类，是所有Web应用的父类加载器，其路径由shared.loader指定，默认为空，此时tomcat使用Common类加载器作为Web应用的父加载器。
         *
         * Web应用：以Shared类加载器为父类，加载/WEB-INF/classes目录下的未压缩的Class和资源文件以及/WEB-INF/lib目录下的jar包，
         *         该类加载器只对当前Web应用可见，对其他Web应用均不可见。
         * 默认情况下，Common、Catalina、Shared类加载器是同一个，但可以配置3个不同的类加载器，使他们各司其职
         */
        String value = CatalinaProperties.getProperty(name + ".loader");
        /**如果找不到name对应的loader配置选项的话，就返回父类加载器*/
        if ((value == null) || (value.equals(""))) {
            return parent;
        }

        /**value值是一个带变量表达式的字符串，如 "xxx/${catalina.base}/lib",replace就是要替换变量换成具体的值 */
        value = replace(value);

        /**Repository是ClassLoaderFactory 中的一个静态内部类, 有2个属性, location, type, 表示某个位置的某种类型的文件*/
        List<Repository> repositories = new ArrayList<>();

        /**
         * 默认情况下，如果我们不配置catalina.properties中的common.loader标签的值的话，这里取出的数组就4个
         * 分别是：
         * 1、${catalina.base}/lib
         * 2、${catalina.base}/lib/*.jar
         * 3、${catalina.home}/lib
         * 4、${catalina.home}/lib/*.jar
         *
         * bin (运行脚本）
         * conf (配置文件）
         * lib (核心库文件）
         * logs (日志目录)
         * temp (临时目录)
         * webapps (自动装载的应用程序的目录）
         * work (JVM临时文件目录[java.io.tmpdir])
         * 其中只有bin和lib被多个tomcat示例共用，其它目录conf、logs、temp、webapps和work 每个Tomcat实例都拥有独自一份
         * 所以：
         *
         * catalina.home(安装目录)：指向公用信息的位置，就是bin和lib的父目录。
         * catalina.base(工作目录)：指向每个Tomcat目录私有信息的位置，就是conf、logs、temp、webapps和work的父目录。
         * 在只有一个tomcat实例的情况下，home和base是一样的
         * */
        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                /**
                 * 一上来先暴力检查是否是一个url类型的仓库，如果不是，
                 * tomcat原来异常是忽略不处理，后面会再判断是不是glob、jar或者dir类型的
                 * 类型是一个枚举类，有：
                 * DIR, -- 目录格式的，如 file://xxxxxx
                 * GLOB, -- N个jar，*.jar结尾的都算
                 * JAR, -- 单个jar
                 * URL -- 网络地址形式的，如http://xxxxxx
                 */
                URL url = new URL(repository);
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
                System.out.printf("%s - 不是URL repository \n",repository);
            }

            // Local repository , 既然不是网络的，那一定是本地文件系统可以访问到的
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Constants.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Constants.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * Initialize daemon.
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {

        System.out.println("======> Bootstrap init.");
        /**初始化类加载器（common、catalina、shared）*/
        initClassLoaders();

        /**
         * 设置当前线程上下文类加载器为catalinaLoader即server类加载器
         * 主要为了打破父委托模式，让父类加载器加载到子类加载器路径下的class
         * ①Tomcat中的代码应用场景：{@link Digester#getClassLoader()}
         * ②其他应用场景：
         * 如果有N个Web应用程序都是用Spring来进行组织和管理的话,
         * 可以把Spring依赖放到Shared目录下让这些程序共享。Spring要对应用程序的类进行管理，
         * 自然要能访问到应用程序的类，而应用程序的类显然是放
         * 在/WebApp/WEB-INF目录中的，那么被SharedClassLoader加载的Spring如何访问并不在
         * 其加载范围内的应用程序类呢？ 那就只能用下面这种方式了，这就打破了父委派模式，还有其他：JNDI、JDBC等都是
         */
        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isDebugEnabled()) {
            log.debug("Loading startup class");
        }
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        /**
         * 利用反射实例化Catalina对象
         * 这里为什么要使用反射？ 你看源码里Bootstrap和Catalina就差“睡在一起了”，为什么还这么生疏要反射获得呢？
         * 答案在这里：https://www.processon.com/view/link/6323f13ce0b34d330066776b
         */
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        if (log.isDebugEnabled()) {
            log.debug("Setting startup class properties");
        }
        /**{@link Catalina#setParentClassLoader(ClassLoader)}*/
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        /**参数类型是：ClassLoader*/
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        /**参数个数1个，其实就是共享的类加载器*/
         Object paramValues[] = new Object[1];
        /**
         * 这个地方注意了，整个Catalina下面所有的容器都将使用这个sharedLoader类加载实例作为父
         * 比如StandardContext中的{@link org.apache.catalina.loader.WebappClassLoaderBase}的父加载器就是sharedLoader
         */
        paramValues[0] = sharedLoader;
        Method method = startupClass.getMethod(methodName, paramTypes);
        /**使用反射方式调用setParentClassLoader设置为shareLoader*/
        method.invoke(startupInstance, paramValues);
        /**设置catalina实例*/
        catalinaDaemon = startupInstance;
    }


    /**
     * Load daemon. 加载daemon，注意这个daemon就是耳熟能详的Catalina
     */
    private void load(String[] arguments) throws Exception {

        /**
         * Call the load() method
         * 通过反射调用Catalina的load方法，主要做一些配置工作，比如解析server.xml,拿到server节点，然后对server组件进行init调用等
         */
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        /**调用{@link Catalina#load()方法}*/
        method.invoke(catalinaDaemon, param);
    }


    /**
     * getServer() for configtest
     * 通过反射从Catalina容器中获取server组件
     */
    private Object getServer() throws Exception {
        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments) throws Exception {
        init();
        load(arguments);
    }


    /**
     * Start the Catalina daemon. 启动Catalina容器
     * @throws Exception Fatal start error
     */
    public void start() throws Exception {
        /**如果catalina等于null，那就再走一遍init*/
        if (catalinaDaemon == null) {
            init();
        }
        Method method = catalinaDaemon.getClass().getMethod("start", (Class [])null);
        /**反射调用Catalina类的start方法*/
        method.invoke(catalinaDaemon, (Object [])null);
    }


    /**
     * Stop the Catalina Daemon.
     * @throws Exception Fatal stop error
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);
    }


    /**
     * Stop the standalone server.
     * @throws Exception Fatal stop error
     */
    public void stopServer() throws Exception {
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);
    }


   /**
     * Stop the standalone server.
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments) throws Exception {
        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    /**
     * Set flag.
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await) throws Exception {
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided scripts.
     * 通过提供的脚本启动 Tomcat 时的主要方法和入口点
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        synchronized (daemonLock) {
            if (daemon == null) {
                // Don't set daemon until init() has completed
                Bootstrap bootstrap = new Bootstrap();
                try {
                    /**初始化启动类（主要是初始化类加载器）*/
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    t.printStackTrace();
                    return;
                }
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            /** 直接调用Bootstrap时，如果传入的args空，默认就是启动tomcat的行为*/
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                /**加载catalina*/
                daemon.load(args);
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof StackOverflowError) {
            // Swallow silently - it should be recoverable
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }

    // Copied from ExceptionUtils so that there is no dependency on utils
    static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character can only be used to quote paths. It must " +
                        "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }

        return result.toArray(new String[0]);
    }
}
