package org.opendatamesh.platform.git.provider.azure.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureCreatePullRequestLinksRes {
    @JsonProperty("web")
    private AzureCreatePullRequestLinkRes web;

    public AzureCreatePullRequestLinkRes getWeb() {
        return web;
    }

    public void setWeb(AzureCreatePullRequestLinkRes web) {
        this.web = web;
    }
}
