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

import java.io.IOException;
import java.io.Writer;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Globals;
import org.apache.coyote.ActionCode;
import org.apache.coyote.CloseNowException;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.C2BConverter;
import org.apache.tomcat.util.res.StringManager;

/**
 * The buffer used by Tomcat response. This is a derivative of the Tomcat 3.3
 * OutputBuffer, with the removal of some of the state handling (which in
 * Coyote is mostly the Processor's responsibility).
 *
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public class OutputBuffer extends Writer {

    private static final StringManager sm = StringManager.getManager(OutputBuffer.class);

    public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;

    /**
     * Encoder cache.
     */
    private final Map<Charset, C2BConverter> encoders = new HashMap<>();


    /**
     * Default buffer size.
     */
    private final int defaultBufferSize;

    // ----------------------------------------------------- Instance Variables

    /**
     * The byte buffer.
     * 字节缓冲区
     */
    private ByteBuffer bb;


    /**
     * The char buffer.
     * 字符缓冲区
     */
    private final CharBuffer cb;


    /**
     * State of the output buffer.
     * 输出缓冲区状态
     */
    private boolean initial = true;


    /**
     * Number of bytes written.
     * 写入字节数
     */
    private long bytesWritten = 0;


    /**
     * Number of chars written.
     * 写入字符数
     */
    private long charsWritten = 0;


    /**
     * Flag which indicates if the output buffer is closed.
     * 是否关闭
     */
    private volatile boolean closed = false;


    /**
     * Do a flush on the next operation.
     * 是否flush
     */
    private boolean doFlush = false;


    /**
     * Current char to byte converter.
     */
    protected C2BConverter conv;


    /**
     * Associated Coyote response.
     * coyote response
     */
    private Response coyoteResponse;


    /**
     * Suspended flag. All output bytes will be swallowed if this is true.
     * 是否暂停
     */
    private volatile boolean suspended = false;


    // ----------------------------------------------------------- Constructors

    /**
     * Create the buffer with the specified initial size.
     *
     * @param size Buffer size to use
     */
    public OutputBuffer(int size) {
        defaultBufferSize = size;
        bb = ByteBuffer.allocate(size);
        clear(bb);
        cb = CharBuffer.allocate(size);
        clear(cb);
    }


    // ------------------------------------------------------------- Properties

    /**
     * Associated Coyote response.
     *
     * @param coyoteResponse Associated Coyote response
     */
    // 设置Coyote response
    public void setResponse(Response coyoteResponse) {
        this.coyoteResponse = coyoteResponse;
    }


    /**
     * Is the response output suspended ?
     *
     * @return suspended flag value
     */
    // 返回是否暂停
    public boolean isSuspended() {
        return this.suspended;
    }


    /**
     * Set the suspended flag.
     *
     * @param suspended New suspended flag value
     */
    // 设置是否暂停
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }


    /**
     * Is the response output closed ?
     *
     * @return closed flag value
     */
    // 是否关闭
    public boolean isClosed() {
        return this.closed;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Recycle the output buffer.
     */
    // 回收
    public void recycle() {

        initial = true;
        bytesWritten = 0;
        charsWritten = 0;

        if (bb.capacity() > 16 * defaultBufferSize) {
            // Discard buffers which are too large
            bb = ByteBuffer.allocate(defaultBufferSize);
        }
        clear(bb);
        clear(cb);
        closed = false;
        suspended = false;
        doFlush = false;

        if (conv != null) {
            conv.recycle();
            conv = null;
        }
    }


    /**
     * Close the output buffer. This tries to calculate the response size if
     * the response has not been committed yet.
     *
     * @throws IOException An underlying IOException occurred
     */
    // 关闭，先flush再close
    @Override
    public void close() throws IOException {

        if (closed) {
            return;
        }
        if (suspended) {
            return;
        }

        // If there are chars, flush all of them to the byte buffer now as bytes are used to
        // calculate the content-length (if everything fits into the byte buffer, of course).
        // 如果字符输出缓冲区有内容，则刷新字符输出缓冲区
        if (cb.remaining() > 0) {
            // 刷新字符输出缓冲区
            flushCharBuffer();
        }

        if ((!coyoteResponse.isCommitted()) && (coyoteResponse.getContentLengthLong() == -1)
                && !coyoteResponse.getRequest().method().equals("HEAD")) {
            // If this didn't cause a commit of the response, the final content
            // length can be calculated. Only do this if this is not a HEAD
            // request since in that case no body should have been written and
            // setting a value of zero here will result in an explicit content
            // length of zero being set on the response.
            if (!coyoteResponse.isCommitted()) {
                coyoteResponse.setContentLength(bb.remaining());
            }
        }

        if (coyoteResponse.getStatus() == HttpServletResponse.SC_SWITCHING_PROTOCOLS) {
            doFlush(true);
        } else {
            doFlush(false);
        }
        closed = true;

        // The request should have been completely read by the time the response
        // is closed. Further reads of the input a) are pointless and b) really
        // confuse AJP (bug 50189) so close the input buffer to prevent them.
        // coyote request inputBuffer执行close
        Request req = (Request) coyoteResponse.getRequest().getNote(CoyoteAdapter.ADAPTER_NOTES);
        req.inputBuffer.close();
        // coyote response outBuffer执行close
        coyoteResponse.action(ActionCode.CLOSE, null);
    }


    /**
     * Flush bytes or chars contained in the buffer.
     *
     * @throws IOException An underlying IOException occurred
     */
    // 刷新字符输出缓冲区和字节输出缓冲区，并刷新coyote response
    @Override
    public void flush() throws IOException {
        doFlush(true);
    }


    /**
     * Flush bytes or chars contained in the buffer.
     *
     * @param realFlush <code>true</code> if this should also cause a real network flush
     * @throws IOException An underlying IOException occurred
     */
    // 刷新字符输出缓冲区和字节输出缓冲区
    protected void doFlush(boolean realFlush) throws IOException {

        if (suspended) {
            return;
        }

        try {
            doFlush = true;
            if (initial) {
                coyoteResponse.sendHeaders();
                initial = false;
            }
            // 如果字符输出缓冲区有内容，刷新字符输出缓冲区
            if (cb.remaining() > 0) {
                flushCharBuffer();
            }
            // 如果字节输出缓冲区有内容，刷新字节输出缓冲区
            if (bb.remaining() > 0) {
                flushByteBuffer();
            }
        } finally {
            doFlush = false;
        }
        // 如果realFlush为真，则执行coyote response flush
        if (realFlush) {
            // 执行coyote response flush
            coyoteResponse.action(ActionCode.CLIENT_FLUSH, null);
            // If some exception occurred earlier, or if some IOE occurred
            // here, notify the servlet with an IOE
            if (coyoteResponse.isExceptionPresent()) {
                throw new ClientAbortException(coyoteResponse.getErrorException());
            }
        }

    }


    // ------------------------------------------------- Bytes Handling Methods

    /**
     * Sends the buffer data to the client output, checking the
     * state of Response and calling the right interceptors.
     *
     * @param buf the ByteBuffer to be written to the response
     *
     * @throws IOException An underlying IOException occurred
     */
    // 写字节缓冲区到coyote response
    public void realWriteBytes(ByteBuffer buf) throws IOException {

        if (closed) {
            return;
        }
        if (coyoteResponse == null) {
            return;
        }

        // If we really have something to write
        // buf.remaining() > 0说明字节缓冲区有需要写的内容
        if (buf.remaining() > 0) {
            // real write to the adapter
            try {
                // 使用coyoteResponse写字节缓冲区
                coyoteResponse.doWrite(buf);
            } catch (CloseNowException e) {
                // Catch this sub-class as it requires specific handling.
                // Examples where this exception is thrown:
                // - HTTP/2 stream timeout
                // Prevent further output for this response
                closed = true;
                throw e;
            } catch (IOException e) {
                // An IOException on a write is almost always due to
                // the remote client aborting the request. Wrap this
                // so that it can be handled better by the error dispatcher.
                coyoteResponse.setErrorException(e);
                throw new ClientAbortException(e);
            }
        }

    }


    // 写字节数组，字节缓冲区空则直接写，否则先写字节输出缓冲区
    public void write(byte b[], int off, int len) throws IOException {

        if (suspended) {
            return;
        }
        // 写字节数组，字节缓冲区空则直接写，否则先写字节输出缓冲区
        writeBytes(b, off, len);

    }


    // 写字节缓冲区，字节缓冲区空则直接写，否则先写字节输出缓冲区
    public void write(ByteBuffer from) throws IOException {

        if (suspended) {
            return;
        }
        // 写字节缓冲区，字节缓冲区空则直接写，否则先写字节输出缓冲区
        writeBytes(from);

    }


    // 写字节数组，字节缓冲区空则直接写，否则先写字节输出缓冲区
    private void writeBytes(byte b[], int off, int len) throws IOException {

        if (closed) {
            return;
        }
        // 写字节数组，字节缓冲区空则直接写，否则先写字节输出缓冲区
        append(b, off, len);
        bytesWritten += len;

        // if called from within flush(), then immediately flush
        // remaining bytes
        if (doFlush) {
            flushByteBuffer();
        }

    }

    // 写字节缓冲区，字节缓冲区空则直接写，否则先写字节输出缓冲区
    private void writeBytes(ByteBuffer from) throws IOException {

        if (closed) {
            return;
        }
        // 写字节缓冲区，字节缓冲区空则直接写，否则先写字节输出缓冲区
        append(from);
        bytesWritten += from.remaining();

        // if called from within flush(), then immediately flush
        // remaining bytes
        if (doFlush) {
            flushByteBuffer();
        }

    }

    // 写字节，字节输出缓冲区满先刷新字节输出缓冲区，否则先写字节输出缓冲区
    public void writeByte(int b) throws IOException {

        if (suspended) {
            return;
        }
        // 如果字节输出缓冲区满，刷新字节输出缓冲区
        if (isFull(bb)) {
            // 刷新字节输出缓冲区
            flushByteBuffer();
        }
        // 写字节到字节输出缓冲区
        transfer((byte) b, bb);
        bytesWritten++;

    }


    // ------------------------------------------------- Chars Handling Methods


    /**
     * Convert the chars to bytes, then send the data to the client.
     *
     * @param from Char buffer to be written to the response
     *
     * @throws IOException An underlying IOException occurred
     */
    // 写字符缓冲区
    // 1、先写字节输出缓冲区；
    // 2、如果待写字符缓冲区不空，刷新字节输出缓冲区；
    // 3、如果字节输出缓冲区快满了则刷新字节输出缓冲区
    public void realWriteChars(CharBuffer from) throws IOException {

        while (from.remaining() > 0) {
            // 先写到字节输出缓冲区
            conv.convert(from, bb);
            // 如果字节输出缓冲区没有待写的内容
            if (bb.remaining() == 0) {
                // Break out of the loop if more chars are needed to produce any output
                break;
            }
            // 如果字符缓冲区有待写内容，则刷新字节输出缓冲区
            if (from.remaining() > 0) {
                // 刷新字节输出缓冲区
                flushByteBuffer();
            } else if (conv.isUndeflow() && bb.limit() > bb.capacity() - 4) {
                // Handle an edge case. There are no more chars to write at the
                // moment but there is a leftover character in the converter
                // which must be part of a surrogate pair. The byte buffer does
                // not have enough space left to output the bytes for this pair
                // once it is complete )it will require 4 bytes) so flush now to
                // prevent the bytes for the leftover char and the rest of the
                // surrogate pair yet to be written from being lost.
                // See TestOutputBuffer#testUtf8SurrogateBody()
                // 如果字符缓冲区没有待写内容，并且字节输出缓冲区满了，则刷新字节输出缓冲区
                flushByteBuffer();
            }
        }

    }

    // 写字符，字符输出缓冲区满先刷新字符输出缓冲区，否则先写字符输出缓冲区
    @Override
    public void write(int c) throws IOException {

        if (suspended) {
            return;
        }
        // 如果字符输出缓冲区满，刷新字符输出缓冲区
        if (isFull(cb)) {
            // 刷新字符输出缓冲区
            flushCharBuffer();
        }
        // 写字符到字符输出缓冲区
        transfer((char) c, cb);
        charsWritten++;

    }


    // 写字符数组
    @Override
    public void write(char c[]) throws IOException {

        if (suspended) {
            return;
        }

        write(c, 0, c.length);

    }


    // 写字符数组
    @Override
    public void write(char c[], int off, int len) throws IOException {

        if (suspended) {
            return;
        }
        // 写字符数组
        append(c, off, len);
        charsWritten += len;

    }


    /**
     * Append a string to the buffer
     */
    // 写字符串一部分
    @Override
    public void write(String s, int off, int len) throws IOException {

        if (suspended) {
            return;
        }

        if (s == null) {
            throw new NullPointerException(sm.getString("outputBuffer.writeNull"));
        }

        int sOff = off;
        int sEnd = off + len;
        while (sOff < sEnd) {
            int n = transfer(s, sOff, sEnd - sOff, cb);
            sOff += n;
            if (sOff < sEnd && isFull(cb)) {
                flushCharBuffer();
            }
        }

        charsWritten += len;
    }


    // 写字符串
    @Override
    public void write(String s) throws IOException {

        if (suspended) {
            return;
        }

        if (s == null) {
            s = "null";
        }
        write(s, 0, s.length());
    }


    public void checkConverter() throws IOException {
        if (conv != null) {
            return;
        }

        Charset charset = null;

        if (coyoteResponse != null) {
            charset = coyoteResponse.getCharset();
        }

        if (charset == null) {
            if (coyoteResponse.getCharacterEncoding() != null) {
                // setCharacterEncoding() was called with an invalid character set
                // Trigger an UnsupportedEncodingException
                charset = B2CConverter.getCharset(coyoteResponse.getCharacterEncoding());
            }
            charset = org.apache.coyote.Constants.DEFAULT_BODY_CHARSET;
        }

        conv = encoders.get(charset);

        if (conv == null) {
            conv = createConverter(charset);
            encoders.put(charset, conv);
        }
    }


    private static C2BConverter createConverter(final Charset charset) throws IOException {
        if (Globals.IS_SECURITY_ENABLED) {
            try {
                return AccessController.doPrivileged(new PrivilegedCreateConverter(charset));
            } catch (PrivilegedActionException ex) {
                Exception e = ex.getException();
                if (e instanceof IOException) {
                    throw (IOException) e;
                } else {
                    throw new IOException(ex);
                }
            }
        } else {
            return new C2BConverter(charset);
        }
    }


    // --------------------  BufferedOutputStream compatibility

    public long getContentWritten() {
        return bytesWritten + charsWritten;
    }

    /**
     * Has this buffer been used at all?
     *
     * @return true if no chars or bytes have been added to the buffer since the
     *         last call to {@link #recycle()}
     */
    public boolean isNew() {
        return (bytesWritten == 0) && (charsWritten == 0);
    }


    public void setBufferSize(int size) {
        if (size > bb.capacity()) {
            bb = ByteBuffer.allocate(size);
            clear(bb);
        }
    }


    public void reset() {
        reset(false);
    }

    public void reset(boolean resetWriterStreamFlags) {
        clear(bb);
        clear(cb);
        bytesWritten = 0;
        charsWritten = 0;
        if (resetWriterStreamFlags) {
            if (conv != null) {
                conv.recycle();
            }
            conv = null;
        }
        initial = true;
    }

    // 获取缓冲区容量
    public int getBufferSize() {
        return bb.capacity();
    }


    /*
     * All the non-blocking write state information is held in the Response so
     * it is visible / accessible to all the code that needs it.
     */
    // 是否准备好输出
    public boolean isReady() {
        return coyoteResponse.isReady();
    }


    public void setWriteListener(WriteListener listener) {
        coyoteResponse.setWriteListener(listener);
    }


    public boolean isBlocking() {
        return coyoteResponse.getWriteListener() == null;
    }

    public void checkRegisterForWrite() {
        coyoteResponse.checkRegisterForWrite();
    }

    /**
     * Add data to the buffer.
     *
     * @param src Bytes array
     * @param off Offset
     * @param len Length
     * @throws IOException Writing overflow data to the output channel failed
     */
    // 写字节数组，字节缓冲区空则直接写，否则先写字节输出缓冲区
    public void append(byte src[], int off, int len) throws IOException {
        // bb.remaining() == 0说明缓冲区没有内容要输出
        if (bb.remaining() == 0) {
            appendByteArray(src, off, len);
        } else {
            // 先输出到缓冲区
            int n = transfer(src, off, len, bb);
            // 更新待输出内容长度
            len = len - n;
            // 更新待输出内容位置
            off = off + n;
            // 如果还有待输出内容，缓冲区已满
            if (len > 0 && isFull(bb)) {
                // 刷新缓冲区
                flushByteBuffer();
                // 继续写待输出内容
                appendByteArray(src, off, len);
            }
        }
    }

    /**
     * Add data to the buffer.
     * @param src Char array
     * @param off Offset
     * @param len Length
     * @throws IOException Writing overflow data to the output channel failed
     */
    // 写字符数组
    // 如果待写字符数组长度小于字符输出缓冲区剩余空间，则先写字符输出缓冲区
    // 如果待写字符数组长度小于字符输出缓冲区容量，则先写字符输出缓冲区，再刷新，再写字符缓冲区
    public void append(char src[], int off, int len) throws IOException {
        // if we have limit and we're below
        if(len <= cb.capacity() - cb.limit()) {
            transfer(src, off, len, cb);
            return;
        }

        // Optimization:
        // If len-avail < length ( i.e. after we fill the buffer with
        // what we can, the remaining will fit in the buffer ) we'll just
        // copy the first part, flush, then copy the second part - 1 write
        // and still have some space for more. We'll still have 2 writes, but
        // we write more on the first.
        if(len + cb.limit() < 2 * cb.capacity()) {
            /* If the request length exceeds the size of the output buffer,
               flush the output buffer and then write the data directly.
               We can't avoid 2 writes, but we can write more on the second
            */
            // 先写字符输出缓冲区
            int n = transfer(src, off, len, cb);
            // 刷新字符输出缓冲区
            flushCharBuffer();
            // 再写字符输出缓冲区
            transfer(src, off + n, len - n, cb);
        } else {
            // long write - flush the buffer and write the rest
            // directly from source
            // 刷新字符缓冲区
            flushCharBuffer();
            // 使用字节输出缓冲区输出
            realWriteChars(CharBuffer.wrap(src, off, len));
        }
    }


    // 写字节缓冲区，字节缓冲区空则直接写，否则先写字节输出缓冲区
    public void append(ByteBuffer from) throws IOException {
        if (bb.remaining() == 0) {
            appendByteBuffer(from);
        } else {
            transfer(from, bb);
            if (from.hasRemaining() && isFull(bb)) {
                flushByteBuffer();
                appendByteBuffer(from);
            }
        }
    }

    // 直接写字节数组，先写字节缓冲区到coyote response，再写字节输出缓冲区
    private void appendByteArray(byte src[], int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        int limit = bb.capacity();
        // 如果待输出的内容大于缓冲区容量
        while (len > limit) {
            // 直接写要输出的内容，避免拷贝到输出缓冲区再写输出缓冲区
            realWriteBytes(ByteBuffer.wrap(src, off, limit));
            // 更新待输出内容长度
            len = len - limit;
            // 更新待输出内容位置
            off = off + limit;
        }
        // 如果待输出内容小于缓冲区容量，则先输出到缓冲区
        if (len > 0) {
            transfer(src, off, len, bb);
        }
    }

    // 直接写字节缓冲区，先写字节缓冲区到coyote response，再写字节输出缓冲区
    private void appendByteBuffer(ByteBuffer from) throws IOException {
        if (from.remaining() == 0) {
            return;
        }

        int limit = bb.capacity();
        int fromLimit = from.limit();
        while (from.remaining() > limit) {
            from.limit(from.position() + limit);
            realWriteBytes(from.slice());
            from.position(from.limit());
            from.limit(fromLimit);
        }

        if (from.remaining() > 0) {
            transfer(from, bb);
        }
    }

    // 刷新字节输出缓冲区
    private void flushByteBuffer() throws IOException {
        // 写字节输出缓冲区
        realWriteBytes(bb.slice());
        // 清空字节输出缓冲区
        clear(bb);
    }

    // 刷新字符输出缓冲区
    private void flushCharBuffer() throws IOException {
        // 写字符输出缓冲区
        realWriteChars(cb.slice());
        // 清空字符输出缓冲区
        clear(cb);
    }

    private void transfer(byte b, ByteBuffer to) {
        toWriteMode(to);
        to.put(b);
        toReadMode(to);
    }

    private void transfer(char b, CharBuffer to) {
        toWriteMode(to);
        to.put(b);
        toReadMode(to);
    }

    private int transfer(byte[] buf, int off, int len, ByteBuffer to) {
        toWriteMode(to);
        int max = Math.min(len, to.remaining());
        if (max > 0) {
            to.put(buf, off, max);
        }
        toReadMode(to);
        return max;
    }

    private int transfer(char[] buf, int off, int len, CharBuffer to) {
        toWriteMode(to);
        int max = Math.min(len, to.remaining());
        if (max > 0) {
            to.put(buf, off, max);
        }
        toReadMode(to);
        return max;
    }

    private int transfer(String s, int off, int len, CharBuffer to) {
        toWriteMode(to);
        int max = Math.min(len, to.remaining());
        if (max > 0) {
            to.put(s, off, off + max);
        }
        toReadMode(to);
        return max;
    }

    private void transfer(ByteBuffer from, ByteBuffer to) {
        toWriteMode(to);
        int max = Math.min(from.remaining(), to.remaining());
        if (max > 0) {
            int fromLimit = from.limit();
            from.limit(from.position() + max);
            to.put(from);
            from.limit(fromLimit);
        }
        toReadMode(to);
    }

    private void clear(Buffer buffer) {
        buffer.rewind().limit(0);
    }

    private boolean isFull(Buffer buffer) {
        return buffer.limit() == buffer.capacity();
    }

    private void toReadMode(Buffer buffer) {
        buffer.limit(buffer.position())
              .reset();
    }

    private void toWriteMode(Buffer buffer) {
        buffer.mark()
              .position(buffer.limit())
              .limit(buffer.capacity());
    }


    private static class PrivilegedCreateConverter
            implements PrivilegedExceptionAction<C2BConverter> {

        private final Charset charset;

        public PrivilegedCreateConverter(Charset charset) {
            this.charset = charset;
        }

        @Override
        public C2BConverter run() throws IOException {
            return new C2BConverter(charset);
        }
    }
}
