package com.chua.ffmpeg.support.processor;

import com.chua.common.support.media.ffmpeg.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"javacv"}, order = 50)
public class JavaCVFFmpegProcessor implements FFmpegProcessor {

    private static volatile boolean available = false;
    private static String loadError = null;

    static {
        try {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
            available = true;
        } catch (Throwable e) {
            loadError = e.getMessage();
            log.warn("[FFmpeg][JavaCV] JavaCV FFmpeg不可用: {}", loadError);
        }
    }

    @Override
    public void convertVideo(File input, File output, String targetFormat) throws IOException {
        convertVideo(input, output, targetFormat, FFmpegOptions.defaultOptions());
    }

    @Override
    public void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        output.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0)) {

            grabber.start();

            recorder.setFormat(targetFormat);
            recorder.setImageWidth(options.getWidth() != null ? options.getWidth() : grabber.getImageWidth());
            recorder.setImageHeight(options.getHeight() != null ? options.getHeight() : grabber.getImageHeight());
            recorder.setFrameRate(options.getFps() != null ? options.getFps() : grabber.getFrameRate());
            recorder.setAudioChannels(grabber.getAudioChannels());
            recorder.setSampleRate(grabber.getSampleRate());

            if (options.getVideoCodec() != null) {
                recorder.setVideoCodecName(options.getVideoCodec());
            } else {
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            }
            if (options.getAudioCodec() != null) {
                recorder.setAudioCodecName(options.getAudioCodec());
            } else {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            }
            if (options.getQuality() != null) {
                recorder.setVideoQuality(options.getQuality());
            }

            recorder.start();

            Frame frame;
            while ((frame = grabber.grab()) != null) {
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public void convertVideo(InputStream inputStream, OutputStream outputStream,
                             String inputFormat, String outputFormat) throws IOException {
        File tempInput = File.createTempFile("ffmpeg_input_", "." + inputFormat);
        File tempOutput = File.createTempFile("ffmpeg_output_", "." + outputFormat);
        try {
            try (FileOutputStream fos = new FileOutputStream(tempInput)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
            convertVideo(tempInput, tempOutput, outputFormat);
            try (FileInputStream fis = new FileInputStream(tempOutput)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                }
            }
        } finally {
            tempInput.delete();
            tempOutput.delete();
        }
    }

    @Override
    public void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        audioOutput.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoInput);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(audioOutput, 0)) {

            grabber.start();
            recorder.setFormat(audioFormat);
            recorder.setAudioChannels(grabber.getAudioChannels());
            recorder.setSampleRate(grabber.getSampleRate());
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
            recorder.start();

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat) throws IOException {
        convertAudio(input, output, targetFormat, FFmpegOptions.defaultOptions());
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        output.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0)) {

            grabber.start();
            recorder.setFormat(targetFormat);
            recorder.setAudioChannels(options.getAudioChannels() != null ? options.getAudioChannels() : grabber.getAudioChannels());
            recorder.setSampleRate(options.getAudioSampleRate() != null ? options.getAudioSampleRate() : grabber.getSampleRate());
            if (options.getAudioCodec() != null) {
                recorder.setAudioCodecName(options.getAudioCodec());
            }
            recorder.start();

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        imageOutput.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoInput)) {
            grabber.start();
            grabber.setTimestamp((long) (timestamp * 1000000));

            Frame frame = grabber.grabImage();
            if (frame != null) {
                Java2DFrameConverter converter = new Java2DFrameConverter();
                BufferedImage image = converter.convert(frame);
                String format = FileUtils.getSimpleExtension(imageOutput.getName());
                ImageIO.write(image, format != null ? format : "jpg", imageOutput);
            }

            grabber.stop();
        }
    }

    @Override
    public File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        outputDir.mkdirs();

        List<File> capturedFiles = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoInput)) {
            grabber.start();
            Java2DFrameConverter converter = new Java2DFrameConverter();
            double duration = grabber.getLengthInTime() / 1000000.0;
            int frameIndex = 0;

            for (double ts = 0; ts < duration; ts += interval) {
                grabber.setTimestamp((long) (ts * 1000000));
                Frame frame = grabber.grabImage();
                if (frame != null) {
                    BufferedImage image = converter.convert(frame);
                    File outputFile = new File(outputDir, String.format("frame_%05d.%s", frameIndex++, imageFormat));
                    ImageIO.write(image, imageFormat, outputFile);
                    capturedFiles.add(outputFile);
                }
            }
            grabber.stop();
        }
        return capturedFiles.toArray(new File[0]);
    }

    @Override
    public void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        imageOutput.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoInput)) {
            grabber.start();
            double duration = grabber.getLengthInTime() / 1000000.0;
            grabber.setTimestamp((long) (duration * 500000));

            Frame frame = grabber.grabImage();
            if (frame != null) {
                Java2DFrameConverter converter = new Java2DFrameConverter();
                BufferedImage image = converter.convert(frame);
                BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                thumbnail.getGraphics().drawImage(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null);

                String format = FileUtils.getSimpleExtension(imageOutput.getName());
                ImageIO.write(thumbnail, format != null ? format : "jpg", imageOutput);
            }
            grabber.stop();
        }
    }

    @Override
    public void trim(File input, File output, double startTime, double duration) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        output.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0)) {

            grabber.start();
            recorder.setFormat(FileUtils.getSimpleExtension(output.getName()));
            recorder.setImageWidth(grabber.getImageWidth());
            recorder.setImageHeight(grabber.getImageHeight());
            recorder.setFrameRate(grabber.getFrameRate());
            recorder.setAudioChannels(grabber.getAudioChannels());
            recorder.setSampleRate(grabber.getSampleRate());
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.start();

            grabber.setTimestamp((long) (startTime * 1000000));
            long endTimestamp = (long) ((startTime + duration) * 1000000);

            Frame frame;
            while ((frame = grabber.grab()) != null) {
                if (grabber.getTimestamp() > endTimestamp) {
                    break;
                }
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public void concat(File[] inputs, File output) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        if (inputs == null || inputs.length == 0) {
            throw new IllegalArgumentException("输入文件不能为空");
        }
        output.getParentFile().mkdirs();

        FFmpegFrameGrabber firstGrabber = new FFmpegFrameGrabber(inputs[0]);
        firstGrabber.start();

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0)) {
            recorder.setFormat(FileUtils.getSimpleExtension(output.getName()));
            recorder.setImageWidth(firstGrabber.getImageWidth());
            recorder.setImageHeight(firstGrabber.getImageHeight());
            recorder.setFrameRate(firstGrabber.getFrameRate());
            recorder.setAudioChannels(firstGrabber.getAudioChannels());
            recorder.setSampleRate(firstGrabber.getSampleRate());
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.start();
            firstGrabber.stop();

            for (File inputFile : inputs) {
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputFile)) {
                    grabber.start();
                    Frame frame;
                    while ((frame = grabber.grab()) != null) {
                        recorder.record(frame);
                    }
                    grabber.stop();
                }
            }

            recorder.stop();
        }
    }

    @Override
    public void resize(File input, File output, int width, int height) throws IOException {
        FFmpegOptions options = new FFmpegOptions();
        options.setWidth(width);
        options.setHeight(height);
        convertVideo(input, output, FileUtils.getSimpleExtension(output.getName()), options);
    }

    @Override
    public void rotate(File input, File output, int angle) throws IOException {
        throw new UnsupportedOperationException("JavaCV处理器不支持旋转功能，请使用命令行处理器");
    }

    @Override
    public void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException {
        throw new UnsupportedOperationException("JavaCV处理器不支持水印功能，请使用命令行处理器");
    }

    @Override
    public FFmpegMediaInfo getMediaInfo(File input) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input)) {
            grabber.start();

            FFmpegMediaInfo.VideoStream videoStream = null;
            if (grabber.getImageWidth() > 0 && grabber.getImageHeight() > 0) {
                videoStream = new FFmpegMediaInfo.VideoStream();
                videoStream.setWidth(grabber.getImageWidth());
                videoStream.setHeight(grabber.getImageHeight());
                videoStream.setFps(grabber.getFrameRate());
                videoStream.setCodec(grabber.getVideoCodecName());
                videoStream.setBitrate(grabber.getVideoBitrate());
                videoStream.setDuration(grabber.getLengthInTime() / 1000000.0);
            }

            FFmpegMediaInfo.AudioStream audioStream = null;
            if (grabber.getAudioChannels() > 0) {
                audioStream = new FFmpegMediaInfo.AudioStream();
                audioStream.setChannels(grabber.getAudioChannels());
                audioStream.setSampleRate(grabber.getSampleRate());
                audioStream.setCodec(grabber.getAudioCodecName());
                audioStream.setBitrate(grabber.getAudioBitrate());
                audioStream.setDuration(grabber.getLengthInTime() / 1000000.0);
            }

            grabber.stop();

            FFmpegMediaInfo info = new FFmpegMediaInfo();
            info.setFilename(input.getName());
            info.setFormatName(grabber.getFormat());
            info.setDuration(grabber.getLengthInTime() / 1000000.0);
            info.setSize(input.length());
            info.setBitrate(grabber.getVideoBitrate() + grabber.getAudioBitrate());
            info.setVideoStream(videoStream);
            info.setAudioStream(audioStream);
            return info;
        }
    }

    @Override
    public double getDuration(File input) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input)) {
            grabber.start();
            double duration = grabber.getLengthInTime() / 1000000.0;
            grabber.stop();
            return duration;
        }
    }

    @Override
    public void videoToGif(File videoInput, File gifOutput, double startTime, double duration,
                           int width, int fps) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        gifOutput.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoInput);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(gifOutput, width, 0)) {

            grabber.start();
            int height = (int) ((double) grabber.getImageHeight() / grabber.getImageWidth() * width);

            recorder.setFormat("gif");
            recorder.setImageWidth(width);
            recorder.setImageHeight(height);
            recorder.setFrameRate(fps);
            recorder.start();

            grabber.setTimestamp((long) (startTime * 1000000));
            long endTimestamp = (long) ((startTime + duration) * 1000000);

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (grabber.getTimestamp() > endTimestamp) {
                    break;
                }
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        videoOutput.getParentFile().mkdirs();

        File[] images = imageDir.listFiles((dir, name) -> name.matches(imagePattern.replace("%", ".*")));
        if (images == null || images.length == 0) {
            throw new IllegalArgumentException("目录中未找到图片");
        }
        java.util.Arrays.sort(images);

        BufferedImage firstImage = ImageIO.read(images[0]);

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(videoOutput, firstImage.getWidth(), firstImage.getHeight())) {
            recorder.setFormat(FileUtils.getSimpleExtension(videoOutput.getName()));
            recorder.setFrameRate(fps);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.start();

            Java2DFrameConverter converter = new Java2DFrameConverter();
            for (File imageFile : images) {
                BufferedImage image = ImageIO.read(imageFile);
                Frame frame = converter.convert(image);
                recorder.record(frame);
            }

            recorder.stop();
        }
    }

    @Override
    public void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException {
        throw new UnsupportedOperationException("推流功能未实现，请使用命令行处理器");
    }

    @Override
    public void pullStream(String streamUrl, File output, double duration) throws IOException {
        if (!available) {
            throw new IllegalStateException("JavaCV FFmpeg不可用: " + loadError);
        }
        output.getParentFile().mkdirs();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
             FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0)) {

            grabber.start();
            recorder.setFormat(FileUtils.getSimpleExtension(output.getName()));
            recorder.setImageWidth(grabber.getImageWidth());
            recorder.setImageHeight(grabber.getImageHeight());
            recorder.setFrameRate(grabber.getFrameRate());
            recorder.setAudioChannels(grabber.getAudioChannels());
            recorder.setSampleRate(grabber.getSampleRate());
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.start();

            long startTime = System.currentTimeMillis();
            long maxDuration = duration > 0 ? (long) (duration * 1000) : Long.MAX_VALUE;

            Frame frame;
            while ((frame = grabber.grab()) != null) {
                if (System.currentTimeMillis() - startTime > maxDuration) {
                    break;
                }
                recorder.record(frame);
            }

            recorder.stop();
            grabber.stop();
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String getVersion() {
        return "JavaCV-based FFmpeg Processor";
    }

    @Override
    public FFmpegResult execute(String... args) throws IOException {
        throw new UnsupportedOperationException("JavaCV处理器不支持执行自定义命令，请使用命令行处理器");
    }
}
