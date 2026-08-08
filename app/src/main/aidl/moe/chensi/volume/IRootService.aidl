package moe.chensi.volume;

import android.content.pm.PackageInfo;
import android.content.ComponentName;
import android.os.Bundle;

interface IRootService {
    List<PackageInfo> getInstalledPackages();
    ComponentName getForegroundTask();
    int getInterruptionFilter();
    void setInterruptionFilter(int filter);
    List<Bundle> getActivePlaybackConfigurations();
    void setAppPlayAudio(String packageName, boolean allow);
    void setAppVolume(String packageName, float volume);
}
