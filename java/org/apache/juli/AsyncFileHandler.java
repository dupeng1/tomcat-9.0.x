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
package org.apache.juli;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
/**
 * A {@link FileHandler} implementation that uses a queue of log entries.
 *
 * <p>Configuration properties are inherited from the {@link FileHandler}
 * class. This class does not add its own configuration properties for the
 * logging configuration, but relies on the following system properties
 * instead:</p>
 *
 * <ul>
 *   <li><code>org.apache.juli.AsyncOverflowDropType</code>
 *    Default value: <code>1</code></li>
 *   <li><code>org.apache.juli.AsyncMaxRecordCount</code>
 *    Default value: <code>10000</code></li>
 *   <li><code>org.apache.juli.AsyncLoggerPollInterval</code>
 *    Default value: <code>1000</code></li>
 * </ul>
 *
 * AsyncFileHandler 继承自 FileHandler，实现了异步的写操作。
 * 其中缓存存储是通过阻塞双端队列 LinkedBlockingDeque 来实现的。
 * 当应用要通过这个 Handler 来记录一条消息时，消息会先被存储到队列中，
 * 而在后台会有一个专门的线程来处理队列中的消息，取出的消息会通过父类的 publish 方法写入相应文件内。
 * 这样就可以在大量日志需要写入的时候起到缓冲作用，防止都阻塞在写日志这个动作上。需要注意的是，
 * 我们可以为阻塞双端队列设置不同的模式，在不同模式下，对新进入的消息有不同的处理方式，
 * 有些模式下会直接丢弃一些日志
 *
 * OVERFLOW_DROP_LAST：丢弃栈顶的元素
 * OVERFLOW_DROP_FIRSH：丢弃栈底的元素
 * OVERFLOW_DROP_FLUSH：等待一定时间并重试，不会丢失元素
 * OVERFLOW_DROP_CURRENT：丢弃放入的元素
 *
 * <p>See the System Properties page in the configuration reference of Tomcat.</p>
 */
public class AsyncFileHandler extends FileHandler {

    public static final int OVERFLOW_DROP_LAST    = 1;
    public static final int OVERFLOW_DROP_FIRST   = 2;
    public static final int OVERFLOW_DROP_FLUSH   = 3;
    public static final int OVERFLOW_DROP_CURRENT = 4;

    public static final int DEFAULT_OVERFLOW_DROP_TYPE = 1;
    public static final int DEFAULT_MAX_RECORDS        = 10000;
    public static final int DEFAULT_LOGGER_SLEEP_TIME  = 1000;

    public static final int OVERFLOW_DROP_TYPE = Integer.parseInt(
            System.getProperty("org.apache.juli.AsyncOverflowDropType",
                               Integer.toString(DEFAULT_OVERFLOW_DROP_TYPE)));
    public static final int MAX_RECORDS = Integer.parseInt(
            System.getProperty("org.apache.juli.AsyncMaxRecordCount",
                               Integer.toString(DEFAULT_MAX_RECORDS)));
    public static final int LOGGER_SLEEP_TIME = Integer.parseInt(
            System.getProperty("org.apache.juli.AsyncLoggerPollInterval",
                               Integer.toString(DEFAULT_LOGGER_SLEEP_TIME)));

    protected static final LinkedBlockingDeque<LogEntry> queue =
            new LinkedBlockingDeque<>(MAX_RECORDS);

    protected static final LoggerThread logger = new LoggerThread();

    static {
        logger.start();
    }

    protected volatile boolean closed = false;

    public AsyncFileHandler() {
        this(null, null, null);
    }

    public AsyncFileHandler(String directory, String prefix, String suffix) {
        this(directory, prefix, suffix, null);
    }

    public AsyncFileHandler(String directory, String prefix, String suffix, Integer maxDays) {
        super(directory, prefix, suffix, maxDays);
        open();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        super.close();
    }

    @Override
    protected void open() {
        if (!closed) {
            return;
        }
        closed = false;
        super.open();
    }


    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        // fill source entries, before we hand the record over to another
        // thread with another class loader
        record.getSourceMethodName();
        LogEntry entry = new LogEntry(record, this);
        boolean added = false;
        try {
            while (!added && !queue.offer(entry)) {
                switch (OVERFLOW_DROP_TYPE) {
                    case OVERFLOW_DROP_LAST: {
                        //remove the last added element
                        queue.pollLast();
                        break;
                    }
                    case OVERFLOW_DROP_FIRST: {
                        //remove the first element in the queue
                        queue.pollFirst();
                        break;
                    }
                    case OVERFLOW_DROP_FLUSH: {
                        added = queue.offer(entry, 1000, TimeUnit.MILLISECONDS);
                        break;
                    }
                    case OVERFLOW_DROP_CURRENT: {
                        added = true;
                        break;
                    }
                }//switch
            }//while
        } catch (InterruptedException x) {
            // Allow thread to be interrupted and back out of the publish
            // operation. No further action required.
        }

    }

    protected void publishInternal(LogRecord record) {
        super.publish(record);
    }

    protected static class LoggerThread extends Thread {
        public LoggerThread() {
            this.setDaemon(true);
            this.setName("AsyncFileHandlerWriter-" + System.identityHashCode(this));
        }

        @Override
        public void run() {
            while (true) {
                try {
                    LogEntry entry = queue.poll(LOGGER_SLEEP_TIME, TimeUnit.MILLISECONDS);
                    if (entry != null) {
                        entry.flush();
                    }
                } catch (InterruptedException x) {
                    // Ignore the attempt to interrupt the thread.
                } catch (Exception x) {
                    x.printStackTrace();
                }
            }
        }
    }

    protected static class LogEntry {
        private final LogRecord record;
        private final AsyncFileHandler handler;
        public LogEntry(LogRecord record, AsyncFileHandler handler) {
            super();
            this.record = record;
            this.handler = handler;
        }

        public boolean flush() {
            if (handler.closed) {
                return false;
            } else {
                handler.publishInternal(record);
                return true;
            }
        }
    }
}
