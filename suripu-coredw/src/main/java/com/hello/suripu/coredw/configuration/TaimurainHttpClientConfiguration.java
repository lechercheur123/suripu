package com.hello.suripu.coredw.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yammer.dropwizard.client.HttpClientConfiguration;
import com.yammer.dropwizard.config.Configuration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * Created by jakepiccolo on 2/23/16.
 */
public class TaimurainHttpClientConfiguration {
    @Valid
    @NotNull
    @JsonProperty("http_client_config")
    private HttpClientConfiguration httpClientConfiguration;
    public HttpClientConfiguration getHttpClientConfiguration() { return httpClientConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("endpoint")
    private String endpoint;
    public String getEndpoint() { return endpoint; }
}
