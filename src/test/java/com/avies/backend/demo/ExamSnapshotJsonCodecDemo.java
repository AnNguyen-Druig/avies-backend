package com.avies.backend.demo;

import com.avies.backend.service.support.ExamSnapshotJsonCodec;

import java.util.Map;

public class ExamSnapshotJsonCodecDemo {

    public static void main(String[] args) throws Exception {
        var first = ExamSnapshotJsonCodec.getInstance();
        var second = ExamSnapshotJsonCodec.getInstance();

        System.out.println(
                "Same instance: " + (first == second)
        );

        Map<String, Object> original = Map.of(
                "schemaVersion", 1,
                "title", "Demo rubric"
        );

        String json = first.writeValueAsString(original);
        Map<?, ?> restored = second.readValue(json, Map.class);

        System.out.println("JSON: " + json);
        System.out.println(
                "Round-trip successful: " + original.equals(restored)
        );
    }
}