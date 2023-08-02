package org.membraneframework.rtc.media;

import org.membraneframework.rtc.models.VadStatus;

@FunctionalInterface
public interface OnSoundVolumeChangedListener {
    void onSoundVolumeChangedListener(VadStatus value);
}
