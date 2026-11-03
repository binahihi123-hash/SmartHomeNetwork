package com.smarthome.common;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Voice Capture Service for SmartHomeNetwork.
 * Captures audio from the microphone and integrates with VoiceCommandParser.
 *
 * Two modes of operation:
 * 1. LOCAL mode: Captures audio, converts to text using a pluggable STT engine.
 *    - Default: Uses a simple keyword-matching fallback (no external API needed).
 *    - Production: Plug in Google Cloud Speech-to-Text, Vosk, or Mozilla DeepSpeech.
 *
 * 2. PUSH-TO-TALK mode: Records while button is held, then processes the audio.
 *
 * Integration with CommandPanel:
 *   VoiceCaptureService vcs = new VoiceCaptureService();
 *   vcs.setSpeechToTextCallback(text -> {
 *       VoiceCommandParser.ParsedCommand cmd = VoiceCommandParser.parse(text);
 *       client.sendControlCommand(cmd.deviceId, cmd.command);
 *   });
 *   vcs.startListening();
 */
public class VoiceCaptureService {

    private static final Logger logger = Logger.getLogger(VoiceCaptureService.class.getName());

    /** Audio format: 16kHz, 16-bit, mono (standard for speech recognition) */
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean available = new AtomicBoolean(false);
    private TargetDataLine microphone;
    private Thread recordingThread;

    /** Buffer that accumulates audio data while recording */
    private volatile ByteArrayOutputStream recordingBuffer;

    /** Callback invoked when speech-to-text produces a result */
    private SpeechResultCallback speechCallback;

    /** Pluggable speech-to-text engine */
    private SpeechToTextEngine sttEngine;

    @FunctionalInterface
    public interface SpeechResultCallback {
        void onSpeechResult(String text);
    }

    @FunctionalInterface
    public interface SpeechToTextEngine {
        /**
         * Convert audio bytes to text.
         * @param audioData raw PCM audio data (16kHz, 16-bit, mono)
         * @param format the AudioFormat used
         * @return recognized text, or null if nothing recognized
         */
        String recognize(byte[] audioData, AudioFormat format);
    }

    public VoiceCaptureService() {
        // Check if microphone is available
        try {
            AudioFormat format = getAudioFormat();
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            available.set(AudioSystem.isLineSupported(info));
            if (available.get()) {
                logger.info("Microphone available for voice capture");
            } else {
                logger.warning("No microphone available — voice capture disabled");
            }
        } catch (Exception e) {
            logger.warning("Failed to check microphone availability: " + e.getMessage());
            available.set(false);
        }

        // Default STT engine: simple echo (for testing without external API)
        this.sttEngine = null; // Will use manual text input fallback
    }

    /**
     * Set the callback to receive recognized speech text.
     */
    public void setSpeechResultCallback(SpeechResultCallback callback) {
        this.speechCallback = callback;
    }

    /**
     * Set a custom speech-to-text engine.
     * Examples: Google Cloud Speech, Vosk offline, Mozilla DeepSpeech.
     */
    public void setSpeechToTextEngine(SpeechToTextEngine engine) {
        this.sttEngine = engine;
    }

    /**
     * Check if microphone hardware is available.
     */
    public boolean isMicrophoneAvailable() {
        return available.get();
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return recording.get();
    }

    /**
     * Start recording audio from real microphone hardware.
     * Opens a TargetDataLine and spawns a background thread that continuously
     * reads PCM data into an internal buffer until stopAndProcess() is called.
     * @return true if recording started successfully
     */
    public boolean startRecording() {
        if (!available.get()) {
            logger.warning("Cannot start recording: no microphone available");
            return false;
        }
        if (recording.get()) {
            logger.warning("Already recording");
            return false;
        }

        try {
            AudioFormat format = getAudioFormat();
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            // Create fresh buffer for this recording session
            recordingBuffer = new ByteArrayOutputStream();
            recording.set(true);

            // Spawn background thread to continuously read from microphone into buffer
            recordingThread = new Thread(() -> {
                byte[] chunk = new byte[4096];
                logger.info("Microphone capture thread started");
                while (recording.get() && microphone.isOpen()) {
                    int bytesRead = microphone.read(chunk, 0, chunk.length);
                    if (bytesRead > 0 && recordingBuffer != null) {
                        recordingBuffer.write(chunk, 0, bytesRead);
                    }
                }
                logger.info("Microphone capture thread ended. Total bytes: "
                        + (recordingBuffer != null ? recordingBuffer.size() : 0));
            }, "MicCapture");
            recordingThread.setDaemon(true);
            recordingThread.start();

            logger.info("Recording started — microphone is live");
            return true;
        } catch (LineUnavailableException e) {
            logger.warning("Failed to start recording: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop recording and process the captured audio.
     * 1. Signals the capture thread to stop
     * 2. Stops and closes the TargetDataLine
     * 3. Retrieves captured PCM audio from the buffer
     * 4. If a SpeechToTextEngine is set, passes audio to it
     * 5. Otherwise, falls back to showing a text-input dialog with audio stats
     * The result is delivered via the SpeechResultCallback.
     */
    public void stopAndProcess() {
        if (!recording.get() || microphone == null) {
            return;
        }

        // Signal capture thread to stop
        recording.set(false);

        // Stop the microphone hardware
        microphone.stop();
        microphone.close();

        // Wait for capture thread to finish flushing
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // Get the captured audio data
        byte[] audioData = (recordingBuffer != null) ? recordingBuffer.toByteArray() : new byte[0];
        float durationSecs = audioData.length / (SAMPLE_RATE * (SAMPLE_SIZE_BITS / 8) * CHANNELS);
        double rmsLevel = calculateRmsLevel(audioData);

        logger.info(String.format("Recording stopped. %d bytes (%.1fs), RMS level: %.1f dB",
                audioData.length, durationSecs, rmsLevel));

        // Process the audio
        if (audioData.length == 0) {
            logger.warning("No audio data captured");
            return;
        }

        new Thread(() -> {
            String recognizedText = null;

            // Try STT engine first
            if (sttEngine != null) {
                try {
                    recognizedText = sttEngine.recognize(audioData, getAudioFormat());
                } catch (Exception e) {
                    logger.warning("STT engine error: " + e.getMessage());
                }
            }

            // If no STT engine or STT returned nothing, use fallback dialog
            if ((recognizedText == null || recognizedText.isEmpty()) && speechCallback != null) {
                // Show dialog on EDT asking user to type what they said
                // This is the fallback when no real STT engine (Vosk/Google) is configured
                javax.swing.SwingUtilities.invokeLater(() -> {
                    String prompt = String.format(
                            "🎤 Đã thu âm %.1f giây (mức âm: %.0f dB)\n\n"
                            + "Chưa có engine nhận dạng giọng nói (STT).\n"
                            + "Vui lòng nhập lệnh bạn vừa nói:\n"
                            + "(VD: bật đèn phòng khách, tắt quạt...)",
                            durationSecs, rmsLevel);
                    String userInput = javax.swing.JOptionPane.showInputDialog(null,
                            prompt, "Nhập lệnh giọng nói", javax.swing.JOptionPane.PLAIN_MESSAGE);
                    if (userInput != null && !userInput.isBlank()) {
                        speechCallback.onSpeechResult(userInput.trim());
                    }
                });
            } else if (recognizedText != null && !recognizedText.isEmpty() && speechCallback != null) {
                speechCallback.onSpeechResult(recognizedText);
            }
        }, "STT-Process").start();
    }

    /**
     * Calculate RMS audio level in dB from raw PCM 16-bit data.
     * Used to verify that the microphone actually captured real audio.
     */
    private double calculateRmsLevel(byte[] audioData) {
        if (audioData.length < 2) return -100;

        long sumSquares = 0;
        int sampleCount = audioData.length / 2; // 16-bit = 2 bytes per sample
        for (int i = 0; i < audioData.length - 1; i += 2) {
            // Little-endian 16-bit signed PCM
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            sumSquares += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumSquares / sampleCount);
        // Convert to dB (relative to max 16-bit value 32768)
        return (rms > 0) ? 20.0 * Math.log10(rms / 32768.0) : -100.0;
    }

    /**
     * Record for a fixed duration, then auto-process.
     * @param durationMs recording duration in milliseconds
     */
    public void recordAndProcess(int durationMs) {
        if (!available.get()) {
            logger.warning("Cannot record: no microphone");
            return;
        }

        new Thread(() -> {
            try {
                AudioFormat format = getAudioFormat();
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                recording.set(true);

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[4096];
                long startTime = System.currentTimeMillis();

                while (System.currentTimeMillis() - startTime < durationMs && recording.get()) {
                    int bytesRead = line.read(data, 0, data.length);
                    if (bytesRead > 0) {
                        buffer.write(data, 0, bytesRead);
                    }
                }

                line.stop();
                line.close();
                recording.set(false);

                byte[] audioData = buffer.toByteArray();
                logger.info("Timed recording done. " + audioData.length + " bytes captured");

                if (sttEngine != null && audioData.length > 0 && speechCallback != null) {
                    String text = sttEngine.recognize(audioData, format);
                    if (text != null && !text.isEmpty()) {
                        speechCallback.onSpeechResult(text);
                    }
                }
            } catch (Exception e) {
                recording.set(false);
                logger.warning("Recording error: " + e.getMessage());
            }
        }, "VoiceRecorder").start();
    }

    /**
     * Process text directly through VoiceCommandParser (bypass microphone).
     * Use this when text input is already available (e.g., typed command, external STT).
     * @param text Vietnamese voice command text
     * @return ParsedCommand result
     */
    public static VoiceCommandParser.ParsedCommand processText(String text) {
        return VoiceCommandParser.parse(text);
    }

    /**
     * Cancel current recording without processing.
     */
    public void cancelRecording() {
        recording.set(false);
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        logger.info("Recording cancelled");
    }

    private AudioFormat getAudioFormat() {
        return new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
    }

    /**
     * Get a list of available audio input devices for diagnostics.
     */
    public static String getAudioDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Audio Input Devices ===\n");
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mi : mixers) {
            Mixer mixer = AudioSystem.getMixer(mi);
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            if (targetLines.length > 0) {
                sb.append("  ").append(mi.getName()).append(" — ").append(mi.getDescription()).append("\n");
            }
        }
        if (sb.toString().equals("=== Audio Input Devices ===\n")) {
            sb.append("  (Không tìm thấy thiết bị ghi âm)\n");
        }
        return sb.toString();
    }
}
