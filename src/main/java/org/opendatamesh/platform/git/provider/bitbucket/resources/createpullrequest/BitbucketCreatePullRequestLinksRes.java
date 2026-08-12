package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketCreatePullRequestLinksRes {
    @JsonProperty("html")
    private BitbucketCreatePullRequestLinkRes html;

    public BitbucketCreatePullRequestLinkRes getHtml() {
        return html;
    }

    public void setHtml(BitbucketCreatePullRequestLinkRes html) {
        this.html = html;
    }
}
