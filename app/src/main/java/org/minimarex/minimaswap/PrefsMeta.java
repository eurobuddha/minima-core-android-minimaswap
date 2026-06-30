package org.minimarex.minimaswap;

import android.content.SharedPreferences;

import org.minimarex.comms.CommsScanner;

/** SharedPreferences-backed scan bookmarks for {@link CommsScanner} (it needs a MetaStore). */
public final class PrefsMeta implements CommsScanner.MetaStore {
    private final SharedPreferences p;
    public PrefsMeta(SharedPreferences p) { this.p = p; }
    @Override public String getMeta(String k, String def) { return p.getString("meta_" + k, def); }
    @Override public void setMeta(String k, String v) { p.edit().putString("meta_" + k, v).apply(); }
}
