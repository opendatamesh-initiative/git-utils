package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketCreatePullRequestRes {
    @JsonProperty("id")
    private Long id;
    @JsonProperty("title")
    private String title;
    @JsonProperty("description")
    private String description;
    @JsonProperty("state")
    private String state;
    @JsonProperty("source")
    private BitbucketCreatePullRequestEndpointRes source;
    @JsonProperty("destination")
    private BitbucketCreatePullRequestEndpointRes destination;
    @JsonProperty("links")
    private BitbucketCreatePullRequestLinksRes links;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public BitbucketCreatePullRequestEndpointRes getSource() {
        return source;
    }

    public void setSource(BitbucketCreatePullRequestEndpointRes source) {
        this.source = source;
    }

    public BitbucketCreatePullRequestEndpointRes getDestination() {
        return destination;
    }

    public void setDestination(BitbucketCreatePullRequestEndpointRes destination) {
        this.destination = destination;
    }

    public BitbucketCreatePullRequestLinksRes getLinks() {
        return links;
    }

    public void setLinks(BitbucketCreatePullRequestLinksRes links) {
        this.links = links;
    }
}
