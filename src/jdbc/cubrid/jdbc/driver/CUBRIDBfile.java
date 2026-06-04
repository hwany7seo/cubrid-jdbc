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

import cubrid.jdbc.jci.UUType;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * CUBRID BFILE: external binary LOB stored as a file locator on the server.
 *
 * <p>This class encapsulates the former external-LOB logic that was previously in CUBRIDBlob.
 * Application code that needs to access BFILE columns should use getBFILE / setBFILE vendor APIs.
 */
public class CUBRIDBfile implements Blob {

    private static final int BFILE_MAX_IO_LENGTH = 128 * 1024; // 128KB at once

    private CUBRIDConnection conn;
    private boolean isWritable;
    private CUBRIDLobHandle lobHandle;

    private ArrayList<java.io.Flushable> streamList = new ArrayList<java.io.Flushable>();

    /* Create a new (writable) BFILE via server round-trip */
    public CUBRIDBfile(CUBRIDConnection conn) throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        byte[] packedLobHandle = conn.lobNew(UUType.U_TYPE_BFILE);

        this.conn = conn;
        isWritable = true;
        lobHandle = new CUBRIDLobHandle(UUType.U_TYPE_BFILE, packedLobHandle, true);
    }

    /* Reconstruct a BFILE from a packed locator received in a result set */
    public CUBRIDBfile(CUBRIDConnection conn, byte[] packedLobHandle) throws SQLException {
        if (conn == null || packedLobHandle == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        isWritable = false;
        lobHandle = new CUBRIDLobHandle(UUType.U_TYPE_BFILE, packedLobHandle, true);
    }

    /* java.sql.Blob */

    public long length() throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        return lobHandle.getLobSize();
    }

    public byte[] getBytes(long pos, int length) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (length == 0) {
            return new byte[0];
        }

        pos--;
        int real_read_len, read_len, total_read_len = 0;

        if (pos + length > length()) {
            length = (int) (length() - pos);
        }

        if (length <= 0) {
            return new byte[0];
        }

        byte[] buf = new byte[length];

        while (length > 0) {
            read_len = Math.min(length, BFILE_MAX_IO_LENGTH);
            real_read_len =
                    conn.lobRead(
                            lobHandle.getPackedLobHandle(), pos, buf, total_read_len, read_len);

            pos += real_read_len;
            length -= real_read_len;
            total_read_len += real_read_len;

            if (real_read_len == 0) {
                break;
            }
        }

        if (total_read_len < buf.length) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.unknown, null);
        }
        return buf;
    }

    public InputStream getBinaryStream() throws SQLException {
        return getBinaryStream(1, length());
    }

    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        return new CUBRIDBufferedInputStream(
                new CUBRIDBfileInputStream(this, pos, length), BFILE_MAX_IO_LENGTH);
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
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || offset < 0 || len < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (offset + len > bytes.length) {
            throw new IndexOutOfBoundsException();
        }

        if (isWritable) {
            if (length() + 1 != pos) {
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
            }

            pos--;

            int real_write_len, write_len, total_write_len = 0;

            while (len > 0) {
                write_len = Math.min(len, BFILE_MAX_IO_LENGTH);
                real_write_len =
                        conn.lobWrite(
                                lobHandle.getPackedLobHandle(), pos, bytes, offset, write_len);

                pos += real_write_len;
                len -= real_write_len;
                offset += real_write_len;
                total_write_len += real_write_len;
            }

            if (pos > length()) {
                lobHandle.setLobSize(pos);
            }

            return total_write_len;
        } else {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
    }

    public OutputStream setBinaryStream(long pos) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        if (isWritable) {
            if (length() + 1 != pos) {
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
            }

            OutputStream out =
                    new CUBRIDBufferedOutputStream(
                            new CUBRIDBfileOutputStream(this, pos), BFILE_MAX_IO_LENGTH);
            addFlushableStream(out);
            return out;
        } else {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
    }

    public void truncate(long len) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public void free() throws SQLException {
        conn = null;
        lobHandle = null;
        streamList = null;
        isWritable = false;
    }

    public CUBRIDLobHandle getLobHandle() {
        return lobHandle;
    }

    private void addFlushableStream(Flushable out) {
        streamList.add(out);
    }

    public void removeFlushableStream(Flushable out) {
        streamList.remove(out);
    }

    public void flushFlushableStreams() {
        if (!streamList.isEmpty()) {
            for (Flushable out : streamList) {
                try {
                    out.flush();
                } catch (IOException e) {
                }
            }
        }
    }

    public String toString() {
        return lobHandle.toString();
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDBfile) {
            CUBRIDBfile that = (CUBRIDBfile) obj;
            return lobHandle.equals(that.lobHandle);
        }
        return false;
    }
}
