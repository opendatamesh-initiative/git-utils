package org.opendatamesh.platform.git.provider.azure.resources.createpullrequest;

import org.eclipse.jgit.lib.Constants;
import org.opendatamesh.platform.git.model.CreatePullRequest;
import org.opendatamesh.platform.git.model.PullRequest;
import org.springframework.util.StringUtils;

public abstract class AzureCreatePullRequestMapper {

    private AzureCreatePullRequestMapper() {
    }

    public static AzureCreatePullRequestReq fromInternalModel(CreatePullRequest createPullRequest, String resolvedTargetBranch) {
        if (createPullRequest == null) {
            return null;
        }
        AzureCreatePullRequestReq req = new AzureCreatePullRequestReq();
        req.setSourceRefName(toFullBranchRef(createPullRequest.getSourceBranch()));
        req.setTargetRefName(toFullBranchRef(resolvedTargetBranch));
        req.setTitle(createPullRequest.getTitle());
        if (StringUtils.hasText(createPullRequest.getBody())) {
            req.setDescription(createPullRequest.getBody());
        }
        return req;
    }

    public static PullRequest toInternalModel(AzureCreatePullRequestRes res, CreatePullRequest request, String resolvedTargetBranch) {
        if (res == null) {
            return null;
        }
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(res.getPullRequestId() != null ? String.valueOf(res.getPullRequestId()) : null);
        if (res.getLinks() != null && res.getLinks().getWeb() != null) {
            pullRequest.setWebUrl(res.getLinks().getWeb().getHref());
        }
        pullRequest.setState(res.getStatus());
        pullRequest.setTitle(res.getTitle() != null ? res.getTitle() : request.getTitle());
        pullRequest.setBody(res.getDescription() != null ? res.getDescription() : request.getBody());
        pullRequest.setSourceBranch(toBareBranchName(res.getSourceRefName(), request.getSourceBranch()));
        pullRequest.setTargetBranch(toBareBranchName(res.getTargetRefName(), resolvedTargetBranch));
        return pullRequest;
    }

    static String toFullBranchRef(String branchName) {
        if (!StringUtils.hasText(branchName)) {
            return branchName;
        }
        if (branchName.startsWith(Constants.R_HEADS)) {
            return branchName;
        }
        return Constants.R_HEADS + branchName;
    }

    private static String toBareBranchName(String refName, String fallback) {
        if (!StringUtils.hasText(refName)) {
            return fallback;
        }
        if (refName.startsWith(Constants.R_HEADS)) {
            return refName.substring(Constants.R_HEADS.length());
        }
        return refName;
    }
}
