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

/**
 * Title: CUBRID Java Client Interface
 *
 * <p>Description: CUBRID Java Client Interface
 *
 * <p>
 *
 * @version 2.0
 */
package cubrid.jdbc.jci;

import java.util.ArrayList;
import java.util.Hashtable;
import java.lang.ref.WeakReference;

public abstract class UJCIManager {
    // static Vector connectionList;
    static String sysCharsetName;
    static Hashtable<UUrlHostKey, UUrlCache> url_cache_table;
    static ArrayList<UUrlCache> url_cache_remove_list;

    /* Phase 1: connections that have (or had) a deferred holdable cursor close.
     * The daemon scans these and flushes any whose oldest pending has passed the TTL.
     * WeakReference so an abandoned/GC'd connection drops out without being resurrected. */
    static ArrayList<WeakReference<UConnection>> deferred_close_conn_list;

    /* item: TTL after which a still-pending deferred cursor close is flushed (24h prod;
     * override to e.g. 3min for tests), and the scan cadence (60s prod). System-property
     * overridable. */
    static final long DEFERRED_CLOSE_TTL_MILLIS =
            Long.getLong("cubrid.deferred.cursor.close.ttl.millis", 86400000L);
    static final long DEFERRED_CLOSE_SCAN_SEC =
            Long.getLong("cubrid.deferred.cursor.close.scan.sec", 60L);

    static JdbcCacheWorker CACHE_Manager;
    static boolean result_cache_enable = true;

    static {
        // connectionList = new Vector();
        sysCharsetName = System.getProperty("file.encoding");
        url_cache_table = new Hashtable<UUrlHostKey, UUrlCache>(10);
        url_cache_remove_list = new ArrayList<UUrlCache>(10);
        deferred_close_conn_list = new ArrayList<WeakReference<UConnection>>(10);

        try {
            CACHE_Manager = new JdbcCacheWorker();
            CACHE_Manager.setDaemon(true);
            CACHE_Manager.setContextClassLoader(null);
            CACHE_Manager.start();
        } catch (Exception e) {
            e.printStackTrace();
            result_cache_enable = false;
        }
    }

    public static UConnection connect(
            String ip, int port, String name, String user, String passwd, String url)
            throws java.sql.SQLException {
        UClientSideConnection connection;

        connection = new UClientSideConnection(ip, port, name, user, passwd, url);
        // connectionList.add(connection);
        return connection;
    }

    public static UConnection connect(
            ArrayList<String> aConList, String name, String user, String passwd, String url)
            throws java.sql.SQLException {
        UClientSideConnection connection;

        connection = new UClientSideConnection(aConList, name, user, passwd, url);
        // connectionList.add(connection);
        return connection;
    }

    static UUrlCache getUrlCache(UUrlHostKey key) {
        UUrlCache url_cache;
        url_cache = url_cache_table.get(key);
        if (url_cache != null) return url_cache;

        synchronized (url_cache_table) {
            url_cache = url_cache_table.get(key);
            if (url_cache == null) {
                url_cache = new UUrlCache();
                url_cache_table.put(key, url_cache);
                synchronized (url_cache_remove_list) {
                    url_cache_remove_list.add(url_cache);
                }
            }
        }

        return url_cache;
    }

    /* Registered once, when a connection first defers a cursor close. */
    static void registerDeferredCloseConn(UConnection c) {
        if (c == null) return;
        synchronized (deferred_close_conn_list) {
            deferred_close_conn_list.add(new WeakReference<UConnection>(c));
        }
    }

    /* Daemon sweep: flush any connection whose deferred cursor-close batch has waited
     * past the TTL. Snapshot first so the connection-close I/O runs without holding the
     * global list lock; dead WeakReferences are pruned afterwards. */
    static void scanExpiredDeferredCloses() {
        ArrayList<WeakReference<UConnection>> snapshot;
        synchronized (deferred_close_conn_list) {
            snapshot = new ArrayList<WeakReference<UConnection>>(deferred_close_conn_list);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            UConnection c = snapshot.get(i).get();
            if (c == null) continue;
            try {
                c.flushExpiredDeferredCursorClose(DEFERRED_CLOSE_TTL_MILLIS);
            } catch (Exception e) {
            }
        }
        synchronized (deferred_close_conn_list) {
            for (int i = deferred_close_conn_list.size() - 1; i >= 0; i--) {
                if (deferred_close_conn_list.get(i).get() == null) {
                    deferred_close_conn_list.remove(i);
                }
            }
        }
    }

    /*
     * delete the UConnection object from connection list
     *
     * synchronized static boolean deleteInList (UConnection element) { if
     * (connectionList.contains(element)==false) return false;
     *
     * return connectionList.remove(element); }
     */
}

class JdbcCacheWorker extends Thread {
    private long deferredCloseScanTick = 0;

    public void run() {
        while (true) {
            try {
                long curTime = System.currentTimeMillis();
                synchronized (UJCIManager.url_cache_remove_list) {
                    for (int i = 0; i < UJCIManager.url_cache_remove_list.size(); i++) {
                        UUrlCache uc = (UUrlCache) UJCIManager.url_cache_remove_list.get(i);
                        if (uc.getCacheSize() > uc.getLimit()) {
                            uc.remove_expired_stmt(curTime);
                        }
                    }
                }
            } catch (Exception e) {
            }

            try {
                if (++deferredCloseScanTick >= UJCIManager.DEFERRED_CLOSE_SCAN_SEC) {
                    deferredCloseScanTick = 0;
                    UJCIManager.scanExpiredDeferredCloses();
                }
            } catch (Exception e) {
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }
}
