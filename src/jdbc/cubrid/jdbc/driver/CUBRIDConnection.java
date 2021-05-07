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

import cubrid.jdbc.jci.CUBRIDIsolationLevel;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UError;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UStatement;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Title: CUBRID JDBC Driver Description:
 *
 * @version 2.0
 */
public class CUBRIDConnection implements Connection {
    // Transaction Isolation Level Contants
    public static final int TRAN_REP_CLASS_REP_INSTANCE = TRANSACTION_REPEATABLE_READ; // 4
    public static final int TRAN_REP_CLASS_COMMIT_INSTANCE = 16;
    public static final int TRAN_REP_CLASS_UNCOMMIT_INSTANCE = 32;
    public static final int TRAN_SERIALIZABLE = TRANSACTION_SERIALIZABLE;

    public static final int CAS_CHANGE_MODE_AUTO = 1;
    public static final int CAS_CHANGE_MODE_KEEP = 2;

    UConnection u_con;
    String user;
    String url;

    protected boolean is_closed;
    protected boolean auto_commit;
    protected int holdability;

    protected UError error;
    private boolean ending;

    protected ArrayList<Statement> statements;
    protected CUBRIDDatabaseMetaData mdata;
    protected CUBRIDShardMetaData shard_mdata;
    protected ArrayList<CUBRIDOutResultSet> outRs;
    private boolean isAutoGeneratedKeys = false;

    /*
     * 3.0 ArrayList savepoints; int sv_count, sv_id; String sv_name; private
     * boolean isFromPooledCon=false; private CUBRIDPooledConnection
     * associatedPhysicalConnection = null;
     */

    public CUBRIDConnection(UConnection u, String r, String s) {
        u_con = u;
        u_con.setCUBRIDConnection(this);
        url = r;
        user = s;
        is_closed = false;
        auto_commit = true;
        holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
        error = null;
        mdata = null;
        ending = false;
        statements = new ArrayList<Statement>();
        outRs = new ArrayList<CUBRIDOutResultSet>();
        shard_mdata = null;

        /*
         * 3.0 savepoints = new ArrayList(); sv_count = 0; sv_name = "";
         * isFromPooledCon = false;
         */
    }

    /*
     * 3.0 CUBRIDConnection (UConnection u, String r, String s,
     * CUBRIDPooledConnection pcon) { u_con = u; url = r; user = s; is_closed =
     * false; auto_commit = true; error = null; mdata = null; ending = false;
     * statements = new ArrayList();
     *
     * savepoints = new ArrayList(); sv_count = 0; sv_name = "";
     *
     * isFromPooledCon = true; associatedPhysicalConnection = pcon; }
     */

    /*
     * java.sql.Connection interface
     */

    @Override
    public String toString() {
        StringBuffer str = new StringBuffer();
        str.append(getClass().getName());
        str.append(
                String.format(
                        "(%s:%d, %d, %d)",
                        u_con.getCasIp(),
                        u_con.getCasPort(),
                        u_con.getCasId(),
                        u_con.getCasProcessId()));
        return str.toString();
    }

    public synchronized Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public synchronized PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepare(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                holdability,
                Statement.NO_GENERATED_KEYS);
    }

    public synchronized CallableStatement prepareCall(String sql) throws SQLException {
        checkIsOpen();
        UStatement us = prepare(sql, UConnection.PREPARE_CALL);
        CallableStatement cstmt = new CUBRIDCallableStatement(this, us);
        addStatement(cstmt);

        return cstmt;
    }

    public String nativeSQL(String sql) throws SQLException {
        throw new SQLException(new UnsupportedOperationException());
    }

    public synchronized void setAutoCommit(boolean autoCommit) throws SQLException {
        checkIsOpen();
        if (auto_commit != autoCommit) {
            commit();
        }
        auto_commit = autoCommit;
        u_con.setAutoCommit(autoCommit);
    }

    public synchronized boolean getAutoCommit() throws SQLException {
        checkIsOpen();
        return auto_commit;
    }

    public synchronized void commit() throws SQLException {
        checkIsOpen();

        if (ending) {
            return;
        }
        ending = true;

        completeStatementForCommit();

        try {
            end(true);
        } finally {
            ending = false;
        }
        /*
         * 3.0 clearSavepoint();
         */
    }

    public synchronized void rollback() throws SQLException {
        checkIsOpen();

        if (ending) return;
        ending = true;

        completeAllStatements();

        try {
            end(false);
        } finally {
            ending = false;
        }
        /*
         * 3.0 clearSavepoint();
         */
    }

    public synchronized void close() throws SQLException {
        if (is_closed) return;

        clear();

        is_closed = true;

        /*
         * 3.0 if (!isFromPooledCon) u_con.close(); else
         * associatedPhysicalConnection.notifyConnectionClosed();
         */
        u_con.close();

        u_con = null;
        url = null;
        user = null;
        mdata = null;
        statements = null;
        error = null;
        shard_mdata = null;
    }

    public synchronized boolean isClosed() throws SQLException {
        return is_closed;
    }

    public synchronized DatabaseMetaData getMetaData() throws SQLException {
        checkIsOpen();

        if (mdata != null) return mdata;

        mdata = new CUBRIDDatabaseMetaData(this);
        return mdata;
    }

    public synchronized CUBRIDShardMetaData getShardMetaData() throws SQLException {
        checkIsOpen();

        if (shard_mdata != null) {
            return shard_mdata;
        }

        shard_mdata = new CUBRIDShardMetaData(this);
        return shard_mdata;
    }

    public synchronized void setReadOnly(boolean readOnly) throws SQLException {
        checkIsOpen();
    }

    public synchronized boolean isReadOnly() throws SQLException {
        checkIsOpen();
        return false;
    }

    public synchronized void setCatalog(String catalog) throws SQLException {
        checkIsOpen();
    }

    public synchronized String getCatalog() throws SQLException {
        checkIsOpen();
        return "";
    }

    public synchronized void setTransactionIsolation(int level) throws SQLException {
        checkIsOpen();

        commit();

        int cubrid_level;
        if (u_con.protoVersionIsAbove(UConnection.PROTOCOL_V7)) {
            switch (level) {
                case TRANSACTION_READ_COMMITTED:
                case TRAN_REP_CLASS_COMMIT_INSTANCE:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_READ_COMMITTED;
                    break;

                case TRANSACTION_REPEATABLE_READ:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_REPEATABLE_READ;
                    break;

                case TRANSACTION_SERIALIZABLE:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_SERIALIZABLE;
                    break;

                default:
                    throw createCUBRIDException(CUBRIDJDBCErrorCode.invalid_trans_iso_level, null);
            }
        } else {
            switch (level) {
                case TRANSACTION_READ_COMMITTED:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_READ_COMMITTED;
                    break;

                case TRANSACTION_READ_UNCOMMITTED:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_REP_CLASS_UNCOMMIT_INSTANCE;
                    break;

                case TRANSACTION_REPEATABLE_READ:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_REPEATABLE_READ;
                    break;

                case TRANSACTION_SERIALIZABLE:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_SERIALIZABLE;
                    break;

                case TRAN_REP_CLASS_COMMIT_INSTANCE:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_READ_COMMITTED;
                    break;

                case TRAN_REP_CLASS_UNCOMMIT_INSTANCE:
                    cubrid_level = CUBRIDIsolationLevel.TRAN_REP_CLASS_UNCOMMIT_INSTANCE;
                    break;

                default:
                    throw createCUBRIDException(CUBRIDJDBCErrorCode.invalid_trans_iso_level, null);
            }
        }

        synchronized (u_con) {
            u_con.setIsolationLevel(cubrid_level);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }
    }

    public synchronized int getTransactionIsolation() throws SQLException {
        checkIsOpen();

        int cubrid_level = 0;
        synchronized (u_con) {
            cubrid_level = u_con.getIsolationLevel();
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }

        if (u_con.protoVersionIsAbove(UConnection.PROTOCOL_V7)) {
            switch (cubrid_level) {
                case CUBRIDIsolationLevel.TRAN_READ_COMMITTED:
                    return TRANSACTION_READ_COMMITTED;

                case CUBRIDIsolationLevel.TRAN_REPEATABLE_READ:
                    return TRANSACTION_REPEATABLE_READ;

                case CUBRIDIsolationLevel.TRAN_SERIALIZABLE:
                    return TRANSACTION_SERIALIZABLE;

                default:
                    return TRANSACTION_NONE;
            }

        } else {
            switch (cubrid_level) {
                case CUBRIDIsolationLevel.TRAN_COMMIT_CLASS_COMMIT_INSTANCE:
                    return TRANSACTION_READ_COMMITTED;

                case CUBRIDIsolationLevel.TRAN_COMMIT_CLASS_UNCOMMIT_INSTANCE:
                    return TRANSACTION_READ_UNCOMMITTED;

                case CUBRIDIsolationLevel.TRAN_REPEATABLE_READ:
                    return TRANSACTION_REPEATABLE_READ;

                case CUBRIDIsolationLevel.TRAN_READ_COMMITTED:
                    return TRANSACTION_READ_COMMITTED;

                case CUBRIDIsolationLevel.TRAN_REP_CLASS_UNCOMMIT_INSTANCE:
                    return TRANSACTION_READ_UNCOMMITTED;

                case CUBRIDIsolationLevel.TRAN_SERIALIZABLE:
                    return TRANSACTION_SERIALIZABLE;

                default:
                    return TRANSACTION_NONE;
            }
        }
    }

    public synchronized SQLWarning getWarnings() throws SQLException {
        checkIsOpen();
        return null;
    }

    public synchronized void clearWarnings() throws SQLException {
        checkIsOpen();
    }

    public synchronized Statement createStatement(int resultSetType, int resultSetConcurrency)
            throws SQLException {
        checkIsOpen();
        Statement stmt =
                new CUBRIDStatement(this, resultSetType, resultSetConcurrency, holdability);
        addStatement(stmt);

        return stmt;
    }

    public synchronized PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepare(
                sql, resultSetType, resultSetConcurrency, holdability, Statement.NO_GENERATED_KEYS);
    }

    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return (prepareCall(sql));
    }

    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    // 3.0 api
    public synchronized Statement createStatement(int type, int concur, int holdable)
            throws SQLException {
        checkIsOpen();
        if (holdable == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            if (type == ResultSet.TYPE_SCROLL_SENSITIVE || concur == ResultSet.CONCUR_UPDATABLE) {
                throw new SQLException(new java.lang.UnsupportedOperationException());
            }
        }
        Statement stmt = new CUBRIDStatement(this, type, concur, holdable);
        addStatement(stmt);

        return stmt;
    }

    public synchronized int getHoldability() throws SQLException {
        checkIsOpen();

        if (holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            if (u_con.supportHoldableResult()) {
                return ResultSet.HOLD_CURSORS_OVER_COMMIT;
            } else {
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
        }

        return holdability;
    }
    
    public CallableStatement prepareCall(String sql, int type, int concur, int holdable)
            throws SQLException {
        return prepareCall(sql);
    }

    public synchronized PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
            throws SQLException {
        return prepare(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                holdability,
                autoGeneratedKeys);
    }

    public synchronized PreparedStatement prepareStatement(
            String sql, int type, int concur, int holdable) throws SQLException {
        if (holdable == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            if (type == ResultSet.TYPE_SCROLL_SENSITIVE || concur == ResultSet.CONCUR_UPDATABLE) {
                throw new SQLException(new java.lang.UnsupportedOperationException());
            }
        }
        return prepare(sql, type, concur, holdable, Statement.NO_GENERATED_KEYS);
    }

    public synchronized PreparedStatement prepareStatement(String sql, int[] indexes)
            throws SQLException {
        // auto = Statement.RETURN_GENERATED_KEYS;
        return prepareStatement(sql);
    }

    public synchronized PreparedStatement prepareStatement(String sql, String[] colName)
            throws SQLException {
        // auto = Statement.RETURN_GENERATED_KEYS;
        return prepareStatement(sql);
    }

    public synchronized void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
        /*
         * 3.0 checkIsOpen(); boolean flag=true;
         *
         * if (!savepoints.isEmpty()) { for (int i=0 ; i < savepoints.size() ;
         * i++) {
         * if(savepoint.equals(((CUBRIDSavepoint)savepoints.get(i)).getSavepointName
         * ())) { savepoints.remove(savepoint); flag=false; } } }
         *
         * if (flag) throw new CUBRIDException("The Savepoint is not exist ");
         */
    }

    public synchronized void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
        /*
         * 3.0 checkIsOpen();
         *
         * if (isRelease((CUBRIDSavepoint)savepoint)) { throw new
         * CUBRIDException("The Savepoint is released"); }
         *
         * synchronized (u_con) { u_con.savepoint(2,
         * savepoint.getSavepointName()); error = u_con.getRecentError(); }
         *
         * switch (error.getErrorCode()) { case UErrorCode.ER_NO_ERROR : break;
         * default : throw new CUBRIDException(error); }
         *
         * deleteSavepoint((CUBRIDSavepoint)savepoint);
         */
    }

    public synchronized void setHoldability(int holdable) throws SQLException {
        checkIsOpen();

        holdability = holdable;
    }

    public synchronized Savepoint setSavepoint() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
        /*
         * 3.0 checkIsOpen();
         *
         * sv_id = createSavepointId(); // sv_name = name;
         *
         * if (sv_name.length() == 0) sv_name = createSavepointName(); else if
         * (existName(sv_name)) throw new
         * CUBRIDException("The Savepoint name "+sv_name+" exist");
         *
         * synchronized (u_con) { u_con.savepoint(1, sv_name); error =
         * u_con.getRecentError(); }
         *
         * switch (error.getErrorCode()) { case UErrorCode.ER_NO_ERROR : break;
         * default : throw new CUBRIDException(error); } Savepoint sv = new
         * CUBRIDSavepoint(this, sv_name, sv_id); savepoints.add(sv); sv_name =
         * ""; sv_id = 0; return sv;
         */
    }

    public synchronized Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
        /*
         * 3.0 checkIsOpen(); sv_name = name;
         *
         * if (existName(sv_name)) throw new
         * CUBRIDException("The Savepoint name "+sv_name+" exist");
         *
         * return setSavepoint();
         */
    }

    // 3.0 api

    public synchronized void setCharset(String charsetName)
            throws java.io.UnsupportedEncodingException {
        u_con.setCharset(charsetName);
    }

    public synchronized CUBRIDConnectionKey Login(String SignedData) throws SQLException {
        return null;
    }

    public synchronized CUBRIDConnectionKey Login(byte[] SignedData) throws SQLException {
        return null;
    }

    public void Logout() {}

    public void SetSignedConnection() {}

    public synchronized UConnection getUConnection() throws SQLException {
        checkIsOpen();
        return u_con;
    }

    public synchronized void setLockTimeout(int timeout) throws SQLException {
        checkIsOpen();

        synchronized (u_con) {
            u_con.setLockTimeout(timeout);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }
    }

    public synchronized int setCASChangeMode(int mode) throws SQLException {
        int prev_mode = 0;

        checkIsOpen();

        synchronized (u_con) {
            if (mode != CAS_CHANGE_MODE_AUTO && mode != CAS_CHANGE_MODE_KEEP) {
                throw createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
            }

            prev_mode = u_con.setCASChangeMode(mode);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }

        return prev_mode;
    }

    public void setAutoGeneratedKeys(boolean isGeneratedKeys) {
        isAutoGeneratedKeys = isGeneratedKeys;
    }

    UStatement prepare(String sql, byte prepareFlag) throws SQLException {
        UStatement us = null;

        synchronized (u_con) {
            u_con.setBeginTime();
            us = u_con.prepare(sql, prepareFlag);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                return us;
            default:
                UError cpErr = new UError(error);
                autoRollback();
                throw createCUBRIDException(cpErr);
        }
    }

    protected void autoCommit() throws SQLException {
        checkIsOpen();
        if (auto_commit) commit();
    }

    protected void autoRollback() throws SQLException {
        checkIsOpen();
        if (auto_commit) rollback();
    }

    synchronized void closeConnection() throws SQLException {
        if (is_closed) return;

        clear();
        is_closed = true;
    }

    synchronized void removeStatement(Statement s) throws SQLException {
        int i = statements.indexOf(s);
        if (i > -1) statements.remove(i);
    }

    protected void clear() throws SQLException {
        closeAllStatements();
        closeAllOutResultSet();

        if (mdata != null) {
            mdata.close();
            mdata = null;
        }
    }

    private void checkIsOpen() throws SQLException {
        if (is_closed) {
            throw createCUBRIDException(CUBRIDJDBCErrorCode.connection_closed, null);
        }
    }

    protected void addStatement(Statement s) throws SQLException {
        statements.add(s);

        if (u_con.getQueryTimeout() > 0) {
            s.setQueryTimeout(u_con.getQueryTimeout());
        }
    }

    private void completeStatementForCommit() throws SQLException {
        for (int i = 0; i < statements.size(); i++) {
            CUBRIDStatement stmt = (CUBRIDStatement) statements.get(i);

            if (stmt.getHoldability() == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
                stmt.setCurrentTransaction(false);
                continue;
            } else if (stmt instanceof CUBRIDPreparedStatement) {
                statements.remove(i);
                if (u_con.brokerInfoStatementPooling() == true) stmt.complete();
                else stmt.close();
            } else stmt.complete();
        }
    }

    private void completeAllStatements() throws SQLException {
        for (int i = 0; i < statements.size(); i++) {
            CUBRIDStatement stmt = (CUBRIDStatement) statements.get(i);

            if (stmt.getHoldability() == ResultSet.HOLD_CURSORS_OVER_COMMIT
                    && !stmt.isFromCurrentTransaction()) {
                continue;
            }

            if (stmt instanceof CUBRIDPreparedStatement) {

                statements.remove(i);
                if (u_con.brokerInfoStatementPooling() == true) stmt.complete();
                else stmt.close();
            } else stmt.complete();
        }
    }

    private void closeAllStatements() throws SQLException {
        Object stmts[] = statements.toArray();
        for (int i = 0; i < stmts.length; i++) {
            CUBRIDStatement stmt = (CUBRIDStatement) stmts[i];
            stmt.close();
        }
        statements.clear();
    }

    private void closeAllOutResultSet() throws SQLException {
        for (int i = 0; i < outRs.size(); i++) {
            CUBRIDOutResultSet rs = (CUBRIDOutResultSet) outRs.get(i);
            rs.close();
        }
        outRs.clear();
    }

    public void addOutResultSet(CUBRIDOutResultSet rs) {
        outRs.add(rs);
    }

    /* JDK 1.6 */
    public Blob createBlob() throws SQLException {
        Blob blob = new CUBRIDBlob(this);
        return blob;
    }

    /* JDK 1.6 */
    public Clob createClob() throws SQLException {
        Clob clob = new CUBRIDClob(this, getUConnection().getCharset());
        return clob;
    }

    private void end(boolean commit) throws SQLException {
        synchronized (u_con) {
            if (isAutoGeneratedKeys) {
                u_con.turnOnAutoCommitBySelf();
            }
            u_con.endTransaction(commit);

            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }
    }

    protected PreparedStatement prepare(
            String sql,
            int resultSetType,
            int resultSetConcurrency,
            int resultHoldability,
            int autoGeneratedKeys)
            throws SQLException {
        checkIsOpen();

        byte prepareFlag = (byte) 0;

        if (resultSetType == ResultSet.TYPE_SCROLL_SENSITIVE
                || resultSetConcurrency == ResultSet.CONCUR_UPDATABLE) {
            prepareFlag = UConnection.PREPARE_UPDATABLE;
        }
        if (resultHoldability == ResultSet.HOLD_CURSORS_OVER_COMMIT
                && u_con.supportHoldableResult()) {
            prepareFlag = UConnection.PREPARE_HOLDABLE;
        }

        UStatement us = prepare(sql, prepareFlag);
        PreparedStatement pstmt =
                new CUBRIDPreparedStatement(
                        this,
                        us,
                        resultSetType,
                        resultSetConcurrency,
                        resultHoldability,
                        autoGeneratedKeys);
        addStatement(pstmt);

        return pstmt;
    }

    public synchronized byte[] lobNew(int lobType) throws SQLException {
        checkIsOpen();
        byte[] packedLobHandle = null;

        synchronized (u_con) {
            packedLobHandle = u_con.lobNew(lobType);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }

        return packedLobHandle;
    }

    public synchronized int lobWrite(
            byte[] packedLobHandle, long offset, byte[] buf, int start, int len)
            throws SQLException {
        checkIsOpen();
        int result;

        synchronized (u_con) {
            result = u_con.lobWrite(packedLobHandle, offset, buf, start, len);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }

        return result;
    }

    public synchronized int lobRead(
            byte[] packedLobHandle, long offset, byte[] buf, int start, int len)
            throws SQLException {
        checkIsOpen();
        int result;

        synchronized (u_con) {
            result = u_con.lobRead(packedLobHandle, offset, buf, start, len);
            error = u_con.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw createCUBRIDException(error);
        }

        return result;
    }

    public synchronized int getShardId() {
        int lastShardId;

        synchronized (u_con) {
            lastShardId = u_con.getShardId();
        }

        return lastShardId;
    }

    public synchronized boolean isShard() {
        boolean isShard;

        synchronized (u_con) {
            isShard = u_con.isConnectedToProxy();
        }

        return isShard;
    }

    /*
     * 3.0 private int createSavepointId() throws SQLException { int tempid=0;
     * do { Random r = new Random(); tempid = Math.abs(r.nextInt()) % 1000000 +
     * 1; } while(existId(tempid)); return tempid; }
     *
     * private String createSavepointName() { String tempname; tempname =
     * "UNISV_"+sv_id; return tempname; }
     *
     * private boolean existId(int tid) throws SQLException { if
     * (!savepoints.isEmpty()) { for (int i=0; i < savepoints.size(); i++) { if
     * (tid == ((CUBRIDSavepoint)savepoints.get(i)).getSavepointId()) return
     * true; } }
     *
     * return false; }
     *
     * private boolean existName(String name) throws SQLException { if
     * (!savepoints.isEmpty()) { for (int i=0 ; i < savepoints.size() ; i++) {
     * if (name.equals(((CUBRIDSavepoint)savepoints.get(i)).getSavepointName()))
     * return true; } } return false; }
     *
     * private boolean isRelease(CUBRIDSavepoint sv) { boolean flag = true;
     *
     * for (int i=0 ; i <savepoints.size() ; i++) {
     * if(((CUBRIDSavepoint)savepoints.get(i)).equals(sv)) { flag = false;
     * break; } } return flag; }
     *
     * private synchronized void deleteSavepoint(CUBRIDSavepoint sv) { int
     * index; index = savepoints.indexOf(sv);
     *
     * for(int j = index; j < savepoints.size();) savepoints.remove(j); }
     *
     * private synchronized void clearSavepoint() { savepoints.clear(); }
     */

    protected void finalize() {
        try {
            close();
        } catch (Exception e) {
        }
    }

    CUBRIDException createCUBRIDException(UError error) {
        CUBRIDException e = new CUBRIDException(error);
        u_con.logException(e);
        return e;
    }

    CUBRIDException createCUBRIDException(int errCode, Throwable t) {
        CUBRIDException e = new CUBRIDException(errCode, t);
        if (u_con != null) {
            u_con.logException(e);
        }
        return e;
    }

    CUBRIDException createCUBRIDException(int errCode, String msg, Throwable t) {
        CUBRIDException e = new CUBRIDException(errCode, msg, t);
        u_con.logException(e);
        return e;
    }

    /* JDK 1.6 */
    public NClob createNClob() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public Array createArrayOf(String arg0, Object[] arg1) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public Struct createStruct(String arg0, Object[] arg1) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public Properties getClientInfo() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public String getClientInfo(String arg0) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public synchronized boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException();
        }

        if (u_con == null || is_closed) return false;

        return u_con.isValid(timeout * 1000);
    }

    /* JDK 1.6 */
    public void setClientInfo(Properties arg0) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new java.lang.UnsupportedOperationException());
        throw clientEx;
    }

    /* JDK 1.6 */
    public void setClientInfo(String arg0, String arg1) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new java.lang.UnsupportedOperationException());
        throw clientEx;
    }

    /* JDK 1.6 */
    public boolean isWrapperFor(Class<?> arg0) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.6 */
    public <T> T unwrap(Class<T> arg0) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.7 */
    public void setSchema(String schema) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.7 */
    public String getSchema() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.7 */
    public void abort(Executor executor) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.7 */
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* JDK 1.7 */
    public int getNetworkTimeout() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }
}
