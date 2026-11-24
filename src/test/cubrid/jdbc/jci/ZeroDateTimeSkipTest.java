/*
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


package cubrid.jdbc.jci;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.ConnectionProperties;
import java.sql.Date;
import java.sql.SQLException;
import java.util.TimeZone;
import org.junit.Test;

/**
 * zeroDateTimeBehavior=convertToSkip (APIS-1143): a zero date shows as it is. getObject and
 * getString give "0000-00-00" or "0000-00-00 hh:mm:ss.f", and getDate, getTime and getTimestamp
 * keep ER_ILLEGAL_TIMESTAMP because no java.sql value can hold it.
 */
public class ZeroDateTimeSkipTest {
    private static final TimeZone SEOUL = TimeZone.getTimeZone("Asia/Seoul");

    private static UDateTimeFields kept(UDateTimeFields f) {
        f.keepZeroDate();
        return f;
    }

    @Test
    public void zeroDateShowsAsText() {
        assertEquals("0000-00-00", kept(UDateTimeFields.ofDate(0, 0, 0, 0)).toSqlValue());
        assertEquals(
                "0000-00-00 00:00:00.0",
                kept(UDateTimeFields.ofTimestamp(0, 0, 0, 0, 0, 0, 0)).toSqlValue());
        assertEquals(
                "0000-00-00 00:00:00.0",
                kept(UDateTimeFields.ofDatetime(0, 0, 0, 0, 0, 0, 0, 0)).toSqlValue());
        assertEquals(
                "0000-00-00 12:34:56.12",
                kept(UDateTimeFields.ofDatetime(0, 0, 0, 0, 12, 34, 56, 120)).toSqlValue());
        assertEquals(
                "0000-00-00 00:00:00.0",
                kept(UDateTimeFields.ofTimestamp(0, 0, 0, 0, 0, 0, 0)).toSqlValue(SEOUL));
    }

    @Test
    public void zeroDateWithTimezoneShowsAsText() {
        assertEquals(
                "0000-00-00 00:00:00.0 Asia/Seoul KST",
                kept(UDateTimeFields.ofDatetime(0, 0, 0, 0, 0, 0, 0, 0))
                        .atTimezone("Asia/Seoul KST")
                        .toSqlValue());
    }

    @Test
    public void otherValuesAreUnchanged() {
        Object rounded = UDateTimeFields.ofDate(0, 0, 0, 0).toSqlValue();
        assertTrue("without convertToSkip a zero date rounds", rounded instanceof Date);

        Object value = kept(UDateTimeFields.ofDate(86400000L, 1970, 1, 2)).toSqlValue();
        assertTrue("a date that is not zero stays a java.sql.Date", value instanceof Date);

        assertNull(kept(UDateTimeFields.ofTime(0, 0, 0, 0)).zeroDateText());
    }

    @Test
    public void typedGettersRefuseTheZeroDate() throws UJciException {
        expectIllegalTimestamp(() -> UGetTypeConvertedValue.getDate("0000-00-00"));
        expectIllegalTimestamp(() -> UGetTypeConvertedValue.getTimestamp("0000-00-00 00:00:00.0"));
        expectIllegalTimestamp(() -> UGetTypeConvertedValue.getTime("0000-00-00 00:00:00.0"));
        expectIllegalTimestamp(
                () -> UGetTypeConvertedValue.getTimestamp("0000-00-00 00:00:00.0", SEOUL));
        expectIllegalTimestamp(
                () -> UGetTypeConvertedValue.getTimestamp("0000-00-00 00:00:00.0 Asia/Seoul KST"));

        assertEquals("2020-01-02", UGetTypeConvertedValue.getDate("2020-01-02").toString());
        assertEquals("0000-00-00", UGetTypeConvertedValue.getString("0000-00-00"));
    }

    @Test
    public void propertyAcceptsConvertToSkip() throws SQLException {
        ConnectionProperties props = new ConnectionProperties();
        props.setProperties("zeroDateTimeBehavior=convertToSkip");
        assertEquals(
                UConnection.ZERO_DATETIME_BEHAVIOR_CONVERT_TO_SKIP,
                props.getZeroDateTimeBehavior());
    }

    private interface Getter {
        Object get() throws UJciException;
    }

    private static void expectIllegalTimestamp(Getter getter) {
        try {
            getter.get();
            fail("expected ER_ILLEGAL_TIMESTAMP");
        } catch (UJciException e) {
            assertEquals(UErrorCode.ER_ILLEGAL_TIMESTAMP, e.getJciError());
        }
    }
}
