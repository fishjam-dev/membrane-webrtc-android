package org.membraneframework.rtc.media;


import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Timer;
import java.util.TimerTask;

import timber.log.Timber;

public class SoundDetection {
    private Timer timer;
    private AudioRecord audioRecord;
    private OnSoundDetectedListener onSoundDetectedListener;
    public boolean isRecording = false;
    private int bufferSize;
    private final int volumeThreshold = -100;
    private final int monitorInterval = 1;
    private final int samplingRate = 22050;

    public void start() throws SecurityException {
        start(monitorInterval, samplingRate);
    }

    public void start(int monitorInterval, int samplingRate) throws SecurityException {
        if (isRecording) return;

        bufferSize = AudioRecord.getMinBufferSize(samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
            isRecording = true;
            startTimer(monitorInterval);
        } else {
            Timber.e("COULDNT_PREPARE_RECORDING AudioRecord couldn't be initialized.");
        }
    }

    public void stop() {
        if (!isRecording) {
            Timber.e("INVALID_STATE Please call start before stopping recording");
            return;
        }
        stopTimer();
        isRecording = false;
        try {
            audioRecord.stop();
            audioRecord.release();
        } catch (final RuntimeException e) {
            Timber.e("RUNTIME_EXCEPTION No valid audio data received. You may be using a device that can't record audio.");
        } finally {
            audioRecord = null;
        }
    }

    public void setSoundDetectionListener() {
    }

    public void setSoundDetectionListener(OnSoundDetectedListener listener) {
        onSoundDetectedListener = listener;
    }

    public void setIsSoundDetected(boolean newValue) {
        onSoundDetectedListener.onSoundDetected(newValue);
    }

    private void detectSound(int volumeValue) {
        detectSound(volumeThreshold, volumeValue);
    }

    private void detectSound(int volumeThreshold, int volumeValue) {
        setIsSoundDetected(volumeValue > volumeThreshold);
    }

    private int getMaxAmplitude(short[] buffer, int bytesRead) {
        int maxAmplitude = 0;
        for (int sample : buffer) {
            maxAmplitude = Math.max(maxAmplitude, Math.abs(sample));
        }
        return maxAmplitude;
    }

    private int calculateValue(int maxAmplitude) {
        if (maxAmplitude <= 0) {
            return -160;
        }
        return (int) (20 * Math.log(((double) maxAmplitude) / 32767d));
    }

    private int readAudioData(short[] buffer) {
        return audioRecord.read(buffer, 0, bufferSize);
    }

    private void startTimer(int monitorInterval) {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                short[] buffer = new short[bufferSize];
                int bytesRead = readAudioData(buffer);
                int amplitude = getMaxAmplitude(buffer, bytesRead);
                int value = calculateValue(amplitude);
                detectSound(value);
            }
        }, 0, monitorInterval);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }
}

