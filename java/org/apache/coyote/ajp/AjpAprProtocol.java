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
package org.apache.coyote.ajp;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AprEndpoint;


/**
 * This the APR/native based protocol handler implementation for AJP.
 * 使用AJP协议反向代理TOMCAT
 * AJP（APACHE JSERV PROTOCOL）协议：
 * 定向包协议，是一个二进制的TCP传输协议，相比HTTP这种纯文本的协议来说，效率和性能更高，也做了很多优化。但是浏览器并不能直接支持AJP13协议，只支持HTTP协议。所以实际情况是，通过Apache的proxy_ajp模块进行反向代理，暴露成http协议给客户端访问
 *
 * 此协议由于爆出漏洞（已修复），所以Tomcat 8.5.51之后版本基于安全需求默认禁用AJP协议
 * 影响版本：
 *
 * Apache Tomcat 9.x < 9.0.31
 * Apache Tomcat 8.x < 8.5.51
 * Apache Tomcat 7.x < 7.0.100
 * Apache Tomcat 6.x
 * 启用AJP协议方法：<Connector protocol="AJP/1.3"  address="0.0.0.0" port="8009" redirectPort="8443" secretRequired="" />
 *
 */
public class AjpAprProtocol extends AbstractAjpProtocol<Long> {

    private static final Log log = LogFactory.getLog(AjpAprProtocol.class);

    @Override
    protected Log getLog() { return log; }


    @Override
    public boolean isAprRequired() {
        // Override since this protocol implementation requires the APR/native
        // library
        return true;
    }


    // ------------------------------------------------------------ Constructor

    public AjpAprProtocol() {
        super(new AprEndpoint());
    }


    // --------------------------------------------------------- Public Methods

    public int getPollTime() { return ((AprEndpoint)getEndpoint()).getPollTime(); }
    public void setPollTime(int pollTime) { ((AprEndpoint)getEndpoint()).setPollTime(pollTime); }


    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        return "ajp-apr";
    }
}
