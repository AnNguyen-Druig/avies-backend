package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.exception.*;
import com.avies.backend.service.support.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Azure F0 backup; pool tối đa 2 session TTS, tách khỏi gate STT 1 session. */
@Component @lombok.extern.slf4j.Slf4j
public class AzureOralSpeechProvider implements AutoCloseable {
    private final AzureSpeechTtsProperties settings;
    private final StreamingSynthesisEngine engine;
    private final Semaphore capacity=new Semaphore(2);
    private final Semaphore nativeSlots=new Semaphore(2);
    private final Deque<Idle> idle=new ArrayDeque<>();
    private volatile boolean shutdown;
    private static final long IDLE_NANOS=TimeUnit.MINUTES.toNanos(5);
    @Autowired public AzureOralSpeechProvider(AzureSpeechTtsProperties settings,StreamingSynthesisEngine engine) { this.settings=settings; this.engine=engine; }
    /** Constructor dùng bởi các test/live fixture trước đây, không tự mở native session. */
    public AzureOralSpeechProvider(AzureSpeechTtsProperties settings) { this(settings,new AzureSpeechSynthesisEngine()); }
    public boolean ready() { return !shutdown && settings.ready(); }
    public byte[] synthesize(String text,String language) {
        var pcm=new ByteArrayOutputStream(); stream(text,language,pcm::writeBytes); return OralPcm.wav(pcm.toByteArray(),24000);
    }
    public void stream(String text,String language,Consumer<byte[]> sink) {
        if(!ready()) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
        String voice="vi".equals(language)?settings.getVietnameseVoice():settings.getEnglishVoice();
        String ssml=AzureSpeechSsml.render(text,language,voice);
        if(!capacity.tryAcquire()) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
        StreamingSynthesisEngine.Session session=null; boolean reusable=false;
        long began=System.nanoTime(); long[] first={-1}; boolean reused=false;
        String key=fingerprint(voice);
        try {
            synchronized(idle) {
                if(shutdown) throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
                for(var iterator=idle.iterator();iterator.hasNext();) {
                    var candidate=iterator.next();
                    if(candidate.key.equals(key) && System.nanoTime()-candidate.usedAt<IDLE_NANOS) { iterator.remove(); session=candidate.session; reused=true; break; }
                }
                if(session==null && !idle.isEmpty()) {
                    var replaced=idle.removeFirst();
                    if(!dispose(replaced.session)) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                }
            }
            if(session==null) {
                if(!nativeSlots.tryAcquire()) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
                try { session=Objects.requireNonNull(engine.open(settings.getApiKey(),settings.getRegion(),voice)); }
                catch(RuntimeException | LinkageError failure) { nativeSlots.release(); throw failure; }
            }
            session.preconnect();
            session.stream(ssml,pcm->{ if(first[0]<0) first[0]=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began); sink.accept(pcm); });
            reusable=true;
        } catch(AppException safe) { throw safe; }
        catch(RuntimeException | LinkageError failure) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
        finally {
            if(session!=null) {
                synchronized(idle) {
                    if(reusable && !shutdown && idle.size()<2) idle.addLast(new Idle(key,session,System.nanoTime()));
                    else dispose(session);
                }
            }
            capacity.release();
            log.info("oral-azure-tts reused={} firstAudioMs={} totalMs={} success={}",reused,first[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),reusable);
        }
    }
    /** Chỉ mở native connection khi dùng backup; thu hồi session nhàn rỗi sau 5 phút. */
    @Scheduled(fixedDelay=30000) public void evictIdle() {
        synchronized(idle) {
            for(var iterator=idle.iterator();iterator.hasNext();) {
                var entry=iterator.next(); if(shutdown || System.nanoTime()-entry.usedAt>=IDLE_NANOS) { iterator.remove(); dispose(entry.session); }
            }
        }
    }
    @Override @PreDestroy public void close() { shutdown=true; evictIdle(); }
    private boolean dispose(StreamingSynthesisEngine.Session session) {
        try { session.close(); nativeSlots.release(); return true; } catch(RuntimeException | LinkageError failure) { log.warn("oral-azure-tts cleanup=FAILED permit=HELD"); return false; }
    }
    private String fingerprint(String voice) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((settings.getApiKey()+"\0"+settings.getRegion()+"\0"+voice).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Idle(String key,StreamingSynthesisEngine.Session session,long usedAt) { }
}
