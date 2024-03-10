package com.appleyk;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RequestFilterValve;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * <p>越努力，越幸运</p>
 *
 * @author appleyk
 * @version V.0.1.1
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on  下午11:56 2022/9/18
 */
public class TestValve extends RequestFilterValve {
    private static final Log log = LogFactory.getLog(TestValve.class);

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        log.info("---->自定义valve阀门，对请求(流量或称作数据)进行特出处理并放行交给下一个阀门处理");
        log.info(String.format("context路径：%s",request.getContext().getPath()));
        /**传递给下一个valve*/
        getNext().invoke(request,response);
    }

    @Override
    protected Log getLog() {
        return null;
    }
}
