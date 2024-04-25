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

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
// http1.1和NIO实现
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

    private static final Log log = LogFactory.getLog(Http11NioProtocol.class);

    /**这个协议，默认创建的端点是Nio类型的，这里面可是coyote连接器干活的核心类啊*/
    public Http11NioProtocol() {
        /**一层层调用父类的构造器。直到AbstractProtocol，把NioEndpoint层层父类传*/
        super(new NioEndpoint());
    }


    @Override
    protected Log getLog() { return log; }


    // -------------------- Pool setup --------------------

    /**
     * NO-OP. 空操作
     *
     * @param count Unused
     *
     * @deprecated This setter will be removed in Tomcat 10.
     */
    @Deprecated
    public void setPollerThreadCount(int count) {
    }

    /**
     * Always returns 1. poller线程仅有一个
     *
     * @return 1
     *
     * @deprecated This getter will be removed in Tomcat 10.
     * 既然默认，10版本后将被移除
     */
    @Deprecated
    public int getPollerThreadCount() {
        return 1;
    }

    public void setSelectorTimeout(long timeout) {
        ((NioEndpoint)getEndpoint()).setSelectorTimeout(timeout);
    }

    public long getSelectorTimeout() {
        return ((NioEndpoint)getEndpoint()).getSelectorTimeout();
    }

    public void setPollerThreadPriority(int threadPriority) {
        ((NioEndpoint)getEndpoint()).setPollerThreadPriority(threadPriority);
    }

    public int getPollerThreadPriority() {
      return ((NioEndpoint)getEndpoint()).getPollerThreadPriority();
    }


    // ----------------------------------------------------- JMX related methods

    @Override
    protected String getNamePrefix() {
        if (isSSLEnabled()) {
            /**https-openssl-nio/https-jsse-nio*/
            return "https-" + getSslImplementationShortName()+ "-nio";
        } else {
            return "http-nio";
        }
    }
}
