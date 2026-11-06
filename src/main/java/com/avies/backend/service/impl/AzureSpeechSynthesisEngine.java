package com.avies.backend.service.impl;

import com.avies.backend.exception.*;
import com.avies.backend.service.support.StreamingSynthesisEngine;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.util.EventHandler;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** SDK adapter: PCM/SSML như trước; session có thể tái sử dụng, không giữ text/callback của lần trước. */
@Component
public class AzureSpeechSynthesisEngine implements StreamingSynthesisEngine {
    @Override public Session open(String apiKey, String region, String voice) {
        SpeechConfig config=null; SpeechSynthesizer synthesis=null; Connection connection=null;
        try {
            config=SpeechConfig.fromSubscription(apiKey,region);
            config.setSpeechSynthesisVoiceName(voice);
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm);
            synthesis=new SpeechSynthesizer(config,(AudioConfig)null);
            connection=Connection.fromSpeechSynthesizer(synthesis);
            return new NativeSession(config,synthesis,connection);
        } catch (RuntimeException | LinkageError failure) {
            if (connection!=null) connection.close();
            if (synthesis!=null) synthesis.close();
            if (config!=null) config.close();
            throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        }
    }
    private static final class NativeSession implements Session {
        final SpeechConfig config; final SpeechSynthesizer synthesis; final Connection connection;
        String previousResultId;
        NativeSession(SpeechConfig config,SpeechSynthesizer synthesis,Connection connection) {
            this.config=config; this.synthesis=synthesis; this.connection=connection;
        }
        @Override public void preconnect() { connection.openConnection(true); }
        @Override public void stream(String ssml,Consumer<byte[]> sink) {
            var delivered=new ByteArrayOutputStream(); var failure=new AtomicReference<RuntimeException>();
            var guard=new Object(); var accepting=new boolean[]{true}; var currentId=new String[]{null};
            EventHandler<SpeechSynthesisEventArgs> started=(sender,event)-> {
                synchronized (guard) {
                    String id=event.getResult().getResultId();
                    if (accepting[0] && !java.util.Objects.equals(id,previousResultId)) currentId[0]=id;
                }
            };
            EventHandler<SpeechSynthesisEventArgs> chunks=(sender,event)-> {
                synchronized (guard) {
                    if (!accepting[0] || failure.get()!=null || currentId[0]==null
                            || !currentId[0].equals(event.getResult().getResultId())) return;
                    byte[] pcm=event.getResult().getAudioData(); if(pcm==null || pcm.length==0) return;
                    try { append(delivered,pcm,sink); } catch(RuntimeException bad) { failure.set(bad); }
                }
            };
            synthesis.SynthesisStarted.addEventListener(started); synthesis.Synthesizing.addEventListener(chunks);
            try (var result=synthesis.SpeakSsmlAsync(ssml).get(35,TimeUnit.SECONDS)) {
                synchronized (guard) {
                    accepting[0]=false; previousResultId=result.getResultId();
                    if(failure.get()!=null) throw failure.get();
                    if(result.getReason()!=ResultReason.SynthesizingAudioCompleted) {
                        var cancellation=SpeechSynthesisCancellationDetails.fromResult(result);
                        if(cancellation.getErrorCode()==CancellationErrorCode.TooManyRequests) throw new AppException(ErrorCode.ORAL_SPEECH_QUOTA);
                        if(cancellation.getErrorCode()==CancellationErrorCode.AuthenticationFailure || cancellation.getErrorCode()==CancellationErrorCode.Forbidden)
                            throw new AppException(ErrorCode.ORAL_SPEECH_NOT_READY);
                        throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                    }
                    byte[] all=result.getAudioData(),sent=delivered.toByteArray();
                    if(all==null || all.length<2 || all.length%2!=0 || all.length+44>GeminiOralSpeechProvider.MAX_AUDIO_BYTES
                            || sent.length>all.length || !Arrays.equals(sent,0,sent.length,all,0,sent.length)) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
                    if(sent.length<all.length) append(delivered,Arrays.copyOfRange(all,sent.length,all.length),sink);
                }
            } catch(AppException safe) { throw safe; }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
            catch(Exception | LinkageError failureFromSdk) { throw new AppException(ErrorCode.ORAL_SPEECH_FAILED); }
            finally {
                synchronized(guard) { accepting[0]=false; }
                synthesis.SynthesisStarted.removeEventListener(started); synthesis.Synthesizing.removeEventListener(chunks);
            }
        }
        @Override public void close() { try { connection.close(); } finally { try { synthesis.close(); } finally { config.close(); } } }
    }
    private static void append(ByteArrayOutputStream delivered,byte[] pcm,Consumer<byte[]> sink) {
        if(pcm.length%2!=0 || delivered.size()+pcm.length+44>GeminiOralSpeechProvider.MAX_AUDIO_BYTES) throw new AppException(ErrorCode.ORAL_SPEECH_FAILED);
        delivered.writeBytes(pcm);
        for(int offset=0;offset<pcm.length;offset+=8192) sink.accept(Arrays.copyOfRange(pcm,offset,Math.min(offset+8192,pcm.length)));
    }
}
