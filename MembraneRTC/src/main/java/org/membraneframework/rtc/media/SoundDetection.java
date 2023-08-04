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
    private int bufferSize;
    public boolean isRecording = false;
    private final int volumeThreshold = -100;
    private final int monitorInterval = 1;
    private final int samplingRate = 22050;

    public void start() throws SecurityException {
        start(monitorInterval, samplingRate, volumeThreshold);
    }

    public void start(int monitorInterval, int samplingRate, int volumeThreshold) throws SecurityException {
        if (isRecording) return;

        bufferSize = AudioRecord.getMinBufferSize(samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
            isRecording = true;
            startTimer(monitorInterval,volumeThreshold);
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

    public void setSoundDetectionListener(OnSoundDetectedListener listener) {
        onSoundDetectedListener = listener;
    }

    private void setIsSoundDetected(boolean newValue) {
        if (onSoundDetectedListener != null) {
            onSoundDetectedListener.onSoundDetected(newValue);
        }
    }

    private void detectSound(int volumeThreshold, int volumeValue) {
        setIsSoundDetected(volumeValue > volumeThreshold);
    }

    private int getMaxAmplitude(short[] buffer, int bytesRead) {
        int maxAmplitude = 0;
        for (int i = 0; i < bytesRead; i++) {
            maxAmplitude = Math.max(maxAmplitude, Math.abs(buffer[i]));
        }
        return maxAmplitude;
    }
    /**
     * Calculates the sound level value in decibels (dB) based on the given maxAmplitude.
     * If maxAmplitude is non-positive (<= 0), returns a default value of -160 dB.
     * Otherwise, computes the value using the formula: 20 * log10(maxAmplitude / 32767),
     * where 32767 is the maximum value for a 16-bit PCM audio sample.
     * The calculated dB value represents the loudness of the audio signal.
     **/
    private int calculateValue(int maxAmplitude) {
        if (maxAmplitude <= 0) {
            return -160;
        }
        return (int) (20 * Math.log(((double) maxAmplitude) / 32767d));
    }

    private int readAudioData(short[] buffer) {
        return audioRecord.read(buffer, 0, bufferSize);
    }

    private void startTimer(int monitorInterval, int volumeThreshold) {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                short[] buffer = new short[bufferSize];
                int bytesRead = readAudioData(buffer);
                if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE)
                    return;
                int amplitude = getMaxAmplitude(buffer, bytesRead);
                int value = calculateValue(amplitude);
                detectSound(value,volumeThreshold);
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

