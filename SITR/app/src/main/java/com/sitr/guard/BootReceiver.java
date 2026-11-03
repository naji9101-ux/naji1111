package com.sitr.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean ads = context.getSharedPreferences("sitr", Context.MODE_PRIVATE)
                .getBoolean("ads", false);
        boolean privacy = context.getSharedPreferences("sitr", Context.MODE_PRIVATE)
                .getBoolean("privacy", false);
        if ((ads || privacy) && VpnService.prepare(context) == null) {
            ShieldVpnService.requestStart(context);
        }
    }
}
