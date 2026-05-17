package com.example.audio_recorder.engine;

import com.nerio.audioengine.UsbAudioInput;

import java.io.File;
import java.io.IOException;

public class WavSink implements EncodingSink {

    private final UsbAudioInput input;

    public WavSink(UsbAudioInput input) {
        this.input = input;
    }

    @Override
    public boolean beginRecording(File tempOut, int rate, int channels, int bitDepth)
            throws IOException {
        return input.startRecording(tempOut);
    }

    @Override
    public void endRecording() {
        input.stopRecording();
    }

    @Override
    public String fileExtension() {
        return "wav";
    }

    @Override
    public String mimeType() {
        return "audio/x-wav";
    }
}
