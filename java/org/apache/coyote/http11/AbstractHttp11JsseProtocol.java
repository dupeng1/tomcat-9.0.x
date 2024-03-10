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
package org.apache.coyote.http11;

import org.apache.tomcat.util.net.AbstractJsseEndpoint;
import org.apache.tomcat.util.net.openssl.OpenSSLImplementation;

/**
 * Tomcat JSSE (Java Secure Socket Extension) 是 Tomcat 服务器中用于处理安全套接字层（SSL/TLS）通信的扩展库。
 * JSSE 提供了对 SSL/TLS 协议的实现，用于在客户端和服务器之间建立安全的加密连接。
 *
 * JSSE 提供了以下主要功能：
 *
 * SSL/TLS 支持：JSSE 实现了 SSL/TLS 协议，允许在客户端和服务器之间进行加密通信，确保数据的安全性和完整性。
 * 密钥管理：JSSE 提供了密钥管理工具，用于生成、存储和管理数字证书、密钥对和信任库。
 * 加密算法支持：JSSE 支持多种加密算法，包括对称加密算法（如 AES、DES）、非对称加密算法（如 RSA、DSA）和哈希算法（如 MD5、SHA）。
 * 客户端身份验证：JSSE 允许服务器要求客户端进行身份验证，确保只有经过授权的客户端可以访问受保护的资源。
 * 协议协商：JSSE 支持协议协商，允许客户端和服务器选择适当的协议和加密算法进行通信。
 * 在 Tomcat 中，JSSE 是通过 Java 的标准安全 API 来实现的。通过配置 Tomcat 的 Connector，
 * 可以启用 JSSE 并配置相关的 SSL/TLS 参数，以实现安全的加密通信。
 *
 * 要配置 Tomcat 的 JSSE，您需要进行以下步骤：
 *
 * 1. 生成证书和密钥对：
 *    - 使用 Java 的 `keytool` 命令生成自签名证书和密钥对，例如：
 *      ```
 *      keytool -genkeypair -alias mycert -keyalg RSA -keysize 2048 -keystore keystore.jks -validity 365
 *      ```
 *    - 上述命令将生成一个名为 `keystore.jks` 的密钥库文件，并要求您提供一些相关信息，例如密钥库密码、组织单位等。
 *
 * 2. 配置 Tomcat Connector：
 *    - 打开 Tomcat 的 `server.xml` 文件。
 *    - 找到 `<Connector>` 元素，该元素用于配置 Tomcat 的连接器。
 *    - 在 `<Connector>` 元素中添加以下属性来启用 SSL/TLS：
 *      ```
 *      <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
 *                 maxThreads="150" SSLEnabled="true"
 *                 scheme="https" secure="true"
 *                 keystoreFile="/path/to/keystore.jks" keystorePass="your_password"
 *                 clientAuth="false" sslProtocol="TLS" />
 *      ```
 *      - `port`：指定 SSL/TLS 连接器的端口号（默认为 8443）。
 *      - `protocol`：指定使用的协议（在此示例中为 NIO 协议）。
 *      - `SSLEnabled`：启用 SSL/TLS 支持。
 *      - `scheme` 和 `secure`：指定连接使用的协议和安全标志。
 *      - `keystoreFile`：指定生成的密钥库文件的路径。
 *      - `keystorePass`：指定密钥库的密码。
 *      - `clientAuth`：设置为 `true` 可要求客户端进行身份验证。
 *      - `sslProtocol`：指定使用的 SSL/TLS 协议（例如 TLS）。
 *
 * 3. 保存并重启 Tomcat：
 *    - 保存 `server.xml` 文件，并重新启动 Tomcat 服务器。
 *    - Tomcat 将加载配置的 JSSE 设置，并在指定的端口上启用 SSL/TLS。
 *
 * 请注意，以上步骤提供了一个基本的配置示例。您可以根据自己的需求进行进一步的配置，例如添加更多的 SSL/TLS 参数或启用双向身份验证等。确保您理解每个配置选项的含义和影响，并根据实际情况进行相应的设置。
 */
public abstract class AbstractHttp11JsseProtocol<S>
        extends AbstractHttp11Protocol<S> {

    public AbstractHttp11JsseProtocol(AbstractJsseEndpoint<S,?> endpoint) {
        super(endpoint);
    }


    @Override
    protected AbstractJsseEndpoint<S,?> getEndpoint() {
        // Over-ridden to add cast
        return (AbstractJsseEndpoint<S,?>) super.getEndpoint();
    }


    protected String getSslImplementationShortName() {
        if (OpenSSLImplementation.class.getName().equals(getSslImplementationName())) {
            return "openssl";
        }
        return "jsse";
    }

    public String getSslImplementationName() { return getEndpoint().getSslImplementationName(); }
    public void setSslImplementationName(String s) { getEndpoint().setSslImplementationName(s); }


    public int getSniParseLimit() { return getEndpoint().getSniParseLimit(); }
    public void setSniParseLimit(int sniParseLimit) {
        getEndpoint().setSniParseLimit(sniParseLimit);
    }
}
