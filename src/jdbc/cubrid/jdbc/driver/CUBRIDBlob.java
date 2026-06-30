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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

public class CUBRIDBlob implements Blob {

    private byte[] data;

    public CUBRIDBlob() {
        this.data = new byte[0];
    }

    public CUBRIDBlob(byte[] data) {
        this.data = (data != null) ? data : new byte[0];
    }

    public long length() throws SQLException {
        checkData();
        return data.length;
    }

    public byte[] getBytes(long pos, int length) throws SQLException {
        checkData();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (length == 0) {
            return new byte[0];
        }

        int offset = (int) (pos - 1);
        int available = Math.max(0, data.length - offset);
        int len = Math.min(length, available);
        return Arrays.copyOfRange(data, offset, offset + len);
    }

    public InputStream getBinaryStream() throws SQLException {
        checkData();
        return new ByteArrayInputStream(data);
    }

    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        checkData();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        int offset = (int) (pos - 1);
        int len = (int) Math.min(length, data.length - offset);
        if (len < 0)
            len = 0;
        return new ByteArrayInputStream(data, offset, len);
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
        checkData();
        if (pos < 1 || offset < 0 || len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (offset + len > bytes.length) {
            throw new IndexOutOfBoundsException();
        }

        int writeOffset = (int) (pos - 1);
        int newLength = Math.max(data.length, writeOffset + len);
        if (newLength > data.length) {
            data = Arrays.copyOf(data, newLength);
        }
        System.arraycopy(bytes, offset, data, writeOffset, len);
        return len;
    }

    public OutputStream setBinaryStream(long pos) throws SQLException {
        checkData();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        final int writeOffset = (int) (pos - 1);
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                byte[] written = toByteArray();
                int newLength = Math.max(data.length, writeOffset + written.length);
                if (newLength > data.length) {
                    data = Arrays.copyOf(data, newLength);
                }
                System.arraycopy(written, 0, data, writeOffset, written.length);
            }
        };
    }

    public void truncate(long len) throws SQLException {
        checkData();
        if (len < 0 || len > data.length) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        data = Arrays.copyOf(data, (int) len);
    }

    public void free() throws SQLException {
        data = null;
    }

    public byte[] getData() {
        return data;
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDBlob) {
            return Arrays.equals(data, ((CUBRIDBlob) obj).data);
        }
        return false;
    }

    private void checkData() throws SQLException {
        if (data == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }
}
