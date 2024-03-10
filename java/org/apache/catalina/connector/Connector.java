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
package org.apache.catalina.connector;

import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.core.AprStatus;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.CharsetUtil;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.openssl.OpenSSLImplementation;
import org.apache.tomcat.util.res.StringManager;

import javax.management.ObjectName;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ScheduledExecutorService;


/**
 * Implementation of a Coyote connector.
 * coyote连接器的实现，在catalina包中
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class Connector extends LifecycleMBeanBase  {

    private static final Log log = LogFactory.getLog(Connector.class);


    /**
     * Alternate flag to enable recycling of facades.
     */
    public static final boolean RECYCLE_FACADES =
        Boolean.parseBoolean(System.getProperty("org.apache.catalina.connector.RECYCLE_FACADES", "false"));


    public static final String INTERNAL_EXECUTOR_NAME = "Internal";

    // ----------------------------------------------------- Instance Variables

    /**
     * The <code>Service</code> we are associated with (if any).
     * 当前connector连接器关联的service,一个service有多个connector
     */
    protected Service service = null;


    /**
     * Do we allow TRACE ?
     * 一个布尔值，可用于启用或禁用 TRACE HTTP 方法。如果未指定，则此属性设置为 false。
     */
    protected boolean allowTrace = false;


    /**
     * Default timeout for asynchronous requests (ms).
     * 异步请求的默认超时时间（以毫秒为单位）。如果未指定，则此属性设置为 Servlet 规范默认值 30000（30 秒）。
     */
    protected long asyncTimeout = 30000;


    /**
     * The "enable DNS lookups" flag for this Connector.
     * 如果希望调用 request.getRemoteHost() 执行 DNS 查找以返回远程客户端的实际主机名，请设置为 true。
     * 设置为 false 可跳过 DNS 查找并以字符串形式返回IP地址（从而提高性能）。默认情况下，禁用 DNS 查找。
     */
    protected boolean enableLookups = false;


    /**
     * Is generation of X-Powered-By response header enabled/disabled?
     */
    protected boolean xpoweredBy = false;


    /**
     * The server name to which we should pretend requests to this Connector
     * were directed.  This is useful when operating Tomcat behind a proxy
     * server, so that redirects get constructed accurately.  If not specified,
     * the server name included in the <code>Host</code> header is used.
     * proxyName如果在代理配置中使用此连接器，请配置此属性以指定要为调用 request.getServerName()
     * 返回的服务器名称。有关详细信息，请参阅代理支持。
     */
    protected String proxyName = null;


    /**
     * The server port to which we should pretend requests to this Connector
     * were directed.  This is useful when operating Tomcat behind a proxy
     * server, so that redirects get constructed accurately.  If not specified,
     * the port number specified by the <code>port</code> property is used.
     * 如果在代理配置中使用此连接器，请配置此属性以指定要为调用 request.getServerName() 返回的服务器名称。有关详细信息，请参阅代理支持。
     */
    protected int proxyPort = 0;


    /**
     * The flag that controls recycling of the facades of the request
     * processing objects. If set to <code>true</code> the object facades
     * will be discarded when the request is recycled. If the security
     * manager is enabled, this setting is ignored and object facades are
     * always discarded.
     * 一个布尔值，可用于启用或禁用隔离容器内部请求处理对象的外观对象的回收。
     * true，则将在每次请求后将外观设置为垃圾收集，否则它们将被重用。
     * 启用安全管理器时，此设置不起作用。如果未指定，此属性将设置为org.apache.catalina.connector.RECYCLE_FAADES 系统属性的值，
     * 如果未设置，则设置为 false。
     */
    protected boolean discardFacades = RECYCLE_FACADES;


    /**
     * The redirect port for non-SSL to SSL redirects.
     * 非SSL到SSL重定向的端口
     */
    protected int redirectPort = 443;


    /**
     * The request scheme that will be set on all requests received
     * through this connector.
     * 将此属性设置为您希望通过调用 request.getScheme() 返回的协议的名称。
     * 例如，对于 SSL 连接器，您可以将此属性设置为 “https”。默认值为 “http”
     */
    protected String scheme = "http";


    /**
     * The secure connection flag that will be set on all requests received
     * through this connector.
     * 如果您希望调用 request.isSecure() 以为此连接器接收到的请求返回 true，请将此属性设置为 true。
     * 您可能希望在 SSL 连接器或从 SSL 加速器接收数据的非 SSL 连接器（如加密卡、SSL 设备甚至网络服务器）上使用它。 默认值为 false
     */
    protected boolean secure = false;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Connector.class);


    /**
     * The maximum number of cookies permitted for a request. Use a value less
     * than zero for no limit. Defaults to 200.
     * 请求允许的最大 cookie 数。 小于零的值意味着没有限制。 如果未指定，将使用默认值 200。
     */
    private int maxCookieCount = 200;

    /**
     * The maximum number of parameters (GET plus POST) which will be
     * automatically parsed by the container. 10000 by default. A value of less
     * than 0 means no limit.
     * 容器将自动解析的参数和值对(GET加POST)的最大数量。超过此限制的参数和值对将被忽略。
     * 值小于0表示没有限制。如果未指定，则使用默认值10000。请注意，FailedRequestFilter 筛选器可用于拒绝达到限制的请求。
     */
    protected int maxParameterCount = 10000;

    /**
     * Maximum size of a POST which will be automatically parsed by the
     * 容器表单 URL 参数解析将处理的 POST 的最大大小（以字节为单位）。通过将此属性设置为小于零的值，
     * 可以禁用该限制。如果未指定，则此属性设置为 2097152(2 MB)。请注意，FailedRequestFilter 可用于拒绝超过此限制的请求。
     */
    protected int maxPostSize = 2 * 1024 * 1024;


    /**
     * Maximum size of a POST which will be saved by the container
     * during authentication. 4kB by default
     * 在 FORM 或 CLIENT-CERT 身份验证期间容器将保存/缓冲的 POST 的最大大小(以字节为单位)。
     * 对于这两种类型的身份验证，POST 将在用户通过身份验证之前保存/缓冲。对于 CLIENT-CERT 身份验证，
     * POST 将在 SSL 握手期间进行缓冲，并在处理请求时清空缓冲区。对于表单身份验证，
     * POST 将在用户重定向到登录表单时保存，并将一直保留到用户成功进行身份验证或与身份验证请求相关联的会话到期为止。
     * 通过将此属性设置为 -1，可以禁用该限制。将该属性设置为零将禁用在身份验证期间保存 POST 数据。
     * 如果未指定，则此属性设置为 4096 (4KB)。
     */
    protected int maxSavePostSize = 4 * 1024;

    /**
     * Comma-separated list of HTTP methods that will be parsed according
     * to POST-style rules for application/x-www-form-urlencoded request bodies.
     *
     * 将使用 application/x-www-form-urlencode 对其请求正文进行逗号分隔的 HTTP 方法列表解析，
     * 以获得与POST 相同的请求参数。这在希望支持 PUT 请求的 POST 样式语义的 RESTful 应用程序中很有用。
     * 请注意，POST 以外的任何设置都会导致 Tomcat 的行为方式与 Servlet 规范的意图背道而驰。根据 HTTP 规范，
     * 此处明确禁止 HTTP 方法跟踪。缺省值为 POST
     */
    protected String parseBodyMethods = "POST";

    /**
     * A Set of methods determined by {@link #parseBodyMethods}.
     */
    protected HashSet<String> parseBodyMethodsSet;


    /**
     * Flag to use IP-based virtual hosting.
     */
    protected boolean useIPVHosts = false;


    /**
     * Coyote Protocol handler class name.
     * See {@link #Connector()} for current default.
     * Coyote 协议处理程序类名称。查看 {@link Connector()} 当前默认值。
     */
    protected final String protocolHandlerClassName;


    /**
     * Coyote protocol handler. coyote 连接器组件协议处理器
     */
    protected final ProtocolHandler protocolHandler;


    /**
     * Coyote adapter.
     * coyote适配器，负责将前端的request请求转换成HttpServletRequest转交给container（就是servlet应用，后端服务）
     */
    protected Adapter adapter = null;


    /**
     * The URI encoding in use.
     * 这指定了在 %xx 解码 URL 之后用于解码 URI 字节的字符编码。如果未指定，则将使用 UTF-8，
     * 除非 org.apache.catalina.STRICT_SERVLET_COMPLIANCE 系统属性设置为 true，在这种情况下将使用 ISO-8859-1。
     */
    private Charset uriCharset = StandardCharsets.UTF_8;


    /**
     * The behavior when an encoded solidus (slash) is submitted.
     * 设置为 reject 时，包含 %2f 序列的请求路径将被拒绝，并返回400响应。设置为解码时，
     * 包含 %2f 序列的请求路径将对该序列进行解码，/ 同时对其他 %nn 序列进行解码。
     * 当设置为通过请求路径时，包含 %2f 序列的路径将在 %2f 序列不变的情况下进行处理。
     * 如果未指定，则默认值为 reject。如果设置了不推荐使用的系统属性org.apache.tomcat.util.buf.UDecoder.ALLOW_ENCODED_SLASH，
     * 则可以修改此默认值。
     */
    @SuppressWarnings("deprecation")
    private EncodedSolidusHandling encodedSolidusHandling =
        UDecoder.ALLOW_ENCODED_SLASH ? EncodedSolidusHandling.DECODE : EncodedSolidusHandling.REJECT;


    /**
     * URI encoding as body.
     */
    protected boolean useBodyEncodingForURI = false;


    // ------------------------------------------------------------ Constructor

    /**
     * Defaults to using HTTP/1.1 NIO implementation.
     * 默认{@link org.apache.coyote.http11.Http11NioProtocol}
     */
    public Connector() {
        this("HTTP/1.1");
    }


    public Connector(String protocol) {
        boolean apr = AprStatus.getUseAprConnector() && AprStatus.isInstanceCreated()
                && AprLifecycleListener.isAprAvailable();
        ProtocolHandler p = null;
        try {
            /**基于协议类型和是否开启apr来创建协议处理器*/
            p = ProtocolHandler.create(protocol, apr);
        } catch (Exception e) {
            log.error(sm.getString(
                    "coyoteConnector.protocolHandlerInstantiationFailed"), e);
        }
        if (p != null) {
            protocolHandler = p;
            protocolHandlerClassName = protocolHandler.getClass().getName();
        } else {
            protocolHandler = null;
            protocolHandlerClassName = protocol;
        }
        // Default for Connector depends on this system property
        setThrowOnFailure(Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE"));
    }


    public Connector(ProtocolHandler protocolHandler) {
        protocolHandlerClassName = protocolHandler.getClass().getName();
        this.protocolHandler = protocolHandler;
        // Default for Connector depends on this system property
        setThrowOnFailure(Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE"));
    }



    // ------------------------------------------------------------- Properties

    /**
     * Return a property from the protocol handler.
     *
     * @param name the property name
     * @return the property value
     */
    public Object getProperty(String name) {
        if (protocolHandler == null) {
            return null;
        }
        return IntrospectionUtils.getProperty(protocolHandler, name);
    }


    /**
     * Set a property on the protocol handler.
     * 以反射的方式设置protocolHandler的各个属性值，主要就是优化线程池相关的配置
     * @param name the property name
     * @param value the property value
     * @return <code>true</code> if the property was successfully set
     */
    public boolean setProperty(String name, String value) {
        if (protocolHandler == null) {
            return false;
        }
        return IntrospectionUtils.setProperty(protocolHandler, name, value);
    }


    /**
     * Return a property from the protocol handler.
     *
     * @param name the property name
     * @return the property value
     *
     * @deprecated Use {@link #getProperty(String)}. This will be removed in
     *             Tomcat 10 onwards.
     */
    @Deprecated
    public Object getAttribute(String name) {
        return getProperty(name);
    }


    /**
     * Set a property on the protocol handler.
     *
     * @param name the property name
     * @param value the property value
     *
     * @deprecated Use {@link #setProperty(String, String)}. This will be
     *             removed in Tomcat 10 onwards.
     */
    @Deprecated
    public void setAttribute(String name, Object value) {
        setProperty(name, String.valueOf(value));
    }


    /**
     * @return the <code>Service</code> with which we are associated (if any).
     */
    public Service getService() {
        return this.service;
    }


    /**
     * Set the <code>Service</code> with which we are associated (if any).
     *
     * @param service The service that owns this Engine
     */
    public void setService(Service service) {
        this.service = service;
    }


    /**
     * @return <code>true</code> if the TRACE method is allowed. Default value
     *         is <code>false</code>.
     */
    public boolean getAllowTrace() {
        return this.allowTrace;
    }


    /**
     * Set the allowTrace flag, to disable or enable the TRACE HTTP method.
     *
     * @param allowTrace The new allowTrace flag
     */
    public void setAllowTrace(boolean allowTrace) {
        this.allowTrace = allowTrace;
        setProperty("allowTrace", String.valueOf(allowTrace));
    }


    /**
     * @return the default timeout for async requests in ms.
     */
    public long getAsyncTimeout() {
        return asyncTimeout;
    }


    /**
     * Set the default timeout for async requests.
     *
     * @param asyncTimeout The new timeout in ms.
     */
    public void setAsyncTimeout(long asyncTimeout) {
        this.asyncTimeout= asyncTimeout;
        setProperty("asyncTimeout", String.valueOf(asyncTimeout));
    }


    /**
     * @return <code>true</code> if the object facades are discarded, either
     *   when the discardFacades value is <code>true</code> or when the
     *   security manager is enabled.
     */
    public boolean getDiscardFacades() {
        return discardFacades || Globals.IS_SECURITY_ENABLED;
    }


    /**
     * Set the recycling strategy for the object facades.
     * @param discardFacades the new value of the flag
     */
    public void setDiscardFacades(boolean discardFacades) {
        this.discardFacades = discardFacades;
    }


    /**
     * @return the "enable DNS lookups" flag.
     */
    public boolean getEnableLookups() {
        return this.enableLookups;
    }


    /**
     * Set the "enable DNS lookups" flag.
     *
     * @param enableLookups The new "enable DNS lookups" flag value
     */
    public void setEnableLookups(boolean enableLookups) {
        this.enableLookups = enableLookups;
        setProperty("enableLookups", String.valueOf(enableLookups));
    }


    public int getMaxCookieCount() {
        return maxCookieCount;
    }


    public void setMaxCookieCount(int maxCookieCount) {
        this.maxCookieCount = maxCookieCount;
    }


    /**
     * @return the maximum number of parameters (GET plus POST) that will be
     * automatically parsed by the container. A value of less than 0 means no
     * limit.
     */
    public int getMaxParameterCount() {
        return maxParameterCount;
    }


    /**
     * Set the maximum number of parameters (GET plus POST) that will be
     * automatically parsed by the container. A value of less than 0 means no
     * limit.
     *
     * @param maxParameterCount The new setting
     */
    public void setMaxParameterCount(int maxParameterCount) {
        this.maxParameterCount = maxParameterCount;
        setProperty("maxParameterCount", String.valueOf(maxParameterCount));
    }


    /**
     * @return the maximum size of a POST which will be automatically
     * parsed by the container.
     */
    public int getMaxPostSize() {
        return maxPostSize;
    }


    /**
     * Set the maximum size of a POST which will be automatically
     * parsed by the container.
     *
     * @param maxPostSize The new maximum size in bytes of a POST which will
     * be automatically parsed by the container
     */
    public void setMaxPostSize(int maxPostSize) {
        this.maxPostSize = maxPostSize;
        setProperty("maxPostSize", String.valueOf(maxPostSize));
    }


    /**
     * @return the maximum size of a POST which will be saved by the container
     * during authentication.
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }


    /**
     * Set the maximum size of a POST which will be saved by the container
     * during authentication.
     *
     * @param maxSavePostSize The new maximum size in bytes of a POST which will
     * be saved by the container during authentication.
     */
    public void setMaxSavePostSize(int maxSavePostSize) {
        this.maxSavePostSize = maxSavePostSize;
        setProperty("maxSavePostSize", String.valueOf(maxSavePostSize));
    }


    /**
     * @return the HTTP methods which will support body parameters parsing
     */
    public String getParseBodyMethods() {
        return this.parseBodyMethods;
    }


    /**
     * Set list of HTTP methods which should allow body parameter
     * parsing. This defaults to <code>POST</code>.
     *
     * @param methods Comma separated list of HTTP method names
     */
    public void setParseBodyMethods(String methods) {

        HashSet<String> methodSet = new HashSet<>();

        if (null != methods) {
            methodSet.addAll(Arrays.asList(methods.split("\\s*,\\s*")));
        }

        if (methodSet.contains("TRACE")) {
            throw new IllegalArgumentException(sm.getString("coyoteConnector.parseBodyMethodNoTrace"));
        }

        this.parseBodyMethods = methods;
        this.parseBodyMethodsSet = methodSet;
        setProperty("parseBodyMethods", methods);
    }


    protected boolean isParseBodyMethod(String method) {
        return parseBodyMethodsSet.contains(method);
    }


    /**
     * @return the port number on which this connector is configured to listen
     * for requests. The special value of 0 means select a random free port
     * when the socket is bound.
     */
    public int getPort() {
        // Try shortcut that should work for nearly all uses first as it does
        // not use reflection and is therefore faster.
        if (protocolHandler instanceof AbstractProtocol<?>) {
            return ((AbstractProtocol<?>) protocolHandler).getPort();
        }
        // Fall back for custom protocol handlers not based on AbstractProtocol
        Object port = getProperty("port");
        if (port instanceof Integer) {
            return ((Integer) port).intValue();
        }
        // Usually means an invalid protocol has been configured
        return -1;
    }


    /**
     * Set the port number on which we listen for requests.
     *
     * @param port The new port number
     */
    public void setPort(int port) {
        setProperty("port", String.valueOf(port));
    }


    public int getPortOffset() {
        // Try shortcut that should work for nearly all uses first as it does
        // not use reflection and is therefore faster.
        if (protocolHandler instanceof AbstractProtocol<?>) {
            return ((AbstractProtocol<?>) protocolHandler).getPortOffset();
        }
        // Fall back for custom protocol handlers not based on AbstractProtocol
        Object port = getProperty("portOffset");
        if (port instanceof Integer) {
            return ((Integer) port).intValue();
        }
        // Usually means an invalid protocol has been configured.
        return 0;
    }


    public void setPortOffset(int portOffset) {
        setProperty("portOffset", String.valueOf(portOffset));
    }


    public int getPortWithOffset() {
        int port = getPort();
        // Zero is a special case and negative values are invalid
        if (port > 0) {
            return port + getPortOffset();
        }
        return port;
    }


    /**
     * @return the port number on which this connector is listening to requests.
     * If the special value for {@link #getPort} of zero is used then this method
     * will report the actual port bound.
     */
    public int getLocalPort() {
        return ((Integer) getProperty("localPort")).intValue();
    }


    /**
     * @return the Coyote protocol handler in use.
     */
    public String getProtocol() {
        boolean apr = AprStatus.getUseAprConnector();
        if ((!apr && org.apache.coyote.http11.Http11NioProtocol.class.getName().equals(protocolHandlerClassName))
                || (apr && org.apache.coyote.http11.Http11AprProtocol.class.getName().equals(protocolHandlerClassName))) {
            return "HTTP/1.1";
        } else if ((!apr && org.apache.coyote.ajp.AjpNioProtocol.class.getName().equals(protocolHandlerClassName))
                || (apr && org.apache.coyote.ajp.AjpAprProtocol.class.getName().equals(protocolHandlerClassName))) {
            return "AJP/1.3";
        }
        return protocolHandlerClassName;
    }


    /**
     * @return the class name of the Coyote protocol handler in use.
     */
    public String getProtocolHandlerClassName() {
        return this.protocolHandlerClassName;
    }


    /**
     * @return the protocol handler associated with the connector.
     */
    public ProtocolHandler getProtocolHandler() {
        return this.protocolHandler;
    }


    /**
     * @return the proxy server name for this Connector.
     */
    public String getProxyName() {
        return this.proxyName;
    }


    /**
     * Set the proxy server name for this Connector.
     *
     * @param proxyName The new proxy server name
     */
    public void setProxyName(String proxyName) {

        if(proxyName != null && proxyName.length() > 0) {
            this.proxyName = proxyName;
        } else {
            this.proxyName = null;
        }
        setProperty("proxyName", this.proxyName);
    }


    /**
     * @return the proxy server port for this Connector.
     */
    public int getProxyPort() {
        return this.proxyPort;
    }


    /**
     * Set the proxy server port for this Connector.
     *
     * @param proxyPort The new proxy server port
     */
    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
        setProperty("proxyPort", String.valueOf(proxyPort));
    }


    /**
     * @return the port number to which a request should be redirected if
     * it comes in on a non-SSL port and is subject to a security constraint
     * with a transport guarantee that requires SSL.
     */
    public int getRedirectPort() {
        return this.redirectPort;
    }


    /**
     * Set the redirect port number.
     *
     * @param redirectPort The redirect port number (non-SSL to SSL)
     */
    public void setRedirectPort(int redirectPort) {
        this.redirectPort = redirectPort;
        setProperty("redirectPort", String.valueOf(redirectPort));
    }


    public int getRedirectPortWithOffset() {
        return getRedirectPort() + getPortOffset();
    }


    /**
     * @return the scheme that will be assigned to requests received
     * through this connector.  Default value is "http".
     */
    public String getScheme() {
        return this.scheme;
    }


    /**
     * Set the scheme that will be assigned to requests received through
     * this connector.
     *
     * @param scheme The new scheme
     */
    public void setScheme(String scheme) {
        this.scheme = scheme;
    }


    /**
     * @return the secure connection flag that will be assigned to requests
     * received through this connector.  Default value is "false".
     */
    public boolean getSecure() {
        return this.secure;
    }


    /**
     * Set the secure connection flag that will be assigned to requests
     * received through this connector.
     *
     * @param secure The new secure connection flag
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
        setProperty("secure", Boolean.toString(secure));
    }


    /**
     * @return the name of character encoding to be used for the URI using the
     * original case.
     */
    public String getURIEncoding() {
        return uriCharset.name();
    }


    /**
     *
     * @return The Charset to use to convert raw URI bytes (after %nn decoding)
     *         to characters. This will never be null
     */
    public Charset getURICharset() {
        return uriCharset;
    }

    /**
     * Set the URI encoding to be used for the URI.
     *
     * @param URIEncoding The new URI character encoding.
     */
    public void setURIEncoding(String URIEncoding) {
        try {
             Charset charset = B2CConverter.getCharset(URIEncoding);
             if (!CharsetUtil.isAsciiSuperset(charset)) {
                 log.error(sm.getString("coyoteConnector.notAsciiSuperset", URIEncoding));
             }
             uriCharset = charset;
        } catch (UnsupportedEncodingException e) {
            log.error(sm.getString("coyoteConnector.invalidEncoding", URIEncoding, uriCharset.name()), e);
        }
    }


    /**
     * @return the true if the entity body encoding should be used for the URI.
     */
    public boolean getUseBodyEncodingForURI() {
        return this.useBodyEncodingForURI;
    }


    /**
     * Set if the entity body encoding should be used for the URI.
     *
     * @param useBodyEncodingForURI The new value for the flag.
     */
    public void setUseBodyEncodingForURI(boolean useBodyEncodingForURI) {
        this.useBodyEncodingForURI = useBodyEncodingForURI;
        setProperty("useBodyEncodingForURI", String.valueOf(useBodyEncodingForURI));
    }

    /**
     * Indicates whether the generation of an X-Powered-By response header for
     * Servlet-generated responses is enabled or disabled for this Connector.
     *
     * @return <code>true</code> if generation of X-Powered-By response header is enabled,
     * false otherwise
     */
    public boolean getXpoweredBy() {
        return xpoweredBy;
    }


    /**
     * Enables or disables the generation of an X-Powered-By header (with value
     * Servlet/2.5) for all servlet-generated responses returned by this
     * Connector.
     *
     * @param xpoweredBy true if generation of X-Powered-By response header is
     * to be enabled, false otherwise
     */
    public void setXpoweredBy(boolean xpoweredBy) {
        this.xpoweredBy = xpoweredBy;
        setProperty("xpoweredBy", String.valueOf(xpoweredBy));
    }


    /**
     * Enable the use of IP-based virtual hosting.
     *
     * @param useIPVHosts <code>true</code> if Hosts are identified by IP,
     *                    <code>false</code> if Hosts are identified by name.
     */
    public void setUseIPVHosts(boolean useIPVHosts) {
        this.useIPVHosts = useIPVHosts;
        setProperty("useIPVHosts", String.valueOf(useIPVHosts));
    }


    /**
     * Test if IP-based virtual hosting is enabled.
     *
     * @return <code>true</code> if IP vhosts are enabled
     */
    public boolean getUseIPVHosts() {
        return useIPVHosts;
    }


    public String getExecutorName() {
        Object obj = protocolHandler.getExecutor();
        if (obj instanceof org.apache.catalina.Executor) {
            return ((org.apache.catalina.Executor) obj).getName();
        }
        return INTERNAL_EXECUTOR_NAME;
    }


    public void addSslHostConfig(SSLHostConfig sslHostConfig) {
        protocolHandler.addSslHostConfig(sslHostConfig);
    }


    public SSLHostConfig[] findSslHostConfigs() {
        return protocolHandler.findSslHostConfigs();
    }


    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol) {
        protocolHandler.addUpgradeProtocol(upgradeProtocol);
    }


    public UpgradeProtocol[] findUpgradeProtocols() {
        return protocolHandler.findUpgradeProtocols();
    }


    public String getEncodedSolidusHandling() {
        return encodedSolidusHandling.getValue();
    }


    public void setEncodedSolidusHandling(String encodedSolidusHandling) {
        this.encodedSolidusHandling = EncodedSolidusHandling.fromString(encodedSolidusHandling);
    }


    public EncodedSolidusHandling getEncodedSolidusHandlingInternal() {
        return encodedSolidusHandling;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Create (or allocate) and return a Request object suitable for
     * specifying the contents of a Request to the responsible Container.
     *
     * @return a new Servlet request object
     */
    public Request createRequest() {
        return new Request(this);
    }


    /**
     * Create (or allocate) and return a Response object suitable for
     * receiving the contents of a Response from the responsible Container.
     *
     * @return a new Servlet response object
     */
    public Response createResponse() {
        int size = protocolHandler.getDesiredBufferSize();
        if (size > 0) {
            return new Response(size);
        } else {
            return new Response();
        }
    }


    protected String createObjectNameKeyProperties(String type) {

        Object addressObj = getProperty("address");

        StringBuilder sb = new StringBuilder("type=");
        sb.append(type);
        String id = (protocolHandler != null) ? protocolHandler.getId() : null;
        if (id != null) {
            // Maintain MBean name compatibility, even if not accurate
            sb.append(",port=0,address=");
            sb.append(ObjectName.quote(id));
        } else {
            sb.append(",port=");
            int port = getPortWithOffset();
            if (port > 0) {
                sb.append(port);
            } else {
                sb.append("auto-");
                sb.append(getProperty("nameIndex"));
            }
            String address = "";
            if (addressObj instanceof InetAddress) {
                address = ((InetAddress) addressObj).getHostAddress();
            } else if (addressObj != null) {
                address = addressObj.toString();
            }
            if (address.length() > 0) {
                sb.append(",address=");
                sb.append(ObjectName.quote(address));
            }
        }
        return sb.toString();
    }


    /**
     * Pause the connector.
     */
    public void pause() {
        try {
            if (protocolHandler != null) {
                protocolHandler.pause();
            }
        } catch (Exception e) {
            log.error(sm.getString("coyoteConnector.protocolHandlerPauseFailed"), e);
        }
    }


    /**
     * Resume the connector.
     */
    public void resume() {
        try {
            if (protocolHandler != null) {
                protocolHandler.resume();
            }
        } catch (Exception e) {
            log.error(sm.getString("coyoteConnector.protocolHandlerResumeFailed"), e);
        }
    }


    @Override
    protected void initInternal() throws LifecycleException {
        System.out.println("======> Connector init.");
        super.initInternal();

        if (protocolHandler == null) { // digester框架会构造connector相关的实例，同时注入xml中的属性值，所以这个地方等于null是有问题的
            throw new LifecycleException(
                    sm.getString("coyoteConnector.protocolHandlerInstantiationFailed"));
        }

        // Initialize adapter （protocolHandler在Connector构造函数的时候实例化），实例化coyote适配器
        adapter = new CoyoteAdapter(this);
        /**协议处理器设置coyote适配器（coyote模块中的request/response转换成catalina模块中的request/response）*/
        protocolHandler.setAdapter(adapter);
        if (service != null) {
            /**复用server组件的定时器线程池，实际上最终受益的是NioEndpoint {@link AbstractProtocol#setUtilityExecutor(ScheduledExecutorService)}*/
            protocolHandler.setUtilityExecutor(service.getServer().getUtilityExecutor());
        }

        // Make sure parseBodyMethodsSet has a default
        if (null == parseBodyMethodsSet) {
            setParseBodyMethods(getParseBodyMethods());
        }

        if (protocolHandler.isAprRequired() && !AprStatus.isInstanceCreated()) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerNoAprListener",
                    getProtocolHandlerClassName()));
        }
        if (protocolHandler.isAprRequired() && !AprStatus.isAprAvailable()) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerNoAprLibrary",
                    getProtocolHandlerClassName()));
        }
        if (AprStatus.isAprAvailable() && AprStatus.getUseOpenSSL() &&
                protocolHandler instanceof AbstractHttp11JsseProtocol) {
            AbstractHttp11JsseProtocol<?> jsseProtocolHandler =
                    (AbstractHttp11JsseProtocol<?>) protocolHandler;
            if (jsseProtocolHandler.isSSLEnabled() &&
                    jsseProtocolHandler.getSslImplementationName() == null) {
                // OpenSSL is compatible with the JSSE configuration, so use it if APR is available
                jsseProtocolHandler.setSslImplementationName(OpenSSLImplementation.class.getName());
            }
        }

        try {
            /**协议处理器的初始化，非常核心*/
            protocolHandler.init();
        } catch (Exception e) {
            throw new LifecycleException(
                    sm.getString("coyoteConnector.protocolHandlerInitializationFailed"), e);
        }
    }


    /**
     * Begin processing requests via this Connector.
     *
     * @exception LifecycleException if a fatal startup error occurs
     */
    @Override
    protected void startInternal() throws LifecycleException {
        System.out.println("======> Connector["+this.getProtocol()+"/"+this.getPort()+"] start.");
        // Validate settings before starting
        String id = (protocolHandler != null) ? protocolHandler.getId() : null;
        if (id == null && getPortWithOffset() < 0) {
            throw new LifecycleException(sm.getString(
                    "coyoteConnector.invalidPort", Integer.valueOf(getPortWithOffset())));
        }

        setState(LifecycleState.STARTING);

        try {
            /**
             * 1个Service仅有一个Engine容器引擎（Servlet容器引擎）
             * 但是会有很多个Connector。1个端口对应1个Connector，1个Connector对应一个协议处理器
             * 协议处理器关联的endpoint通信端点负责处理tcp/ip协议，
             * 而协议处理器下属的连接处理器关联的协议processor则是处理应用层协议的（HTTP1.1 or HTTP2 or APR）
             */
            protocolHandler.start();
        } catch (Exception e) {
            throw new LifecycleException(
                    sm.getString("coyoteConnector.protocolHandlerStartFailed"), e);
        }
    }


    /**
     * Terminate processing requests via this Connector.
     *
     * @exception LifecycleException if a fatal shutdown error occurs
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);

        try {
            if (protocolHandler != null) {
                protocolHandler.stop();
            }
        } catch (Exception e) {
            throw new LifecycleException(
                    sm.getString("coyoteConnector.protocolHandlerStopFailed"), e);
        }
    }


    @Override
    protected void destroyInternal() throws LifecycleException {
        try {
            if (protocolHandler != null) {
                protocolHandler.destroy();
            }
        } catch (Exception e) {
            throw new LifecycleException(
                    sm.getString("coyoteConnector.protocolHandlerDestroyFailed"), e);
        }

        if (getService() != null) {
            getService().removeConnector(this);
        }

        super.destroyInternal();
    }


    /**
     * Provide a useful toString() implementation as it may be used when logging
     * Lifecycle errors to identify the component.
     */
    @Override
    public String toString() {
        // Not worth caching this right now
        StringBuilder sb = new StringBuilder("Connector[");
        sb.append(getProtocol());
        sb.append('-');
        String id = (protocolHandler != null) ? protocolHandler.getId() : null;
        if (id != null) {
            sb.append(id);
        } else {
            int port = getPortWithOffset();
            if (port > 0) {
                sb.append(port);
            } else {
                sb.append("auto-");
                sb.append(getProperty("nameIndex"));
            }
        }
        sb.append(']');
        return sb.toString();
    }


    // -------------------- JMX registration  --------------------

    @Override
    protected String getDomainInternal() {
        Service s = getService();
        if (s == null) {
            return null;
        } else {
            return service.getDomain();
        }
    }

    @Override
    protected String getObjectNameKeyProperties() {
        return createObjectNameKeyProperties("Connector");
    }

}
