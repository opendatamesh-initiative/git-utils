package org.opendatamesh.platform.git.provider.bitbucket.resources.createpullrequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BitbucketCreatePullRequestEndpointReq {
    @JsonProperty("branch")
    private BitbucketCreatePullRequestBranchReq branch;

    public BitbucketCreatePullRequestBranchReq getBranch() {
        return branch;
    }

    public void setBranch(BitbucketCreatePullRequestBranchReq branch) {
        this.branch = branch;
    }
}
