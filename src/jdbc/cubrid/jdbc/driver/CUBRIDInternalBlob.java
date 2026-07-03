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
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * V13+ internal (inline) binary LOB.
 *
 * <p>Unlike {@link CUBRIDBlob}, which is locator-based and requires a server round-trip for every
 * read/write, an internal BLOB stores its bytes inline in the row (the same representation as
 * VARBIT). It is therefore a self-contained in-memory object that needs no {@link CUBRIDConnection}
 * and no LOBFILE protocol. The driver instantiates this class when connected to a PROTOCOL_V13
 * (or later) server; older servers continue to use {@link CUBRIDBlob}.
 */
public class CUBRIDInternalBlob implements Blob {

    private byte[] data;
    private boolean freed;

    /** Creates an empty, writable internal BLOB. */
    public CUBRIDInternalBlob() {
        this.data = new byte[0];
    }

    /** Creates an internal BLOB initialized with the given bytes (null is treated as empty). */
    public CUBRIDInternalBlob(byte[] data) {
        this.data = (data == null) ? new byte[0] : data;
    }

    private void checkFreed() throws SQLException {
        if (freed || data == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }

    public long length() throws SQLException {
        checkFreed();
        return data.length;
    }

    public byte[] getBytes(long pos, int length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        int start = (int) (pos - 1);
        if (start >= data.length) {
            return new byte[0];
        }

        int avail = data.length - start;
        int n = Math.min(length, avail);
        return Arrays.copyOfRange(data, start, start + n);
    }

    public InputStream getBinaryStream() throws SQLException {
        checkFreed();
        return new ByteArrayInputStream(data);
    }

    /* JDK 1.6 */
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        int start = (int) (pos - 1);
        if (start >= data.length) {
            return new ByteArrayInputStream(new byte[0]);
        }
        int n = (int) Math.min(length, (long) (data.length - start));
        return new ByteArrayInputStream(data, start, n);
    }

    public long position(byte[] pattern, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public long position(Blob pattern, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        checkFreed();
        if (pos < 1 || offset < 0 || len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (bytes == null || offset + len > bytes.length) {
            throw new IndexOutOfBoundsException();
        }

        int start = (int) (pos - 1);
        int newLen = Math.max(data.length, start + len);
        if (newLen != data.length) {
            data = Arrays.copyOf(data, newLen);
        }
        System.arraycopy(bytes, offset, data, start, len);
        return len;
    }

    public OutputStream setBinaryStream(long pos) throws SQLException {
        checkFreed();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        return new CUBRIDInternalBlobOutputStream(this, pos);
    }

    public void truncate(long len) throws SQLException {
        checkFreed();
        if (len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (len < data.length) {
            data = Arrays.copyOf(data, (int) len);
        }
    }

    /* JDK 1.6 */
    public void free() throws SQLException {
        data = null;
        freed = true;
    }

    /** Returns the raw inline bytes backing this BLOB (used by the wire layer when binding). */
    public byte[] getData() {
        return (data == null) ? new byte[0] : data;
    }

    /*
     * Internal BLOBs hold their data in memory, so there are no server-bound streams to flush.
     * The method is provided for symmetry with the binding code path.
     */
    public void flushFlushableStreams() {}

    /* Streams created from setBinaryStream are write-through, so this is a no-op placeholder. */
    private ArrayList<Flushable> streamList = new ArrayList<Flushable>();

    void removeFlushableStream(Flushable out) {
        streamList.remove(out);
    }

    public String toString() {
        return "CUBRIDInternalBlob[length=" + (data == null ? 0 : data.length) + "]";
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDInternalBlob) {
            return Arrays.equals(data, ((CUBRIDInternalBlob) obj).data);
        }
        return false;
    }

    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
