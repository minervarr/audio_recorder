package com.example.audio_recorder.engine;

import com.nerio.audioengine.AudioInput;

import java.io.File;
import java.io.IOException;

public class FlacSink implements EncodingSink {

    private final AudioInput input;

    public FlacSink(AudioInput input) {
        this.input = input;
    }

    @Override
    public boolean beginRecording(File tempOut, int rate, int channels, int bitDepth)
            throws IOException {
        return input.startRecordingFlac(tempOut);
    }

    @Override
    public void endRecording() {
        input.stopRecording();
    }

    @Override
    public String fileExtension() {
        return "flac";
    }

    @Override
    public String mimeType() {
        return "audio/flac";
    }
}
