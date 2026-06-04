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
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * CUBRID CFILE: external character LOB stored as a file locator on the server.
 *
 * <p>This class encapsulates the former external-LOB logic that was previously in CUBRIDClob.
 * Application code that needs to access CFILE columns should use getCFILE / setCFILE vendor APIs.
 */
public class CUBRIDCfile implements Clob {

    private static final int CFILE_MAX_IO_LENGTH = 128 * 1024; // 128kB at once
    private static final int CFILE_MAX_IO_CHARS = CFILE_MAX_IO_LENGTH / 2;

    private CUBRIDConnection conn;
    private boolean isWritable;
    private CUBRIDLobHandle lobHandle;
    private String charsetName;

    private StringBuffer cfileCharBuffer = new StringBuffer("");
    private long cfileCharPos;
    private long cfileCharLength;

    private byte[] cfileByteBuffer = new byte[CFILE_MAX_IO_LENGTH];
    private long cfileBytePos;
    private long cfileNextReadBytePos;

    private ArrayList<java.io.Flushable> streamList = new ArrayList<java.io.Flushable>();

    /* Create a new (writable) CFILE via server round-trip */
    public CUBRIDCfile(CUBRIDConnection conn, String charsetName) throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        byte[] packedLobHandle = conn.lobNew(UUType.U_TYPE_CFILE);

        this.conn = conn;
        isWritable = true;
        lobHandle = new CUBRIDLobHandle(UUType.U_TYPE_CFILE, packedLobHandle, true);
        this.charsetName = charsetName;

        cfileCharPos = 0;
        cfileCharLength = 0;
        cfileBytePos = 0;
        cfileNextReadBytePos = 0;
    }

    /* Reconstruct a CFILE from a packed locator received in a result set */
    public CUBRIDCfile(CUBRIDConnection conn, byte[] packedLobHandle, String charsetName)
            throws SQLException {
        if (conn == null || packedLobHandle == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        isWritable = false;
        lobHandle = new CUBRIDLobHandle(UUType.U_TYPE_CFILE, packedLobHandle, true);
        this.charsetName = charsetName;

        cfileCharPos = 0;
        cfileCharLength = -1;
        cfileBytePos = 0;
        cfileNextReadBytePos = 0;
    }

    /* java.sql.Clob */

    public synchronized long length() throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (cfileCharLength < 0) {
            readCfilePartially(Long.MAX_VALUE, 1);
            if (cfileCharLength < 0) {
                return 0;
            }
        }

        return cfileCharLength;
    }

    public synchronized String getSubString(long pos, int length) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (length == 0) {
            return "";
        }

        int read_len = readCfilePartially(pos, length);
        if (read_len <= 0) {
            return "";
        }

        return (cfileCharBuffer.substring(0, read_len));
    }

    public Reader getCharacterStream() throws SQLException {
        return getCharacterStream(1, Long.MAX_VALUE);
    }

    public Reader getCharacterStream(long pos, long length) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        return new CUBRIDBufferedReader(
                new CUBRIDCfileReader(this, pos, length), CFILE_MAX_IO_CHARS);
    }

    public InputStream getAsciiStream() throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        return new CUBRIDBufferedInputStream(
                new CUBRIDCfileInputStream(this), CFILE_MAX_IO_LENGTH);
    }

    public long position(String searchstr, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public long position(Clob searchClob, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public synchronized int setString(long pos, String str) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (!isWritable) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
        if (str == null || str.length() <= 0) {
            return 0;
        }

        if (readCfilePartially(pos, 1) != 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
        }

        byte[] bytes = string2bytes(str);
        int bytes_len = bytes.length;
        int bytes_offset = 0;

        while (bytes_len > 0) {
            int bytesWritten =
                    conn.lobWrite(
                            lobHandle.getPackedLobHandle(),
                            cfileBytePos + bytes_offset,
                            bytes,
                            bytes_offset,
                            Math.min(bytes_len, CFILE_MAX_IO_LENGTH));

            bytes_len -= bytesWritten;
            bytes_offset += bytesWritten;
        }

        lobHandle.setLobSize(cfileBytePos + bytes_offset);
        cfileCharLength = length() + str.length();

        return str.length();
    }

    public int setString(long pos, String str, int offset, int len) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1 || offset < 0 || len < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (offset + len > str.length()) {
            throw new IndexOutOfBoundsException();
        }
        if (!isWritable) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
        if (str == null || len == 0) {
            return 0;
        }

        return (setString(pos, str.substring(offset, offset + len)));
    }

    public synchronized OutputStream setAsciiStream(long pos) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (!isWritable) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }

        if (readCfilePartially(pos, 1) != 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
        }

        OutputStream out =
                new CUBRIDBufferedOutputStream(
                        new CUBRIDCfileOutputStream(this, cfileBytePos + 1), CFILE_MAX_IO_LENGTH);
        addFlushableStream(out);
        return out;
    }

    public Writer setCharacterStream(long pos) throws SQLException {
        if (lobHandle == null) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (pos < 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (!isWritable) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }

        if (readCfilePartially(pos, 1) != 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
        }

        Writer out =
                new CUBRIDBufferedWriter(new CUBRIDCfileWriter(this, pos), CFILE_MAX_IO_CHARS);
        addFlushableStream(out);
        return out;
    }

    public void truncate(long len) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public void free() throws SQLException {
        conn = null;
        lobHandle = null;
        streamList = null;
        cfileCharBuffer = null;
        cfileByteBuffer = null;
        isWritable = false;
    }

    private int readCfilePartially(long pos, int length) throws SQLException {
        if (cfileCharLength != -1 && pos > cfileCharLength) {
            cfileBytePos = cfileNextReadBytePos = lobHandle.getLobSize();
            cfileCharPos = cfileCharLength;
            cfileCharBuffer.setLength(0);
            if (pos == cfileCharLength + 1) return 0;
            else return -1;
        }

        pos--;

        if (pos < cfileCharPos) {
            cfileBytePos = cfileNextReadBytePos = 0;
            cfileCharPos = 0;
            cfileCharBuffer.setLength(0);
            readCfile();
        }

        while (pos >= cfileCharPos + cfileCharBuffer.length()) {
            cfileBytePos = cfileNextReadBytePos;
            cfileCharPos += cfileCharBuffer.length();
            cfileCharBuffer.setLength(0);
            if (cfileNextReadBytePos >= lobHandle.getLobSize()) {
                return 0;
            }
            readCfile();
        }

        int delete_len = (int) (pos - cfileCharPos);
        if (delete_len > 0) {
            cfileCharPos = pos;
            cfileBytePos += string2bytes(cfileCharBuffer.substring(0, delete_len)).length;
            cfileCharBuffer.delete(0, delete_len);
        }

        while (length > cfileCharBuffer.length()) {
            if (cfileNextReadBytePos >= lobHandle.getLobSize()) {
                return cfileCharBuffer.length();
            }
            readCfile();
        }

        return length;
    }

    private void readCfile() throws SQLException {
        int read_len;

        if (conn == null || lobHandle == null) {
            throw new NullPointerException();
        }

        read_len =
                conn.lobRead(
                        lobHandle.getPackedLobHandle(),
                        cfileNextReadBytePos,
                        cfileByteBuffer,
                        0,
                        CFILE_MAX_IO_LENGTH);

        StringBuffer sb = new StringBuffer(bytes2string(cfileByteBuffer, 0, read_len));

        if (cfileNextReadBytePos + read_len >= lobHandle.getLobSize()) {
            cfileNextReadBytePos += read_len;
            cfileCharLength =
                    cfileCharPos + cfileCharBuffer.length() + sb.length();
        } else {
            cfileNextReadBytePos +=
                    string2bytes(sb.substring(0, sb.length() - 1)).length;
            sb.setLength(sb.length() - 1);
        }

        cfileCharBuffer.append(sb);
    }

    private byte[] string2bytes(String s) throws SQLException {
        try {
            return (s.getBytes(charsetName));
        } catch (UnsupportedEncodingException e) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage(), e);
        }
    }

    private String bytes2string(byte[] b, int start, int len) throws SQLException {
        try {
            return (new String(b, start, len, charsetName));
        } catch (UnsupportedEncodingException e) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage(), e);
        }
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
        if (obj instanceof CUBRIDCfile) {
            CUBRIDCfile that = (CUBRIDCfile) obj;
            return lobHandle.equals(that.lobHandle);
        }
        return false;
    }

    public byte[] getBytes(long pos, int length) throws SQLException {
        if (conn == null || lobHandle == null) {
            throw new NullPointerException();
        }
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (length == 0) {
            return new byte[0];
        }

        pos--;
        int real_read_len, read_len, total_read_len = 0;

        if (pos + length > lobHandle.getLobSize()) {
            length = (int) (lobHandle.getLobSize() - pos);
        }

        byte[] buf = new byte[length];

        while (length > 0) {
            read_len = Math.min(length, CFILE_MAX_IO_LENGTH);

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

    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        if (conn == null || lobHandle == null) {
            throw new NullPointerException();
        }
        if (pos < 1 || offset < 0 || len < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (offset + len > bytes.length) {
            throw new IndexOutOfBoundsException();
        }

        if (isWritable) {
            if (lobHandle.getLobSize() + 1 != pos) {
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
            }

            pos--;
            int real_write_len, write_len, total_write_len = 0;

            while (len > 0) {
                write_len = Math.min(len, CFILE_MAX_IO_LENGTH);
                real_write_len =
                        conn.lobWrite(
                                lobHandle.getPackedLobHandle(), pos, bytes, offset, write_len);

                pos += real_write_len;
                len -= real_write_len;
                offset += real_write_len;
                total_write_len += real_write_len;
            }

            if (pos > lobHandle.getLobSize()) {
                lobHandle.setLobSize(pos);
                cfileCharLength = -1;
            }

            return total_write_len;
        } else {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
    }
}
