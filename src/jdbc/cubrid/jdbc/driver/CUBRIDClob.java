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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * CUBRID internal CLOB: character LOB stored inline in the heap page.
 *
 * <p>Data is held as a Java String and transferred over the wire as raw text (same encoding as
 * VARCHAR). This replaces the former external-locator-based CUBRIDClob implementation; external
 * character LOBs are now handled by {@link CUBRIDCfile}.
 */
public class CUBRIDClob implements Clob {

    private String charsetName;
    private StringBuilder data;

    /* Create an empty writable CLOB (used by Connection.createClob) */
    public CUBRIDClob(String charsetName) {
        this.charsetName = charsetName;
        this.data = new StringBuilder();
    }

    /* Reconstruct a CLOB from a string received in a result set */
    public CUBRIDClob(String value, String charsetName) {
        this.charsetName = charsetName;
        this.data = new StringBuilder(value != null ? value : "");
    }

    /* java.sql.Clob */

    public long length() throws SQLException {
        checkData();
        return data.length();
    }

    public String getSubString(long pos, int length) throws SQLException {
        checkData();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (length == 0) {
            return "";
        }

        int offset = (int) (pos - 1);
        if (offset >= data.length()) {
            return "";
        }
        int end = Math.min(offset + length, data.length());
        return data.substring(offset, end);
    }

    public Reader getCharacterStream() throws SQLException {
        checkFreed();
        return new StringReader(data.toString());
    }

    public Reader getCharacterStream(long pos, long length) throws SQLException {
        checkData();
        if (pos < 1 || length < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        int offset = (int) (pos - 1);
        int len = (int) Math.min(length, data.length() - offset);
        if (len < 0) len = 0;
        return new StringReader(data.substring(offset, offset + len));
    }

    public InputStream getAsciiStream() throws SQLException {
        checkFreed();
        try {
            return new ByteArrayInputStream(data.toString().getBytes("US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            return new ByteArrayInputStream(data.toString().getBytes());
        }
    }

    public long position(String searchstr, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public long position(Clob searchClob, long start) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    public int setString(long pos, String str, int offset, int len) throws SQLException {
        checkData();
        if (pos < 1 || offset < 0 || len < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        if (offset + len > str.length()) {
            throw new IndexOutOfBoundsException();
        }
        if (str == null || len == 0) {
            return 0;
        }

        String sub = str.substring(offset, offset + len);
        int writeOffset = (int) (pos - 1);

        if (writeOffset > data.length()) {
            // pad with spaces up to writeOffset
            for (int i = data.length(); i < writeOffset; i++) {
                data.append(' ');
            }
        }

        if (writeOffset == data.length()) {
            data.append(sub);
        } else {
            data.replace(writeOffset, Math.min(writeOffset + len, data.length()), sub);
        }

        return len;
    }

    public OutputStream setAsciiStream(long pos) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public Writer setCharacterStream(long pos) throws SQLException {
        checkData();
        if (pos < 1) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        final int writeOffset = (int) (pos - 1);
        return new StringWriter() {
            @Override
            public void close() throws IOException {
                String written = toString();
                try {
                    setString(writeOffset + 1, written);
                } catch (SQLException e) {
                    throw new IOException(e.getMessage());
                }
            }
        };
    }

    public void truncate(long len) throws SQLException {
        checkData();
        if (len < 0 || len > data.length()) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
        data.setLength((int) len);
    }

    public void free() throws SQLException {
        data = null;
    }

    /**
     * Returns the CLOB content as a byte array using the connection charset, for wire
     * serialization.
     */
    public byte[] getDataAsBytes() throws SQLException {
        checkData();
        try {
            return data.toString().getBytes(charsetName);
        } catch (UnsupportedEncodingException e) {
            return data.toString().getBytes();
        }
    }

    /** Returns the CLOB content as a String. */
    public String getData() {
        return data != null ? data.toString() : null;
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDClob) {
            CUBRIDClob that = (CUBRIDClob) obj;
            return data != null && data.toString().equals(that.getData());
        }
        return false;
    }

    private void checkData() throws SQLException {
        if (data == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }
}
