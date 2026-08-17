package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BitbucketCreatePullRequestReq {
    @JsonProperty("title")
    private String title;
    @JsonProperty("description")
    private String description;
    @JsonProperty("source")
    private BitbucketCreatePullRequestEndpointReq source;
    @JsonProperty("destination")
    private BitbucketCreatePullRequestEndpointReq destination;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BitbucketCreatePullRequestEndpointReq getSource() {
        return source;
    }

    public void setSource(BitbucketCreatePullRequestEndpointReq source) {
        this.source = source;
    }

    public BitbucketCreatePullRequestEndpointReq getDestination() {
        return destination;
    }

    public void setDestination(BitbucketCreatePullRequestEndpointReq destination) {
        this.destination = destination;
    }
}
