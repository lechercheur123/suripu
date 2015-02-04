package com.hello.suripu.core.models;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;

import java.net.URL;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class TimelineTest {


    final ObjectMapper objectMapper = new ObjectMapper();


    public String getJSON(final String filePath) throws Exception {
        URL url = Resources.getResource(filePath);
        return Resources.toString(url, Charsets.UTF_8);
    }

    @Before
    public void setUp() {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    }

    @Test
    public void testValidAccountDeserialization() throws Exception {
        String json = getJSON("fixtures/timeline/timeline-without-statistics.json");
        final List<Timeline> timelines = objectMapper.readValue(json, new TypeReference<List<Timeline>>(){});

        assertThat(timelines.size(), is(1));
        assertThat(timelines.get(0).statistics.isPresent(), is(false));
    }


    @Test
    public void testValidAccountDeserializationUnknown() throws Exception {
        String json = getJSON("fixtures/timeline/timeline-with-statistics.json");
        final List<Timeline> timelines = objectMapper.readValue(json, new TypeReference<List<Timeline>>(){});
        assertThat(timelines.size(), is(1));
        assertThat(timelines.get(0).statistics.isPresent(), is(true));
    }
}
