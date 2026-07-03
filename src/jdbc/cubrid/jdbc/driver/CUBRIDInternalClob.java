/*
 * Copyright (C) 2008 Search Solution Corporation.
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors
 *   may be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.driver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * V13+ internal (inline) character LOB.
 *
 * <p>Unlike {@link CUBRIDClob}, which is locator-based and requires a server round-trip for every
 * read/write, an internal CLOB stores its characters inline in the row (the same representation as
 * VARCHAR). It is therefore a self-contained in-memory object that needs no {@link
 * CUBRIDConnection} and no LOBFILE protocol. The driver instantiates this class when connected to a
 * PROTOCOL_V13 (or later) server; older servers continue to use {@link CUBRIDClob}.
 */
public class CUBRIDInternalClob implements Clob {

    private StringBuilder content;
    private final String charsetName;
    private boolean freed;

    private ArrayList<Flushable> streamList = new ArrayList<Flushable>();

    /** Creates an empty, writable internal CLOB. */
    public CUBRIDInternalClob(String charsetName) {
        this.content = new StringBuilder();
        this.charsetName = charsetName;
    }

    /** Creates an internal CLOB initialized with the given text (null is treated as empty). */
    public CUBRIDInternalClob(String content, String charsetName) {
        this.content = new StringBuilder(content == null ? "" : content);
        this.charsetName = charsetName;
    }

    private void checkFreed() throws SQLException {
        if (freed || content == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }

    public synchronized long length() throws SQLException {
        checkFreed();
        return content.length();
    }

    public synchronized String getSubString(long pos, int length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (length == 0) {
            return "";
        }

        int start = (int) (pos - 1);
        if (start >= content.length()) {
            return "";
        }
        int end = (int) Math.min((long) content.length(), (long) start + length);
        return content.substring(start, end);
    }

    public Reader getCharacterStream() throws SQLException {
        checkFreed();
        return new StringReader(content.toString());
    }

    /* JDK 1.6 */
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        int start = (int) (pos - 1);
        if (start >= content.length()) {
            return new StringReader("");
        }
        int end = (int) Math.min((long) content.length(), (long) start + length);
        return new StringReader(content.substring(start, end));
    }

    public InputStream getAsciiStream() throws SQLException {
        checkFreed();
        return new ByteArrayInputStream(getEncodedBytes());
    }

    public long position(String searchstr, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public long position(Clob searchClob, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public synchronized int setString(long pos, String str) throws SQLException {
        checkFreed();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (str == null || str.length() == 0) {
            return 0;
        }

        int start = (int) (pos - 1);
        if (start > content.length()) {
            // only overwrite of existing chars or append at the end is supported
            throw new CUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid);
        }

        for (int i = 0; i < str.length(); i++) {
            if (start + i < content.length()) {
                content.setCharAt(start + i, str.charAt(i));
            } else {
                content.append(str.charAt(i));
            }
        }
        return str.length();
    }

    public int setString(long pos, String str, int offset, int len) throws SQLException {
        if (pos < 1 || offset < 0 || len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (str == null || len == 0) {
            return 0;
        }
        if (offset + len > str.length()) {
            throw new IndexOutOfBoundsException();
        }
        return setString(pos, str.substring(offset, offset + len));
    }

    public synchronized OutputStream setAsciiStream(long pos) throws SQLException {
        checkFreed();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        final long startPos = pos;
        final CUBRIDInternalClob self = this;
        OutputStream out =
                new OutputStream() {
                    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

                    public void write(int b) {
                        buf.write(b);
                    }

                    public void write(byte[] b, int off, int len) {
                        buf.write(b, off, len);
                    }

                    public void close() throws IOException {
                        try {
                            self.setString(startPos, self.decode(buf.toByteArray()));
                        } catch (SQLException e) {
                            throw new IOException(e.getMessage());
                        }
                        self.removeFlushableStream(this);
                    }
                };
        addFlushableStream(out);
        return out;
    }

    public synchronized Writer setCharacterStream(long pos) throws SQLException {
        checkFreed();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        Writer out = new CUBRIDInternalClobWriter(this, pos);
        addFlushableStream(out);
        return out;
    }

    public synchronized void truncate(long len) throws SQLException {
        checkFreed();
        if (len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (len < content.length()) {
            content.setLength((int) len);
        }
    }

    /* JDK 1.6 */
    public void free() throws SQLException {
        content = null;
        streamList = null;
        freed = true;
    }

    /** Returns the current string value (used by tests and the wire layer). */
    public String getData() {
        return (content == null) ? "" : content.toString();
    }

    /** Returns the value encoded with this CLOB's charset (inline bytes sent on the wire). */
    public byte[] getEncodedBytes() throws SQLException {
        return encode(getData());
    }

    private byte[] encode(String s) throws SQLException {
        try {
            return (charsetName == null) ? s.getBytes() : s.getBytes(charsetName);
        } catch (UnsupportedEncodingException e) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage(), e);
        }
    }

    private String decode(byte[] b) throws SQLException {
        try {
            return (charsetName == null) ? new String(b) : new String(b, charsetName);
        } catch (UnsupportedEncodingException e) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage(), e);
        }
    }

    private void addFlushableStream(Flushable out) {
        streamList.add(out);
    }

    void removeFlushableStream(Flushable out) {
        if (streamList != null) {
            streamList.remove(out);
        }
    }

    public void flushFlushableStreams() {
        if (streamList != null && !streamList.isEmpty()) {
            for (Flushable out : streamList) {
                try {
                    out.flush();
                } catch (IOException e) {
                }
            }
        }
    }

    public String toString() {
        return getData();
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDInternalClob) {
            return getData().equals(((CUBRIDInternalClob) obj).getData());
        }
        return false;
    }

    public int hashCode() {
        return getData().hashCode();
    }
}
