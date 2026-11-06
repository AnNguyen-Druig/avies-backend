package com.avies.backend.service.impl;

import com.avies.backend.config.OralDecisionProperties;
import com.avies.backend.service.support.OralDecisionWork;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Một quyết định text thật với dữ liệu tự tạo; không đọc exam/Student hoặc gọi TTS. */
@Tag("external-services") @EnabledIfSystemProperty(named="allowExternalServices",matches="true")
class GeminiDecisionContextLiveTest {
    @Test void providerAcceptsSharedMainQuestionReferencesAndReturnsAValidatedDecision() throws Exception {
        var local=new Properties();try(var reader=Files.newBufferedReader(Path.of(".env"),StandardCharsets.UTF_8)){local.load(reader);}
        var settings=new OralDecisionProperties();
        ReflectionTestUtils.setField(settings,"enabled",Boolean.parseBoolean(value(local,"ORAL_AI_ENABLED","false")));
        ReflectionTestUtils.setField(settings,"apiKey",value(local,"GEMINI_ORAL_API_KEY",value(local,"GEMINI_API_KEY","")));
        ReflectionTestUtils.setField(settings,"model",value(local,"GEMINI_ORAL_MODEL","gemini-3.5-flash-lite"));
        assertThat(settings.ready()).as("Configure local oral AI before running the opt-in test").isTrue();
        String question="Explain what encapsulation means in object-oriented software, how a class protects its internal state through an interface, and why this matters for maintaining object invariants when an application changes.";
        var work=new OralDecisionWork(1,UUID.randomUUID(),UUID.randomUUID(),"en",question,
                List.of(new OralDecisionWork.Exchange(question,"Encapsulation groups data and behavior into a class and hides fields.")),true,
                new OralDecisionWork.Assessment("Encapsulation protects internal state through controlled access and preserves object invariants.","Encapsulation","Assess completeness and reasoning.",List.of("Explain data hiding and controlled access.","Explain the reason for preserving invariants.")));
        long started=System.nanoTime();var decision=new GeminiOralDecisionProvider(settings).decide(work);
        assertThat(decision).isNotNull();
        System.out.printf("GEMINI_DECISION_CONTEXT_LIVE followUp=%s providerMs=%d%n",decision.followUp(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    private static String value(Properties local,String key,String fallback){String configured=System.getenv(key);return (configured==null?local.getProperty(key,fallback):configured).strip();}
}
