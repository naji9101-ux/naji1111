package com.sitr.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Domain-based protection only. First-party video and social-media hosts are deliberately allowed. */
public final class BlockRules {
    private static final String[] ADS = {
        "doubleclick.net","googlesyndication.com","googleadservices.com",
        "adservice.google.com","adservice.google.co.uk","adservice.google.de",
        "pagead2.googlesyndication.com","tpc.googlesyndication.com",
        "adnxs.com","adsrvr.org","rubiconproject.com","pubmatic.com",
        "openx.net","criteo.com","criteo.net","outbrain.com","taboola.com",
        "smartadserver.com","indexexchange.com","casalemedia.com",
        "contextweb.com","lijit.com","yieldmo.com","sharethrough.com",
        "triplelift.com","amazon-adsystem.com","serving-sys.com",
        "adform.net","adcolony.com","unityads.unity3d.com",
        "applovin.com","applvn.com","vungle.com","inmobi.com",
        "chartboost.com","ironsrc.com","is.com","mopub.com",
        "startappservice.com","startappexchange.com","tapjoy.com",
        "fyber.com","smaato.net","smaato.com","mintegral.com",
        "pangle.io","ads.tiktok.com","admob.com","admost.com",
        "adkernel.com","adition.com","sovrn.com","sovrn.net",
        "adsafeprotected.com","bidr.io","bidswitch.net","bidtheatre.com",
        "adzerk.net","adzerk.com","revcontent.com","mgid.com",
        "popads.net","propellerads.com","exoclick.com","adsterra.com",
        "adroll.com","media.net","quantserve.com","spotxchange.com",
        "spotx.tv","undertone.com","flashtalking.com","gumgum.com"
    };
    private static final String[] TRACKERS = {
        "app-measurement.com","google-analytics.com","googletagmanager.com",
        "analytics.google.com","firebase-settings.crashlytics.com",
        "mixpanel.com","segment.io","segment.com","amplitude.com",
        "adjust.com","appsflyer.com","branch.io","branch.app.link",
        "kochava.com","singular.net","tenjin.io","onesignal.com",
        "hotjar.com","fullstory.com","mouseflow.com","clarity.ms",
        "newrelic.com","bugsnag.com","sentry.io","datadoghq.com",
        "optimizely.com","heap.io","heapanalytics.com","pendo.io",
        "scorecardresearch.com","comscore.com","mathtag.com",
        "demdex.net","bluekai.com","krxd.net","rlcdn.com",
        "tapad.com","exelator.com","lotame.com","agkn.com",
        "crwdcntrl.net","adsymptotic.com","ads-twitter.com"
    };
    private static volatile Set<String> downloaded = Collections.emptySet();
    private static volatile boolean loaded = false;

    private BlockRules() {}

    public static synchronized void load(Context context) {
        if (loaded) return;
        Set<String> set = new HashSet<>();
        File file = new File(context.getFilesDir(), "blocked_hosts.txt");
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (valid(line)) set.add(line);
                }
            } catch (Exception ignored) { }
        }
        downloaded = Collections.unmodifiableSet(set);
        loaded = true;
    }

    public static boolean shouldBlock(Context context, String domain,
                                      boolean adProtection, boolean privacy) {
        if (domain == null || domain.isEmpty()) return false;
        String host = domain.toLowerCase(Locale.ROOT);
        SharedPreferences prefs = context.getSharedPreferences("sitr", Context.MODE_PRIVATE);
        String rawAllow = prefs.getString("allow", "");
        for (String allowed : rawAllow.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            if (!allowed.isEmpty() && matches(host, allowed)) return false;
        }
        if (adProtection && (matchesAny(host, ADS) || matchesDownloaded(host))) return true;
        return privacy && matchesAny(host, TRACKERS);
    }

    private static boolean matchesAny(String domain, String[] list) {
        for (String entry : list) if (matches(domain, entry)) return true;
        return false;
    }

    private static boolean matchesDownloaded(String domain) {
        Set<String> current = downloaded;
        String suffix = domain;
        while (true) {
            if (current.contains(suffix)) return true;
            int dot = suffix.indexOf('.');
            if (dot < 0) return false;
            suffix = suffix.substring(dot + 1);
        }
    }

    private static boolean matches(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private static boolean valid(String s) {
        return s.length() > 3 && s.length() < 254 && s.contains(".")
                && s.matches("[a-z0-9.-]+")
                && !s.startsWith(".") && !s.endsWith(".");
    }

    public static int filterCount() {
        return downloaded.size() + ADS.length + TRACKERS.length;
    }

    /** Downloads a community hosts file over HTTPS; never executes downloaded content. */
    public static int update(Context context) throws Exception {
        URL url = new URL("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "SITR-Android/0.1");
        Set<String> next = new HashSet<>();
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 400000) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) continue;
                    if (!parts[0].equals("0.0.0.0") && !parts[0].equals("127.0.0.1")) continue;
                    String host = parts[1];
                    if (!host.equals("localhost") && valid(host)) next.add(host);
                }
            }
        } finally { conn.disconnect(); }
        if (next.size() < 1000) throw new Exception("Filter list did not pass validation.");
        File tmp = new File(context.getFilesDir(), "blocked_hosts.tmp");
        File finalFile = new File(context.getFilesDir(), "blocked_hosts.txt");
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            for (String host : next) {
                stream.write(host.getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
            }
            stream.getFD().sync();
        }
        if (!tmp.renameTo(finalFile)) throw new Exception("Could not save filter update.");
        downloaded = Collections.unmodifiableSet(next);
        loaded = true;
        return next.size();
    }

    public static String queryHost(byte[] query) {
        if (query == null || query.length < 17) return null;
        int count = ((query[4] & 255) << 8) | (query[5] & 255);
        if (count != 1) return null;
        int p = 12;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (p >= query.length) return null;
            int len = query[p++] & 255;
            if (len == 0) {
                if (p + 4 > query.length) return null;
                return name.toString().toLowerCase(Locale.ROOT);
            }
            if (len > 63 || p + len > query.length) return null;
            if (name.length() > 0) name.append('.');
            for (int j = 0; j < len; j++) {
                char ch = (char) (query[p++] & 255);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '-') name.append(ch);
                else return null;
            }
        }
        return null;
    }

    public static byte[] blockedDnsReply(byte[] query) {
        int p = 12;
        for (int i = 0; i < 128 && p < query.length; i++) {
            int len = query[p++] & 255;
            if (len == 0) break;
            if (len > 63 || p + len > query.length) return query.clone();
            p += len;
        }
        if (p + 4 > query.length) return query.clone();
        byte[] reply = java.util.Arrays.copyOf(query, p + 4);
        int flags = ((query[2] & 255) << 8) | (query[3] & 255);
        int responseFlags = 0x8083 | (flags & 0x0100);
        reply[2] = (byte)(responseFlags >>> 8);
        reply[3] = (byte) responseFlags;
        for (int i = 6; i <= 11; i++) reply[i] = 0;
        return reply;
    }
}
