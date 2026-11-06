package com.avies.backend.service.impl;

import com.avies.backend.config.AzureSpeechTtsProperties;
import com.avies.backend.service.support.StreamingSynthesisEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Hai request F0 thật trên cùng native session; không gọi Gemini hoặc database. */
@Tag("external-services") @EnabledIfSystemProperty(named="allowExternalServices",matches="true")
class AzureTtsReuseLiveTest {
    @Test void vietnameseAndEnglishStreamOnTheSameReusableAzureConnection() throws Exception {
        var local=new Properties(); try(var reader=Files.newBufferedReader(Path.of(".env"),StandardCharsets.UTF_8)){local.load(reader);}
        var settings=new AzureSpeechTtsProperties();
        ReflectionTestUtils.setField(settings,"enabled",true);
        ReflectionTestUtils.setField(settings,"apiKey",value(local,"AZURE_TTS_API_KEY",value(local,"AZURE_SPEECH_API_KEY","")));
        ReflectionTestUtils.setField(settings,"region",value(local,"AZURE_TTS_REGION",value(local,"AZURE_SPEECH_REGION","southeastasia")));
        ReflectionTestUtils.setField(settings,"vietnameseVoice","en-US-AvaMultilingualNeural");
        ReflectionTestUtils.setField(settings,"englishVoice","en-US-AvaMultilingualNeural");
        assertThat(settings.ready()).as("Configure local Azure Speech F0").isTrue();
        var nativeEngine=new AzureSpeechSynthesisEngine(); var opened=new AtomicInteger();
        StreamingSynthesisEngine counted=(key,region,voice)->{opened.incrementAndGet();return nativeEngine.open(key,region,voice);};
        try(var provider=new AzureOralSpeechProvider(settings,counted)) {
            for(String language:java.util.List.of("vi","en")) {
                long started=System.nanoTime();long[] first={-1};int[] bytes={0},chunks={0};
                provider.stream(language.equals("vi")?"Câu số một. Bạn hãy giải thích use case?":"Question number two. Please explain use cases.",language,pcm->{
                    if(first[0]<0) first[0]=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                    assertThat(pcm.length%2).isZero(); assertThat(pcm.length).isBetween(2,8192);bytes[0]+=pcm.length;chunks[0]++;
                });
                assertThat(bytes[0]).isGreaterThan(24000); assertThat(chunks[0]).isGreaterThan(1);
                System.out.printf("AZURE_TTS_REUSE language=%s chunks=%d bytes=%d firstAudioMs=%d totalMs=%d%n",language,chunks[0],bytes[0],first[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            }
            assertThat(opened.get()).as("Both languages reuse one real native connection").isEqualTo(1);
        }
    }
    private static String value(Properties local,String key,String fallback){String configured=System.getenv(key);return (configured==null?local.getProperty(key,fallback):configured).strip();}
}
