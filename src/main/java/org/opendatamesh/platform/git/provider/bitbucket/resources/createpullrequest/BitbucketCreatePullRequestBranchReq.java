package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BitbucketCreatePullRequestBranchReq {
    @JsonProperty("name")
    private String name;

    public BitbucketCreatePullRequestBranchReq() {
    }

    public BitbucketCreatePullRequestBranchReq(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
