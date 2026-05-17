package com.example.audio_recorder.engine;

import java.io.File;
import java.io.IOException;

public interface EncodingSink {

    boolean beginRecording(File tempOut, int rate, int channels, int bitDepth) throws IOException;

    void endRecording();

    String fileExtension();

    String mimeType();
}
