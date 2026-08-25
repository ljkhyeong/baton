package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.personal.baton.application.brief.BriefContinuityEvent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class BriefContinuityEventContractTest {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @DisplayName("BRIEF 이벤트 v2 직렬화는 고정한 계약 예시와 일치한다")
    void serializesPinnedContractExamples() throws Exception {
        var schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(
                        SchemaRegistryConfig.builder()
                                .formatAssertionsEnabled(true)
                                .build()
                )
        );
        Schema schema;
        try (InputStream input = new ClassPathResource(SCHEMA).getInputStream()) {
            schema = schemaRegistry.getSchema(input);
        }
        schema.initializeValidators();

        for (String examplePath : EXAMPLES) {
            String expected = resource(examplePath);
            BriefContinuityEvent event = jsonMapper.readValue(expected, BriefContinuityEvent.class);
            String actual = jsonMapper.writeValueAsString(event);

            assertThat(jsonMapper.readTree(actual))
                    .describedAs("%s 직렬화", examplePath)
                    .isEqualTo(jsonMapper.readTree(expected));
            assertThat(schema.validate(actual, InputFormat.JSON))
                    .describedAs("%s 스키마 검증", examplePath)
                    .isEmpty();
        }
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(BASE_PATH + path)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static final String BASE_PATH = "contracts/brief/2.0.0-rc.1/";
    private static final String SCHEMA = BASE_PATH + "schemas/source-event.v2.schema.json";
    private static final List<String> EXAMPLES = List.of(
            "examples/role-unassigned.active-r1-critical.json",
            "examples/role-successor-missing.active-r1-warning.json",
            "examples/role-preparation-incomplete.active-r1-warning.json",
            "examples/routine-repeatedly-overdue.active-r1-critical.json",
            "examples/handoff-incomplete.active-r1-warning.json",
            "examples/role-unassigned.active-r2-warning.json",
            "examples/role-unassigned.resolved-r3-warning.json"
    );
}
