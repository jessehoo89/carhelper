package com.carhelper.phone;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 车机发现：只认 WiFi 网络 → 取默认网关（手机连车机热点时网关即车机）→
 * 探测 :5555；网关不通再按本机网段 1..254 并发兜底。
 */
public class CarFinder {

    public static final int ADB_PORT = 5555;

    public static class Wifi {
        public Network network;
        public String localIp;
        public String gateway;
        public String ssidHint;
    }

    /** 取当前 WiFi（要求有 IPv4）。 */
    public static Wifi currentWifi(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            String ip = null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    ip = la.getAddress().getHostAddress();
                    break;
                }
            }
            if (ip == null) continue;
            Wifi w = new Wifi();
            w.network = n;
            w.localIp = ip;
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                    w.gateway = r.getGateway().getHostAddress();
                    break;
                }
            }
            return w;
        }
        return null;
    }

    /** 发现开着 5555 的设备。返回命中的 IP 列表。 */
    public static List<String> discover(Context ctx, Wifi w, int perProbeTimeoutMs) {
        List<String> hits = new ArrayList<String>();
        if (w == null || w.network == null) return hits;
        List<String> first = new ArrayList<String>();
        if (w.gateway != null) first.add(w.gateway);
        if (!first.isEmpty()) {
            hits = probe(w.network, first, perProbeTimeoutMs);
            if (!hits.isEmpty()) return hits;
        }
        String[] p = w.localIp.split("\\.");
        if (p.length != 4) return hits;
        List<String> cand = new ArrayList<String>();
        for (int i = 1; i <= 254; i++) {
            cand.add(p[0] + "." + p[1] + "." + p[2] + "." + i);
        }
        return probe(w.network, cand, perProbeTimeoutMs);
    }

    private static List<String> probe(final Network net, List<String> ips, final int timeoutMs) {
        List<String> out = new ArrayList<String>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<String>> fs = new ArrayList<Future<String>>();
        for (final String ip : ips) {
            fs.add(pool.submit(new Callable<String>() {
                public String call() {
                    Socket s = new Socket();
                    try {
                        net.bindSocket(s);
                        s.connect(new InetSocketAddress(ip, ADB_PORT), timeoutMs);
                        return ip;
                    } catch (Exception e) {
                        return null;
                    } finally {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }));
        }
        for (Future<String> f : fs) {
            try {
                String r = f.get(3, TimeUnit.SECONDS);
                if (r != null) out.add(r);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return out;
    }
}
