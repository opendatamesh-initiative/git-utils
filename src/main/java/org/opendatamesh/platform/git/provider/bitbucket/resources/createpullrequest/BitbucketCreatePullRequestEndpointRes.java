package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketCreatePullRequestEndpointRes {
    @JsonProperty("branch")
    private BitbucketCreatePullRequestBranchRes branch;

    public BitbucketCreatePullRequestBranchRes getBranch() {
        return branch;
    }

    public void setBranch(BitbucketCreatePullRequestBranchRes branch) {
        this.branch = branch;
    }
}
